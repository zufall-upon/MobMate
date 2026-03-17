package whisper;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LocalTranslator {

    private final File modelDir;
    private final int numThreads;
    private final String modelType;
    private OrtTranslator ortTranslator;

    public LocalTranslator(File modelDir, int numThreads) {
        this(modelDir, numThreads, TranslatorCore.inferModelType(modelDir));
    }

    public LocalTranslator(File modelDir, int numThreads, String modelType) {
        this.modelDir = modelDir;
        this.numThreads = numThreads;
        this.modelType = TranslatorCore.normalizeModelType(modelType);
    }

    public boolean load(File exeDir) {
        File resolvedModelDir = TranslatorCore.resolveModelDir(exeDir, modelDir, modelType);
        if (resolvedModelDir == null || !resolvedModelDir.isDirectory()) {
            String path = (resolvedModelDir == null) ? "<null>" : resolvedModelDir.getAbsolutePath();
            Config.log("[Translator] model dir not found: type=" + modelType + " dir=" + path);
            return false;
        }
        if (!TranslatorCore.loadLibrary(exeDir)) {
            Config.log("[Translator] runtime load failed: " + TranslatorCore.getLoadError());
            return false;
        }
        OrtTranslator translator = new OrtTranslator(numThreads);
        if (!translator.load(resolvedModelDir, exeDir)) {
            translator.close();
            return false;
        }
        ortTranslator = translator;
        Config.log("[Translator] loaded OK, type=" + modelType
                + " dir=" + resolvedModelDir.getAbsolutePath());
        return true;
    }

    public String translate(String text, String srcLang, String tgtLang) {
        if (ortTranslator == null || text == null || text.isBlank()) return text;
        try {
            return ortTranslator.translate(text, srcLang, tgtLang);
        } catch (Throwable t) {
            Config.logError("[Translator] translate error", t);
            return text;
        }
    }

    public void unload() {
        if (ortTranslator != null) {
            ortTranslator.close();
            ortTranslator = null;
        }
    }

    public boolean isLoaded() { return ortTranslator != null; }
}

// ============================================================
class TranslatorCore {

    static final String MODEL_TYPE_M2M100 = "m2m100";
    static final String MODEL_TYPE_MT5    = "mt5";

    private static boolean loaded    = false;
    private static String  loadError = null;

    static synchronized boolean loadLibrary(File exeDir) {
        if (loaded) return true;
        if (loadError != null) return false;
        try {
            if (exeDir != null) {
                String[] preload = {
                        "libiomp5md.dll",
                        "libgcc_s_seh-1.dll",
                        "libstdc++-6.dll",
                        "libgomp-1.dll",
                        "libwinpthread-1.dll",
                        "onnxruntime.dll",
                        "translatorcore.dll"
                };
                for (String name : preload) {
                    File dll = new File(exeDir, name);
                    if (!dll.isFile()) continue;
                    System.load(dll.getAbsolutePath());
                    Config.log("[Translator] Force-loaded " + name + " from: " + dll);
                }
            }
            loaded = true;
            return true;
        } catch (Throwable t) {
            loadError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        }
    }

    static String getLoadError() { return loadError; }

    // ---- SPM JNI（translatorcore.dll経由） ----
    static native long     spmLoad(String modelPath);
    static native int[]    spmEncode(long handle, String text);
    static native String   spmDecode(long handle, int[] ids);
    static native String[] spmEncodePieces(long handle, String text);
    static native String   spmDecodePieces(long handle, String[] pieces);
    static native void     spmFree(long handle);

    // ---- モデルタイプ推定 ----
    static String inferModelType(File modelDir) {
        if (hasMt5OnnxFiles(modelDir))   return MODEL_TYPE_MT5;
        if (hasM2M100OnnxFiles(modelDir)) return MODEL_TYPE_M2M100;
        if (modelDir != null && new File(modelDir, "spiece.model").isFile())
            return MODEL_TYPE_MT5;
        return MODEL_TYPE_M2M100;
    }

    static File resolveModelDir(File exeDir, File requestedModelDir, String modelType) {
        String t = normalizeModelType(modelType);

        if (MODEL_TYPE_MT5.equals(t)) {
            // ★mt5: ONNX版を優先
            File[] candidates = {
                    requestedModelDir,
                    child(exeDir, "models", "mt5_onnx_int8"),
            };
            for (File c : candidates) {
                if (hasMt5OnnxFiles(c)) return c;
            }
            return requestedModelDir;
        }

        // m2m100
        File[] candidates = {
                requestedModelDir,
                child(exeDir, "models", "m2m100_onnx_int8"),
        };
        for (File c : candidates) {
            if (hasM2M100OnnxFiles(c)) return c;
        }
        return requestedModelDir;
    }

    static String normalizeModelType(String modelType) {
        if (modelType == null) return MODEL_TYPE_M2M100;
        String s = modelType.trim().toLowerCase(Locale.ROOT);
        return MODEL_TYPE_MT5.equals(s) ? MODEL_TYPE_MT5 : MODEL_TYPE_M2M100;
    }

    // ---- ファイル存在チェック ----
    static boolean hasM2M100OnnxFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        return (new File(dir, "encoder_model.onnx").isFile()
                || new File(dir, "encoder_model_quantized.onnx").isFile())
                && (new File(dir, "decoder_model.onnx").isFile()
                || new File(dir, "decoder_model_quantized.onnx").isFile())
                && new File(dir, "sentencepiece.bpe.model").isFile();
    }

    static boolean hasMt5OnnxFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        return (new File(dir, "encoder_model.onnx").isFile()
                || new File(dir, "encoder_model_quantized.onnx").isFile())
                && (new File(dir, "decoder_model.onnx").isFile()
                || new File(dir, "decoder_model_quantized.onnx").isFile())
                && new File(dir, "spiece.model").isFile();
    }

    private static File child(File root, String a, String b) {
        if (root == null) return null;
        return new File(new File(root, a), b);
    }
}

// ============================================================
class PostProcessor {
    record ProcessResult(String text, int elapsedMs, boolean timedOut) {}

    private static final long TIMEOUT_MS = 10_000; // ★まずは観測優先で10秒

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "postprocessor");
        t.setDaemon(true);
        return t;
    });

    private final File requestedModelDir;
    private OrtTranslator ortTranslator = null; // ★ONNX mt5インスタンス

    PostProcessor(File modelDir) {
        this.requestedModelDir = modelDir;
    }

    public boolean load(File exeDir) {
        File modelDir = TranslatorCore.resolveModelDir(
                exeDir, requestedModelDir, TranslatorCore.MODEL_TYPE_MT5);
        if (modelDir == null || !TranslatorCore.hasMt5OnnxFiles(modelDir)) {
            String path = (modelDir == null) ? "<null>" : modelDir.getAbsolutePath();
            Config.log("[PostProcessor] mt5 ONNX model dir not found: " + path);
            return false;
        }
        if (!TranslatorCore.loadLibrary(exeDir)) {
            Config.log("[PostProcessor] DLL load failed: " + TranslatorCore.getLoadError());
            return false;
        }
        OrtTranslator tr = new OrtTranslator(2);
        if (!tr.load(modelDir, exeDir)) {
            tr.close();
            return false;
        }
        this.ortTranslator = tr;
        Config.log("[PostProcessor] ONNX mt5 loaded OK: " + modelDir);
        return true;
    }

    /** タイムアウト付き。失敗・タイムアウト時は元テキストを返す */
    public ProcessResult process(String text, String promptLanguage) {
        if (ortTranslator == null || text == null || text.isBlank())
            return new ProcessResult(text, 0, false);
        long t0 = System.currentTimeMillis();
        try {
            Future<String> f = exec.submit(() ->
                    ortTranslator.translate(text, promptLanguage, ""));
            String result = f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            int elapsed = safeElapsedMs(t0);
            Config.logDebug("[PostProcessor] " + elapsed + "ms: "
                    + text + " -> " + result);
            return new ProcessResult(
                    (result == null || result.isBlank()) ? text : result,
                    elapsed, false);
        } catch (TimeoutException e) {
            Config.logDebug("[PostProcessor] timeout(" + TIMEOUT_MS + "ms): " + text);
            return new ProcessResult(text, safeElapsedMs(t0), true);
        } catch (Exception e) {
            Config.logError("[PostProcessor] error", e);
            return new ProcessResult(text, safeElapsedMs(t0), false);
        }
    }

    public void unload() {
        exec.shutdownNow();
        if (ortTranslator != null) { ortTranslator.close(); ortTranslator = null; }
    }

    public boolean isLoaded() { return ortTranslator != null; }

    private static int safeElapsedMs(long startMs) {
        long e = System.currentTimeMillis() - startMs;
        if (e < 0) e = 0;
        if (e > Integer.MAX_VALUE) e = Integer.MAX_VALUE;
        return (int) e;
    }
}

// ============================================================
class OrtTranslator implements AutoCloseable {

    private static final int MAX_NEW_TOKENS        = 32;
    private static final int MAX_REPEAT_TOKEN_STREAK = 8;

    private final int numThreads;
    private OrtEnvironment  env;
    private OrtSession      encoderSession;
    private OrtSession      decoderSession;
    private M2M100Tokenizer tokenizer;       // m2m100用
    private Mt5Tokenizer    mt5Tokenizer;    // ★mt5用
    private boolean         isMt5 = false;  // ★モデルタイプフラグ

    OrtTranslator(int numThreads) { this.numThreads = numThreads; }

    public boolean load(File modelDir, File exeDir) {
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            if (numThreads > 0) opts.setIntraOpNumThreads(numThreads);

            // ★spiece.model の有無で mt5 か判定
            isMt5 = new File(modelDir, "spiece.model").isFile();

            // ★quantizedサフィックス付きファイルを自動選択
            String encFile = pickOnnxFile(modelDir, "encoder_model");
            String decFile = pickOnnxFile(modelDir, "decoder_model");

            encoderSession = env.createSession(
                    new File(modelDir, encFile).getAbsolutePath(), opts);
            decoderSession = env.createSession(
                    new File(modelDir, decFile).getAbsolutePath(), opts);

            if (isMt5) {
                mt5Tokenizer = new Mt5Tokenizer(modelDir);
                Config.log("[OrtTranslator] mt5 loaded: " + modelDir);
            } else {
                tokenizer = new M2M100Tokenizer(modelDir);
                Config.log("[OrtTranslator] m2m100 loaded: " + modelDir);
            }
            return true;
        } catch (Throwable e) {
            Config.logError("[OrtTranslator] load failed", e);
            return false;
        }
    }

    /** 振り分けエントリーポイント */
    public String translate(String text, String srcLang, String tgtLang)
            throws OrtException {
        return isMt5
                ? translateMt5(text, srcLang)
                : translateM2M100(text, srcLang, tgtLang);
    }

    // ---- m2m100翻訳（既存ロジックそのまま） ----
    private String translateM2M100(String text, String srcLang, String tgtLang)
            throws OrtException {
        String original = (text == null) ? "" : text.trim();
        if (original.isEmpty()) return "";

        long[] inputIds = tokenizer.encode(original, srcLang);
        long[] attnMask = ones(inputIds.length);
        float[][][] encoderHidden;

        try (OnnxTensor inTensor  = OnnxTensor.createTensor(env, new long[][]{inputIds});
             OnnxTensor attTensor = OnnxTensor.createTensor(env, new long[][]{attnMask});
             OrtSession.Result encOut = encoderSession.run(Map.of(
                     "input_ids",      inTensor,
                     "attention_mask", attTensor))) {
            encoderHidden = (float[][][]) encOut.get("last_hidden_state").get().getValue();
        }

        long tgtLangId = tokenizer.getLangId(tgtLang);
        List<Long> generated = new ArrayList<>();
        generated.add(tokenizer.getEosId()); // decoder_start=2
        long prevTokenId = Long.MIN_VALUE;
        int repeatTokenStreak = 0;

        for (int step = 0; step < MAX_NEW_TOKENS; step++) {
            long nextTokenId;
            try (OnnxTensor decTensor    = OnnxTensor.createTensor(env,
                    new long[][]{toArray(generated)});
                 OnnxTensor encHidTensor = OnnxTensor.createTensor(env, encoderHidden);
                 OnnxTensor encAttTensor = OnnxTensor.createTensor(env,
                         new long[][]{attnMask});
                 OrtSession.Result decOut = decoderSession.run(Map.of(
                         "input_ids",              decTensor,
                         "encoder_hidden_states",  encHidTensor,
                         "encoder_attention_mask", encAttTensor))) {
                float[][][] logits = (float[][][]) decOut.get("logits").get().getValue();
                nextTokenId = argmax(logits[0][logits[0].length - 1]);
            }
            if (step == 0) { generated.add(tgtLangId); continue; } // forced_bos
            if (nextTokenId == tokenizer.getEosId()) break;
            if (nextTokenId == prevTokenId) repeatTokenStreak++;
            else { repeatTokenStreak = 1; prevTokenId = nextTokenId; }
            generated.add(nextTokenId);
            if (repeatTokenStreak >= MAX_REPEAT_TOKEN_STREAK) break;
        }

        if (generated.size() > 2) {
            generated = new ArrayList<>(generated.subList(2, generated.size()));
        } else {
            generated = new ArrayList<>();
        }
        Config.logDebug("[OrtTranslator][m2m100] raw_tokens=" + generated);
        String translated = tokenizer.decode(generated);
        translated = sanitizeTranslatedOutput(original, translated);
        return (translated == null || translated.isBlank()) ? original : translated.trim();
    }

    // ---- ★mt5後処理（新規） ----
    private String translateMt5(String text, String promptLanguage) throws OrtException {
        String original = (text == null) ? "" : text.trim();
        if (original.isEmpty()) return "";
        String prompt = buildMt5RewritePrompt(original, promptLanguage);

        long[] inputIds = mt5Tokenizer.encode(prompt);
        long[] attnMask = ones(inputIds.length);
        float[][][] encoderHidden;

        try (OnnxTensor inTensor  = OnnxTensor.createTensor(env, new long[][]{inputIds});
             OnnxTensor attTensor = OnnxTensor.createTensor(env, new long[][]{attnMask});
             OrtSession.Result encOut = encoderSession.run(Map.of(
                     "input_ids",      inTensor,
                     "attention_mask", attTensor))) {
            encoderHidden = (float[][][]) encOut.get("last_hidden_state").get().getValue();
        }

        List<Long> generated = new ArrayList<>();
        generated.add(mt5Tokenizer.getDecoderStartId()); // PAD=0で開始
        long prevTokenId = Long.MIN_VALUE;
        int repeatStreak = 0;

        for (int step = 0; step < MAX_NEW_TOKENS; step++) {
            long nextTokenId;
            try (OnnxTensor decTensor    = OnnxTensor.createTensor(env,
                    new long[][]{toArray(generated)});
                 OnnxTensor encHidTensor = OnnxTensor.createTensor(env, encoderHidden);
                 OnnxTensor encAttTensor = OnnxTensor.createTensor(env,
                         new long[][]{attnMask});
                 OrtSession.Result decOut = decoderSession.run(Map.of(
                         "input_ids",              decTensor,
                         "encoder_hidden_states",  encHidTensor,
                         "encoder_attention_mask", encAttTensor))) {
                float[][][] logits = (float[][][]) decOut.get("logits").get().getValue();
                nextTokenId = argmax(logits[0][logits[0].length - 1]);
            }
            if (nextTokenId == mt5Tokenizer.getEosId()) break;
            if (nextTokenId == prevTokenId) repeatStreak++;
            else { repeatStreak = 1; prevTokenId = nextTokenId; }
            generated.add(nextTokenId);
            if (repeatStreak >= MAX_REPEAT_TOKEN_STREAK) break;
        }

        // 先頭のPADを除去してdecode
        List<Long> output = generated.subList(
                Math.min(1, generated.size()), generated.size());
        String result = mt5Tokenizer.decode(output);
        result = sanitizeMt5Output(original, promptLanguage, result);
        Config.logDebug("[OrtTranslator][mt5] " + original + " -> " + result);
        return (result == null || result.isBlank()) ? original : result.trim();
    }

    @Override
    public void close() {
        try { if (mt5Tokenizer  != null) mt5Tokenizer.close();  } catch (Exception ignore) {}
        try { if (tokenizer     != null) tokenizer.close();     } catch (Exception ignore) {}
        try { if (decoderSession!= null) decoderSession.close();} catch (Exception ignore) {}
        try { if (encoderSession!= null) encoderSession.close();} catch (Exception ignore) {}
    }

    // ---- utils ----
    private static String pickOnnxFile(File dir, String base) {
        String q = base + "_quantized.onnx";
        return new File(dir, q).exists() ? q : base + ".onnx";
    }

    private static long[] ones(int len) {
        long[] a = new long[len]; Arrays.fill(a, 1L); return a;
    }

    private static long[] toArray(List<Long> list) {
        long[] a = new long[list.size()];
        for (int i = 0; i < list.size(); i++) a[i] = list.get(i);
        return a;
    }

    private static long argmax(float[] logits) {
        int best = 0;
        for (int i = 1; i < logits.length; i++)
            if (logits[i] > logits[best]) best = i;
        return best;
    }

    private static String sanitizeTranslatedOutput(String original, String translated) {
        if (translated == null) return original;
        String normalized = translated.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) return original;
        if (isClearlyBrokenTranslation(normalized)) return original;
        return normalized;
    }

    private static boolean isClearlyBrokenTranslation(String text) {
        String compact = text.replaceAll("\\s+", "");
        if (compact.length() >= 12 && compact.matches("^(.{1,4})\\1{4,}$")) return true;
        if (compact.length() >= 24 && hasVeryLowCharacterDiversity(compact)) return true;
        return false;
    }

    private static boolean hasVeryLowCharacterDiversity(String text) {
        List<Character> uniques = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!uniques.contains(c)) {
                uniques.add(c);
                if (uniques.size() > 6) return false;
            }
        }
        return uniques.size() <= 4;
    }

    private static String buildMt5RewritePrompt(String original, String promptLanguage) {
        String lang = normalizePromptLanguage(promptLanguage);
        return "Please correct the following " + lang
                + " speech recognition result into a natural sentence without changing the meaning. "
                + "Output only the corrected sentence.\n"
                + "Input: " + original + "\n"
                + "Output:";
    }

    private static String normalizePromptLanguage(String promptLanguage) {
        String lang = (promptLanguage == null) ? "" : promptLanguage.trim();
        if (lang.isEmpty()) return "Japanese";
        int paren = lang.indexOf('(');
        if (paren > 0) lang = lang.substring(0, paren).trim();
        return switch (lang.toLowerCase(Locale.ROOT)) {
            case "ja", "jp" -> "Japanese";
            case "en" -> "English";
            case "zh", "zh-cn", "zh-tw" -> "Chinese";
            case "ko" -> "Korean";
            default -> lang;
        };
    }

    private static String sanitizeMt5Output(String original, String promptLanguage, String result) {
        if (result == null) return original;
        Config.logDebug("[mt5][raw] result=" + result);
        String cleaned = result.replace("\r", " ").replace("\n", " ").trim();
        if (cleaned.isEmpty()) return original;
        if (cleaned.toLowerCase(Locale.ROOT).contains("<extra_id_")) return original;

        String lang = normalizePromptLanguage(promptLanguage);
        cleaned = cleaned.replaceFirst("(?i)^Please\\s*correct\\s*the\\s*following\\s*"
                + Pattern.quote(lang) + "\\s*speech\\s*recognition\\s*result.*?Output\\s*:\\s*", "");
        cleaned = cleaned.replaceFirst("(?i)^Input\\s*:\\s*", "");
        cleaned = cleaned.replaceFirst("(?i)^Output\\s*:\\s*", "");
        cleaned = cleaned.replaceFirst("(?i)^Correct\\s+" + Pattern.quote(lang) + "\\s+ASR\\s*:\\s*", "");
        cleaned = cleaned.trim();
        if (cleaned.isEmpty()) return original;
        if (cleaned.toLowerCase(Locale.ROOT).contains("<extra_id_")) return original;
        if (cleaned.equalsIgnoreCase(original)) return original;
        if (cleaned.toLowerCase(Locale.ROOT).contains("pleasecorrectthefollowing")) return original;
        if (isClearlyBrokenTranslation(cleaned)) return original;
        return cleaned;
    }
}

// ============================================================
class TestOrtPostProcessor {
    public static void main(String[] args) {
        File exeDir = (args.length >= 1)
                ? new File(args[0])
                : new File("C:\\@work\\@SourceTree\\MobMate\\app\\dist");
        File modelDir = (args.length >= 2)
                ? new File(args[1])
                : new File(exeDir, "models" + File.separator + "mt5_onnx_int8");
        String text = (args.length >= 3) ? args[2] : "ありがとございます。";
        String lang = (args.length >= 4) ? args[3] : "Japanese";

        PostProcessor pp = new PostProcessor(modelDir);
        boolean loaded = pp.load(exeDir);
        System.out.println("loaded=" + loaded);
        if (!loaded) {
            System.out.println("load_failed");
            return;
        }
        try {
            PostProcessor.ProcessResult r = pp.process(text, lang);
            System.out.println("input=" + text);
            System.out.println("output=" + r.text());
            System.out.println("elapsedMs=" + r.elapsedMs());
            System.out.println("timedOut=" + r.timedOut());
        } finally {
            pp.unload();
        }
    }
}

// ============================================================
// ★新規追加：mt5用トークナイザー
class Mt5Tokenizer implements AutoCloseable {

    private static final long PAD_ID = 0L; // decoder_start_token_id
    private static final long EOS_ID = 1L; // mt5のEOS

    private final long spHandle;

    Mt5Tokenizer(File modelDir) {
        File spmModel = new File(modelDir, "spiece.model");
        spHandle = TranslatorCore.spmLoad(spmModel.getAbsolutePath());
        if (spHandle <= 0)
            throw new IllegalStateException(
                    "[Mt5Tokenizer] spiece.model load failed: " + spmModel);
    }

    /** text → input_ids（末尾EOS付き、言語タグなし） */
    public long[] encode(String text) {
        int[] encoded = TranslatorCore.spmEncode(spHandle, text);
        int count = (encoded == null) ? 0 : encoded.length;
        long[] ids = new long[count + 1];
        for (int i = 0; i < count; i++) ids[i] = encoded[i];
        ids[count] = EOS_ID;
        return ids;
    }

    public String decode(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return "";
        int[] buf = ids.stream().mapToInt(Long::intValue).toArray();
        return TranslatorCore.spmDecode(spHandle, buf);
    }

    public long getDecoderStartId() { return PAD_ID; }
    public long getEosId()          { return EOS_ID; }

    @Override
    public void close() {
        if (spHandle > 0) TranslatorCore.spmFree(spHandle);
    }
}

// ============================================================
class M2M100Tokenizer implements AutoCloseable {

    private static final long EOS_ID = 2L;
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("\"(\\d+)\"\\s*:\\s*\\{[^{}]*\"content\"\\s*:\\s*\"__([a-zA-Z\\-]+)__\"");

    private final long spHandle;
    private final Map<String, Long>    langIds;
    private final Map<String, Integer> tokenToId;
    private final Map<Integer, String> idToToken;

    M2M100Tokenizer(File modelDir) {
        File spmModel = new File(modelDir, "sentencepiece.bpe.model");
        spHandle = TranslatorCore.spmLoad(spmModel.getAbsolutePath());
        if (spHandle <= 0)
            throw new IllegalStateException(
                    "sentencepiece load failed: " + spmModel.getAbsolutePath());
        langIds   = loadLangIds(new File(modelDir, "tokenizer_config.json"));
        tokenToId = loadTokenToId(new File(modelDir, "vocab.json"));
        idToToken = invertTokenToId(tokenToId);
    }

    public long[] encode(String text, String srcLang) {
        String[] pieces = TranslatorCore.spmEncodePieces(spHandle, text);
        int count = (pieces == null) ? 0 : pieces.length;
        long[] ids = new long[count + 2];
        ids[0] = getLangId(srcLang);
        for (int i = 0; i < count; i++) {
            String piece = pieces[i];
            Integer vocabId = tokenToId.get(piece);
            if (vocabId == null) vocabId = tokenToId.get("<unk>");
            if (vocabId == null)
                throw new IllegalStateException("vocab token not found: " + piece);
            ids[i + 1] = vocabId.longValue();
        }
        ids[count + 1] = EOS_ID;
        return ids;
    }

    public String decode(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return "";
        List<String> pieces = new ArrayList<>();
        for (Long id : ids) {
            String token = idToToken.get(id.intValue());
            if (token == null || isSpecialToken(token)) continue;
            pieces.add(token);
        }
        if (pieces.isEmpty()) return "";
        return TranslatorCore.spmDecodePieces(spHandle, pieces.toArray(String[]::new));
    }

    public long getLangId(String lang) {
        String normalized = normalizeLangCode(lang);
        Long exact = langIds.get(normalized);
        if (exact != null) return exact;
        Long english = langIds.get("en");
        if (english != null) return english;
        throw new IllegalStateException("language token not found: " + normalized);
    }

    public long getEosId() { return EOS_ID; }

    @Override
    public void close() {
        if (spHandle > 0) TranslatorCore.spmFree(spHandle);
    }

    private static String normalizeLangCode(String lang) {
        if (lang == null || lang.isBlank()) return "en";
        String s = lang.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "zh-cn", "zh-tw" -> "zh";
            case "jp" -> "ja";
            default -> s;
        };
    }

    private static Map<String, Long> loadLangIds(File tokenizerConfig) {
        if (tokenizerConfig == null || !tokenizerConfig.isFile())
            throw new IllegalStateException("tokenizer_config.json not found: " + tokenizerConfig);
        try {
            String json = Files.readString(tokenizerConfig.toPath(), StandardCharsets.UTF_8);
            Matcher matcher = TOKEN_PATTERN.matcher(json);
            Map<String, Long> parsed = new LinkedHashMap<>();
            while (matcher.find()) {
                long tokenId = Long.parseLong(matcher.group(1));
                String lang  = normalizeLangCode(matcher.group(2));
                parsed.put(lang, tokenId);
            }
            if (parsed.isEmpty())
                throw new IllegalStateException("no language tokens found in: " + tokenizerConfig);
            return Map.copyOf(parsed);
        } catch (IOException e) {
            throw new IllegalStateException("tokenizer_config parse failed: " + tokenizerConfig, e);
        }
    }

    private static Map<String, Integer> loadTokenToId(File vocabJson) {
        if (vocabJson == null || !vocabJson.isFile())
            throw new IllegalStateException("vocab.json not found: " + vocabJson);
        try {
            JSONObject root = new JSONObject(
                    Files.readString(vocabJson.toPath(), StandardCharsets.UTF_8));
            Map<String, Integer> parsed = new LinkedHashMap<>();
            for (String key : root.keySet()) parsed.put(key, root.getInt(key));
            return Map.copyOf(parsed);
        } catch (IOException e) {
            throw new IllegalStateException("vocab.json parse failed: " + vocabJson, e);
        }
    }

    private static Map<Integer, String> invertTokenToId(Map<String, Integer> tokenToId) {
        Map<Integer, String> inverted = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : tokenToId.entrySet())
            inverted.put(e.getValue(), e.getKey());
        return Map.copyOf(inverted);
    }

    private static boolean isSpecialToken(String token) {
        return token == null
                || token.isBlank()
                || token.startsWith("<")
                || (token.startsWith("__") && token.endsWith("__"));
    }
}
