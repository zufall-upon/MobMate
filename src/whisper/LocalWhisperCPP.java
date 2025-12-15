package whisper;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import io.github.ggerganov.whispercpp.WhisperCpp;
import io.github.ggerganov.whispercpp.params.CBool;
import io.github.ggerganov.whispercpp.params.WhisperFullParams;
import io.github.ggerganov.whispercpp.params.WhisperSamplingStrategy;

public class LocalWhisperCPP {

    private String language;
    private String initialPrompt;
    private Map<String, List<String>> dictMap = new HashMap<>();
    private Random rnd = new Random();

    private static WhisperCpp whisper = new WhisperCpp();

    public LocalWhisperCPP(File model) throws FileNotFoundException {
        whisper.initContext(model);

        this.language = loadSetting("language", "en");
        this.initialPrompt = loadSetting("initial_prompt", "");
        Config.log("=== Whisper init  === lang=" + this.language + ";");
        loadDictionary();
    }

    public String transcribe(File file) throws UnsupportedAudioFileException, IOException {
        String result = "";
        AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(file);

        byte[] b = new byte[audioInputStream.available()];
        float[] floats = new float[b.length / 2];

        WhisperFullParams params = whisper.getFullDefaultParams(WhisperSamplingStrategy.WHISPER_SAMPLING_BEAM_SEARCH);
        params.setProgressCallback((ctx, state, progress, user_data) -> Config.log("progress: " + progress));
        params.print_progress = CBool.FALSE;
        params.language = this.language;
        params.initial_prompt = initialPrompt;
        params.detect_language = CBool.FALSE;

        params.n_threads = Runtime.getRuntime().availableProcessors();

        try {
            int r = audioInputStream.read(b);

            for (int i = 0, j = 0; i < r; i += 2, j++) {
                short sample = (short) (((b[i + 1] & 0xFF) << 8) | (b[i] & 0xFF));
                floats[j] = sample / 32768.0f;
            }

            result = whisper.fullTranscribe(params, floats);

            // === Post-filter ignore words ===
            String ignoreMode = Config.get("ignore.mode", "simple"); // simple | regex
            List<String> ignoreWords = loadIgnoreWords();
            if ("regex".equalsIgnoreCase(ignoreMode)) {
                for (String rule : ignoreWords) {
                    try {
                        Pattern p = Pattern.compile(rule);
                        if (p.matcher(result).find()) {
                            Config.log("Ignored by regex filter: " + rule);
                            return "";
                        }
                    } catch (PatternSyntaxException e) {
                        Config.logError("Invalid regex ignored: " + rule,e);
                    }
                }
            } else {
                // simple mode
                for (String w : ignoreWords) {
                    if (result.contains(w)) {
                        Config.log("Ignored by word filter: " + w);
                        return "";
                    }
                }
            }

        } finally {
            audioInputStream.close();
        }
        return result;
    }

    public String transcribeRaw(byte[] pcmData) throws IOException {
        int numSamples = pcmData.length / 2;
        float[] floats = new float[numSamples];

        for (int i = 0, j = 0; i < pcmData.length; i += 2, j++) {
            short sample = (short) (((pcmData[i + 1] & 0xFF) << 8) | (pcmData[i] & 0xFF));
            floats[j] = sample / 32768.0f;
        }

        WhisperFullParams params = whisper.getFullDefaultParams(WhisperSamplingStrategy.WHISPER_SAMPLING_GREEDY);
//        params.setProgressCallback((ctx, state, progress, user_data) -> log("progress: " + progress));
        params.print_progress = CBool.FALSE;
        params.language = this.language;
        params.initial_prompt = initialPrompt;
        params.detect_language = CBool.FALSE;
        params.max_len = 32; //TODO 1 sentence
        params.single_segment = CBool.TRUE;

        params.n_threads = Runtime.getRuntime().availableProcessors();

        // ---- transcribe ----
        String raw = whisper.fullTranscribe(params, floats);
//        raw = filterLowConfidenceTokens(raw);
//        log("(raw): " + whisper.fullTranscribeWithTime(params, floats));

        // === Post-filter ignore words ===
        List<String> ignoreWords = loadIgnoreWords();
        for (String w : ignoreWords) {
            if (!w.isEmpty() && raw.contains(w)) {
//                log("Ignored by word filter(raw): " + w);
                return "";
            }
        }
        return raw;
    }

    public static void main(String[] args) throws Exception {
        Config.logDebug("-1");
        LocalWhisperCPP w = new LocalWhisperCPP(new File("models", "ggml-small.bin"));
        Config.logDebug("-");
        w.transcribe(new File("jfk.wav"));
        long t1 = System.currentTimeMillis();
        w.transcribe(new File("jfk.wav"));
        long t2 = System.currentTimeMillis();
        Config.logDebug("LocalWhisperCPP.main() " + (t2 - t1) + " ms");
    }

    private static String loadSetting(String key, String defaultValue) {
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
    private List<String> loadIgnoreWords() {
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
    private void loadDictionary() {
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
    public String applyDictionary(String text) {
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
}
