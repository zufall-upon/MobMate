package whisper;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;

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
    public static float getFloat(String key, float defaultValue) {
        String v = get(key);
        if (v == null) return defaultValue;
        try {
            return Float.parseFloat(v.trim());
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
        try {
            Files.write(
                    OUT_TTS,
                    (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
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


                int eq = line.indexOf('=');
                if (eq <= 0) continue;

                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                v = stripInlineComment(v);

                if ((v.startsWith("\"") && v.endsWith("\""))
                        || (v.startsWith("'") && v.endsWith("'"))) {
                    v = v.substring(1, v.length() - 1);
                }
                cache.put(k, v);
            }
        } catch (Exception e) {

        }
    }

    private static final File IGNORE_FILE = new File("_ignore.txt");
    public static LinkedHashSet<String> loadIgnoreSet() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (!IGNORE_FILE.exists()) return set;

        try (BufferedReader br = new BufferedReader(new FileReader(IGNORE_FILE))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    set.add(line);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return set;
    }
    public static void saveIgnoreSet(LinkedHashSet<String> set) {
        if (set == null || set.isEmpty()) {
            Config.log("Skip saveIgnoreSet (empty)");
            return;
        }
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(IGNORE_FILE), StandardCharsets.UTF_8))) {
            for (String s : set) {
                pw.println(s);
            }
            Config.mirrorAllToCloud();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static java.util.List<String> loadIgnoreWords() {
        List<String> list = new ArrayList<>();
        File f = new File("_ignore.txt");
        if (!f.exists()) return list;

        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    list.add(line);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return list;
    }


    private static final File GOOD_FILE = new File("_initprmpt_add.txt");
    public static LinkedHashSet<String> loadGoodSet() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (!GOOD_FILE.exists()) return set;

        try (BufferedReader br = new BufferedReader(new FileReader(GOOD_FILE))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    set.add(line);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return set;
    }
    public static void saveGoodSet(LinkedHashSet<String> set) {
        if (set == null || set.isEmpty()) {
            Config.log("Skip saveGoodSet (empty)");
            return;
        }
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(GOOD_FILE), StandardCharsets.UTF_8))) {
            for (String s : set) {
                pw.println(s);
            }
            Config.mirrorAllToCloud();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public static java.util.List<String> loadGoodWords() {
        List<String> list = new ArrayList<>();
        File f = new File("_initprmpt_add.txt");
        if (!f.exists()) return list;

        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    list.add(line);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return list;
    }

    // Steam Cloud
    private static final String[] CLOUD_TARGET_FILES = {
            "_dictionary.txt",
            "_ignore.txt",
            "_initprmpt_add.txt",
            "_radiocmd.txt",
            "_outtts.txt"
    };
    public static void syncAllFromCloud() {
        for (String name : CLOUD_TARGET_FILES) {
            syncFromCloud(name);
        }
    }
    private static void syncFromCloud(String fileName) {
        try {
            Path local = getLocalDir().resolve(fileName);
            Path cloud = getCloudDir().resolve(fileName);
            if (!Files.exists(cloud)) return;
            if (!Files.exists(local)) {
                Files.copy(cloud, local, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            if (Files.size(cloud) == 0) {
                Config.log("Skip cloud sync (empty): " + fileName);
                return;
            }
            long localTime = Files.getLastModifiedTime(local).toMillis();
            long cloudTime = Files.getLastModifiedTime(cloud).toMillis();
            long localSize = Files.size(local);
            long cloudSize = Files.size(cloud);
            if (cloudTime > localTime || cloudSize != localSize) {
                Files.copy(cloud, local, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Config.logError("Cloud sync failed: " + fileName, e);
        }
    }
    public static void mirrorAllToCloud() {
        for (String name : CLOUD_TARGET_FILES) {
            mirrorToCloud(name);
        }
    }
    private static void mirrorToCloud(String fileName) {
        try {
            Files.createDirectories(getCloudDir());
            Path local = getLocalDir().resolve(fileName);
            Path cloud = getCloudDir().resolve(fileName);
            if (Files.exists(local)) {
                // log compress
                if ("_outtts.txt".equals(fileName)) {
                    trimOutttsSafely(local, OUTTTS_MAX_LINES);
                    trimFileBySize(getLocalDir().resolve("_log.txt"), LOG_MAX_BYTES);
                }
                Files.copy(local, cloud, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Config.logError("Cloud mirror failed: " + fileName, e);
        }
    }
    public static Path getLocalDir() {
        try {
            return Paths.get(
                    Config.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()
            ).getParent();
        } catch (Exception e) {
            return Paths.get("").toAbsolutePath();
        }
    }
    public static Path getCloudDir() {
        return Paths.get(
                System.getProperty("user.home"),
                "Documents",
                "MobMateWhispTalk"
        );
    }

    private static final int OUTTTS_MAX_LINES = 500;
    private static final long LOG_MAX_BYTES = 2 * 1024 * 1024; // 2MB
    private static final String OUTTTS_MARKER =
            "↑Settings↓Logs below";
    public static void trimOutttsSafely(Path file, int maxLines) throws IOException {
        if (!Files.exists(file)) return;

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return;

        int markerIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(OUTTTS_MARKER)) {
                markerIndex = i;
                break;
            }
        }
        if (markerIndex >= 0 && markerIndex + 1 >= lines.size()) {
            return;
        }
        List<String> result = new ArrayList<>();
        if (markerIndex >= 0) {
            // setting safe
            result.addAll(lines.subList(0, markerIndex + 1));
            List<String> logs = lines.subList(markerIndex + 1, lines.size());
            if (logs.size() > maxLines) {
                logs = logs.subList(logs.size() - maxLines, logs.size());
            }
            result.addAll(logs);

        } else {
            // tail delete
            if (lines.size() <= maxLines) return;
            result.addAll(lines.subList(lines.size() - maxLines, lines.size()));
        }
        Files.write(file, result, StandardCharsets.UTF_8);
    }
    public static void trimFileBySize(Path file, long maxBytes) throws IOException {
        if (!Files.exists(file)) return;
        if (Files.size(file) <= maxBytes) return;

        byte[] all = Files.readAllBytes(file);
        byte[] tail = Arrays.copyOfRange(
                all, all.length - (int)maxBytes, all.length
        );
        Files.write(file, tail);
    }

    public static String loadSetting(String key, String defaultValue) {
        File f = new File("_outtts.txt");
        if (!f.exists()) return defaultValue;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith(key + "=")) {
                    return line.substring((key + "=").length()).trim();
                }
                if (line.startsWith("---")) break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return defaultValue;
    }
    static private Map<String, List<String>> dictMap = new HashMap<>();
    static private Random rnd = new Random();
    public static void loadDictionary() {
        File f = new File("_dictionary.txt");
        if (!f.exists()) return;

        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty()) continue;
                if (line.startsWith("#")) continue; // comments

                String[] parts = line.split("=", 2);
                if (parts.length != 2) continue;

                String key = parts[0].trim().toLowerCase();
                String[] values = parts[1].split(",");

                List<String> list = new ArrayList<>();
                for (String v : values) {
                    list.add(v.trim());
                }

                dictMap.put(key, list);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public static String applyDictionary(String text) {
        if (text == null) return null;

        for (Map.Entry<String, List<String>> e : dictMap.entrySet()) {
            String key = e.getKey();
            List<String> choices = e.getValue();

            // ignore case
            if (text.toLowerCase().contains(key)) {
                String pick = choices.get(rnd.nextInt(choices.size()));
                text = text.replaceAll("(?i)" + key, pick);
            }
        }
        return text;
    }
    public static synchronized void addDictionaryEntry(String key, String value) {
        try {
            File dict = new File("_dictionary.txt");


            if (!dict.exists()) {
                dict.getParentFile().mkdirs();
                dict.createNewFile();
            }


            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(dict), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith(key + "=")) {
                        return;
                    }
                }
            }


            try (BufferedWriter bw = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(dict, true), StandardCharsets.UTF_8))) {
                bw.write(key + "=" + value);
                bw.newLine();
            }

            logDebug("Dictionary entry added: " + key + "=" + value);

        } catch (IOException e) {
            logError("Failed to add dictionary entry: " + key, e);
        }
    }
    private static String stripInlineComment(String v) {
        if (v == null) return null;
        // 先頭の # は色コード等に使うので温存する（例: #1D6F5A）
        for (int i = 1; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '#' || c == '＃') {
                char prev = v.charAt(i - 1);
                if (Character.isWhitespace(prev)) {
                    return v.substring(0, i).trim();
                }
            }
        }
        return v.trim();
    }
}

class DebugLogZipper {

    public static File createZip() throws IOException {
        String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        File zip = new File("MobMateWhispTalk_DebugLog_" + ts + ".zip");

        List<String> targets = List.of(
                "_log.txt",
                "_outtts.txt",
                "_dictionary.txt",
                "_ignore.txt",
                "_initprmpt_add.txt"
        );

        try (FileOutputStream fos = new FileOutputStream(zip);
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(fos)) {

            for (String name : targets) {
                File f = new File(name);
                if (!f.exists()) continue;

                zos.putNextEntry(new java.util.zip.ZipEntry(name));
                Files.copy(f.toPath(), zos);
                zos.closeEntry();
            }

            // ENV
            Runtime rt = Runtime.getRuntime();
            zos.putNextEntry(new java.util.zip.ZipEntry("_env.txt"));
            String env =
                    "version=" + Version.APP_VERSION + "\n" +
                            "log.debug=" + Config.getBool("log.debug", false) + "\n" +
                            "\n" +
                            "os=" + System.getProperty("os.name") + "\n" +
                            "os.version=" + System.getProperty("os.version") + "\n" +
                            "os.arch=" + System.getProperty("os.arch") + "\n" +
                            "\n" +
                            "java.version=" + System.getProperty("java.version") + "\n" +
                            "java.vendor=" + System.getProperty("java.vendor") + "\n" +
                            "java.vm=" + System.getProperty("java.vm.name") + "\n" +
                            "java.arch=" + System.getProperty("os.arch") + "\n" +
                            "\n" +
                            "cpu.cores=" + rt.availableProcessors() + "\n" +
                            "mem.maxMB=" + (rt.maxMemory() / 1024 / 1024) + "\n" +
                            "mem.totalMB=" + (rt.totalMemory() / 1024 / 1024) + "\n" +
                            "mem.freeMB=" + (rt.freeMemory() / 1024 / 1024) + "\n" +
                            "\n" +
                            "audio.input=" + MobMateWhisp.prefs.get("audio.device", "") + "\n" +
                            "audio.output=" + MobMateWhisp.prefs.get("audio.output.device", "") + "\n" +
                            "tts.voice=" + MobMateWhisp.prefs.get("tts.windows.voice", "auto") + "\n" +
                            "vulkan.gpu.index=" + MobMateWhisp.prefs.get("vulkan.gpu.index", "") + "\n" +
                             "\n" +
                            "timezone=" + TimeZone.getDefault().getID() + "\n" +
                            "locale=" + Locale.getDefault();
            zos.write(env.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        return zip;
    }
}

class VulkanGpuUtil {

    private static List<String> cachedNames;


    public static int getGpuCount() {
        ensureLoaded();
        return cachedNames.size();
    }


    public static String getGpuName(int index) {
        ensureLoaded();
        if (index < 0 || index >= cachedNames.size()) {
            return "Unknown Vulkan GPU";
        }
        return cachedNames.get(index);
    }

    private static synchronized void ensureLoaded() {
        if (cachedNames != null) return;
        cachedNames = new ArrayList<>();

        // vulkaninfo の候補パス（PATH優先、SDK入れてない環境でも対処）
        String[] candidates = {
                "vulkaninfo",
                "C:\\Program Files (x86)\\Vulkan SDK\\Bin\\vulkaninfo.exe",
                "C:\\VulkanSDK\\Bin\\vulkaninfo.exe"
        };
        Process p = null;
        for (String cmd : candidates) {
            try {
                p = new ProcessBuilder(cmd, "--summary")
                        .redirectErrorStream(true)
                        .start();
                break;
            } catch (Exception ignore) {}
        }
        if (p == null) {
            cachedNames.add("Vulkan GPU (unknown)");
            return;
        }

        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean inGpuBlock = false;
            while ((line = r.readLine()) != null) {
                String trimmed = line.trim();
                // GPU0: / GPU1: などの行を検出
                if (trimmed.matches("GPU\\d+:")) {
                    cachedNames.add("Vulkan GPU"); // deviceNameで上書き
                    inGpuBlock = true;
                } else if (inGpuBlock && trimmed.startsWith("deviceName")) {
                    int eq = trimmed.indexOf('=');
                    if (eq > 0) {
                        String name = trimmed.substring(eq + 1).trim();
                        cachedNames.set(cachedNames.size() - 1, name);
                        inGpuBlock = false;
                    }
                }
            }
        } catch (Exception ignore) {}

        try { p.waitFor(); } catch (Exception ignore) {}

        if (cachedNames.isEmpty()) {
            cachedNames.add("Vulkan GPU (unknown)");
        }
        Config.log("[VulkanGpuUtil] found " + cachedNames.size() + " GPU(s): " + cachedNames);
    }
}

final class UiLang {
    public static String normalize(String lang) {
        if (lang == null) return "en";
        lang = lang.toLowerCase(Locale.ROOT);
        if (lang.startsWith("ja")) return "ja";
        if (lang.startsWith("ko")) return "ko";
        if (lang.startsWith("zh")) {
            if (lang.contains("tw") || lang.contains("hk")) return "zh_tw";
            return "zh_cn";
        }
        return "en";
    }
    public static Path resolveUiFile(String lang) {
        String code = normalize(lang);
        return Paths.get("libs/preset/_ui_" + code + ".txt");
    }
}
class UiText {
    private static final Map<String, String> map = new HashMap<>();
    public static void load(Path dictFile) {
        map.clear();
        try {
            for (String line : Files.readAllLines(dictFile, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int idx = line.indexOf('=');
                if (idx <= 0) continue;
                map.put(
                        line.substring(0, idx).trim(),
                        unescape(line.substring(idx + 1).trim())
                );
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, "Error :\n" + e.getMessage());
            e.printStackTrace();
        }
    }
    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == 'u' && i + 5 < s.length()) {
                    String hex = s.substring(i + 2, i + 6);
                    try {
                        sb.append((char) Integer.parseInt(hex, 16));
                        i += 5; // 'u' + 4桁ぶん消費
                        continue;
                    } catch (NumberFormatException ignored) {
                        // fallthrough
                    }
                }
                switch (n) {
                    case 'n': sb.append('\n'); i++; continue;
                    case 'r': sb.append('\r'); i++; continue;
                    case 't': sb.append('\t'); i++; continue;
                    case '\\': sb.append('\\'); i++; continue;
                    default:
                        // 未知の \x はそのまま（\ と次文字を保持）
                        sb.append(c);
                        continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
    // UiText.load(Paths.get("libs/preset/_ui_ja.txt"));
    // UiText.t("ui.history");
    public static String t(String key) {
        return map.getOrDefault(key, key);
    }
}
