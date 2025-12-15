package whisper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class Config {

    private static final Path OUT_TTS =
            Paths.get(System.getProperty("user.dir"), "_outtts.txt");

    private static PrintWriter LOG;
    private static final DateTimeFormatter LOG_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static {
        try {
            File out = new File("_log.txt");
            LOG = new PrintWriter(new FileWriter(out, true));
            log("=== MobMateWhisp Started ===");
        } catch (Exception e) {
            // ログ初期化失敗時は黙る（今の思想どおり）
        }
    }

    public static void log(String s) {
        String line = String.format(
                "[%s] %s",
                LocalDateTime.now().format(LOG_TIME), s
        );
        System.out.println(line);
        if (LOG != null) {
            LOG.println(line);
            LOG.flush();
        }
    }
    public static void logDebug(String s) {
        if (!getBool("log.debug", false)) return;
        log("[DEBUG] " + s);
    }
    public static void logError(String s, Throwable t) {
        log("[ERROR] " + s);
        if (t != null) t.printStackTrace();
    }

    private static Map<String, String> cache;
    private static final File FILE = new File("_outtts.txt");

    private Config() {}

    public static String get(String key) {
        ensureLoaded();
        return cache.get(key);
    }

    public static String get(String key, String def) {
        String v = get(key);
        return v != null ? v : def;
    }
    public static String getString(String key, String defaultValue) {
        String v = get(key);
        return (v != null) ? v : defaultValue;
    }
    public static int getInt(String key, int defaultValue) {
        String v = get(key);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    public static boolean getBool(String key, boolean def) {
        String v = get(key);
        if (v == null) return def;
        return v.equalsIgnoreCase("true")
                || v.equalsIgnoreCase("1")
                || v.equalsIgnoreCase("yes");
    }
    public static String[] splitCsv(String v) {
        if (v == null || v.isEmpty()) return new String[0];
        return Arrays.stream(v.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
    public static boolean matchAny(String text, String[] keys) {
        if (keys == null) return false;
        for (String k : keys) {
            if (!k.isEmpty() && text.contains(k)) return true;
        }
        return false;
    }

    public static synchronized void appendOutTts(String line) {
        if (line == null) return;
        String s = line.trim();
        if (s.isEmpty()) return;
        try {
            Files.write(
                    OUT_TTS,
                    (s + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            Config.logError("",e);
        }
    }

    public static void reload() {
        cache = null;
        ensureLoaded();
    }

    private static synchronized void ensureLoaded() {
        if (cache != null) return;
        cache = new HashMap<>();
        if (!FILE.exists()) return;

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(FILE), StandardCharsets.UTF_8))) {

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("---")) break;

                int eq = line.indexOf('=');
                if (eq <= 0) continue;

                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();

                if ((v.startsWith("\"") && v.endsWith("\""))
                        || (v.startsWith("'") && v.endsWith("'"))) {
                    v = v.substring(1, v.length() - 1);
                }
                cache.put(k, v);
            }
        } catch (Exception e) {
            // 落とさない思想
        }
    }
}
