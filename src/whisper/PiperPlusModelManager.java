package whisper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import org.json.JSONObject;

final class PiperPlusModelManager {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private PiperPlusModelManager() {}

    static Path getRootDir() {
        return Path.of(System.getProperty("user.dir"), "models", "piper_plus");
    }

    static Path findBundledExe() {
        String override = MobMateWhisp.prefs != null ? MobMateWhisp.prefs.get("piper.plus.exe", "").trim() : "";
        if (!override.isBlank()) {
            Path overridden = Path.of(override);
            if (Files.isRegularFile(overridden)) return overridden;
        }

        Path[] candidates = new Path[]{
                Path.of(System.getProperty("user.dir"), "piper_plus", "bin", "PiperPlus.Cli.exe"),
                Path.of(System.getProperty("user.dir"), "app", "piper_plus", "bin", "PiperPlus.Cli.exe"),
                Path.of(System.getProperty("user.dir"), "..", "app", "piper_plus", "bin", "PiperPlus.Cli.exe").normalize()
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    static boolean isRuntimeAvailable() {
        return findBundledExe() != null;
    }

    static Path getModelDir(String modelId) {
        return getRootDir().resolve(safeName(modelId));
    }

    static Path getModelDir(PiperPlusCatalog.Entry entry) {
        if (entry == null) return getModelDir("default");
        return getModelDir(entry.installKey());
    }

    static Path getModelFile(PiperPlusCatalog.Entry entry) {
        String fallback = entry.installKey() + ".onnx";
        Path direct = getModelDir(entry).resolve(fileNameFromUrl(entry.modelUrl(), fallback));
        if (Files.isRegularFile(direct)) return direct;
        try (var stream = Files.list(getModelDir(entry))) {
            return stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".onnx"))
                    .findFirst()
                    .orElse(direct);
        } catch (Exception ex) {
            return direct;
        }
    }

    static Path getConfigFile(PiperPlusCatalog.Entry entry) {
        Path direct = getModelDir(entry).resolve(fileNameFromUrl(entry.configUrl(), "config.json"));
        if (Files.isRegularFile(direct)) return direct;
        Path fallback = getModelDir(entry).resolve("config.json");
        return Files.isRegularFile(fallback) ? fallback : direct;
    }

    static boolean isInstalled(PiperPlusCatalog.Entry entry) {
        if (entry == null) return false;
        return Files.isRegularFile(getModelFile(entry)) && Files.isRegularFile(getConfigFile(entry));
    }

    static int getSampleRate(PiperPlusCatalog.Entry entry) {
        if (entry == null) return 22050;
        Path configPath = getConfigFile(entry);
        if (!Files.isRegularFile(configPath)) return 22050;
        try {
            String json = Files.readString(configPath);
            JSONObject root = new JSONObject(json);
            JSONObject audio = root.optJSONObject("audio");
            if (audio == null) return 22050;
            int sampleRate = audio.optInt("sample_rate", 22050);
            return sampleRate > 0 ? sampleRate : 22050;
        } catch (Exception ex) {
            return 22050;
        }
    }

    static String statusText(PiperPlusCatalog.Entry entry) {
        if (entry == null) return "No model selected";
        if (isInstalled(entry)) {
            return "Installed: " + getModelDir(entry).toAbsolutePath();
        }
        return "Not downloaded";
    }

    static void ensureDownloaded(PiperPlusCatalog.Entry entry) throws IOException, InterruptedException {
        Objects.requireNonNull(entry, "entry");
        Path dir = getModelDir(entry);
        Files.createDirectories(dir);
        downloadTo(entry.modelUrl(), getModelFile(entry));
        downloadTo(entry.configUrl(), getConfigFile(entry));
        verifyModelIfNeeded(entry);
        writeMetadata(entry);
    }

    static void deleteInstalled(PiperPlusCatalog.Entry entry) throws IOException {
        if (entry == null) return;
        Path dir = getModelDir(entry);
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
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

    static List<PiperPlusCatalog.Entry> discoverInstalledEntries() {
        LinkedHashMap<String, PiperPlusCatalog.Entry> found = new LinkedHashMap<>();
        Path root = getRootDir();
        if (!Files.isDirectory(root)) return List.of();
        try (var dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).sorted().forEach(dir -> {
                PiperPlusCatalog.Entry entry = readInstalledEntry(dir);
                if (entry != null) found.putIfAbsent(entry.id().toLowerCase(), entry);
            });
        } catch (Exception ignore) {}
        return new ArrayList<>(found.values());
    }

    private static PiperPlusCatalog.Entry readInstalledEntry(Path dir) {
        try {
            Path configPath = dir.resolve("config.json");
            if (!Files.isRegularFile(configPath)) return null;
            Path modelPath;
            try (var files = Files.list(dir)) {
                modelPath = files.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".onnx"))
                        .findFirst().orElse(null);
            }
            if (modelPath == null) return null;

            Properties props = new Properties();
            Path meta = dir.resolve("mobmate-model.properties");
            if (Files.isRegularFile(meta)) {
                try (InputStream in = Files.newInputStream(meta, StandardOpenOption.READ)) {
                    props.load(in);
                }
            }

            JSONObject config = new JSONObject(Files.readString(configPath));
            String configLang = inferConfigLanguage(config);

            String id = propOr(props, "id", dir.getFileName().toString());
            String installId = propOr(props, "install_id", dir.getFileName().toString());
            String displayName = propOr(props, "display_name", "piper-plus Local - " + dir.getFileName());
            String lang = normalizeModelLanguage(propOr(props, "language", configLang));
            String textModeLanguageTag = resolveLocalTextModeLanguageTag(props, config, lang);
            String summary = "User-provided local piper-plus model. License and quality depend on the files you placed here.";
            String license = propOr(props, "license", "user-provided");
            String sourcePageUrl = propOr(props, "source_page_url", "");
            String modelUrl = propOr(props, "model_url", modelPath.getFileName().toString());
            String configUrl = propOr(props, "config_url", "config.json");
            String modelSha256 = propOr(props, "model_sha256", "");

            return new PiperPlusCatalog.Entry(
                    id,
                    installId,
                    displayName,
                    lang,
                    textModeLanguageTag,
                    summary,
                    license,
                    sourcePageUrl,
                    modelUrl,
                    configUrl,
                    modelSha256
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private static void writeMetadata(PiperPlusCatalog.Entry entry) throws IOException {
        Properties props = new Properties();
        props.setProperty("id", entry.id());
        props.setProperty("install_id", entry.installKey());
        props.setProperty("display_name", entry.displayName());
        props.setProperty("language", entry.language());
        props.setProperty("text_mode_language_tag", entry.cliTextLanguage());
        props.setProperty("license", entry.license());
        props.setProperty("source_page_url", entry.sourcePageUrl());
        props.setProperty("model_url", entry.modelUrl());
        props.setProperty("config_url", entry.configUrl());
        props.setProperty("model_sha256", entry.modelSha256());
        try (OutputStream out = Files.newOutputStream(
                getModelDir(entry).resolve("mobmate-model.properties"),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            props.store(out, "MobMate piper-plus model metadata");
        }
    }

    private static void downloadTo(String url, Path target) throws IOException, InterruptedException {
        Path temp = target.resolveSibling(target.getFileName().toString() + ".part");
        Files.createDirectories(target.getParent());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Download failed: " + response.statusCode() + " " + url);
        }
        try (InputStream in = response.body()) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void verifyModelIfNeeded(PiperPlusCatalog.Entry entry) throws IOException {
        if (entry.modelSha256() == null || entry.modelSha256().isBlank()) return;
        String actual = sha256(getModelFile(entry));
        if (!entry.modelSha256().equalsIgnoreCase(actual)) {
            throw new IOException("SHA-256 mismatch for " + entry.id());
        }
    }

    private static String fileNameFromUrl(String url, String fallback) {
        if (url == null || url.isBlank()) return fallback;
        int slash = url.lastIndexOf('/');
        String name = (slash >= 0) ? url.substring(slash + 1) : url;
        int q = name.indexOf('?');
        if (q >= 0) name = name.substring(0, q);
        return name.isBlank() ? fallback : name;
    }

    private static String safeName(String value) {
        if (value == null || value.isBlank()) return "default";
        return value.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    private static String propOr(Properties props, String key, String fallback) {
        String value = props.getProperty(key, fallback);
        return value == null ? fallback : value.trim();
    }

    private static String normalizeModelLanguage(String lang) {
        if (lang == null || lang.isBlank()) return "ja";
        String trimmed = lang.trim().toLowerCase();
        if ("multilingual".equals(trimmed)) return "ja";
        if (trimmed.startsWith("zh")) return "zh";
        if (trimmed.startsWith("ja")) return "ja";
        if (trimmed.startsWith("en")) return "en";
        if (trimmed.startsWith("es")) return "es";
        if (trimmed.startsWith("fr")) return "fr";
        if (trimmed.startsWith("pt")) return "pt";
        return trimmed;
    }

    private static String resolveLocalTextModeLanguageTag(Properties props, JSONObject config, String lang) {
        String configured = propOr(props, "text_mode_language_tag", "");
        String normalizedConfigured = normalizeCliLanguageTag(configured);
        String inferredCombined = inferCombinedLanguageTag(config);
        if (!inferredCombined.isBlank()) {
            // User-provided local multilingual models often arrive with a simple "ja" fallback in metadata.
            // In that case, prefer the config-derived combined tag so PiperPlus uses the right multilingual frontend.
            if (normalizedConfigured.isBlank() || normalizedConfigured.equals(lang)) {
                return inferredCombined;
            }
        }
        if (!normalizedConfigured.isBlank()) return normalizedConfigured;
        String inferredSingle = inferSingleLanguageTextTag(config);
        if (!inferredSingle.isBlank()) return inferredSingle;
        return normalizeCliLanguageTag(lang);
    }

    private static String inferConfigLanguage(JSONObject config) {
        if (config == null) return "";
        JSONObject language = config.optJSONObject("language");
        String configLang = language != null ? language.optString("code", "") : "";
        if (!configLang.isBlank()) return normalizeModelLanguage(configLang);

        JSONObject espeak = config.optJSONObject("espeak");
        String espeakVoice = espeak != null ? espeak.optString("voice", "") : "";
        if (!espeakVoice.isBlank()) return normalizeModelLanguage(espeakVoice);

        String phonemeType = config.optString("phoneme_type", "").trim().toLowerCase();
        if ("pinyin".equals(phonemeType)) return "zh";
        return "";
    }

    private static String inferSingleLanguageTextTag(JSONObject config) {
        return normalizeCliLanguageTag(inferConfigLanguage(config));
    }

    private static String normalizeCliLanguageTag(String tag) {
        if (tag == null || tag.isBlank()) return "";
        String normalized = tag.trim().toLowerCase();
        if (normalized.contains("-")) return normalized;
        if (normalized.startsWith("zh")) return "zh";
        if (normalized.startsWith("ja")) return "ja";
        if (normalized.startsWith("en")) return "en";
        if (normalized.startsWith("es")) return "es";
        if (normalized.startsWith("fr")) return "fr";
        if (normalized.startsWith("pt")) return "pt";
        return normalized;
    }

    private static String inferCombinedLanguageTag(JSONObject config) {
        if (config == null) return "";
        JSONObject language = config.optJSONObject("language");
        String configLang = language != null ? language.optString("code", "").trim().toLowerCase() : "";
        JSONObject languageIdMap = config.optJSONObject("language_id_map");
        if (!"multilingual".equals(configLang) || languageIdMap == null || languageIdMap.keySet().isEmpty()) {
            return "";
        }
        return languageIdMap.keySet().stream()
                .sorted(Comparator.comparingInt(key -> languageIdMap.optInt(key, Integer.MAX_VALUE)))
                .map(key -> key == null ? "" : key.trim().toLowerCase())
                .filter(key -> !key.isBlank())
                .reduce((a, b) -> a + "-" + b)
                .orElse("");
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) >= 0) {
                    if (read > 0) digest.update(buf, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IOException("Failed to compute SHA-256", ex);
        }
    }
}
