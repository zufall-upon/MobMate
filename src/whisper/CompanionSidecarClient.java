package whisper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class CompanionSidecarClient implements AutoCloseable {
    private static final Duration CHAT_TIMEOUT = Duration.ofSeconds(30);

    record Status(boolean installed, boolean running, String desiredEngine, String activeEngine,
                  boolean modelPresent, String detail, String debugReportPath) {
        static Status missing(String detail) {
            return new Status(false, false, "mobecho_lfm25", "missing", false, detail, "");
        }

        static Status failed(String detail) {
            return new Status(true, false, "mobecho_lfm25", "failed", false, detail, "");
        }
    }

    record Reply(String text, String activeEngine, boolean fallback, String observationTag) {}
    record SceneObservation(String sceneMemo, String activeEngine, boolean fallback, String detail) {}
    record AudioObservation(String audioMemo, String activeEngine, boolean fallback, String detail) {}

    private final MobMateWhisp host;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private Process process;
    private URI baseUri;
    private int port = -1;

    CompanionSidecarClient(MobMateWhisp host) {
        this.host = host;
    }

    synchronized boolean isInstalled() {
        File runtimeDir = getRuntimeDir();
        File jarFile = getJarFile();
        return runtimeDir.isDirectory() && jarFile.isFile();
    }

    synchronized Status probe() {
        if (!isInstalled()) {
            return Status.missing("MobEcho DLC not installed");
        }
        if (isHealthy()) {
            JSONObject health = readHealth();
            return new Status(
                    true,
                    true,
                    health.optString("desired_engine", "mobecho_qwen_onnx"),
                    health.optString("active_engine", "mobecho_fallback"),
                    health.optBoolean("model_present", false),
                    "Sidecar ready",
                    health.optString("debug_report_path", "")
            );
        }
        return new Status(true, false, "mobecho_lfm25", "stopped", false, "Sidecar stopped", "");
    }

    synchronized Reply generateReply(JSONArray messages,
                                     String tone,
                                     String locale,
                                     JSONObject observation,
                                     JSONObject situationSummary,
                                     JSONObject multimodalContext) throws IOException, InterruptedException {
        ensureStarted();

        JSONObject req = new JSONObject();
        req.put("messages", messages == null ? new JSONArray() : messages);
        req.put("tone", tone == null ? "desu" : tone);
        req.put("locale", locale == null ? "auto" : locale);
        req.put("debug_mode", host.isCompanionDebugModeEnabled());
        req.put("debug_capture_images", host.isCompanionDebugCaptureImagesEnabled());
        if (observation != null) {
            req.put("observation", observation);
        }
        if (situationSummary != null) {
            req.put("situation_summary", situationSummary);
        }
        if (multimodalContext != null && multimodalContext.length() > 0) {
            req.put("multimodal_context", multimodalContext);
        }

        String path = (multimodalContext != null && multimodalContext.length() > 0) ? "/v1/mm/chat" : "/v1/chat";
        JSONObject res = postJson(path, req);
        return new Reply(
                res.optString("reply_text", ""),
                res.optString("active_engine", "mobecho_fallback"),
                res.optBoolean("fallback", true),
                res.optString("observation_tag", "")
        );
    }

    synchronized SceneObservation observeScene(String tone,
                                               String locale,
                                               JSONObject situationSummary,
                                               JSONObject multimodalContext) throws IOException, InterruptedException {
        ensureStarted();
        JSONObject req = new JSONObject();
        req.put("tone", tone == null ? "desu" : tone);
        req.put("locale", locale == null ? "auto" : locale);
        req.put("debug_mode", host.isCompanionDebugModeEnabled());
        req.put("debug_capture_images", host.isCompanionDebugCaptureImagesEnabled());
        if (situationSummary != null) {
            req.put("situation_summary", situationSummary);
        }
        if (multimodalContext != null && multimodalContext.length() > 0) {
            req.put("multimodal_context", multimodalContext);
        }
        JSONObject res = postJson("/v1/mm/observe", req);
        return new SceneObservation(
                res.optString("scene_memo", ""),
                res.optString("active_engine", "lfm25_vl_unavailable"),
                res.optBoolean("fallback", true),
                res.optString("detail", "")
        );
    }

    synchronized AudioObservation observeAudio(String tone,
                                               String locale,
                                               JSONObject situationSummary,
                                               JSONObject multimodalContext) throws IOException, InterruptedException {
        ensureStarted();
        JSONObject req = new JSONObject();
        req.put("tone", tone == null ? "desu" : tone);
        req.put("locale", locale == null ? "auto" : locale);
        req.put("debug_mode", host.isCompanionDebugModeEnabled());
        req.put("debug_capture_images", host.isCompanionDebugCaptureImagesEnabled());
        if (situationSummary != null) {
            req.put("situation_summary", situationSummary);
        }
        if (multimodalContext != null && multimodalContext.length() > 0) {
            req.put("multimodal_context", multimodalContext);
        }
        JSONObject res = postJson("/v1/mm/observe_audio", req);
        return new AudioObservation(
                res.optString("audio_memo", ""),
                res.optString("active_engine", "lfm25_audio_unavailable"),
                res.optBoolean("fallback", true),
                res.optString("detail", "")
        );
    }

    synchronized void ensureStarted() throws IOException, InterruptedException {
        File runtimeDir = getRuntimeDir();
        File jarFile = getJarFile();
        if (!isInstalled()) {
            throw new IOException("MobEcho DLC not installed");
        }

        if (isHealthy()) {
            return;
        }

        stopProcessLocked();
        port = pickFreePort();
        baseUri = URI.create("http://127.0.0.1:" + port);
        Files.createDirectories(runtimeDir.toPath().resolve("logs"));

        ProcessBuilder pb = new ProcessBuilder(
                findJavaCommand(),
                "-jar",
                jarFile.getAbsolutePath(),
                "--port",
                String.valueOf(port)
        );
        pb.directory(runtimeDir);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(runtimeDir.toPath().resolve("logs").resolve("sidecar.log").toFile()));
        process = pb.start();

        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("MobEcho sidecar exited early");
            }
            if (isHealthy()) {
                return;
            }
            Thread.sleep(200L);
        }
        throw new IOException("MobEcho sidecar did not become healthy");
    }

    @Override
    public synchronized void close() {
        stopProcessLocked();
    }

    private File getRuntimeDir() {
        return host.getExeDir().toPath().resolve("mobecho").toFile();
    }

    private File getJarFile() {
        return new File(getRuntimeDir(), "mobecho-sidecar.jar");
    }

    private boolean isHealthy() {
        if (baseUri == null) return false;
        try {
            HttpRequest req = HttpRequest.newBuilder(baseUri.resolve("/health"))
                    .GET()
                    .timeout(Duration.ofSeconds(2))
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return res.statusCode() == 200;
        } catch (Exception ex) {
            return false;
        }
    }

    private JSONObject readHealth() {
        if (baseUri == null) {
            return new JSONObject();
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(baseUri.resolve("/health"))
                    .GET()
                    .timeout(Duration.ofSeconds(2))
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200 || res.body() == null || res.body().isBlank()) {
                return new JSONObject();
            }
            return new JSONObject(res.body());
        } catch (Exception ex) {
            return new JSONObject();
        }
    }

    private JSONObject postJson(String path, JSONObject body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(baseUri.resolve(path))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(CHAT_TIMEOUT)
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            throw new IOException("MobEcho sidecar bad status: " + res.statusCode());
        }
        return new JSONObject(res.body());
    }

    private String findJavaCommand() {
        Path bundled = host.getExeDir().toPath().resolve("jre").resolve("bin").resolve("java.exe");
        if (bundled.toFile().isFile()) {
            return bundled.toAbsolutePath().toString();
        }
        String javaHome = System.getProperty("java.home", "");
        Path current = Path.of(javaHome, "bin", "java.exe");
        if (current.toFile().isFile()) {
            return current.toAbsolutePath().toString();
        }
        return "java";
    }

    private static int pickFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private void stopProcessLocked() {
        Process current = process;
        URI currentBaseUri = baseUri;
        process = null;
        baseUri = null;
        port = -1;

        if (current != null) {
            List<ProcessHandle> descendants = snapshotDescendants(current);
            try {
                requestShutdown(currentBaseUri);
            } catch (Exception ignore) {
            }
            try {
                if (!current.waitFor(2200L, TimeUnit.MILLISECONDS)) {
                    closeStreams(current);
                    current.destroy();
                }
                if (!current.waitFor(1800L, TimeUnit.MILLISECONDS)) {
                    current.destroyForcibly();
                    current.waitFor(1800L, TimeUnit.MILLISECONDS);
                }
            } catch (Exception ignore) {
            } finally {
                terminateDescendants(descendants);
            }
        }
    }

    private void requestShutdown(URI currentBaseUri) {
        if (currentBaseUri == null) {
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(currentBaseUri.resolve("/shutdown"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(1))
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignore) {
        }
    }

    private static void closeStreams(Process proc) {
        try { proc.getInputStream().close(); } catch (Exception ignore) {}
        try { proc.getErrorStream().close(); } catch (Exception ignore) {}
        try { proc.getOutputStream().close(); } catch (Exception ignore) {}
    }

    private static List<ProcessHandle> snapshotDescendants(Process proc) {
        try {
            return proc.toHandle().descendants().toList();
        } catch (Throwable ignore) {
            return List.of();
        }
    }

    private static void terminateDescendants(List<ProcessHandle> descendants) {
        if (descendants == null || descendants.isEmpty()) {
            return;
        }
        for (ProcessHandle child : descendants) {
            try {
                if (child != null && child.isAlive()) {
                    child.destroy();
                }
            } catch (Throwable ignore) {
            }
        }
        for (ProcessHandle child : descendants) {
            try {
                if (child != null && child.isAlive()) {
                    child.destroyForcibly();
                }
            } catch (Throwable ignore) {
            }
        }
    }
}
