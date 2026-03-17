package whisper;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class LanguageOptions {

    static final String[] WHISPER_LANGS = new String[] {
            "auto",
            "en","ja","zh","de","fr","es","ru","ko","it","pt","nl","pl","tr","uk","vi","th","id","sv","fi","da","no","cs","hu","ro","el","he","hi","ar",
            "af","am","as","az","ba","be","bg","bn","bo","br","bs","ca","cy","et","eu","fa","fo","gl","gu","ha","haw","hr","ht","hy","is","jw","ka","kk",
            "km","kn","la","lb","ln","lo","lt","lv","mg","mi","mk","ml","mn","mr","ms","mt","my","ne","nn","oc","pa","ps","sa","sd","si","sk","sl","sn",
            "so","sq","sr","su","sw","ta","te","tg","tk","tl","tt","ur","uz","yi","yo","yue"
    };

    static final String[] TRANSLATION_LANGS = new String[] {
            "af","am","ar","ast","az","ba","be","bg","bn","br","bs","ca","ceb","cs","cy","da","de","el","en","es","et","fa","ff","fi","fr","fy","ga","gd",
            "gl","gu","ha","he","hi","hr","ht","hu","hy","id","ig","ilo","is","it","ja","jv","ka","kk","km","kn","ko","lb","lg","ln","lo","lt","lv","mg",
            "mk","ml","mn","mr","ms","my","ne","nl","no","ns","oc","or","pa","pl","ps","pt","ro","ru","sd","si","sk","sl","so","sq","sr","ss","su","sv",
            "sw","ta","th","tl","tn","tr","uk","ur","uz","vi","wo","xh","yi","yo","zh","zu"
    };

    static final String[] TRANSLATION_TARGETS = buildTranslationTargets();

    private static final Set<String> TRANSLATION_LANG_SET = new HashSet<>(Arrays.asList(TRANSLATION_LANGS));
    private static final Set<String> WHISPER_LANG_SET = new HashSet<>(Arrays.asList(WHISPER_LANGS));
    private static final Map<String, String> LANGUAGE_NAME_OVERRIDES = createLanguageNameOverrides();

    private LanguageOptions() {}

    private static String[] buildTranslationTargets() {
        String[] values = new String[TRANSLATION_LANGS.length + 1];
        values[0] = "OFF";
        System.arraycopy(TRANSLATION_LANGS, 0, values, 1, TRANSLATION_LANGS.length);
        return values;
    }

    private static Map<String, String> createLanguageNameOverrides() {
        Map<String, String> map = new HashMap<>();
        map.put("auto", "Auto detect");
        map.put("off", "Off");
        map.put("ast", "Asturian");
        map.put("az", "Azerbaijani");
        map.put("ba", "Bashkir");
        map.put("ceb", "Cebuano");
        map.put("ff", "Fulah");
        map.put("fy", "West Frisian");
        map.put("haw", "Hawaiian");
        map.put("ig", "Igbo");
        map.put("ilo", "Ilocano");
        map.put("jw", "Javanese");
        map.put("jv", "Javanese");
        map.put("lg", "Ganda");
        map.put("ln", "Lingala");
        map.put("ns", "Northern Sotho");
        map.put("oc", "Occitan");
        map.put("or", "Odia");
        map.put("sd", "Sindhi");
        map.put("ss", "Swati");
        map.put("su", "Sundanese");
        map.put("tl", "Tagalog");
        map.put("tn", "Tswana");
        map.put("tt", "Tatar");
        map.put("wo", "Wolof");
        map.put("xh", "Xhosa");
        map.put("yue", "Cantonese");
        map.put("zu", "Zulu");
        return map;
    }
    static String[] whisperLangs() {
        return WHISPER_LANGS.clone();
    }

    static String[] translationTargets() {
        return TRANSLATION_TARGETS.clone();
    }

    static ListCellRenderer<? super String> whisperRenderer() {
        return createRenderer(LanguageOptions::displayWhisperLang);
    }

    static ListCellRenderer<? super String> translationRenderer() {
        return createRenderer(LanguageOptions::displayTranslationTarget);
    }

    static String displayWhisperLang(String value) {
        return displayLabel(value, true);
    }

    static String displayTranslationTarget(String value) {
        return displayLabel(value, false);
    }

    static String promptLanguageLabel(String value) {
        if (value == null || value.isBlank()) {
            return "text";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("off".equals(normalized) || "auto".equals(normalized)) {
            return "text";
        }
        String name = resolveLanguageName(normalized);
        return (name == null || name.isBlank()) ? "text" : name;
    }

    private static ListCellRenderer<? super String> createRenderer(java.util.function.Function<String, String> labeler) {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value == null) {
                    setText("");
                } else {
                    setText(labeler.apply(value.toString()));
                }
                return this;
            }
        };
    }

    private static String displayLabel(String value, boolean allowAuto) {
        if (value == null || value.isBlank()) {
            return allowAuto ? "Auto detect (auto)" : "Off (OFF)";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("off".equals(normalized)) {
            return "Off (OFF)";
        }
        if ("auto".equals(normalized)) {
            return "Auto detect (auto)";
        }
        String name = resolveLanguageName(normalized);
        if (name == null || name.isBlank() || name.equalsIgnoreCase(normalized)) {
            name = normalized.toUpperCase(Locale.ROOT);
        }
        return name + " (" + normalized + ")";
    }

    private static String resolveLanguageName(String normalized) {
        String name = LANGUAGE_NAME_OVERRIDES.get(normalized);
        if (name == null || name.isBlank()) {
            Locale locale = Locale.forLanguageTag(normalized);
            name = locale.getDisplayLanguage(Locale.ENGLISH);
            if (name == null || name.isBlank() || name.equalsIgnoreCase(normalized)) {
                String enName = locale.getDisplayLanguage(Locale.ENGLISH);
                if (enName != null && !enName.isBlank() && !enName.equalsIgnoreCase(normalized)) {
                    name = enName;
                }
            }
        }
        return name;
    }

    static boolean isWhisperLangSupported(String value) {
        if (value == null) return false;
        return WHISPER_LANG_SET.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    static String normalizeWhisperLang(String value, String fallback) {
        String normalizedFallback = (fallback == null || fallback.isBlank()) ? "auto" : fallback.trim().toLowerCase(Locale.ROOT);
        if (!WHISPER_LANG_SET.contains(normalizedFallback)) {
            normalizedFallback = "auto";
        }
        if (value == null || value.isBlank()) {
            return normalizedFallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return WHISPER_LANG_SET.contains(normalized) ? normalized : normalizedFallback;
    }

    static boolean isTranslationLangSupported(String value) {
        if (value == null) return false;
        return TRANSLATION_LANG_SET.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    static String normalizeTranslationTarget(String value) {
        if (value == null || value.isBlank()) {
            return "OFF";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("off".equals(normalized)) {
            return "OFF";
        }
        return TRANSLATION_LANG_SET.contains(normalized) ? normalized : "OFF";
    }
}
