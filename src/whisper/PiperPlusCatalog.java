package whisper;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

final class PiperPlusCatalog {

    private static final List<String> PICKER_PRIMARY_ORDER = List.of(
            "css10-ja-6lang-ja",
            "css10-ja-6lang-en",
            "css10-ja-6lang-es",
            "rhasspy-fr-fr-gilles-low",
            "rhasspy-pt-br-cadu-medium",
            "css10-ja-6lang-zh"
    );

    private PiperPlusCatalog() {}

    static List<Entry> entries() {
        LinkedHashMap<String, Entry> merged = new LinkedHashMap<>();
        for (Entry entry : builtInEntries()) {
            merged.put(entry.id().toLowerCase(Locale.ROOT), entry);
        }
        for (Entry entry : PiperPlusModelManager.discoverInstalledEntries()) {
            merged.putIfAbsent(entry.id().toLowerCase(Locale.ROOT), entry);
        }
        return new ArrayList<>(merged.values());
    }

    private static List<Entry> builtInEntries() {
        return List.of(
                new Entry(
                        "css10-ja-6lang-ja",
                        "css10-ja-6lang-fp16",
                        "Piper+ CSS10 6lang - Japanese",
                        "ja",
                        "ja-en-zh-es-fr-pt",
                        "Multilingual CSS10 voice routed through the shared 6-language model. Natural model-side intonation is expected, but fine-grained prosody transfer is not guaranteed.",
                        "css10-public-domain",
                        "https://huggingface.co/ayousanz/piper-plus-css10-ja-6lang",
                        "https://huggingface.co/ayousanz/piper-plus-css10-ja-6lang/resolve/main/css10-ja-6lang-fp16.onnx",
                        "https://huggingface.co/ayousanz/piper-plus-css10-ja-6lang/resolve/main/config.json",
                        ""
                ),
                new Entry(
                        "css10-ja-6lang-en",
                        "css10-ja-6lang-fp16",
                        "Piper+ CSS10 6lang - English",
                        "en",
                        "en",
                        "Multilingual CSS10 voice routed through the shared 6-language model. English path is useful for dependency-light cross-language playback, though voice identity stays shared with the Japanese base model.",
                        "css10-public-domain",
                        "https://huggingface.co/ayousanz/piper-plus-css10-ja-6lang",
                        "https://huggingface.co/ayousanz/piper-plus-css10-ja-6lang/resolve/main/css10-ja-6lang-fp16.onnx",
                        "https://huggingface.co/ayousanz/piper-plus-css10-ja-6lang/resolve/main/config.json",
                        ""
                ),
                new Entry(
                        "css10-ja-6lang-zh",
                        "css10-ja-6lang-fp16",
                        "Piper+ CSS10 6lang - Chinese",
                        "zh",
                        "zh",
                        "Multilingual CSS10 voice routed through the shared 6-language model. Chinese text mode uses the same shared multilingual frontend path.",
                        "css10-public-domain",
                        "https://huggingface.co/ayousanz/piper-plus-css10-ja-6lang",
                        "https://huggingface.co/ayousanz/piper-plus-css10-ja-6lang/resolve/main/css10-ja-6lang-fp16.onnx",
                        "https://huggingface.co/ayousanz/piper-plus-css10-ja-6lang/resolve/main/config.json",
                        ""
                ),
                new Entry(
                        "css10-ja-6lang-es",
                        "css10-ja-6lang-fp16",
                        "Piper+ CSS10 6lang - Spanish",
                        "es",
                        "es",
                        "Multilingual CSS10 voice routed through the shared 6-language model. Spanish text mode uses the same shared multilingual frontend path.",
                        "css10-public-domain",
                        "https://huggingface.co/ayousanz/piper-plus-css10-ja-6lang",
                        "https://huggingface.co/ayousanz/piper-plus-css10-ja-6lang/resolve/main/css10-ja-6lang-fp16.onnx",
                        "https://huggingface.co/ayousanz/piper-plus-css10-ja-6lang/resolve/main/config.json",
                        ""
                ),
                new Entry(
                        "css10-ja-6lang-fr",
                        "css10-ja-6lang-fp16",
                        "Piper+ CSS10 6lang - French",
                        "fr",
                        "fr",
                        "Multilingual CSS10 voice routed through the shared 6-language model. French text mode uses the same shared multilingual frontend path.",
                        "css10-public-domain",
                        "https://huggingface.co/ayousanz/piper-plus-css10-ja-6lang",
                        "https://huggingface.co/ayousanz/piper-plus-css10-ja-6lang/resolve/main/css10-ja-6lang-fp16.onnx",
                        "https://huggingface.co/ayousanz/piper-plus-css10-ja-6lang/resolve/main/config.json",
                        ""
                ),
                new Entry(
                        "css10-ja-6lang-pt",
                        "css10-ja-6lang-fp16",
                        "Piper+ CSS10 6lang - Portuguese",
                        "pt",
                        "pt",
                        "Multilingual CSS10 voice routed through the shared 6-language model. Portuguese text mode uses the same shared multilingual frontend path.",
                        "css10-public-domain",
                        "https://huggingface.co/ayousanz/piper-plus-css10-ja-6lang",
                        "https://huggingface.co/ayousanz/piper-plus-css10-ja-6lang/resolve/main/css10-ja-6lang-fp16.onnx",
                        "https://huggingface.co/ayousanz/piper-plus-css10-ja-6lang/resolve/main/config.json",
                        ""
                ),
                new Entry(
                        "rhasspy-fr-fr-gilles-low",
                        "rhasspy-fr-fr-gilles-low",
                        "Piper+ French - Gilles",
                        "fr",
                        "fr",
                        "Curated default French voice based on Rhasspy Piper voices. Dataset license verified as CC0 from the model card on 2026-04-01.",
                        "CC0",
                        "https://huggingface.co/rhasspy/piper-voices/tree/main/fr/fr_FR/gilles/low",
                        "https://huggingface.co/rhasspy/piper-voices/resolve/main/fr/fr_FR/gilles/low/fr_FR-gilles-low.onnx",
                        "https://huggingface.co/rhasspy/piper-voices/resolve/main/fr/fr_FR/gilles/low/fr_FR-gilles-low.onnx.json",
                        ""
                ),
                new Entry(
                        "rhasspy-pt-br-cadu-medium",
                        "rhasspy-pt-br-cadu-medium",
                        "Piper+ Portuguese - Cadu",
                        "pt",
                        "pt",
                        "Curated default Portuguese voice based on Rhasspy Piper voices. Dataset license verified as CC0 from the model card on 2026-04-01.",
                        "CC0",
                        "https://huggingface.co/rhasspy/piper-voices/tree/main/pt/pt_BR/cadu/medium",
                        "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/cadu/medium/pt_BR-cadu-medium.onnx",
                        "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/cadu/medium/pt_BR-cadu-medium.onnx.json",
                        ""
                )
        );
    }

    static Entry findById(String id) {
        if (id == null || id.isBlank()) return null;
        for (Entry entry : entries()) {
            if (entry.id().equalsIgnoreCase(id.trim())) return entry;
        }
        for (Entry entry : entries()) {
            if (entry.installKey().equalsIgnoreCase(id.trim())) return entry;
        }
        return null;
    }

    static List<Entry> pickerEntries() {
        LinkedHashMap<String, Entry> visible = new LinkedHashMap<>();
        for (String id : PICKER_PRIMARY_ORDER) {
            Entry entry = findById(id);
            if (entry != null) {
                visible.put(entry.id().toLowerCase(Locale.ROOT), entry);
            }
        }

        LinkedHashMap<String, Entry> all = new LinkedHashMap<>();
        for (Entry entry : entries()) {
            all.put(entry.id().toLowerCase(Locale.ROOT), entry);
        }

        for (Entry entry : all.values()) {
            if (isHiddenBuiltInPickerEntry(entry)) continue;
            visible.putIfAbsent(entry.id().toLowerCase(Locale.ROOT), entry);
        }
        return new ArrayList<>(visible.values());
    }

    static Entry pickerSelectionForSaved(String currentId) {
        Entry saved = findById(currentId);
        if (saved == null) return null;
        if (!isHiddenBuiltInPickerEntry(saved)) return saved;

        String replacementId = switch (saved.id().toLowerCase(Locale.ROOT)) {
            case "css10-ja-6lang-fr" -> "rhasspy-fr-fr-gilles-low";
            case "css10-ja-6lang-pt" -> "rhasspy-pt-br-cadu-medium";
            default -> saved.id();
        };
        Entry replacement = findById(replacementId);
        return replacement != null ? replacement : saved;
    }

    private static boolean isHiddenBuiltInPickerEntry(Entry entry) {
        if (entry == null) return false;
        String id = entry.id().toLowerCase(Locale.ROOT);
        return "css10-ja-6lang-fr".equals(id) || "css10-ja-6lang-pt".equals(id);
    }

    static Entry resolveForLanguage(String currentId, String requestedLanguage) {
        Entry current = findById(currentId);
        String normalized = normalizeOutputLanguage(requestedLanguage);
        if (current == null) {
            return initialDefaultEntryForLanguage(normalized);
        }
        if (normalized.isBlank() || normalized.equalsIgnoreCase(current.language())) {
            return current;
        }
        Entry curatedOverride = curatedOverrideEntryForLanguage(normalized);
        if (curatedOverride != null) {
            return curatedOverride;
        }
        for (Entry entry : entries()) {
            if (entry.installKey().equalsIgnoreCase(current.installKey())
                    && normalized.equalsIgnoreCase(entry.language())) {
                return entry;
            }
        }
        return current;
    }

    static Entry initialDefaultEntryForLanguage(String requestedLanguage) {
        String normalized = normalizeOutputLanguage(requestedLanguage);
        String id = switch (normalized) {
            case "ja" -> "css10-ja-6lang-ja";
            case "en" -> "css10-ja-6lang-en";
            case "es" -> "css10-ja-6lang-es";
            case "fr" -> "rhasspy-fr-fr-gilles-low";
            case "pt" -> "rhasspy-pt-br-cadu-medium";
            default -> "";
        };
        return id.isBlank() ? null : findById(id);
    }

    static Entry curatedOverrideEntryForLanguage(String requestedLanguage) {
        String normalized = normalizeOutputLanguage(requestedLanguage);
        String id = switch (normalized) {
            case "fr" -> "rhasspy-fr-fr-gilles-low";
            case "pt" -> "rhasspy-pt-br-cadu-medium";
            default -> "";
        };
        return id.isBlank() ? null : findById(id);
    }

    static String normalizeOutputLanguage(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (normalized.startsWith("zh")) return "zh";
        if (normalized.startsWith("en")) return "en";
        if (normalized.startsWith("ja")) return "ja";
        if (normalized.startsWith("es")) return "es";
        if (normalized.startsWith("fr")) return "fr";
        if (normalized.startsWith("pt")) return "pt";
        if (normalized.startsWith("ko")) return "ko";
        return normalized;
    }

    record Entry(
            String id,
            String installId,
            String displayName,
            String language,
            String textModeLanguageTag,
            String summary,
            String license,
            String sourcePageUrl,
            String modelUrl,
            String configUrl,
            String modelSha256
    ) {
        String comboLabel() {
            return switch (id.toLowerCase(Locale.ROOT)) {
                case "css10-ja-6lang-ja" -> "Recommended - Japanese [JA]";
                case "css10-ja-6lang-en" -> "Recommended - English [EN]";
                case "css10-ja-6lang-es" -> "Recommended - Spanish [ES]";
                case "rhasspy-fr-fr-gilles-low" -> "Recommended - French (Gilles) [FR]";
                case "rhasspy-pt-br-cadu-medium" -> "Recommended - Portuguese (Cadu) [PT]";
                case "css10-ja-6lang-zh" -> "Chinese (manual model recommended) [ZH]";
                default -> {
                    String prefix = "user-provided".equalsIgnoreCase(license) ? "Local - " : "";
                    yield prefix + displayName + " [" + language.toUpperCase(Locale.ROOT) + "]";
                }
            };
        }

        String installKey() {
            if (installId == null || installId.isBlank()) return id;
            return installId;
        }

        String cliTextLanguage() {
            if (textModeLanguageTag == null || textModeLanguageTag.isBlank()) return language;
            return textModeLanguageTag;
        }

        boolean isDownloadable() {
            return modelUrl != null && modelUrl.startsWith("http")
                    && configUrl != null && configUrl.startsWith("http");
        }
    }
}
