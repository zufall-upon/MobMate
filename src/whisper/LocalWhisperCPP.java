package whisper;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.github.ggerganov.whispercpp.WhisperCpp;
import io.github.ggerganov.whispercpp.params.CBool;
import io.github.ggerganov.whispercpp.params.WhisperFullParams;
import io.github.ggerganov.whispercpp.params.WhisperSamplingStrategy;

import static whisper.MobMateWhisp.prefs;

public class LocalWhisperCPP {

    // NOTE: LocalWhisperCPP is singleton in current design.
    // initialPromptDirty is shared intentionally.
    private String language;
    private String initialPrompt;
    private final WhisperFullParams baseParamsGreedy;
    private final WhisperFullParams baseParamsBeam;
    private static volatile boolean initialPromptDirty = true;
    private String cachedInitialPrompt = null;
    // ignore cache

    private static volatile boolean ignoreDirty = true;
    private List<String> cachedIgnoreWords = new ArrayList<>();
    private final List<Pattern> cachedIgnorePatterns = new ArrayList<>();
    private String cachedIgnoreMode = "simple";
    private boolean instanceIgnoreLoaded = false;   // ★追加
    public static volatile boolean dictionaryDirty = true;
    private static final WhisperCpp whisperMain = new WhisperCpp();
    private static final WhisperCpp whisperHearing = new WhisperCpp();
    private final WhisperCpp whisper; // ★staticを外してインスタンス変数に
    private boolean isHearingMode = false; // ★Hearingモードかどうかのフラグ
    private volatile boolean lowGainMic = false;
    private volatile boolean hearingTranslateToEn = false;


    public synchronized void setHearingTranslateToEn(boolean v) {
        Config.log("[Hearing][Translate] setHearingTranslateToEn: " + v);
        this.hearingTranslateToEn = v;
    }
    public void setLowGainMic(boolean v) {
        this.lowGainMic = v;
    }
    public synchronized void setLanguage(String lang) {
        if (lang == null || lang.trim().isEmpty()) lang = "auto";
        this.language = lang;

        // ベースparamsも合わせておく（主に安全策）
        try {
            if (baseParamsGreedy != null) {
                baseParamsGreedy.language = lang;
                baseParamsGreedy.detect_language = "auto".equals(lang) ? CBool.TRUE : CBool.FALSE;
                baseParamsGreedy.write();
            }
        } catch (Exception ignore) {}
    }

    public LocalWhisperCPP(File model, String mode) throws FileNotFoundException {
        // ★modeに応じて使うインスタンスを選択
        this.isHearingMode = "Hearing".equals(mode);
        if (isHearingMode) {
            this.whisper = whisperHearing;
        } else {
            this.whisper = whisperMain;
        }
        // ★既存コンテキストを解放してからロード（ネイティブメモリリーク防止）
//        try { whisper.close(); } catch (Throwable ignore) {}
        whisper.initContext(model);

        // ★Hearingモードは言語自動検出、プロンプトなし
        if (isHearingMode) {
            this.language = "auto"; // 自動検出
            this.initialPrompt = ""; // プロンプトなし
            this.cachedInitialPrompt = "";
            initialPromptDirty = false;
        } else {
            // 通常モード（MobMate本体）
            this.language = Config.loadSetting("language", "en");
            this.initialPrompt = Config.loadSetting("initial_prompt", null);
            this.cachedInitialPrompt = null;
            initialPromptDirty = true;
            this.initialPrompt = buildInitialPrompt();
        }
        baseParamsGreedy =
                whisper.getFullDefaultParams(
                        WhisperSamplingStrategy.WHISPER_SAMPLING_GREEDY
                );
        baseParamsGreedy.print_progress = CBool.FALSE;
        baseParamsGreedy.detect_language = CBool.FALSE;
        baseParamsGreedy.single_segment = CBool.TRUE;
        baseParamsGreedy.max_len = 0;
        baseParamsGreedy.n_threads =
                (!MobMateWhisp.lowGpuMode) ? Math.min(
                        12, // 上限
                        Math.max(4, Runtime.getRuntime().availableProcessors() / 3) // 下限4
                ) : 1;
        baseParamsGreedy.language = this.language;
        baseParamsGreedy.initial_prompt = this.initialPrompt;
        baseParamsGreedy.write();

        baseParamsBeam =
                whisper.getFullDefaultParams(
                        WhisperSamplingStrategy.WHISPER_SAMPLING_BEAM_SEARCH
                );
        baseParamsBeam.print_progress = CBool.FALSE;
        baseParamsBeam.detect_language = CBool.FALSE;
        baseParamsBeam.n_threads =
                (!MobMateWhisp.lowGpuMode) ? Math.min(
                        12, // 上限
                        Math.max(4, Runtime.getRuntime().availableProcessors() / 3) // 下限4
                ) : 1;
        baseParamsBeam.language = this.language;
        baseParamsBeam.initial_prompt = this.initialPrompt;
        baseParamsBeam.write();

        Config.log("=== Whisper init  === lang=" + this.language + ";");
        Config.log("initial_prompt: " + this.initialPrompt);
        Config.loadDictionary();
    }

    private WhisperFullParams createBeamParams() {
        WhisperFullParams p =
                whisper.getFullDefaultParams(
                        WhisperSamplingStrategy.WHISPER_SAMPLING_BEAM_SEARCH
                );
//        p.progress_callback = (ctx, state, progress, user_data) -> {};
        p.progress_callback_user_data = null;
        p.print_progress = CBool.FALSE;
        p.language = this.language;
        p.detect_language = CBool.FALSE;
        p.n_threads =
                (!MobMateWhisp.lowGpuMode) ? Math.min(
                        12,
                        Math.max(4, Runtime.getRuntime().availableProcessors() / 3)
                ) : 1;
        p.initial_prompt = buildInitialPrompt();
        p.write();
        return p;
    }

    private static final int SAMPLE_RATE_HZ = 16000; // whisper.cppのPCM想定(16k)
    private WhisperFullParams createGreedyParams(boolean longMode) {
        WhisperFullParams p =
                whisper.getFullDefaultParams(
                        WhisperSamplingStrategy.WHISPER_SAMPLING_GREEDY
                );
        p.print_progress = CBool.FALSE;
        p.detect_language = CBool.FALSE;
        p.single_segment = longMode ? CBool.FALSE : CBool.TRUE;
        p.no_context     = longMode ? CBool.FALSE : CBool.TRUE;
        p.max_len = 0;
        p.temperature = 0.0f;
        if (lowGainMic) {
            p.temperature = 0.2f;
        }
        p.n_threads =
                (!MobMateWhisp.lowGpuMode) ? Math.min(
                        12,
                        Math.max(4, Runtime.getRuntime().availableProcessors() / 3)
                ) : 1;
        p.language = this.language;
        p.initial_prompt = buildInitialPrompt();
        applyTranslatePref(p);
        p.write();
        return p;
    }
    private WhisperFullParams createGreedyParamsHearing(boolean longMode) {
        WhisperFullParams p =
                whisperHearing.getFullDefaultParams(
                        WhisperSamplingStrategy.WHISPER_SAMPLING_GREEDY
                );
        p.print_progress = CBool.FALSE;
        p.detect_language = CBool.FALSE; // ★FALSEに変更（言語固定の方が安定）
//        p.detect_language = "auto".equals(this.language) ? CBool.TRUE : CBool.FALSE;
        p.single_segment = longMode ? CBool.FALSE : CBool.TRUE;
        p.no_context     = longMode ? CBool.FALSE : CBool.TRUE;
        p.max_len = 0;
        p.temperature = 0.0f;
        if (lowGainMic) {
            p.temperature = 0.2f;
        }
        p.n_threads =
                (!MobMateWhisp.lowGpuMode) ? Math.min(
                        12,
                        Math.max(4, Runtime.getRuntime().availableProcessors() / 3)
                ) : 1;

        // ★言語と自動検出は「autoのときだけ検出」
        //   翻訳ON/OFFに関係なく、このルールが一番安定するっす
        String lang = (this.language == null) ? "auto" : this.language.trim();
        if (lang.isEmpty()) lang = "auto";
        boolean auto = "auto".equalsIgnoreCase(lang);
        p.language = lang;
//        p.detect_language = auto ? CBool.TRUE : CBool.FALSE;

        p.initial_prompt = ""; // ★Hearing用はプロンプトなし
        // ★翻訳時は言語を明示的に設定
        if (this.hearingTranslateToEn) {
            p.translate = CBool.TRUE;
        } else {
            p.translate = CBool.FALSE;
        }
        p.write();
        return p;
    }


    private static void applyTranslatePref(WhisperFullParams p) {
        boolean on = false;
        try {
            on = (MobMateWhisp.prefs != null) &&
                    MobMateWhisp.prefs.getBoolean("whisper.translate_to_en", false);
        } catch (Exception ignore) {}

        // ★重要：ON/OFFどっちでも必ず反映する（OFF時に false を書く）
        try {
            java.lang.reflect.Field f = p.getClass().getField("translate");
            Class<?> t = f.getType();

            if (t == boolean.class) {
                f.setBoolean(p, on);
            } else if (t == Boolean.class) {
                f.set(p, on);
            } else if (t == int.class) {
                f.setInt(p, on ? 1 : 0);
            } else if (t.getSimpleName().equals("CBool")) {
                f.set(p, on ? CBool.TRUE : CBool.FALSE);
            } else {
                // last resort
                f.set(p, on);
            }
        } catch (Exception ignore) {
            // bindingsにフィールドが無い場合は何もしない
        }
    }




    private String buildInitialPrompt() {
        // ★Hearingモードは常に空
        if (isHearingMode) {
            return "";
        }

        if (!initialPromptDirty && cachedInitialPrompt != null) {
            return cachedInitialPrompt;
        }
        StringBuilder prompt = new StringBuilder();
        if (initialPrompt != null && !initialPrompt.isEmpty()) {
            prompt.append(initialPrompt).append("\n");
        }
        List<String> goodWords = Config.loadGoodWords();
        if (!goodWords.isEmpty()) {
            prompt.append("Words: ");
            for (String w : goodWords) {
                prompt.append(w).append(", ");
            }
        }
        cachedInitialPrompt =
                !prompt.isEmpty() ? prompt.toString() : null;
        initialPromptDirty = false;
        return cachedInitialPrompt;
    }

    public static void markInitialPromptDirty() {
        initialPromptDirty = true;
    }

    public static void markDictionaryDirty() {
        dictionaryDirty = true;
    }

    private void reloadIgnoreIfNeeded() {
        if (!ignoreDirty && instanceIgnoreLoaded) return;   // ★変更
        cachedIgnoreMode = Config.get("ignore.mode", "simple");
        cachedIgnoreWords = Config.loadIgnoreWords();
        cachedIgnorePatterns.clear();
        if ("regex".equalsIgnoreCase(cachedIgnoreMode)) {
            for (String rule : cachedIgnoreWords) {
                try {
                    cachedIgnorePatterns.add(Pattern.compile(rule));
                } catch (PatternSyntaxException e) {
                    Config.logError("Invalid regex ignored: " + rule, e);
                }
            }
        }
        instanceIgnoreLoaded = true;    // ★追加
        ignoreDirty = false;
    }

    public boolean isIgnored(String text) {
        if (text == null || text.isEmpty()) return false;
        reloadIgnoreIfNeeded();
        if ("regex".equalsIgnoreCase(cachedIgnoreMode)) {
            for (Pattern p : cachedIgnorePatterns) {
                if (p.matcher(text).find()) {
                    return true;
                }
            }
        } else {
            for (String w : cachedIgnoreWords) {
                if (!w.isEmpty() && text.contains(w)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isIgnoredStatic(String text) {
        if (text == null || text.isEmpty()) return true;
        String mode = Config.get("ignore.mode", "simple");
        List<String> words = Config.loadIgnoreWords();
        if ("regex".equalsIgnoreCase(mode)) {
            for (String w : words) {
                if (text.matches(w)) return true;
            }
        } else {
            for (String w : words) {
                if (!w.isEmpty() && text.contains(w)) return true;
            }
        }
        return false;
    }

    public static void markIgnoreDirty() {
        ignoreDirty = true;
    }

    public static void applySoftwareGain(short[] samples, float gain) {
        if (gain <= 1.01f) return;

        for (int i = 0; i < samples.length; i++) {
            int v = Math.round(samples[i] * gain);
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
            samples[i] = (short) v;
        }
    }

//    public String transcribe(File file) throws UnsupportedAudioFileException, IOException {
//        String raw = "";
//        AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(file);
//
//        byte[] b = new byte[audioInputStream.available()];
//        float[] floats = new float[b.length / 2];
//
//        WhisperFullParams params = createBeamParams();
//
//        try {
//            int r = audioInputStream.read(b);
//            for (int i = 0, j = 0; i < r; i += 2, j++) {
//                short sample = (short) (((b[i + 1] & 0xFF) << 8) | (b[i] & 0xFF));
//                floats[j] = sample / 32768.0f;
//            }
//
//            raw = whisper.fullTranscribe(params, floats);
//            if (isIgnored(raw)) {
//                return "";
//            }
//        } finally {
//            audioInputStream.close();
//        }
//        return raw;
//    }


    // ★Partial ノイズ暴走（!!!!!!!!! 等）を捨てる
    private static boolean isGarbagePartial(String s) {
        if (s == null) return true;
        String t = s.trim();
        if (t.isEmpty()) return true;

        // 1) 同一文字の繰り返し（!だけ、-だけ等）
        if (t.length() >= 40) {
            char c0 = t.charAt(0);
            boolean allSame = true;
            for (int i = 1; i < t.length(); i++) {
                if (t.charAt(i) != c0) { allSame = false; break; }
            }
            if (allSame) return true;
        }

        // 2) 記号率が高すぎる（英数かな漢字がほぼ無い）
        int good = 0, total = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isWhitespace(c)) continue;
            total++;
            if (Character.isLetterOrDigit(c)) good++;
            // 日本語も “文字” 扱い（isLetter が true になる）
        }
        if (total >= 20) {
            double ratio = good * 1.0 / total;
            return ratio < 0.15; // 文字が15%未満ならゴミ扱い
        }

        return false;
    }
    private static String clampForUi(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\r', ' ').replace('\n', ' ').trim();
        if (t.length() <= max) return t;
        return t.substring(0, max) + "…";
    }

    // Hearing(ループバック)経路用：Instant Final を hearing専用ハンドラへ流す
    public String transcribeRawHearing(byte[] pcmData, final MobMateWhisp.Action action, MobMateWhisp mobMateWhisp) throws IOException {
        int numSamples = pcmData.length / 2;
        short[] shorts = new short[numSamples];
        float[] floats = new float[numSamples];
        for (int i = 0, j = 0; i < pcmData.length; i += 2, j++) {
            shorts[j] = (short) (((pcmData[i + 1] & 0xFF) << 8) | (pcmData[i] & 0xFF));
        }
        for (int i = 0; i < numSamples; i++) {
            floats[i] = shorts[i] / 32768.0f;
        }

        int longSec = Config.getInt("whisper.long_mode_sec", 5); // 例:秒以上は長文扱い
        boolean longMode = numSamples >= (SAMPLE_RATE_HZ * longSec);
        WhisperFullParams params = createGreedyParamsHearing(longMode);
        String raw = whisper.fullTranscribe(params, floats);

        // ★Ignore判定
        if (isIgnored(raw)) {
            Config.logDebug("★Hearing Ignored text: " + raw);
            return "";
        } else {
            return raw.trim();
        }
    }

    // ★変更: シグネチャにisEndOfCapture追加（後方互換のためオーバーロード残す）
    public String transcribeRaw(byte[] pcmData, final MobMateWhisp.Action action, MobMateWhisp mobMateWhisp) throws IOException {
        return transcribeRaw(pcmData, action, mobMateWhisp, true); // 既存呼び出しはfinal扱い
    }
    public String transcribeRaw(byte[] pcmData, final MobMateWhisp.Action action, MobMateWhisp mobMateWhisp, boolean isEndOfCapture) throws IOException {
        int numSamples = pcmData.length / 2;
        short[] shorts = new short[numSamples];
        float[] floats = new float[numSamples];
        for (int i = 0, j = 0; i < pcmData.length; i += 2, j++) {
            shorts[j] = (short) (((pcmData[i + 1] & 0xFF) << 8) | (pcmData[i] & 0xFF));
        }
        for (int i = 0; i < numSamples; i++) {
            floats[i] = shorts[i] / 32768.0f;
        }

        int longSec = Config.getInt("whisper.long_mode_sec", 3); // 例:秒以上は長文扱い
        boolean longMode = numSamples >= (SAMPLE_RATE_HZ * longSec);
        WhisperFullParams params = createGreedyParams(longMode);
        String raw = whisper.fullTranscribe(params, floats);

        // ★BGM判定を最優先
//        String lowerRaw = raw.toLowerCase();
//        if ((lowerRaw.startsWith("bgm") || lowerRaw.equals("bgm、") || lowerRaw.equals("bgm,"))) {
//            Config.logDebug("★BGM detected in transcribeRaw, returning empty");
//            MobMateWhisp.setLastPartial("");
//            return "";
//        }

        // ★Ignore判定
        if (isIgnored(raw)) {
            Config.logDebug("★Ignored text: " + raw);
            MobMateWhisp.setLastPartial("");
            return "";
        }

        if (raw != null && !raw.trim().isEmpty()) {
            if (isIgnored(raw)) {
                MobMateWhisp.setLastPartial("");
            }
            // ★Partial暴走対策
            if (isGarbagePartial(raw)) {
                Config.logDebug("★Partial dropped (garbage): len=" + raw.length());
                raw = ""; // lastPartial/overlay/speak に流さない
            }
            // UI/ログ用は長さ制限（詰まり防止）
            raw = clampForUi(raw, 100);
            MobMateWhisp.setLastPartial(raw.trim());
            Config.logDebug("★Partial: " + MobMateWhisp.getLastPartial());
        } else {
            return "";
        }
        int INSTANT_FINAL_MAX_CHARS = (MobMateWhisp.isSilenceAlternate() && !lowGainMic) ? 12 : 10;
        int len = raw.codePointCount(0, raw.length());
        if (len <= INSTANT_FINAL_MAX_CHARS) {
            String fin = raw.trim();
            if (isEndOfCapture) {
                // ★Final経路: 即確定OK
                Config.logDebug("★Instant FINAL (short utterance): " + fin);
                mobMateWhisp.instantFinalFromWhisper(fin, action);
                return "";
            } else {
                // ★Partial経路: キャッシュだけして即確定しない（暴発防止）
                // → finalが来ればそちらが正しい結果を出す / finalが空ならrescueが拾う
                Config.logDebug("★Partial short, skip instant FINAL (awaiting real final): " + fin);
                return raw.trim();
            }
        }

        return raw.trim();
    }
    public static void main(String[] args) throws Exception {
        Config.logDebug("-1");
//        LocalWhisperCPP w = new LocalWhisperCPP(new File("models", "ggml-small.bin"),"");
        Config.logDebug("-");
    }
    /**
     * ★ネイティブのwhisperコンテキスト(モデル+KVキャッシュ)だけを解放する。
     *
     * WhisperCpp.close() は freeParams() → freeContext() の順で呼ぶが、
     * freeParams() 内の JNA Native.free() がネイティブ SEGFAULT を起こし
     * JVM ごと即死するケースがある（hs_err_pid*.log で確認済み）。
     *
     * そのためリフレクションで freeContext() だけを呼び、
     * モデルメモリ (~3 GB) を安全に解放する。
     * params はそのまま残るが、次の initContext() で上書きされるので問題ない。
     */
    public static void freeAllContexts() {
        freeContextOnly(whisperMain,    "main");
        freeContextOnly(whisperHearing, "hearing");
        Config.log("★ Whisper native contexts freed (freeContext only, params kept)");
    }

    private static void freeContextOnly(WhisperCpp wc, String label) {
        try {
            java.lang.reflect.Method m =
                    WhisperCpp.class.getDeclaredMethod("freeContext");
            m.setAccessible(true);
            m.invoke(wc);
            Config.log("★ freeContextOnly(" + label + ") OK");
        } catch (NoSuchMethodException nsm) {
            // freeContext メソッドが存在しない場合（バインディング差異）
            Config.logError("★ freeContextOnly(" + label
                    + ") skipped: freeContext() not found in WhisperCpp", nsm);
        } catch (Throwable t) {
            Config.logError("★ freeContextOnly(" + label + ") failed", t);
        }
    }
}

/**
 * VAD (Voice Activity Detection)
 */
class SimpleVAD {
    private static final int AMP_PEAK_THRESHOLD;
    private static final int AMP_AVG_THRESHOLD;
    private static final int HOLD_FRAMES;
    static {
        int vadPeak = Config.getInt("vad.peak", 0);
        int oldSilence = Config.getInt("silence", 3000);
        AMP_PEAK_THRESHOLD = vadPeak > 0 ? vadPeak : Math.max(2000, oldSilence / 2);
        AMP_AVG_THRESHOLD = AMP_PEAK_THRESHOLD / 4;
        HOLD_FRAMES = Math.max(4, AMP_PEAK_THRESHOLD / 400);
        Config.logDebug(String.format("★SimpleVAD設定 - Peak閾値: %d, Avg閾値: %d, 保持フレーム: %d",
            AMP_PEAK_THRESHOLD, AMP_AVG_THRESHOLD, HOLD_FRAMES));
    }
    protected int maxPeak = 0;
    protected int speechHold = 0;

    boolean isSpeech(byte[] pcm, int len) {
        int peak = getPeak(pcm, len);
        int avg = getAvg(pcm, len);
        boolean detected = avg >= AMP_AVG_THRESHOLD || peak >= AMP_PEAK_THRESHOLD;
        if (detected) {
            maxPeak = Math.max(maxPeak, peak);
            speechHold = HOLD_FRAMES;
            return true;
        }
        if (speechHold > 0) {
            if (maxPeak > 0 && peak < maxPeak * 0.3) {
                speechHold--;
            } else {
                speechHold--;
            }
            return true;
        }
        maxPeak = 0;
        return false;
    }
    public int getPeak(byte[] pcm, int len) {
        int peak = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int s = (pcm[i + 1] << 8) | (pcm[i] & 0xff);
            peak = Math.max(peak, Math.abs(s));
        }
        return peak;
    }
    public int getAvg(byte[] pcm, int len) {
        long sum = 0;
        int count = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int s = (pcm[i + 1] << 8) | (pcm[i] & 0xff);
            sum += Math.abs(s);
            count++;
        }
        return (int) (sum / Math.max(1, count));
    }
    public void reset() {
        speechHold = 0;
        maxPeak = 0;
    }
}

class AdaptiveNoiseProfile {
    private final int SENSITIVITY;
    private final int SILENCE_TOLERANCE;
    private final boolean AUTO_CALIBRATE;
    public final int CALIBRATION_FRAMES = 50;

    public List<Integer> noiseSamples = new ArrayList<>();
    private int noiseFloor = 0;
    private int noiseStdDev = 0;

    private int currentPeakThreshold;
    private int currentAvgThreshold;

    private boolean isCalibrated = false;
    private final int manualPeak;

    public boolean isLowGainMic = false;
    public final List<Integer> rawPeaks = new ArrayList<>();
    public final List<Integer> rawAvgs  = new ArrayList<>();


    public AdaptiveNoiseProfile() {
        this.SENSITIVITY = Config.getInt("vad.sensitivity", 50);
        this.SILENCE_TOLERANCE = Config.getInt("vad.tolerance", 5);
        this.AUTO_CALIBRATE = Config.getBool("vad.auto_calibrate", true);
        this.manualPeak = Integer.parseInt(Config.loadSetting("vad.peak", "2000"));

        this.currentPeakThreshold = Math.max(600, manualPeak);
        this.currentAvgThreshold  = Math.max(120, manualPeak / 4);

        Config.logDebug("★VAD設定 - 感度: " + SENSITIVITY + ", 無音許容: " + SILENCE_TOLERANCE + ", 自動較正: " + AUTO_CALIBRATE);
    }

    public synchronized void updateRaw(int peak, int avg) {
        // cap to avoid unbounded growth
        int cap = CALIBRATION_FRAMES * 4; // 200
        if (rawPeaks.size() >= cap) return;
        rawPeaks.add(peak);
        rawAvgs.add(avg);
    }
    public synchronized void forceCalibrateByPercentile() {
        if (rawPeaks.isEmpty()) {
            forceCalibrate(); // fallback
            return;
        }

        List<Integer> sorted = new ArrayList<>(rawPeaks);
        Collections.sort(sorted);

        // take lowest 35% as "noise"
        int take = Math.max(12, (int) Math.round(sorted.size() * 0.35));
        noiseSamples.clear();
        noiseSamples.addAll(sorted.subList(0, Math.min(take, sorted.size())));

        calibrate();
    }

    public int getManualPeakThreshold() { return manualPeak; }

    public synchronized void resetForNewDevice() {
        noiseSamples.clear();
        noiseFloor = 0;
        noiseStdDev = 0;
        isCalibrated = false;
        isLowGainMic = false;
        currentPeakThreshold = Math.max(600, manualPeak);
        currentAvgThreshold  = Math.max(120, manualPeak / 4);
        Config.logDebug("★NoiseProfile resetForNewDevice()");
    }

    public synchronized void setLowGainMic(boolean b) {
        if (this.isLowGainMic != b) {
            this.isLowGainMic = b;
            updateThresholds();
            Config.logDebug("★LowGainMic toggled: " + b + " -> peakTh=" + currentPeakThreshold + " avgTh=" + currentAvgThreshold);
        }
    }

    public synchronized void update(int peak, int avg, boolean isSilent) {
        if (!isCalibrated && noiseSamples.size() < CALIBRATION_FRAMES && isSilent) {
            noiseSamples.add(peak);
            if (noiseSamples.size() >= CALIBRATION_FRAMES) {
                calibrate();
            }
        }
    }

    public synchronized void forceCalibrate() {
        if (noiseSamples.isEmpty()) {
            isCalibrated = true;
            updateThresholds();
            Config.logDebug("★強制較正 - サンプル不足、manual基準で継続");
            return;
        }
        calibrate();
    }

    public synchronized void calibrate() {
        if (noiseSamples.isEmpty()) {
            Config.logDebug("★較正失敗 - サンプル不足");
            return;
        }

        noiseFloor = calculateMean(noiseSamples);
        noiseStdDev = calculateStdDev(noiseSamples, noiseFloor);

        isCalibrated = true;

        // ★修正: 少数サンプルでの誤判定防止（samples<30ではLOW GAIN判定しない）
        boolean looksLowGain = (noiseSamples.size() >= 30) &&
                ((noiseFloor <= 10) || (noiseFloor <= 30 && noiseStdDev <= 20));

        if (looksLowGain) {
            isLowGainMic = true;
            Config.logDebug("★Mic profile hint: LOW GAIN (noiseFloor=" + noiseFloor + ", std=" + noiseStdDev + ")");
        } else {
            isLowGainMic = false;
            Config.logDebug("★Mic profile hint: NORMAL (noiseFloor=" + noiseFloor + ", std=" + noiseStdDev + ")");
        }

        updateThresholds();

        Config.logDebug("★較正完了 - ノイズフロア: " + noiseFloor +
                ", 標準偏差: " + noiseStdDev +
                ", Peak閾値: " + currentPeakThreshold +
                ", Avg閾値: " + currentAvgThreshold +
                " (samples=" + noiseSamples.size() + ")");
    }

    private synchronized void updateThresholds() {
        double sensitivityFactor = (100.0 - SENSITIVITY) / 50.0;

        boolean useAuto = AUTO_CALIBRATE && !noiseSamples.isEmpty();
        int basePeakThreshold;

        // ★通常マイクの方が環境ノイズ拾いやすいので、std倍数と最低閾値を上げる
        int stdMulNormal = Config.getInt("vad.stdMul.normal", 6);     // 既定: 6
        int stdMulLow    = Config.getInt("vad.stdMul.lowGain", 3);    // 既定: 3
        int stdMul = isLowGainMic ? stdMulLow : stdMulNormal;

        if (useAuto) {
            basePeakThreshold = noiseFloor + (noiseStdDev * stdMul);
            currentPeakThreshold = (int) (basePeakThreshold * sensitivityFactor);
        } else {
            currentPeakThreshold = (int) (manualPeak * sensitivityFactor);
        }
        currentAvgThreshold = currentPeakThreshold / 4;

        // ★最低閾値：通常マイクは高め、低ゲインは低め
        int minPeakNormal = Config.getInt("vad.minPeak.normal", 1800);
        int minAvgNormal  = Config.getInt("vad.minAvg.normal",  450);
        int minPeakLow    = Config.getInt("vad.minPeak.lowGain", 600);
        int minAvgLow     = Config.getInt("vad.minAvg.lowGain",  120);

        int minPeak = isLowGainMic ? minPeakLow : minPeakNormal;
        int minAvg  = isLowGainMic ? minAvgLow  : minAvgNormal;

        currentPeakThreshold = Math.max(minPeak, currentPeakThreshold);
        currentAvgThreshold  = Math.max(minAvg,  currentAvgThreshold);
    }


    private int calculateMean(List<Integer> samples) {
        return samples.stream().mapToInt(Integer::intValue).sum() / samples.size();
    }

    private int calculateStdDev(List<Integer> samples, int mean) {
        double variance = samples.stream()
                .mapToDouble(sample -> Math.pow(sample - mean, 2))
                .average()
                .orElse(0.0);
        return (int) Math.sqrt(variance);
    }

    public synchronized int getPeakThreshold() { return isCalibrated ? currentPeakThreshold : manualPeak; }
    public synchronized int getAvgThreshold()  { return isCalibrated ? currentAvgThreshold  : manualPeak / 4; }
    public synchronized boolean isCalibrated() { return isCalibrated; }
    public int getSilenceTolerance() { return SILENCE_TOLERANCE; }

    public synchronized void resetAfterSpeech() {
        if (isCalibrated) {
            updateThresholds();
            Config.logDebug("★NoiseProfile resetAfterSpeech - peakTh=" +
                    currentPeakThreshold + ", avgTh=" + currentAvgThreshold);
        } else {
            currentPeakThreshold = manualPeak;
            currentAvgThreshold = manualPeak / 4;
            Config.logDebug("★NoiseProfile resetAfterSpeech - manualへ復帰");
        }
    }
}

class ImprovedVAD extends SimpleVAD {
    private final AdaptiveNoiseProfile noiseProfile;
    private static final int MAX_BUFFER_SIZE = 16000 * 30;
    private static final int WARN_BUFFER_SIZE = 16000 * 20;
    private int consecutiveSilenceFrames = 0;
    private int ultraRescueCount = 0;
    private int lastPeak = 0;
    private int stablePeakFrames = 0;

    public ImprovedVAD(AdaptiveNoiseProfile profile) {
        this.noiseProfile = profile;
    }

    public void calibrateOnStartup(byte[] pcm, int len) {
        int peak = getPeak(pcm, len);
        int avg = getAvg(pcm, len);
        noiseProfile.update(peak, avg, true);
        if (noiseProfile.noiseSamples.size() >= noiseProfile.CALIBRATION_FRAMES) {
            if (!noiseProfile.isCalibrated()) {
                noiseProfile.calibrate();
            }
        }
    }
    public boolean looksLikeBGM(int peak, int avg) {
        if (noiseProfile.isLowGainMic) {
            return false; // ★低ゲイン時はBGM判定しない
        }
        if (peak > noiseProfile.getPeakThreshold() * 1.4) {
            stablePeakFrames = 0;
            return false;
        }

        int diff = Math.abs(peak - lastPeak);
        lastPeak = peak;

        // 声は peak と avg の比が大きく揺れる
        double ratio = avg > 0 ? (double) peak / avg : 0.0;

        if (diff < 80 && ratio < 3.5) { // ★ ratio条件追加
            stablePeakFrames++;
        } else {
            stablePeakFrames = 0;
        }

        // ★ 必要フレーム数も緩和
        return stablePeakFrames >= 18; // 12 → 18（約300ms）
    }


    public boolean isSpeech(byte[] pcm, int len, int currentBufferSize) {
        int peak = getPeak(pcm, len);
        int avg = getAvg(pcm, len);

        if (!noiseProfile.isCalibrated()) {
            noiseProfile.update(peak, avg, true);
            return super.isSpeech(pcm, len);
        }

        int peakThreshold = noiseProfile.getPeakThreshold();
        int avgThreshold  = noiseProfile.getAvgThreshold();
        boolean isPeakSpeech = peak >= peakThreshold;
        boolean isAvgSpeech  = avg  >= avgThreshold;
        // ★通常マイクは「avgだけ」で拾うと環境音を拾いがちなので、peak寄りにする
        double peakFactor = noiseProfile.isLowGainMic ? 0.60 : 0.85;
        boolean currentIsSpeech =
                isPeakSpeech ||
                        (isAvgSpeech && peak >= peakThreshold * peakFactor);

        if (!currentIsSpeech && noiseProfile.isLowGainMic) {
            int ULTRA_PEAK = Config.getInt("vad.ultra.peak", 140);
            int ULTRA_AVG  = Config.getInt("vad.ultra.avg",  32);

            if (peak >= ULTRA_PEAK && avg >= ULTRA_AVG) {
                ultraRescueCount++;
                if (ultraRescueCount >= 2) { // 2フレーム連続でだけ拾う
                    noiseProfile.setLowGainMic(true);
                    currentIsSpeech = true;
                    if (Config.getBool("log.vad.detailed", false)) {
                        Config.logDebug("VAD ultra-rescue: peak=" + peak + " avg=" + avg +
                                " (ULTRA_PEAK=" + ULTRA_PEAK + " ULTRA_AVG=" + ULTRA_AVG + ")");
                    }
                }
            } else {
                ultraRescueCount = 0;
            }
        } else {
            ultraRescueCount = 0;
        }

//        if (!currentIsSpeech) {
//            // 閾値の40%を超え、かつ絶対値が小さくても拾えるようにする
//            if (noiseProfile.isLowGainMic && peak > (int)(peakThreshold * 0.40) && avg > (int)(avgThreshold * 0.40)
//                    && peak > 80 && avg > 20) {   // 200/50 → 80/20
//                noiseProfile.setLowGainMic(true);
//                currentIsSpeech = true;
//                if (Config.getBool("log.vad.detailed", false)) {
//                    Config.logDebug("VAD low-gain rescue(soft): peak=" + peak + " avg=" + avg
//                            + " thPeak=" + peakThreshold + " thAvg=" + avgThreshold);
//                }
//            }
//        }

        if (Config.getBool("log.vad.detailed", false)) {
            Config.logDebug(String.format("VAD: peak=%d(>%d?%s) avg=%d(>%d?%s) → %s",
                    peak, peakThreshold, isPeakSpeech,
                    avg, avgThreshold, isAvgSpeech,
                    currentIsSpeech));
        }
//        if (!currentIsSpeech && noiseProfile.isLowGainMic) {
//            if (peak >= 100 && avg < avgThreshold * 0.5) {
//                currentIsSpeech = true;
//                Config.logDebug("★LowGain peak-only speech accepted");
//            }
//        }

        if (currentIsSpeech) {
            maxPeak = Math.max(maxPeak, peak);
            speechHold = Math.max(4, peak / 400);
            consecutiveSilenceFrames = 0;
            return true;
        } else if (speechHold > 0) {
            speechHold--;
            consecutiveSilenceFrames = 0;
            return true;
        } else {
            consecutiveSilenceFrames++;
            return false;
        }
    }

    @Override
    public boolean isSpeech(byte[] pcm, int len) {
        return isSpeech(pcm, len, 0);
    }

    public int getConsecutiveSilenceFrames() {
        return consecutiveSilenceFrames;
    }

    public boolean shouldForceFinalize(int currentBufferSize, int silenceFrames, int minSilenceFrames) {
        if (currentBufferSize >= MAX_BUFFER_SIZE) {
            Config.logDebug("★強制Final - バッファサイズ上限: " + currentBufferSize);
            return true;
        }
        if (currentBufferSize >= WARN_BUFFER_SIZE) {
            int relaxedSilence = Math.max(1, minSilenceFrames / 2);
            if (silenceFrames >= relaxedSilence) {
                Config.logDebug("★強制Final - 長時間録音での条件緩和: " + silenceFrames + "/" + relaxedSilence);
                return true;
            }
        }
//        if (noiseProfile.isLowGainMic) {
//            int minBytes = Config.getInt("vad.lowgain.min_final_bytes", 8000); // 約0.5秒
//            int minPeak  = Config.getInt("vad.lowgain.min_peak", 90);
//            int minAvg   = Config.getInt("vad.lowgain.min_avg", 18);
//
//            if (currentBufferSize >= minBytes
//                    && maxPeak >= minPeak
//                    && silenceFrames >= 1) {
//                Config.logDebug("★LowGain FINAL rescue (short utterance)");
//                return true;
//            }
//        }
        return false;
    }

    public int getAdjustedSilenceFrames(int baseSilenceFrames) {
        if (!noiseProfile.isCalibrated()) {
            return baseSilenceFrames;
        }
        int tolerance = noiseProfile.getSilenceTolerance();
        double multiplier = 1.0 + (tolerance - 5) * 0.2;
        int adjusted = (int) (baseSilenceFrames * multiplier * 0.8);
        Config.logDebug("★調整後 SILENCE_FRAMES_FOR_FINAL: " + adjusted);
        return Math.max(1, adjusted);
    }

    @Override
    public void reset() {
        super.reset();
        consecutiveSilenceFrames = 0;
    }

    public AdaptiveNoiseProfile getNoiseProfile() {
        return noiseProfile;
    }

    public boolean shouldFinalizeEarlyByText(String text) {
        // EarlyFinalizeは完全に無効化
        return false;
    }

    private static final Pattern INCOMPLETE_SUFFIX =
            Pattern.compile(
                    "(んだから|んだけど|けど|から|ので|because|so|and|or|但|因为|所以|但是|인데|그래서)$"
            );

    public boolean endsWithIncompleteSuffix(String text) {
        if (text == null) return false;
        return INCOMPLETE_SUFFIX.matcher(text.trim()).find();
    }
}


class LaughDetector {
    private int minPeakForBurst = 1200;
    private long burstWindowMs = 1000;
    private static final int ALT_MIN_PEAK_FOR_BURST = 2200; // 1200 → 2200
    private static final int ALT_BURST_MIN = 3;             // 2 → 3
    private static final long ALT_BURST_WINDOW_MS = 800;    // 1000 → 800

    private static final long MIN_BURST_INTERVAL_MS = 90;
    private static final long MAX_BURST_INTERVAL_MS = 350;

    // ★咳判定を大幅に緩和（倍率を下げる）
    private static final float COUGH_PEAK_RATIO = 3.5f; // 2.6 → 3.5（さらに緩和）
    private static final int SR = 16000;

    private long lastSpeechStartMs = -1;
    private long lastSpeechEndMs   = -1;

    private double burstEnergySum = 0;
    private int zcrStableCount = 0;

    private long cooldownUntilMs = 0;

    private final ArrayDeque<Long> burstTimes = new ArrayDeque<>();

    private boolean lowGain = false;

    public void setMinPeakForBurst(int v) { this.minPeakForBurst = Math.max(60, v); }
    public void setLowGain(boolean v) { this.lowGain = v; }

    public void reset() {
        burstTimes.clear();
        burstEnergySum = 0;
        zcrStableCount = 0;
        cooldownUntilMs = 0;
    }

    public boolean updateAndDetectLaugh(byte[] pcm, int len, int peak, long nowMs, boolean speech) {
        boolean debugLog = Config.getBool("log.laugh.debug", false);
        boolean altMode = prefs.getBoolean("silence.alternate", false);
        if (altMode) {
            this.minPeakForBurst = ALT_MIN_PEAK_FOR_BURST;
            this.burstWindowMs   = ALT_BURST_WINDOW_MS;
        }

        // ★ガード時間を延長（発話開始・終了付近での誤検知防止）
        long guardStart = 250; // 150 → 250ms
        long guardEnd   = 300; // 180 → 300ms

        if (lastSpeechStartMs > 0 && nowMs - lastSpeechStartMs < guardStart) {
            if (debugLog && nowMs - lastSpeechStartMs > 100) {
                Config.logDebug("[Laugh] Blocked by guardStart (" + (nowMs - lastSpeechStartMs) + "ms < " + guardStart + "ms)");
            }
            return false;
        }
        if (lastSpeechEndMs > 0 && nowMs - lastSpeechEndMs < guardEnd) {
            if (debugLog) {
                Config.logDebug("[Laugh] Blocked by guardEnd");
            }
            return false;
        }

        if (nowMs < cooldownUntilMs) {
            if (debugLog) {
                Config.logDebug("[Laugh] In cooldown");
            }
            return false;
        }

        // ★咳判定を厳格化（倍率アップ）
        int coughAbsoluteMax = Config.getInt("laugh.cough_abs_peak", 12000); // 10000 → 12000
        int coughThreshold = Math.max(coughAbsoluteMax, (int)(minPeakForBurst * 4.0f)); // 3.5 → 4.0

        if (peak > coughThreshold) {
            if (debugLog && !burstTimes.isEmpty()) {
                Config.logDebug("[Laugh] Blocked by cough (peak=" + peak + " > " + coughThreshold + ")");
            }
            reset();
            return false;
        }

        if (peak < minPeakForBurst) {
            cleanupOld(nowMs);
            if (!burstTimes.isEmpty() && debugLog) {
                Config.logDebug("[Laugh] peak=" + peak + " < minPeak=" + minPeakForBurst +
                        " (burstCount=" + burstTimes.size() + ")");
            }
            return false;
        }

        // ZCR計算
        double zcr = calcZcr(pcm, len);
        double zcrStableMax = 0.25;
        double zcrStableMin = 0.04;
        boolean zcrOk = (zcr >= zcrStableMin && zcr <= zcrStableMax);

        if (debugLog && peak >= minPeakForBurst) {
            Config.logDebug(String.format("[Laugh] ZCR=%.4f (%.2f-%.2f) %s, count=%d, peak=%d, coughTh=%d",
                    zcr, zcrStableMin, zcrStableMax, zcrOk ? "OK" : "NG", zcrStableCount, peak, coughThreshold));
        }

        // ★ZCR安定要求を厳格化
        int requiredZcrStable = altMode ? 3 : 2; // alt時は3フレーム必要
        if (zcrOk) {
            zcrStableCount++;
        } else {
            zcrStableCount = 0;
            cleanupOld(nowMs);
            return false;
        }
        if (zcrStableCount < requiredZcrStable) {
            return false;
        }

        // ★低周波チェックを厳格化
        float lowRatio = calcLowFreqRatio(pcm, len);
        if (lowRatio > 0.40f) { // 0.45 → 0.40
            if (debugLog) {
                Config.logDebug("[Laugh] Blocked by lowRatio=" + lowRatio + " > 0.40");
            }
            reset();
            return false;
        }

        // ★ゲップ判定を厳格化
        if (lowRatio > 0.45f && burstTimes.size() <= 1) { // 0.50 → 0.45
            if (altMode && debugLog) {
                Config.logDebug(String.format("[LAUGH-ALT] ★BURP suspected (lowRatio=%.2f, single burst)", lowRatio));
            }
            reset();
            return false;
        }

        // Burst登録
        burstTimes.addLast(nowMs);
        cleanupOld(nowMs);

        if (debugLog) {
            Config.logDebug("[Laugh] ★Burst registered! count=" + burstTimes.size() +
                    ", peak=" + peak + ", zcr=" + String.format("%.4f", zcr) +
                    ", lowRatio=" + String.format("%.2f", lowRatio));
        }

        // 連続間隔チェック
        if (burstTimes.size() >= 2) {
            long last = burstTimes.peekLast();
            long prev = getPrev(burstTimes);
            long d = last - prev;

            // ★間隔要求を厳格化
            if (d < 100) { // 90 → 100ms
                burstTimes.removeLast();
                if (debugLog) {
                    Config.logDebug("[Laugh] Interval too short: " + d + "ms");
                }
                return false;
            }
            if (d > 320) { // 350 → 320ms
                while (burstTimes.size() > 1) burstTimes.removeFirst();
                burstEnergySum = 0;
                if (debugLog) {
                    Config.logDebug("[Laugh] Interval too long: " + d + "ms, reset");
                }
            }
        }

        // energy加算
        burstEnergySum += (double) peak * (double) peak;

        // "短い笑い"救済（より厳格に）
        boolean denseShortLaugh = false;
        if (!altMode && burstTimes.size() >= 2) {
            long span = burstTimes.peekLast() - burstTimes.peekFirst();
            if (span <= 250) { // 300 → 250ms
                denseShortLaugh = true;
                if (debugLog) {
                    Config.logDebug("[Laugh] Dense short laugh detected (span=" + span + "ms)");
                }
            }
        }

        int burstMin = altMode ? ALT_BURST_MIN : 2;
        if (burstTimes.size() < burstMin && !denseShortLaugh) {
            if (debugLog) {
                Config.logDebug("[Laugh] Not enough bursts: " + burstTimes.size() + " < " + burstMin);
            }
            return false;
        }

        // ★midRatio要求を厳格化
        double midRatio = calcMidRatio(pcm, len);
        double midRatioRequired = altMode ? 0.15 : 0.14; // 0.12 → 0.14
        if (debugLog) {
            Config.logDebug(String.format("[Laugh] midRatio=%.4f (min=%.2f) %s",
                    midRatio, midRatioRequired, midRatio >= midRatioRequired ? "OK" : "NG"));
        }

        if (midRatio < midRatioRequired) {
            if (debugLog) {
                Config.logDebug("[Laugh] Blocked by midRatio");
            }
            return false;
        }

        // ★エネルギーチェックを厳格化
        double energyFactor = 0.60; // 0.55 → 0.60
        if (denseShortLaugh) energyFactor *= 0.70; // 0.65 → 0.70
        if (lowGain) energyFactor *= 0.70; // 0.65 → 0.70

        double minEnergy = (double) minPeakForBurst * (double) minPeakForBurst * (double) burstMin * energyFactor;
        if (debugLog) {
            Config.logDebug(String.format("[Laugh] Energy check: %.0f >= %.0f ? %s",
                    burstEnergySum, minEnergy, burstEnergySum >= minEnergy ? "OK" : "NG"));
        }

        if (burstEnergySum < minEnergy) {
            return false;
        }

        // ★★★ HIT ★★★
        Config.log("★★★ LAUGH DETECTED ★★★ bursts=" + burstTimes.size() +
                ", energy=" + String.format("%.0f", burstEnergySum) +
                ", midRatio=" + String.format("%.3f", midRatio) +
                ", zcr=" + String.format("%.4f", zcr) +
                ", peak=" + peak);

        long cooldownMs = 1200;
        cooldownUntilMs = nowMs + cooldownMs;
        reset();
        return true;
    }

    private static long getPrev(ArrayDeque<Long> q) {
        Long last = null;
        for (Long t : q) {
            if (last != null && t.equals(q.peekLast())) return last;
            last = t;
        }
        Long prev = null;
        for (Long t : q) { prev = t; }
        return prev == null ? 0 : prev;
    }

    private void cleanupOld(long nowMs) {
        while (!burstTimes.isEmpty() && nowMs - burstTimes.peekFirst() > burstWindowMs) {
            burstTimes.removeFirst();
        }
        if (burstTimes.isEmpty()) {
            burstEnergySum = 0;
            zcrStableCount = 0;
        }
    }

    private float calcLowFreqRatio(byte[] data, int len) {
        int samples = len / 2;
        int lowEnergy = 0;
        int totalEnergy = 0;
        for (int i = 0; i < samples; i++) {
            int s = (short)((data[i*2+1] << 8) | (data[i*2] & 0xff));
            int a = Math.abs(s);
            totalEnergy += a;
            if (a < 600) lowEnergy += a;
        }
        if (totalEnergy == 0) return 0f;
        return (float)lowEnergy / (float)totalEnergy;
    }

    private double calcZcr(byte[] pcm, int len) {
        int prev = 0;
        int crossings = 0;
        int count = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int s = (short)(((pcm[i+1] & 0xFF) << 8) | (pcm[i] & 0xFF));
            if (count > 0) {
                if ((s >= 0 && prev < 0) || (s < 0 && prev >= 0)) crossings++;
            }
            prev = s;
            count++;
        }
        return count <= 0 ? 0.0 : (double) crossings / (double) count;
    }

    private double calcMidRatio(byte[] pcm, int len) {
        double hp_a = Math.exp(-2.0 * Math.PI * 300.0 / SR);
        double lp_a = Math.exp(-2.0 * Math.PI * 3000.0 / SR);
        double xPrev = 0, hpPrev = 0;
        double lpPrev = 0;
        double eTotal = 0;
        double eMid = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            double x = (short)(((pcm[i+1] & 0xFF) << 8) | (pcm[i] & 0xFF));
            double hp = hp_a * (hpPrev + x - xPrev);
            xPrev = x;
            hpPrev = hp;
            double lp = (1.0 - lp_a) * hp + lp_a * lpPrev;
            lpPrev = lp;
            eTotal += x * x;
            eMid   += lp * lp;
        }
        if (eTotal <= 1e-9) return 0.0;
        return eMid / eTotal;
    }

    public void notifySpeechState(boolean speech, long nowMs) {
        if (speech) {
            if (lastSpeechStartMs < lastSpeechEndMs || lastSpeechStartMs < 0) {
                lastSpeechStartMs = nowMs;
            }
        } else {
            lastSpeechEndMs = nowMs;
        }
    }
}
