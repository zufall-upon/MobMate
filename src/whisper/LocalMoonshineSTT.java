package whisper;

import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import java.io.File;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class LocalMoonshineSTT {

    // ---- 定数（moonshine-c-api.h 準拠） ----
    public static final int MOONSHINE_HEADER_VERSION          = 20000;
    public static final int MOONSHINE_MODEL_ARCH_TINY         = 0;
    public static final int MOONSHINE_MODEL_ARCH_BASE         = 1;
    public static final int MOONSHINE_MODEL_ARCH_TINY_STREAMING  = 2;
    public static final int MOONSHINE_MODEL_ARCH_BASE_STREAMING  = 3;
    public static final int MOONSHINE_MODEL_ARCH_SMALL_STREAMING = 4;
    public static final int MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING = 5;
    public static final int MOONSHINE_FLAG_FORCE_UPDATE        = (1 << 0);

    // ---- transcript_line_t の手動オフセット (64-bit Windows MSVC) ----
    // Moonshine 0.0.51 で words / word_count が追加されたので 1行サイズは 80 bytes っす。
    // JNA Structure のアライメント問題を完全に回避するため手動計算
    private static final int LINE_OFF_TEXT             = 0;   // const char*  (8)
    private static final int LINE_OFF_AUDIO_DATA       = 8;   // const float* (8)
    private static final int LINE_OFF_AUDIO_COUNT      = 16;  // size_t       (8)
    private static final int LINE_OFF_START_TIME       = 24;  // float        (4)
    private static final int LINE_OFF_DURATION         = 28;  // float        (4)
    private static final int LINE_OFF_ID               = 32;  // uint64_t     (8)
    private static final int LINE_OFF_IS_COMPLETE      = 40;  // int8_t       (1)
    private static final int LINE_OFF_IS_UPDATED       = 41;  // int8_t       (1)
    private static final int LINE_OFF_IS_NEW           = 42;  // int8_t       (1)
    private static final int LINE_OFF_HAS_TEXT_CHANGED = 43;  // int8_t       (1)
    private static final int LINE_OFF_HAS_SPEAKER_ID   = 44;  // int8_t       (1)
    // 45-47: 3 bytes padding
    private static final int LINE_OFF_SPEAKER_ID       = 48;  // uint64_t     (8)
    private static final int LINE_OFF_SPEAKER_INDEX    = 56;  // uint32_t     (4)
    private static final int LINE_OFF_LATENCY_MS       = 60;  // uint32_t     (4)
    private static final int LINE_OFF_WORDS            = 64;  // transcript_word_t* (8)
    private static final int LINE_OFF_WORD_COUNT       = 72;  // uint64_t            (8)
    private static final int LINE_SIZE                 = 80;  // total
    // transcript_word_t の手動オフセット (64-bit Windows MSVC)
    // const char* + float + float + float + 4 bytes padding = 24 bytes
    private static final int WORD_OFF_TEXT             = 0;   // const char* (8)
    private static final int WORD_OFF_START            = 8;   // float       (4)
    private static final int WORD_OFF_END              = 12;  // float       (4)
    private static final int WORD_OFF_CONFIDENCE       = 16;  // float       (4)
    private static final int WORD_SIZE                 = 24;  // total

    // transcript_t のオフセット
    private static final int TX_OFF_LINES      = 0;  // transcript_line_t* (8)
    private static final int TX_OFF_LINE_COUNT = 8;  // uint64_t           (8)
    private static final int WORD_TIMESTAMP_DIAG_BUDGET = 12;
    private static final String[] WORD_TIMESTAMP_NON_STREAMING_FILES =
            new String[] { "decoder_with_attention.ort", "alignment_model.ort" };
    private static final String[] WORD_TIMESTAMP_STREAMING_FILES =
            new String[] { "decoder_kv_with_attention.ort" };

    // ---- JNA インターフェース ----
    public interface MoonshineLib extends Library {
        MoonshineLib INSTANCE = Native.load("moonshine", MoonshineLib.class,
                java.util.Collections.singletonMap(Library.OPTION_STRING_ENCODING, "UTF-8"));

        int    moonshine_get_version();
        String moonshine_error_to_string(int error);
        String moonshine_transcript_to_string(Pointer transcript);

        // String に戻すっす（JNAが自動で NUL 終端して UTF-8 エンコードしてくれるっす）
        int  moonshine_load_transcriber_from_files(
                String path, int model_arch,
                Pointer options, long options_count,
                int moonshine_version);
        void moonshine_free_transcriber(int handle);

        int  moonshine_create_stream(int handle, int flags);
        int  moonshine_start_stream(int handle, int stream);
        int  moonshine_stop_stream(int handle, int stream);
        int  moonshine_free_stream(int handle, int stream);

        int  moonshine_transcribe_add_audio_to_stream(
                int handle, int stream,
                float[] data, long length,
                int sampleRate, int flags);

        int  moonshine_transcribe_stream(
                int handle, int stream,
                int flags, PointerByReference out);
        int  moonshine_transcribe_without_streaming(
                int handle, float[] data, long length,
                int sampleRate, int flags, PointerByReference out);
    }

    // ★Moonshine公式 options 用
    public static class TranscriberOption extends Structure {
        public String name;
        public String value;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("name", "value");
        }
    }

    // ★短い発話向けの攻め設定（MobMate側VADと二重なので少し攻める）
    private double vadWindowDuration = 0.15;      // default 0.5 → 短発話向けに短縮
    private int    vadLookBehindSamples = 20000;  // default 8192 → 文頭救済を増やす
    private double vadThreshold = 0.30;           // default 0.5 → 少し感度を上げる
    private double maxTokensPerSecond = 6.5;      // Moonshine default. Non-English can need a higher cap.
    private boolean wordTimestampsEnabled = true;
    private boolean effectiveWordTimestampsEnabled = true;
    private String wordTimestampCapabilityLog = "";

    public static final class OneShotWordTiming {
        public final String text;
        public final float startSec;
        public final float endSec;
        public final float confidence;

        OneShotWordTiming(String text, float startSec, float endSec, float confidence) {
            this.text = text == null ? "" : text;
            this.startSec = startSec;
            this.endSec = endSec;
            this.confidence = confidence;
        }
    }
    public static final class OneShotTranscriptResult {
        public static final OneShotTranscriptResult EMPTY =
                new OneShotTranscriptResult("", Float.NaN, 0, -1, java.util.Collections.emptyList());

        public final String text;
        public final float lineConfidence;
        public final int wordCount;
        public final int latencyMs;
        public final List<OneShotWordTiming> words;

        OneShotTranscriptResult(String text,
                                float lineConfidence,
                                int wordCount,
                                int latencyMs,
                                List<OneShotWordTiming> words) {
            this.text = text == null ? "" : text;
            this.lineConfidence = lineConfidence;
            this.wordCount = Math.max(0, wordCount);
            this.latencyMs = latencyMs;
            this.words = words == null ? java.util.Collections.emptyList() : words;
        }

        public boolean hasConfidence() {
            return !Float.isNaN(lineConfidence) && lineConfidence >= 0.0f;
        }
    }
    private static final class WordTimestampCapability {
        final boolean available;
        final boolean streamingArch;
        final String matchedFile;
        final String expectedFilesLabel;
        final String reason;

        WordTimestampCapability(boolean available,
                                boolean streamingArch,
                                String matchedFile,
                                String expectedFilesLabel,
                                String reason) {
            this.available = available;
            this.streamingArch = streamingArch;
            this.matchedFile = matchedFile == null ? "" : matchedFile;
            this.expectedFilesLabel = expectedFilesLabel == null ? "" : expectedFilesLabel;
            this.reason = reason == null ? "" : reason;
        }
    }

    public void setVadWindowDuration(double sec) {
        this.vadWindowDuration = Math.max(0.05, Math.min(1.00, sec));
    }
    public void setVadLookBehindSamples(int samples) {
        this.vadLookBehindSamples = Math.max(0, Math.min(64000, samples));
    }
    public void setVadThreshold(double threshold) {
        this.vadThreshold = Math.max(0.05, Math.min(0.95, threshold));
    }
    public void setMaxTokensPerSecond(double tokensPerSecond) {
        this.maxTokensPerSecond = Math.max(1.0, Math.min(30.0, tokensPerSecond));
    }

    private TranscriberOption[] buildLoadOptions() {
        TranscriberOption[] opts = (TranscriberOption[]) new TranscriberOption().toArray(5);

        opts[0].name  = "vad_window_duration";
        opts[0].value = String.format(Locale.ROOT, "%.3f", vadWindowDuration);

        opts[1].name  = "vad_look_behind_sample_count";
        opts[1].value = Integer.toString(vadLookBehindSamples);

        opts[2].name  = "vad_threshold";
        opts[2].value = String.format(Locale.ROOT, "%.3f", vadThreshold);

        opts[3].name  = "word_timestamps";
        opts[3].value = effectiveWordTimestampsEnabled ? "true" : "false";

        opts[4].name = "max_tokens_per_second";
        opts[4].value = String.format(Locale.ROOT, "%.3f", maxTokensPerSecond);

        for (TranscriberOption opt : opts) {
            opt.write();
        }
        return opts;
    }

    // ---- フィールド ----
    private long lastCompletedCount = 0;
    private int transcriber = -1;
    private int stream      = -1;
    private volatile boolean running = false;
    private Thread pollThread;
    private volatile int pollGeneration = 0;  // ゾンビpollThread防止用世代カウンタ
    // ★ADD: stream resetカウント（一定回数でtranscriber完全リロード）
    private int streamResetCount = 0;
    private static final int STREAM_RESET_RELOAD_THRESHOLD = 100;  // 30→100（2-3分で発動してたのを7-10分に延長
    private final Object audioNotify = new Object(); // ★v1.5.0: audio→poll即起床
    private volatile boolean hasNewAudio = false;
    private Consumer<String> onCompleted;
    private Consumer<String> onPartial;

    // ★v1.5.0: Whisperから移植可能な設定値
    private int pollIntervalMs = 500;     // ポーリング間隔(ms)
    private int numThreads     = -1;      // スレッド数(-1=auto)
    private int wordTimestampDiagBudget = WORD_TIMESTAMP_DIAG_BUDGET;

    //** ポーリング間隔を設定（50-2000ms）。推論レイテンシの都合上150ms未満は空振りが増える */
    public void setPollInterval(int ms) {
        this.pollIntervalMs = Math.max(50, Math.min(2000, ms));
        if (ms < 150) {
            Config.log("[Moonshine] WARNING: pollInterval=" + ms
                    + "ms is below inference latency (~200ms). Effective gain is minimal.");
        }
    }

    /** スレッド数を設定（-1=auto）。load() 前に呼ぶこと。 */
    public void setNumThreads(int threads) {
        this.numThreads = threads;
    }

    // ---- 2. loadメソッド ----
    private String modelPath;
    private int arch;
    public boolean load(String modelPath, int arch) {
        // ★v1.5.0: ONNX Runtime向け環境変数（load前に設定する必要あり）
        applyEnvSettings();

        if (!modelPath.endsWith("\\") && !modelPath.endsWith("/")) {
            modelPath += java.io.File.separator;
        }
        // ★ADD: reload用に保存（セパレータ付与後のパスを保持する）
        this.modelPath = modelPath;
        this.arch = arch;

        java.io.File dir = new java.io.File(modelPath);
        if (!dir.exists() || !dir.isDirectory()) {
            Config.log("[Moonshine] ERROR: Directory not found: " + modelPath);
            return false;
        }
        WordTimestampCapability wordTimestampCapability = resolveWordTimestampCapability(dir, arch);
        effectiveWordTimestampsEnabled = wordTimestampsEnabled && wordTimestampCapability.available;
        wordTimestampCapabilityLog = buildWordTimestampCapabilityLog(
                dir, arch, wordTimestampCapability, wordTimestampsEnabled, effectiveWordTimestampsEnabled);
        Config.log(wordTimestampCapabilityLog);
        if (wordTimestampsEnabled && !wordTimestampCapability.available) {
            Config.log("[Moonshine] WARNING: word confidence is unavailable for this model. " + wordTimestampCapability.reason);
        }

        // ★ 対策2: システムの古い onnxruntime.dll を勝手に読み込んで自爆するのを防ぐため、
        // JNAが動く前に、明示的に横にある正しいDLLを絶対パスで「強制ロード」させるっす！
        try {
            java.io.File ortDll = new java.io.File("onnxruntime.dll");
            if (ortDll.exists()) {
                System.load(ortDll.getAbsolutePath());
                Config.log("[Moonshine] Force-loaded exact onnxruntime.dll from: " + ortDll.getAbsolutePath());
            } else {
                Config.log("[Moonshine] WARNING: local onnxruntime.dll not found. System DLL might be loaded instead.");
            }
        } catch (Throwable t) {
            Config.log("[Moonshine] ERROR during ORT forced load: " + t.getMessage());
        }

        try {
            Config.log("[Moonshine] step1: moonshine_get_version...");
            int ver = MoonshineLib.INSTANCE.moonshine_get_version();
            Config.log("[Moonshine] step1 OK: version=" + ver);

            Config.log("[Moonshine] step2: load model path=" + modelPath + " arch=" + arch);

            // C++側に投げるっす！
            TranscriberOption[] opts = buildLoadOptions();

            Config.log("[Moonshine] load options:"
                    + " vad_window_duration=" + vadWindowDuration
                    + ", vad_look_behind_sample_count=" + vadLookBehindSamples
                    + ", vad_threshold=" + vadThreshold
                    + ", max_tokens_per_second=" + maxTokensPerSecond
                    + ", word_timestamps_requested=" + wordTimestampsEnabled
                    + ", word_timestamps_effective=" + effectiveWordTimestampsEnabled);

            int transcriberHandle = MoonshineLib.INSTANCE
                    .moonshine_load_transcriber_from_files(
                            modelPath, arch,
                            opts[0].getPointer(), opts.length,
                            MOONSHINE_HEADER_VERSION);

            if (transcriberHandle < 0) {
                String errMsg = MoonshineLib.INSTANCE.moonshine_error_to_string(transcriberHandle);
                Config.log("[Moonshine] load FAILED! code=" + transcriberHandle + " reason=" + errMsg);
                return false;
            }

            Config.log("[Moonshine] step2 OK! handle=" + transcriberHandle);
            transcriber = transcriberHandle;
            warmUpAfterLoadIfEnabled();
            return true;

        } catch (Error e) {
            // ★ JNA の Invalid memory access は Error
            Config.log("[Moonshine] load ERROR: " + e.getClass().getName()
                    + " - " + e.getMessage());
            e.printStackTrace();
            return false;
        } catch (Exception e) {
            Config.log("[Moonshine] load exception: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void warmUpAfterLoadIfEnabled() {
        if (!Config.getBool("moonshine.warmup.enabled", true)) return;
        try {
            long startNs = System.nanoTime();
            int warmupMs = Math.max(40, Math.min(250, Config.getInt("moonshine.warmup.ms", 80)));
            float[] silence = new float[Math.max(160, (16000 * warmupMs) / 1000)];
            OneShotDecodeOptions options = new OneShotDecodeOptions(80, 80, 1, 0, true);
            OneShotTranscriptResult result = transcribeOneShotInternal(silence, options, "warmup");
            long elapsedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            Config.log("[Moonshine] warmup OK: ms=" + elapsedMs
                    + " inputMs=" + warmupMs
                    + " text=" + (result.text.isEmpty() ? "<blank>" : result.text));
        } catch (Throwable t) {
            Config.log("[Moonshine] warmup skipped: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    public void setOnCompleted(Consumer<String> cb) { this.onCompleted = cb; }
    public void setOnPartial(Consumer<String> cb)   { this.onPartial   = cb; }

    // ---- 録音開始 ----
    public void start() {
        if (transcriber < 0) return;
        try {
            Config.log("[Moonshine] step3: create_stream...");
            stream = MoonshineLib.INSTANCE.moonshine_create_stream(transcriber, 0);
            if (stream < 0) {
                Config.log("[Moonshine] create_stream FAIL: " + stream);
                return;
            }
            Config.log("[Moonshine] step3 OK: stream=" + stream);

            Config.log("[Moonshine] step4: start_stream...");
            int err = MoonshineLib.INSTANCE
                    .moonshine_start_stream(transcriber, stream);
            if (err != 0) {
                Config.log("[Moonshine] start_stream FAIL: " + err);
                return;
            }
            Config.log("[Moonshine] step4 OK");

            running = true;
            startPollThread();
            injectSilencePadding(); // ★初回開始時もデコーダを温める
        } catch (Error e) {
            Config.log("[Moonshine] start ERROR: " + e.getClass().getName()
                    + " - " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ---- 音声データ投入 ----
    public synchronized void addAudio(float[] data, int sampleRate) {
        if (!running || transcriber < 0 || stream < 0) return;
        try {
            MoonshineLib.INSTANCE.moonshine_transcribe_add_audio_to_stream(
                    transcriber, stream,
                    data, (long) data.length, sampleRate, 0);
            // ★v1.5.0: ポーリングスレッドを即座に起こす
            synchronized (audioNotify) {
                hasNewAudio = true;
                audioNotify.notify();
            }
        } catch (Error e) {
            Config.log("[Moonshine] addAudio ERROR: " + e.getMessage());
        }
    }

    // ---- 停止 ----
    public void stop() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
            try { pollThread.join(2000); } catch (InterruptedException ignored) {}
        }
        if (transcriber >= 0 && stream >= 0) {
            try {
                MoonshineLib.INSTANCE.moonshine_stop_stream(transcriber, stream);
                MoonshineLib.INSTANCE.moonshine_free_stream(transcriber, stream);
            } catch (Error e) {
                Config.log("[Moonshine] stop ERROR: " + e.getMessage());
            }
        }
        stream = -1;
        Config.log("[Moonshine] stopped");
    }

    public void free() {
        stop();
        if (transcriber >= 0) {
            Config.log("[Moonshine] freeing transcriber... handle=" + transcriber);
            MoonshineLib.INSTANCE.moonshine_free_transcriber(transcriber);
            transcriber = -1;
            Config.log("[Moonshine] freed successfully.");
        }
    }

    // ---- ★追加：終了時のメモリ解放処理 ----
    public void close() {
        running = false; // ポーリングスレッドを安全に止めるっす
        if (pollThread != null) {
            pollThread.interrupt();
            try { pollThread.join(1000); } catch (InterruptedException ignored) {}
        }

        if (stream >= 0) {
            Config.log("[Moonshine] freeing stream... handle=" + stream);
            MoonshineLib.INSTANCE.moonshine_free_stream(transcriber, stream);
            stream = -1;
        }

        if (transcriber >= 0) {
            Config.log("[Moonshine] freeing transcriber... handle=" + transcriber);
            MoonshineLib.INSTANCE.moonshine_free_transcriber(transcriber);
            transcriber = -1;
        }

        Config.log("[Moonshine] Successfully closed and freed memory.");
    }
    /** ★発話開始時に呼ぶ：ストリームをリセットして新しい発話コンテキストを開始 */
    public synchronized void resetStream() {
        if (!running || transcriber < 0) return;

        flushPendingTranscriptOnce();   // ★ADD: reset前に最後のpartial/COMPLETEを回収

        streamResetCount++;

        // ★ADD: 一定回数でtranscriberを完全リロード（ONNX Runtime内部蓄積防止）
        if (streamResetCount >= STREAM_RESET_RELOAD_THRESHOLD) {
            Config.log("[Moonshine] ★ stream reset count=" + streamResetCount
                    + " >= " + STREAM_RESET_RELOAD_THRESHOLD + " → full transcriber reload");
            reloadTranscriber();
            streamResetCount = 0;
            return;
        }

        try {
            if (stream >= 0) {
                MoonshineLib.INSTANCE.moonshine_stop_stream(transcriber, stream);
                MoonshineLib.INSTANCE.moonshine_free_stream(transcriber, stream);
                stream = -1;
            }
            stream = MoonshineLib.INSTANCE.moonshine_create_stream(transcriber, 0);
            if (stream < 0) {
                Config.log("[Moonshine] resetStream: create_stream FAIL: " + stream);
                return;
            }
            lastCompletedCount = 0; // 新しいストリームになったら0に戻す
            int err = MoonshineLib.INSTANCE.moonshine_start_stream(transcriber, stream);
            if (err != 0) {
                Config.log("[Moonshine] resetStream: start_stream FAIL: " + err);
                stream = -1;
                return;
            }
            injectSilencePadding(); // ★FIX: デコーダ安定化
            Config.logDebug("[Moonshine] stream reset OK (new utterance)");
        } catch (Error e) {
            Config.log("[Moonshine] resetStream ERROR: " + e.getMessage());
        }
    }

    /** ★ADD: transcriber完全リロード（stream蓄積によるONNX劣化リセット） */
    private void reloadTranscriber() {
        try {
            // 旧streamとtranscriberを解放
            if (stream >= 0) {
                try {
                    MoonshineLib.INSTANCE.moonshine_stop_stream(transcriber, stream);
                    MoonshineLib.INSTANCE.moonshine_free_stream(transcriber, stream);
                } catch (Error ignore) {}
                stream = -1;
            }
            if (transcriber >= 0) {
                try {
                    MoonshineLib.INSTANCE.moonshine_free_transcriber(transcriber);
                } catch (Error ignore) {}
                transcriber = -1;
            }

            // 同じモデルパス・archで再ロード
            Config.log("[Moonshine] reloading transcriber from path=" + this.modelPath + " arch=" + this.arch);
            TranscriberOption[] opts = buildLoadOptions();

            Config.log("[Moonshine] reload options:"
                    + " vad_window_duration=" + vadWindowDuration
                    + ", vad_look_behind_sample_count=" + vadLookBehindSamples
                    + ", vad_threshold=" + vadThreshold
                    + ", max_tokens_per_second=" + maxTokensPerSecond);

            int newHandle = MoonshineLib.INSTANCE
                    .moonshine_load_transcriber_from_files(
                            this.modelPath, this.arch,
                            opts[0].getPointer(), opts.length,
                            MOONSHINE_HEADER_VERSION);
            if (newHandle < 0) {
                Config.log("[Moonshine] reload FAILED: " + newHandle);
                running = false;
                return;
            }
            transcriber = newHandle;

            // 新しいstream作成
            stream = MoonshineLib.INSTANCE.moonshine_create_stream(transcriber, 0);
            if (stream < 0) {
                Config.log("[Moonshine] reload: create_stream FAIL");
                running = false;
                return;
            }
            lastCompletedCount = 0; // 新しいストリームになったら0に戻す
            int err = MoonshineLib.INSTANCE.moonshine_start_stream(transcriber, stream);
            if (err != 0) {
                Config.log("[Moonshine] reload: start_stream FAIL");
                running = false;
                return;
            }
            injectSilencePadding(); // ★FIX: デコーダ安定化
            Config.log("[Moonshine] ★ transcriber reload OK! new handle=" + transcriber + " stream=" + stream);

            // ★FIX: pollThreadを再起動（reload後に内部状態をリセット）
            if (pollThread != null && pollThread.isAlive()) {
                pollThread.interrupt();
                try { pollThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            startPollThread();
        } catch (Error e) {
            Config.log("[Moonshine] reloadTranscriber ERROR: " + e.getMessage());
            running = false;
        }
    }
    public boolean isLoaded() { return transcriber >= 0; }

    /** 外部から強制リロード（連続無出力からの復旧用） *//** ★FIX: synchronized追加 + 二重start_stream除去 */
    public synchronized void forceReload() {
        Config.log("[Moonshine] ★ forceReload requested");
        reloadTranscriber();
        streamResetCount = 0;
        // reloadTranscriber内でstart_stream済み → ここでは呼ばない
    }

    // ★v1.5.0: ONNX Runtime向け環境変数をプロセスレベルで設定
    private void applyEnvSettings() {
        // CPU推論のスレッド数（ONNX Runtimeが参照）
        if (numThreads > 0) {
            setEnv("OMP_NUM_THREADS", String.valueOf(numThreads));
            Config.log("[Moonshine] OMP_NUM_THREADS=" + numThreads);
        }
        Config.log("[Moonshine] pollInterval=" + pollIntervalMs
                + "ms (CPU inference)");
    }

    private static void setEnv(String name, String value) {
        try {
            // JNA経由でプロセス環境変数を直接設定（Windowsネイティブ）
            com.sun.jna.platform.win32.Kernel32 k =
                    com.sun.jna.platform.win32.Kernel32.INSTANCE;
            k.SetEnvironmentVariable(name, value);
        } catch (Throwable t) {
            // JNA不可時はJava側にフォールバック（効果は薄いが無害）
            if (value != null) System.setProperty(name, value);
            else System.clearProperty(name);
        }
    }
    // clipping専用reset（カウントに含めない）
    public synchronized void resetStreamForClipping() {
        if (!running || transcriber < 0) return;
        flushPendingTranscriptOnce();
        try {
            if (stream >= 0) {
                MoonshineLib.INSTANCE.moonshine_stop_stream(transcriber, stream);
                MoonshineLib.INSTANCE.moonshine_free_stream(transcriber, stream);
                stream = -1;
            }
            stream = MoonshineLib.INSTANCE.moonshine_create_stream(transcriber, 0);
            if (stream < 0) return;
            lastCompletedCount = 0; // 新しいストリームになったら0に戻す
            MoonshineLib.INSTANCE.moonshine_start_stream(transcriber, stream);
            injectSilencePadding(); // ★FIX: デコーダ安定化
            Config.logDebug("[Moonshine] stream reset OK (clipping)");
        } catch (Error e) {
            Config.log("[Moonshine] resetStreamForClipping ERROR: " + e.getMessage());
        }
        // ★streamResetCount は更新しない
    }
    private void injectSilencePaddingMs(int ms) {
        if (transcriber < 0 || stream < 0) return;
        try {
            int clampedMs = Math.max(50, Math.min(1000, ms));
            final int padSamples = Math.max(160, (16000 * clampedMs) / 1000);

            float[] pad = new float[padSamples];
            MoonshineLib.INSTANCE.moonshine_transcribe_add_audio_to_stream(
                    transcriber, stream, pad, (long) padSamples, 16000, 0);

            synchronized (audioNotify) {
                hasNewAudio = true;
                audioNotify.notify();
            }

            Config.logDebug("[Moonshine] silence padding injected: " + padSamples + " samples");
        } catch (Error e) {
            Config.logDebug("[Moonshine] silence padding error: " + e.getMessage());
        }
    }
    /** ストリーム開始直後に無音パディング投入（デコーダウォームアップ） */
    private void injectSilencePadding() {
        injectSilencePaddingMs(300);
    }
    /** flush直前に末尾へ短い無音を足して、語尾の着地を安定させる */
    private void injectTailSilencePaddingMs(int ms) {
        if (transcriber < 0 || stream < 0) return;
        try {
            int clampedMs = Math.max(50, Math.min(1000, ms));
            final int padSamples = Math.max(160, (16000 * clampedMs) / 1000);
            float[] pad = new float[padSamples];
            MoonshineLib.INSTANCE.moonshine_transcribe_add_audio_to_stream(
                    transcriber, stream, pad, (long) padSamples, 16000, 0);
            Config.logDebug("[Moonshine] tail silence injected: " + padSamples + " samples");
        } catch (Error e) {
            Config.logDebug("[Moonshine] tail silence error: " + e.getMessage());
        }
    }
    /** flush直前に末尾へ短い無音を足して、語尾の着地を安定させる */
    private void injectTailSilencePadding() {
        injectTailSilencePaddingMs(300);
    }
    private static final class OneShotDecodeOptions {
        final int leadingPaddingMs;
        final int tailPaddingMs;
        final int passes;
        final int settleSleepMs;
        final boolean useWithoutStreaming;

        OneShotDecodeOptions(int leadingPaddingMs, int tailPaddingMs, int passes, int settleSleepMs, boolean useWithoutStreaming) {
            this.leadingPaddingMs = Math.max(50, Math.min(1000, leadingPaddingMs));
            this.tailPaddingMs = Math.max(50, Math.min(1000, tailPaddingMs));
            this.passes = Math.max(1, Math.min(4, passes));
            this.settleSleepMs = Math.max(0, Math.min(120, settleSleepMs));
            this.useWithoutStreaming = useWithoutStreaming;
        }
    }
    private static final OneShotDecodeOptions DEFAULT_ONE_SHOT_OPTIONS =
            new OneShotDecodeOptions(300, 300, 2, 35, false);
    private static final OneShotDecodeOptions ACCURATE_ONE_SHOT_OPTIONS =
            new OneShotDecodeOptions(300, 450, 3, 45, true);
    /**
     * ★Hearing用ワンショット推論: PCMを投入してストリームを即時フラッシュし結果を返す
     * onCompleted/onPartialコールバックは呼ばない（Hearing専用経路用）
     */
    public synchronized String transcribeOneShot(float[] pcmFloat) {
        return transcribeOneShotDetailed(pcmFloat).text;
    }
    public synchronized String transcribeOneShotAccurate(float[] pcmFloat) {
        return transcribeOneShotAccurateDetailed(pcmFloat).text;
    }
    public synchronized OneShotTranscriptResult transcribeOneShotDetailed(float[] pcmFloat) {
        return transcribeOneShotInternal(pcmFloat, DEFAULT_ONE_SHOT_OPTIONS, "oneShot");
    }
    public synchronized OneShotTranscriptResult transcribeOneShotAccurateDetailed(float[] pcmFloat) {
        return transcribeOneShotInternal(pcmFloat, ACCURATE_ONE_SHOT_OPTIONS, "oneShot+");
    }
    public synchronized OneShotTranscriptResult transcribeOneShotDetailed(float[] pcmFloat,
                                                                          int leadingPaddingMs,
                                                                          int tailPaddingMs,
                                                                          int passes,
                                                                          int settleSleepMs,
                                                                          boolean useWithoutStreaming,
                                                                          String logTag) {
        OneShotDecodeOptions options = new OneShotDecodeOptions(
                leadingPaddingMs, tailPaddingMs, passes, settleSleepMs, useWithoutStreaming);
        String tag = (logTag == null || logTag.isBlank()) ? "oneShotCustom" : logTag.trim();
        return transcribeOneShotInternal(pcmFloat, options, tag);
    }
    private OneShotTranscriptResult transcribeOneShotInternal(float[] pcmFloat,
                                                              OneShotDecodeOptions options,
                                                              String logTag) {
        if (transcriber < 0 || pcmFloat == null || pcmFloat.length == 0) return OneShotTranscriptResult.EMPTY;
        if (options.useWithoutStreaming) {
            return transcribeOneShotWithoutStreaming(pcmFloat, options, logTag);
        }
        try {
            if (stream >= 0) {
                try { MoonshineLib.INSTANCE.moonshine_stop_stream(transcriber, stream); } catch (Error ignore) {}
                try { MoonshineLib.INSTANCE.moonshine_free_stream(transcriber, stream); } catch (Error ignore) {}
                stream = -1;
            }

            stream = MoonshineLib.INSTANCE.moonshine_create_stream(transcriber, 0);
            if (stream < 0) return OneShotTranscriptResult.EMPTY;
            lastCompletedCount = 0;

            int err = MoonshineLib.INSTANCE.moonshine_start_stream(transcriber, stream);
            if (err != 0) {
                stream = -1;
                return OneShotTranscriptResult.EMPTY;
            }

            injectSilencePaddingMs(options.leadingPaddingMs);
            MoonshineLib.INSTANCE.moonshine_transcribe_add_audio_to_stream(
                    transcriber, stream, pcmFloat, (long) pcmFloat.length, 16000, 0);
            injectTailSilencePaddingMs(options.tailPaddingMs);

            OneShotTranscriptResult best = null;
            for (int pass = 0; pass < options.passes; pass++) {
                if (pass > 0) {
                    try {
                        Thread.sleep(options.settleSleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }

                PointerByReference pRef = new PointerByReference();
                int ferr = MoonshineLib.INSTANCE.moonshine_transcribe_stream(
                        transcriber, stream, MOONSHINE_FLAG_FORCE_UPDATE, pRef);
                if (ferr != 0) continue;

                Pointer pTx = pRef.getValue();
                if (pTx == null) continue;
                Pointer pLines = pTx.getPointer(TX_OFF_LINES);
                long lineCount = pTx.getLong(TX_OFF_LINE_COUNT);
                if (pLines == null || lineCount <= 0) continue;

                for (long i = 0; i < lineCount; i++) {
                    long base = i * LINE_SIZE;
                    OneShotTranscriptResult line = readOneShotTranscriptLine(pLines, base);
                    if (!line.text.isEmpty()) best = line;
                }
                if (best != null) break;
            }

            Config.logDebug("[Moonshine][Hearing] " + logTag + ": "
                    + (best == null ? "null" : best.text)
                    + (best != null && best.wordCount > 0
                    ? " words=" + best.wordCount
                    + (best.hasConfidence()
                    ? " conf=" + String.format(Locale.ROOT, "%.3f", best.lineConfidence)
                    : " conf=NA")
                    : ""));
            return (best != null) ? best : OneShotTranscriptResult.EMPTY;
        } catch (Error e) {
            Config.logDebug("[Moonshine][Hearing] " + logTag + " ERROR: " + e.getMessage());
            return OneShotTranscriptResult.EMPTY;
        }
    }
    private OneShotTranscriptResult transcribeOneShotWithoutStreaming(float[] pcmFloat,
                                                                      OneShotDecodeOptions options,
                                                                      String logTag) {
        if (transcriber < 0 || pcmFloat == null || pcmFloat.length == 0) return OneShotTranscriptResult.EMPTY;
        try {
            int leadingSamples = Math.max(160, (16000 * options.leadingPaddingMs) / 1000);
            int tailSamples = Math.max(160, (16000 * options.tailPaddingMs) / 1000);
            float[] padded = new float[leadingSamples + pcmFloat.length + tailSamples];
            System.arraycopy(pcmFloat, 0, padded, leadingSamples, pcmFloat.length);
            PointerByReference pRef = new PointerByReference();
            int err = MoonshineLib.INSTANCE.moonshine_transcribe_without_streaming(
                    transcriber, padded, (long) padded.length, 16000, 0, pRef);
            if (err != 0) {
                Config.logDebug("[Moonshine][Hearing] " + logTag + " without_streaming err=" + err);
                return OneShotTranscriptResult.EMPTY;
            }
            OneShotTranscriptResult best = readBestTranscriptResult(pRef.getValue(), logTag + " without_streaming");
            Config.logDebug("[Moonshine][Hearing] " + logTag + " without_streaming: "
                    + (best.text.isEmpty() ? "null" : best.text)
                    + (best.wordCount > 0
                    ? " words=" + best.wordCount
                    + (best.hasConfidence()
                    ? " conf=" + String.format(Locale.ROOT, "%.3f", best.lineConfidence)
                    : " conf=NA")
                    : ""));
            return best;
        } catch (Error e) {
            Config.logDebug("[Moonshine][Hearing] " + logTag + " without_streaming ERROR: " + e.getMessage());
            return OneShotTranscriptResult.EMPTY;
        }
    }
    private OneShotTranscriptResult readBestTranscriptResult(Pointer pTx, String debugContext) {
        if (pTx == null) return OneShotTranscriptResult.EMPTY;
        Pointer pLines = pTx.getPointer(TX_OFF_LINES);
        long lineCount = pTx.getLong(TX_OFF_LINE_COUNT);
        if (pLines == null || lineCount <= 0) return OneShotTranscriptResult.EMPTY;
        OneShotTranscriptResult best = OneShotTranscriptResult.EMPTY;
        for (long i = 0; i < lineCount; i++) {
            long base = i * LINE_SIZE;
            OneShotTranscriptResult line = readOneShotTranscriptLine(pTx, pLines, base, debugContext);
            if (!line.text.isEmpty()) best = line;
        }
        return best;
    }
    private OneShotTranscriptResult readOneShotTranscriptLine(Pointer pLines, long base) {
        return readOneShotTranscriptLine(null, pLines, base, null);
    }
    private OneShotTranscriptResult readOneShotTranscriptLine(Pointer pTx, Pointer pLines, long base, String debugContext) {
        Pointer pText = pLines.getPointer(base + LINE_OFF_TEXT);
        if (pText == null) return OneShotTranscriptResult.EMPTY;
        String text;
        try {
            text = pText.getString(0, "UTF-8").trim();
        } catch (Error ignore) {
            return OneShotTranscriptResult.EMPTY;
        }
        if (text.isEmpty()) return OneShotTranscriptResult.EMPTY;
        int latencyMs = pLines.getInt(base + LINE_OFF_LATENCY_MS);
        Pointer pWords = pLines.getPointer(base + LINE_OFF_WORDS);
        long rawWordCount = pLines.getLong(base + LINE_OFF_WORD_COUNT);
        long rawWordCount32 = Integer.toUnsignedLong(pLines.getInt(base + LINE_OFF_WORD_COUNT));
        int wordCount = normalizeWordCount(rawWordCount);
        if (wordCount == 0 && rawWordCount32 > 0L && rawWordCount32 <= 4096L) {
            wordCount = (int) rawWordCount32;
        }
        ArrayList<OneShotWordTiming> words = new ArrayList<>();
        float lineConfidence = Float.NaN;
        if (pWords != null && wordCount > 0) {
            double weightedConfidence = 0.0d;
            double totalWeight = 0.0d;
            for (int wi = 0; wi < wordCount; wi++) {
                long wordBase = (long) wi * WORD_SIZE;
                Pointer pWordText = pWords.getPointer(wordBase + WORD_OFF_TEXT);
                String wordText = "";
                if (pWordText != null) {
                    try {
                        wordText = pWordText.getString(0, "UTF-8").trim();
                    } catch (Error ignore) {
                        wordText = "";
                    }
                }
                float startSec = pWords.getFloat(wordBase + WORD_OFF_START);
                float endSec = pWords.getFloat(wordBase + WORD_OFF_END);
                float confidence = pWords.getFloat(wordBase + WORD_OFF_CONFIDENCE);
                words.add(new OneShotWordTiming(wordText, startSec, endSec, confidence));
                if (confidence >= 0.0f && confidence <= 1.0f) {
                    double durationWeight = Math.max(0.02d, Math.min(1.25d, endSec - startSec));
                    weightedConfidence += confidence * durationWeight;
                    totalWeight += durationWeight;
                }
            }
            if (totalWeight > 0.0d) {
                lineConfidence = (float) (weightedConfidence / totalWeight);
            }
        } else if (effectiveWordTimestampsEnabled && pTx != null && wordTimestampDiagBudget > 0 && !text.isEmpty()) {
            logWordTimestampDiagnostic(debugContext, pTx, pLines, base, text, latencyMs, pWords, rawWordCount, rawWordCount32);
        }
        return new OneShotTranscriptResult(text, lineConfidence, wordCount, latencyMs, words);
    }
    private static boolean isStreamingArch(int arch) {
        return arch == MOONSHINE_MODEL_ARCH_TINY_STREAMING
                || arch == MOONSHINE_MODEL_ARCH_BASE_STREAMING
                || arch == MOONSHINE_MODEL_ARCH_SMALL_STREAMING
                || arch == MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING;
    }
    private static WordTimestampCapability resolveWordTimestampCapability(File modelDir, int arch) {
        boolean streamingArch = isStreamingArch(arch);
        String[] expectedFiles = streamingArch
                ? WORD_TIMESTAMP_STREAMING_FILES
                : WORD_TIMESTAMP_NON_STREAMING_FILES;
        String expectedFilesLabel = String.join(" or ", expectedFiles);
        for (String expectedFile : expectedFiles) {
            File candidate = new File(modelDir, expectedFile);
            if (candidate.isFile()) {
                return new WordTimestampCapability(
                        true,
                        streamingArch,
                        expectedFile,
                        expectedFilesLabel,
                        "using " + expectedFile);
            }
        }
        return new WordTimestampCapability(
                false,
                streamingArch,
                "",
                expectedFilesLabel,
                "missing " + expectedFilesLabel + " in " + modelDir.getAbsolutePath());
    }
    private static String buildWordTimestampCapabilityLog(File modelDir,
                                                          int arch,
                                                          WordTimestampCapability capability,
                                                          boolean requested,
                                                          boolean effective) {
        return "[Moonshine] word timestamp capability:"
                + " arch=" + arch
                + ", mode=" + (capability.streamingArch ? "streaming" : "non-streaming")
                + ", requested=" + requested
                + ", available=" + capability.available
                + ", effective=" + effective
                + ", expected=" + capability.expectedFilesLabel
                + (capability.matchedFile.isEmpty() ? "" : ", matched=" + capability.matchedFile)
                + ", modelDir=" + modelDir.getAbsolutePath();
    }
    private int normalizeWordCount(long rawWordCount) {
        return (rawWordCount <= 0L || rawWordCount > 4096L) ? 0 : (int) rawWordCount;
    }
    private void logWordTimestampDiagnostic(String debugContext,
                                            Pointer pTx,
                                            Pointer pLines,
                                            long base,
                                            String text,
                                            int latencyMs,
                                            Pointer pWords,
                                            long rawWordCount,
                                            long rawWordCount32) {
        if (!Config.getBool("log.debug", false)) return;
        wordTimestampDiagBudget--;
        String transcriptDump = "";
        try {
            transcriptDump = MoonshineLib.INSTANCE.moonshine_transcript_to_string(pTx);
        } catch (Error ignore) {
            transcriptDump = "";
        }
        String lineHex = "";
        try {
            lineHex = bytesToHex(pLines.getByteArray(base, LINE_SIZE), LINE_SIZE);
        } catch (Error ignore) {
            lineHex = "";
        }
        Config.logDebug("[Moonshine][Diag] "
                + (debugContext == null ? "oneShot" : debugContext)
                + " no word data: text=" + shortenForLog(text, 80)
                + " latency=" + latencyMs
                + " pWords=" + formatPointer(pWords)
                + " rawWordCount64=" + rawWordCount
                + " rawWordCount32=" + rawWordCount32
                + (transcriptDump == null || transcriptDump.isBlank() ? "" : " tx=" + shortenForLog(transcriptDump, 220))
                + (lineHex.isBlank() ? "" : " lineHex=" + lineHex));
    }
    private static String formatPointer(Pointer ptr) {
        if (ptr == null) return "null";
        return "0x" + Long.toUnsignedString(Pointer.nativeValue(ptr), 16);
    }
    private static String shortenForLog(String text, int maxLen) {
        if (text == null) return "";
        String normalized = text.replace("\r", " ").replace("\n", " ").trim();
        if (normalized.length() <= maxLen) return normalized;
        return normalized.substring(0, Math.max(0, maxLen - 3)) + "...";
    }
    private static String bytesToHex(byte[] bytes, int maxBytes) {
        if (bytes == null || bytes.length == 0 || maxBytes <= 0) return "";
        int limit = Math.min(bytes.length, maxBytes);
        StringBuilder sb = new StringBuilder(limit * 3);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.ROOT, "%02x", bytes[i] & 0xff));
        }
        return sb.toString();
    }
    // reset直前に pending transcript を1回だけ吸い上げる
    private void flushPendingTranscriptOnce() {
        if (transcriber < 0 || stream < 0) return;

        try {
            // ★語尾欠け対策: 末尾に短い無音を足す
            injectTailSilencePadding();

            String bestComplete = null;
            String bestPartial = null;
            int bestLatency = -1;
            long completedCountSnapshot = lastCompletedCount;
            // ★FIX: pollThreadが既にCOMPLETEを発火済みならflushはCOMPLETE発火しない
            boolean alreadyFiredComplete = (completedCountSnapshot > 0);

            // ★1回勝負をやめて、短い2パス確認にする
            for (int pass = 0; pass < 2; pass++) {
                if (pass > 0) {
                    try {
                        Thread.sleep(35); // 語尾の着地を少し待つ
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }

                PointerByReference pRef = new PointerByReference();
                int err = MoonshineLib.INSTANCE.moonshine_transcribe_stream(
                        transcriber, stream,
                        MOONSHINE_FLAG_FORCE_UPDATE, pRef);

                if (err != 0) continue;

                Pointer pTx = pRef.getValue();
                if (pTx == null) continue;

                Pointer pLines = pTx.getPointer(TX_OFF_LINES);
                long lineCount = pTx.getLong(TX_OFF_LINE_COUNT);
                if (pLines == null || lineCount <= 0) continue;

                for (long i = 0; i < lineCount; i++) {
                    long base = i * LINE_SIZE;

                    Pointer pText = pLines.getPointer(base + LINE_OFF_TEXT);
                    if (pText == null) continue;

                    String text;
                    try {
                        text = pText.getString(0, "UTF-8").trim();
                    } catch (Error ignore) {
                        continue;
                    }
                    if (text.isEmpty()) continue;

                    byte isComplete = pLines.getByte(base + LINE_OFF_IS_COMPLETE);
                    int latencyMs = pLines.getInt(base + LINE_OFF_LATENCY_MS);

                    if (isComplete != 0) {
                        if (i >= completedCountSnapshot) {
                            bestComplete = text;
                            bestLatency = latencyMs;
                            completedCountSnapshot = i + 1;
                        }
                    } else {
                        bestPartial = text;
                        bestLatency = latencyMs;
                    }
                }

                // ★completeが取れたらそこで勝ち
                if (bestComplete != null) break;
            }

            lastCompletedCount = completedCountSnapshot;

            // ★FIX: pollThreadが既にCOMPLETE発火済みならflushはCOMPLETE発火しない（二重発火防止）
            if (bestComplete != null) {
                Config.log("[Moonshine] COMPLETE(flush): " + bestComplete
                        + " (latency=" + bestLatency + "ms)");
                if (onCompleted != null) onCompleted.accept(bestComplete);
            } else if (bestPartial != null) {
                // ★発話終了/reset直前のflushでは、partial止まりでも最終候補として確定扱いにする
                Config.log("[Moonshine] COMPLETE(flush-promoted): " + bestPartial
                        + " (latency=" + bestLatency + "ms)");
                if (onCompleted != null) onCompleted.accept(bestPartial);
            }
        } catch (Error e) {
            Config.logDebug("[Moonshine] flushPendingTranscriptOnce ERROR: " + e.getMessage());
        }
    }

    // ---- ポーリングスレッド（5ms間隔）----
    // ★ JNA Structure 不使用。生Pointerから手動オフセットで読む。
    private void startPollThread() {
        final int myGeneration = ++pollGeneration;  // この世代を覚える
        pollThread = new Thread(() -> {
//            long lastCompletedCount = 0;
            boolean firstPoll = true;

            while (running && pollGeneration == myGeneration && !Thread.currentThread().isInterrupted()) {
                // ★ addAudioが来たら即起床、来なくてもpollIntervalMsでタイムアウト
                boolean shouldPoll;
                synchronized (audioNotify) {
                    if (!hasNewAudio) {
                        try {
                            audioNotify.wait(pollIntervalMs);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    shouldPoll = hasNewAudio; // ★FIX: タイムアウト起床(audio無し)はpollしない
                    hasNewAudio = false;
                }
                if (!shouldPoll) continue; // ★FIX: 無音中にFORCE_UPDATEを連打してstream劣化するのを防ぐ


                synchronized (LocalMoonshineSTT.this) {
                    if (transcriber < 0 || stream < 0) continue;

                    try {
                        PointerByReference pRef = new PointerByReference();
                        // ★ FORCE_UPDATE: 今ある音声で即座に推論実行
                        int err = MoonshineLib.INSTANCE.moonshine_transcribe_stream(
                                transcriber, stream,
                                MOONSHINE_FLAG_FORCE_UPDATE, pRef);

                        if (firstPoll) {
                            Config.log("[Moonshine] first poll: err=" + err
                                    + " (FORCE_UPDATE enabled)");
                            firstPoll = false;
                        }
                        if (err != 0) {
                            Config.logDebug("[Moonshine] poll err=" + err);
                            continue;
                        }

                        Pointer pTx = pRef.getValue();
                        if (pTx == null) continue;

                        // ★ transcript_t を生ポインタで読む
                        Pointer pLines = pTx.getPointer(TX_OFF_LINES);
                        long lineCount = pTx.getLong(TX_OFF_LINE_COUNT);
                        if (pLines == null || lineCount <= 0) continue;

                        // ★ 各 line を手動オフセットで読む
                        for (long i = 0; i < lineCount; i++) {
                            long base = i * LINE_SIZE;

                            Pointer pText = pLines.getPointer(base + LINE_OFF_TEXT);
                            if (pText == null) continue;

                            String text;
                            try {
                                text = pText.getString(0, "UTF-8").trim();
                            } catch (Error readErr) {
                                Config.log("[Moonshine] text read error at line "
                                        + i + ": " + readErr.getMessage());
                                continue;
                            }

                            byte isComplete = pLines.getByte(base + LINE_OFF_IS_COMPLETE);
                            byte isUpdated = pLines.getByte(base + LINE_OFF_IS_UPDATED);
                            byte isNew = pLines.getByte(base + LINE_OFF_IS_NEW);
                            byte hasTextChanged = pLines.getByte(base + LINE_OFF_HAS_TEXT_CHANGED);
                            int latencyMs = pLines.getInt(base + LINE_OFF_LATENCY_MS);

                            // ★FIX: 空テキスト行はログだけ出してスキップ（幽霊行の可視化）
                            if (text.isEmpty()) {
                                Config.logDebug("[Moonshine] ghost line: i=" + i
                                        + " complete=" + isComplete
                                        + " updated=" + isUpdated
                                        + " new=" + isNew);
                                continue;
                            }

                            if (isComplete != 0) {
                                if (isUpdated != 0 || isNew != 0 || i >= lastCompletedCount) {
                                    Config.log("[Moonshine] COMPLETE: " + text
                                            + " (latency=" + latencyMs + "ms)");
                                    if (onCompleted != null) onCompleted.accept(text);
                                }
                            } else {
                                if (hasTextChanged != 0 || isNew != 0) {
                                    Config.log("[Moonshine] partial: " + text
                                            + " (" + latencyMs + "ms)");
                                    if (onPartial != null) onPartial.accept(text);
                                }
                            }
                        }

                        // ★FIX: 確定済み行カウント — テキストが空の行は数えない
                        long completed = 0;
                        for (long i = 0; i < lineCount; i++) {
                            long base = i * LINE_SIZE;
                            byte ic = pLines.getByte(base + LINE_OFF_IS_COMPLETE);
                            if (ic != 0) {
                                Pointer pt = pLines.getPointer(base + LINE_OFF_TEXT);
                                if (pt != null) {
                                    try {
                                        String t = pt.getString(0, "UTF-8").trim();
                                        if (!t.isEmpty()) completed = i + 1;
                                    } catch (Error ignore) {}
                                }
                            } else {
                                break;
                            }
                        }
                        lastCompletedCount = completed;

                    } catch (Error e) {
                        Config.log("[Moonshine] poll ERROR: "
                                + e.getClass().getName() + " - " + e.getMessage());
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ex) {
                            break;
                        }
                    }
                }
            }
        }, "moonshine-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }
}
