package whisper;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.nio.charset.Charset;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class PiperPlusPersistentSessionManager implements AutoCloseable {
    private static final int MAX_ACTIVE_SESSIONS = 10;

    record SynthesisOptions(
            int sampleRate,
            String lengthScaleArg,
            String sentenceSilenceArg,
            String noiseScaleArg,
            String noiseWArg
    ) {}

    record SynthesisResult(
            Path wavPath,
            long wavBytes,
            boolean reused,
            long bootMs,
            long synthWaitMs,
            String sessionKey
    ) {}

    record PrewarmResult(
            String sessionKey,
            boolean alreadyWarm,
            long bootMs,
            long warmupMs,
            int activeSessions
    ) {}

    private final LinkedHashMap<SessionKey, ActiveSession> sessionPool = new LinkedHashMap<>(8, 0.75f, true);

    synchronized SynthesisResult synth(Path exePath,
                                       PiperPlusCatalog.Entry entry,
                                       String text,
                                       SynthesisOptions options) throws Exception {
        SessionKey key = SessionKey.from(exePath, entry, options);
        ActiveSession session = ensureSession(key);
        long requestId = ++session.requestCounter;
        String fileName = String.format(Locale.ROOT, "utt_%08d.wav", requestId);
        Path outFile = session.outputDir.resolve(fileName);
        Files.deleteIfExists(outFile);

        long waitStartNs = System.nanoTime();
        submitJsonSynthesis(session, text, fileName);
        waitForFileReady(outFile, session);
        long synthWaitMs = (System.nanoTime() - waitStartNs) / 1_000_000L;

        long wavBytes = Files.size(outFile);

        boolean reused = session.reuseCount > 0;
        session.reuseCount++;
        return new SynthesisResult(outFile, wavBytes, reused, session.bootMs, synthWaitMs, key.logLabel());
    }

    synchronized PrewarmResult prewarm(Path exePath,
                                       PiperPlusCatalog.Entry entry,
                                       SynthesisOptions options,
                                       String warmupText) throws Exception {
        SessionKey key = SessionKey.from(exePath, entry, options);
        ActiveSession existing = sessionPool.get(key);
        if (existing != null && existing.process.isAlive() && existing.reuseCount > 0) {
            return new PrewarmResult(key.logLabel(), true, existing.bootMs, 0L, sessionPool.size());
        }
        ActiveSession started = ensureSession(key);
        String warmText = (warmupText == null || warmupText.isBlank()) ? "." : warmupText;
        long requestId = ++started.requestCounter;
        String fileName = String.format(Locale.ROOT, "warm_%08d.wav", requestId);
        Path outFile = started.outputDir.resolve(fileName);
        Files.deleteIfExists(outFile);
        long waitStartNs = System.nanoTime();
        try {
            submitJsonSynthesis(started, warmText, fileName);
            waitForFileReady(outFile, started);
            long warmupMs = (System.nanoTime() - waitStartNs) / 1_000_000L;
            started.reuseCount++;
            return new PrewarmResult(key.logLabel(), false, started.bootMs, warmupMs, sessionPool.size());
        } finally {
            try { Files.deleteIfExists(outFile); } catch (Exception ignore) {}
        }
    }

    private void submitJsonSynthesis(ActiveSession session, String text, String fileName) throws IOException {
        JSONObject payload = new JSONObject();
        payload.put("text", text == null ? "" : text);
        payload.put("output_file", fileName);
        session.writer.write(payload.toString());
        session.writer.newLine();
        session.writer.flush();
    }

    private ActiveSession ensureSession(SessionKey key) throws Exception {
        ActiveSession existing = sessionPool.get(key);
        if (existing != null) {
            if (existing.process.isAlive()) {
                return existing;
            }
            sessionPool.remove(key);
            stopSession(existing);
        }
        ActiveSession started = startSession(key);
        sessionPool.put(key, started);
        trimSessionPool();
        return started;
    }

    private ActiveSession startSession(SessionKey key) throws Exception {
        Path outputDir = Path.of(System.getProperty("java.io.tmpdir"), "mobmate_piper_plus_session", key.safeDirName());
        resetDirectory(outputDir);
        Path logFile = outputDir.resolve("_session.log");

        ArrayList<String> cmd = new ArrayList<>();
        cmd.add(key.exePath().toAbsolutePath().toString());
        cmd.add("--model");
        cmd.add(key.modelPath().toAbsolutePath().toString());
        cmd.add("--config");
        cmd.add(key.configPath().toAbsolutePath().toString());
        cmd.add("--json-input");
        cmd.add("--output-dir");
        cmd.add(outputDir.toAbsolutePath().toString());
        cmd.add("--language");
        cmd.add(key.languageTag());
        cmd.add("--speaker");
        cmd.add("0");
        cmd.add("--length-scale");
        cmd.add(key.lengthScaleArg());
        cmd.add("--sentence-silence");
        cmd.add(key.sentenceSilenceArg());
        cmd.add("--noise-scale");
        cmd.add(key.noiseScaleArg());
        cmd.add("--noise-w");
        cmd.add(key.noiseWArg());
        cmd.add("--quiet");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(key.exePath().getParent().toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());

        long bootStartNs = System.nanoTime();
        Process process = pb.start();
        long bootMs = (System.nanoTime() - bootStartNs) / 1_000_000L;
        // PiperPlus CLI's JSONL stdin follows the native Windows encoding here.
        // Java 21 defaults file.encoding to UTF-8, so we need native.encoding/sun.jnu.encoding explicitly.
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(),
                resolveNativeProcessCharset()));
        return new ActiveSession(key, process, writer, outputDir, logFile, bootMs);
    }

    private Charset resolveNativeProcessCharset() {
        String nativeEncoding = System.getProperty("native.encoding");
        if (nativeEncoding != null && !nativeEncoding.isBlank()) {
            try {
                return Charset.forName(nativeEncoding.trim());
            } catch (Exception ignore) {}
        }
        String jnuEncoding = System.getProperty("sun.jnu.encoding");
        if (jnuEncoding != null && !jnuEncoding.isBlank()) {
            try {
                return Charset.forName(jnuEncoding.trim());
            } catch (Exception ignore) {}
        }
        try {
            return Charset.forName("MS932");
        } catch (Exception ignore) {
            return Charset.defaultCharset();
        }
    }

    private void waitForFileReady(Path outFile, ActiveSession session) throws Exception {
        long deadline = System.nanoTime() + 35_000_000_000L;
        long lastSize = -1L;
        int stableCount = 0;
        while (System.nanoTime() < deadline) {
            if (!session.process.isAlive()) {
                throw new IOException("PiperPlus persistent session exited early: " + readSessionLog(session));
            }
            if (Files.isRegularFile(outFile)) {
                long size = Files.size(outFile);
                if (size > 128) {
                    if (size == lastSize) {
                        stableCount++;
                        if (stableCount >= 2) return;
                    } else {
                        lastSize = size;
                        stableCount = 0;
                    }
                }
            }
            Thread.sleep(25L);
        }
        throw new IOException("Timed out waiting persistent wav: " + outFile + " log=" + readSessionLog(session));
    }

    private String readSessionLog(ActiveSession session) {
        try {
            if (!Files.isRegularFile(session.logFile)) return "(no session log)";
            String text = Files.readString(session.logFile, StandardCharsets.UTF_8).trim();
            if (text.isBlank()) return "(empty session log)";
            if (text.length() > 1000) return text.substring(text.length() - 1000);
            return text;
        } catch (Exception ex) {
            return "(failed to read session log: " + ex.getMessage() + ")";
        }
    }

    private void trimSessionPool() {
        while (sessionPool.size() > MAX_ACTIVE_SESSIONS) {
            Map.Entry<SessionKey, ActiveSession> eldest = sessionPool.entrySet().iterator().next();
            ActiveSession evicted = eldest.getValue();
            sessionPool.remove(eldest.getKey());
            stopSession(evicted);
        }
    }

    private void stopSession(ActiveSession session) {
        if (session == null) return;
        try { session.writer.close(); } catch (Exception ignore) {}
        try {
            if (session.process.isAlive()) {
                boolean exited = session.process.waitFor(500, TimeUnit.MILLISECONDS);
                if (!exited) session.process.destroy();
            }
        } catch (Exception ignore) {}
        try {
            if (session.process.isAlive()) {
                boolean exited = session.process.waitFor(1200, TimeUnit.MILLISECONDS);
                if (!exited) session.process.destroyForcibly();
            }
        } catch (Exception ignore) {}
        try {
            if (session.process.isAlive()) {
                session.process.waitFor(800, TimeUnit.MILLISECONDS);
            }
        } catch (Exception ignore) {}
    }

    private void stopAllSessions() {
        if (sessionPool.isEmpty()) return;
        ArrayList<ActiveSession> snapshot = new ArrayList<>(sessionPool.values());
        sessionPool.clear();
        for (ActiveSession session : snapshot) {
            stopSession(session);
        }
    }

    private void resetDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                });
            } catch (RuntimeException ex) {
                if (ex.getCause() instanceof IOException io) throw io;
                throw ex;
            }
        }
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(".keep"), "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    @Override
    public synchronized void close() {
        stopAllSessions();
    }

    private record SessionKey(
            Path exePath,
            Path modelPath,
            Path configPath,
            String installKey,
            String languageTag,
            String lengthScaleArg,
            String sentenceSilenceArg,
            String noiseScaleArg,
            String noiseWArg
    ) {
        static SessionKey from(Path exePath, PiperPlusCatalog.Entry entry, SynthesisOptions options) {
            return new SessionKey(
                    exePath,
                    PiperPlusModelManager.getModelFile(entry),
                    PiperPlusModelManager.getConfigFile(entry),
                    entry.installKey(),
                    entry.cliTextLanguage(),
                    options.lengthScaleArg(),
                    options.sentenceSilenceArg(),
                    options.noiseScaleArg(),
                    options.noiseWArg()
            );
        }

        String safeDirName() {
            String seed = installKey + "_" + languageTag + "_" + lengthScaleArg + "_" + sentenceSilenceArg
                    + "_" + noiseScaleArg + "_" + noiseWArg;
            return seed.replaceAll("[^a-zA-Z0-9._-]+", "_");
        }

        String logLabel() {
            return installKey + "/" + languageTag
                    + " len=" + lengthScaleArg
                    + " sil=" + sentenceSilenceArg
                    + " noise=" + noiseScaleArg
                    + " noiseW=" + noiseWArg;
        }
    }

    private static final class ActiveSession {
        final SessionKey key;
        final Process process;
        final BufferedWriter writer;
        final Path outputDir;
        final Path logFile;
        final long bootMs;
        long requestCounter = 0L;
        int reuseCount = 0;

        ActiveSession(SessionKey key,
                      Process process,
                      BufferedWriter writer,
                      Path outputDir,
                      Path logFile,
                      long bootMs) {
            this.key = key;
            this.process = process;
            this.writer = writer;
            this.outputDir = outputDir;
            this.logFile = logFile;
            this.bootMs = bootMs;
        }

        boolean matches(SessionKey other) {
            return other != null && key.equals(other);
        }
    }
}
