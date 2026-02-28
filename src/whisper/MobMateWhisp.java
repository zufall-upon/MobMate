package whisper;

import com.sun.jna.WString;
import com.sun.jna.platform.win32.Shell32;
import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.awt.event.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.*;
import java.awt.*;
import java.awt.Window.Type;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.plaf.FontUIResource;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import org.json.JSONArray;
import org.json.JSONObject;

import static whisper.VoicegerManager.httpOk;

public class MobMateWhisp implements NativeKeyListener {

    private static final String PREF_WIN_X = "ui.window.x";
    private static final String PREF_WIN_Y = "ui.window.y";
    private static final String PREF_WIN_W = "ui.window.w";
    private static final String PREF_WIN_H = "ui.window.h";

    public static Preferences prefs;
    private String lastOutput = null;
    private String cpugpumode = "";
    public String getCpuGpuMode() {
        return cpugpumode != null ? cpugpumode : "CPU MODE";
    }
    private final Random rnd = new Random();
    private String[] laughOptions;
    private HistoryFrame historyFrame;

    // Moonshine
    private static LocalMoonshineSTT moonshine;
    // Whisper
    volatile LocalWhisperCPP w;
    private volatile LocalWhisperCPP wHearing;
    private final Object hearingWhisperLock = new Object();
    private String model;
    private String model_dir;
    private final String remoteUrl;
    // Tray icon
    private TrayIcon trayIcon;
    private Image imageRecording;
    private Image imageTranscribing;
    private Image imageInactive;

    // Execution services
    private static final ExecutorService executorService = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "whisper-executor");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService audioService = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "audio-executor");
        t.setDaemon(true);
        return t;
    });

    // Add calibration status tracking field
    private volatile boolean isCalibrationComplete = false;
    // ★ADD: 話者照合プロファイル
    private SpeakerProfile speakerProfile;

    // Audio capture
    private final AudioFormat audioFormat;

    private boolean recording;
    private boolean transcribing;

    // History
    public List<String> history = new ArrayList<>();
    private final List<ChangeListener> historyListeners = new ArrayList<>();

    // Hotkey for recording
    private String hotkey;
    private boolean shiftHotkey;
    private boolean ctrltHotkey;
    private long recordingStartTime = 0;
    private boolean hotkeyPressed;
    // Trigger mode
    private static final String START_STOP = "start_stop";
    private static final String PUSH_TO_TALK_DOUBLE_TAP = "push_to_talk_double_tap";
    private static final String PUSH_TO_TALK = "push_to_talk";
    public JFrame window;
    private JButton button = null;
    final JLabel label = new JLabel(UiText.t("ui.main.ready"));
    // ===== ステータスバー用 =====
    private JLabel statusLabel;
    private JLabel engineLabel;
    private JLabel versionLabel;
    private JLabel vvStatusLabel;      // VoiceVox接続状態
    private JLabel vgStatusLabel;      // Voiceger接続状態（★追加）
    private JLabel durationLabel;      // 録音時間
    private JLabel memLabel;           // メモリ使用量
//    private JLabel gpuMemLabel;        // ★GPU VRAM使用量
    private volatile long gpuVramTotalBytes = -1;
    private JLabel modelLabel;         // 利用モデル（★追加）
    private JLabel speakerStatusLabel; // ★話者照合ステータス
    private Timer statusUpdateTimer;   // ステータスバー更新タイマー
    private long recordingStartTime2 = 0; // 録音開始時刻
    private JPanel statusBar;          // ★ステータスバー本体（再構築用）

    private Process psProcess;
    private BufferedWriter psWriter;
    private BufferedReader psReader;

    private volatile boolean isPriming = false;
    private boolean vadPrimed = false;
    private AdaptiveNoiseProfile sharedNoiseProfile = new AdaptiveNoiseProfile();
    private ImprovedVAD vad = new ImprovedVAD(sharedNoiseProfile);

    private volatile boolean pendingLaughAppend = false;

    private final Object psLock = new Object();
    private volatile boolean psStarting = false;

    private final ScheduledExecutorService clipExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "clip-restore-thread");
                t.setDaemon(true);
                return t;
            });
    private final java.util.concurrent.atomic.AtomicBoolean transcribeBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private long incompleteHoldUntilMs = 0L;
    private static long INCOMPLETE_GRACE_MS = 200;

    private static volatile boolean useAlternateLaugh = false;
    private volatile boolean laughAlreadyHandled = false;

    private static final AtomicLong lastFinalExecutionTime = new AtomicLong(0);
    private static final AtomicBoolean isProcessingFinal =
            new AtomicBoolean(false);
    private static final AtomicBoolean isProcessingPartial =
            new AtomicBoolean(false);
    private static final AtomicReference<String> lastPartialResult =
            new AtomicReference<>("");

    private volatile boolean earlyFinalizeRequested = false;
    private volatile long earlyFinalizeUntilMs = 0;
    private static final long EARLY_FINAL_WINDOW_MS = 800;
    private static final Set<String> recentOutputs = Collections.synchronizedSet(new LinkedHashSet<>());

    private volatile String lastCalibratedInputDevice = "";

    private volatile boolean voiceVoxAlive = false;
    private volatile long lastVoiceVoxCheckMs = 0;
    private static final long VOICEVOX_CHECK_INTERVAL_MS = 1000 * 5; // 5秒に1回
    private volatile boolean isTtsSpeaking = false;  // TTS再生中フラグ（VADループバック防止
    private volatile boolean moonshineLastGateOk = true;

    private volatile String pendingLaughText = null;
    private final Object laughLock = new Object();

    private int laughPartialCount = 0;
    private long lastLaughPartialMs = 0;
    private static final long LAUGH_PARTIAL_WINDOW_MS = 1200;

    // ★ GPU負荷軽減モード
    public static volatile boolean lowGpuMode = true;

    private static long PARTIAL_INTERVAL_MS = 300;
    private static final int  PARTIAL_TAIL_BYTES  = 16000*2;

    // 録音開始要求が詰まった時に固まらないためのフラグ
    private final java.util.concurrent.atomic.AtomicBoolean isStartingRecording = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean primeAgain = false;

    // ===== Voiceger HTTP (reuse) =====
    private static final HttpClient VG_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1) // ローカルAPIならこれで十分
            .build();

    // health の結果キャッシュ（毎回叩かない）
    private static volatile long vgHealthOkUntilMs = 0L;
    private static final long VG_HEALTH_CACHE_MS = 3000; // 3秒だけ信じる

    private boolean debug;

    // ===== Hearing (WIP) =====
    private static HearingFrame hearingFrame;
    private static volatile boolean hearingActive = false;
    public static void setHearingActive(boolean b) { hearingActive = b; }
    public static boolean isHearingActive() { return hearingActive; }

    private static final String[] ALLOWED_HOTKEYS = { "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12", "F13", "F14", "F15", "F16", "F17", "F18" };
    private static final int[] ALLOWED_HOTKEYS_CODE = { NativeKeyEvent.VC_F1, NativeKeyEvent.VC_F2, NativeKeyEvent.VC_F3, NativeKeyEvent.VC_F4, NativeKeyEvent.VC_F5, NativeKeyEvent.VC_F6,
            NativeKeyEvent.VC_F7, NativeKeyEvent.VC_F8, NativeKeyEvent.VC_F9, NativeKeyEvent.VC_F10, NativeKeyEvent.VC_F11, NativeKeyEvent.VC_F12, NativeKeyEvent.VC_F13, NativeKeyEvent.VC_F14,
            NativeKeyEvent.VC_F15, NativeKeyEvent.VC_F16, NativeKeyEvent.VC_F17, NativeKeyEvent.VC_F18 };
    // Action
    enum Action {
        COPY_TO_CLIPBOARD_AND_PASTE, TYPE_STRING, NOTHING, NOTHING_NO_SPEAK, TRANSLATE,TRANSLATE_AND_SPEAK
    }

    public static boolean isSilenceAlternate() {
        return useAlternateLaugh;
    }

    public static void setSilenceAlternate(boolean v) {
        useAlternateLaugh = v;
        prefs.putBoolean("silence.alternate", v);
        try { prefs.sync(); } catch (Exception ignore) {}
        Config.log("silence.alternate set to: " + v);
    }

    public MobMateWhisp get_MobMateWhisp() {
        return this;
    }
    private void reloadAudioPrefsForMeter() {
        autoGainEnabled = prefs.getBoolean("audio.autoGain", true);

        // autoGainMultiplierの安全域（保存値が壊れてても復旧）
        if (autoGainMultiplier < 0.25f) autoGainMultiplier = 0.25f;
        if (autoGainMultiplier > 9.0f)  autoGainMultiplier = 9.0f;
    }

    // ★ デモモードアナウンス（体験版のみ有効にする制御はお好みで）
    private void startDemoAnnounceTimer() {
        if (!Version.IS_DEMO) return;

        // デモメッセージ（5言語）
        Map<String, String> demoMsg = new java.util.LinkedHashMap<>();
        demoMsg.put("ja", "これは体験版です。製品版をご購入いただくと、このアナウンスは停止します。");
        demoMsg.put("en", "This is a trial version. Purchase the full version to remove this announcement.");
        demoMsg.put("zh", "这是试用版。购买完整版后，此提示将停止播放。");
        demoMsg.put("ko", "이것은 체험판입니다. 정식 버전을 구매하시면 이 안내가 사라집니다。");
        demoMsg.put("zh-TW", "這是體驗版。購買完整版後，此提示將停止播放。");

        java.util.Timer demoTimer = new java.util.Timer("demo-announce", true);
        demoTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                try {
                    String uiLang = prefs.get("ui.language", "en");
                    // ui.languageに近い言語を選ぶ
                    String msg = demoMsg.getOrDefault(uiLang,
                            demoMsg.getOrDefault(
                                    uiLang.startsWith("zh") ? "zh" : "en",
                                    demoMsg.get("en")));
                    speak(msg);
                } catch (Exception e) {
                    Config.log("[Demo] announce error: " + e.getMessage());
                }
            }
        }, 3 * 60 * 1000L, 3 * 60 * 1000L); // 3分ごと
    }
    public MobMateWhisp(String remoteUrl) throws FileNotFoundException, NativeHookException {
        Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(
                new WString("MobMate.MobMateWhispTalk")
        );
        prefs = Preferences.userRoot().node("MobMateWhispTalk");
        // ★話者プロファイル初期化（prefsから設定値を取得）
        this.speakerProfile = new SpeakerProfile(
                prefs.getInt("speaker.enroll_samples", 5),
                prefs.getFloat("speaker.threshold_initial", 0.60f),
                prefs.getFloat("speaker.threshold_target", 0.82f)
        );
        UiText.load(UiLang.resolveUiFile(prefs.get("ui.language", "en")));
        Config.syncAllFromCloud();
        Config.log("JVM: " + System.getProperty("java.vm.name"));
        Config.log("JVM vendor: " + System.getProperty("java.vm.vendor"));
        loadWhisperNative();
        this.button = new JButton(UiText.t("ui.main.start"));
        Config.logDebug("Locale=" + java.util.Locale.getDefault() + " / user.language=" + System.getProperty("user.language"));

        int size = prefs.getInt("ui.font.size", 12);
        applyUIFont(size);
        lowGpuMode = prefs.getBoolean("perf.low_gpu_mode", true);
        useAlternateLaugh = prefs.getBoolean("silence.alternate", false);
        autoGainEnabled = prefs.getBoolean("audio.autoGain", true);
        reloadAudioPrefsForMeter();
        loadRadioHotkeyFromPrefs();

        if (window != null) {
            Rectangle fallback = new Rectangle(15, 15, this.window.getWidth(), this.window.getHeight());
            restoreBounds(this.window, "ui.main", fallback);
            installBoundsSaver(this.window, "ui.main");
            SwingUtilities.updateComponentTreeUI(window);
            window.invalidate();
            window.validate();
            window.repaint();
        }
        if (historyFrame != null) {
            SwingUtilities.updateComponentTreeUI(historyFrame);
            historyFrame.refresh();
        }

        String lang = Config.get("language");
        if (lang == null) lang = "auto";

        if (MobMateWhisp.ALLOWED_HOTKEYS.length != MobMateWhisp.ALLOWED_HOTKEYS_CODE.length) {
            throw new IllegalStateException("ALLOWED_HOTKEYS size mismatch");
        }

        this.hotkey = prefs.get("hotkey", "F9");
        this.shiftHotkey = prefs.getBoolean("shift-hotkey", false);
        this.ctrltHotkey = prefs.getBoolean("ctrl-hotkey", false);
        this.model = prefs.get("model", "ggml-small-q8_0.bin");
        this.model_dir = prefs.get("model_dir", "");

        GlobalScreen.registerNativeHook();
        GlobalScreen.addNativeKeyListener(this);

        String laughSetting = Config.getString("laughs", "ワハハハハハ");
        laughOptions = laughSetting.split(",");
        for (int i = 0; i < laughOptions.length; i++) {
            laughOptions[i] = laughOptions[i].trim();
        }

        // Create audio format
        float sampleRate = 16000.0F;
        int sampleSizeInBits = 16;
        int channels = 1;
        boolean signed = true;
        boolean bigEndian = false;
        this.audioFormat = new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);

        this.remoteUrl = remoteUrl;
        if (remoteUrl == null) {
            File dir = new File("models");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            boolean hasModels = false;
            for (File f : Objects.requireNonNull(dir.listFiles())) {
                if (f.getName().endsWith(".bin")) {
                    hasModels = true;
                }
            }
            if (!hasModels) {
                JOptionPane.showMessageDialog(null,
                        "Please download a model (.bin file) from :\nhttps://huggingface.co/ggerganov/whisper.cpp/tree/main\n\n and copy it in :\n" + dir.getAbsolutePath());
                if (Desktop.isDesktopSupported()) {
                    final Desktop desktop = Desktop.getDesktop();
                    if (desktop.isSupported(Desktop.Action.BROWSE)) {
                        try {
                            desktop.browse(new URI("https://huggingface.co/ggerganov/whisper.cpp/tree/main"));
                        } catch (IOException | URISyntaxException e) {
                        }
                    }
                    if (desktop.isSupported(Desktop.Action.OPEN)) {
                        try {
                            desktop.open(dir);
                        } catch (IOException e) {
                        }
                    }
                }
                System.exit(0);
            }
            if (!hasVirtualAudioDevice()) {
                JOptionPane.showMessageDialog(
                        null,
                        "Virtual audio device not found.\n" +
                                "MobMate needs a virtual microphone/cable to send synthesized voice to games.\n\n" +
                                "Setup guide:\nhttps://github.com/zufall-upon/MobMate#-virtual-audio-setup--仮想オーディオ設定",
                        "MobMate Setup",
                        JOptionPane.INFORMATION_MESSAGE
                );
                if (Desktop.isDesktopSupported()) {
                    try {
                        Desktop.getDesktop().browse(new URI("https://github.com/zufall-upon/MobMate#-virtual-audio-setup--仮想オーディオ設定"));
                    } catch (Exception ex) {}
                }
            }
            if (!new File(dir, this.model).exists()) {
                for (File f : Objects.requireNonNull(dir.listFiles())) {
                    if (f.getName().endsWith(".bin")) {
                        this.model = f.getName();
                        setModelPref(f.getName());
                        break;
                    }
                }
            }
            this.model_dir = dir.getPath();
            prefs.put("model_dir", model_dir);

            // ★v1.5.0: エンジン選択に応じて初期化を分岐
            if (isEngineMoonshine()) {
                Config.log("★RecogEngine = Moonshine (Whisper skip)");
                this.w = null;  // Whisperは使わない
                initMoonshine();
            } else {
                Config.log("★RecogEngine = Whisper (Moonshine skip)");
                this.w = new LocalWhisperCPP(new File(dir, this.model), "");
                Config.log("MobMateWhispTalk using WhisperCPP with " + this.model);
            }
        } else {
            Config.log("MobMateWhispTalk using remote speech to text service : " + remoteUrl);
        }
    }
    private LocalWhisperCPP getOrCreateHearingWhisper() throws FileNotFoundException {
        LocalWhisperCPP cur = wHearing;
        if (cur != null) {
            // ★キャッシュでも毎回 prefs を反映（UI変更が即効く）
            try { cur.setLanguage(prefs.get("hearing.lang", "auto")); } catch (Exception ignore) {}
            try { cur.setHearingTranslateToEn(prefs.getBoolean("hearing.translate_to_en", false)); } catch (Exception ignore) {}
            return cur;
        }
        synchronized (hearingWhisperLock) {
            if (wHearing != null) {
                // ★二重チェック側でも同様に反映
                try { wHearing.setLanguage(prefs.get("hearing.lang", "auto")); } catch (Exception ignore) {}
                try { wHearing.setHearingTranslateToEn(prefs.getBoolean("hearing.translate_to_en", false)); } catch (Exception ignore) {}
                return wHearing;
            }
            wHearing = new LocalWhisperCPP(new File(this.model_dir, this.model), "Hearing");
            try { wHearing.setLanguage(prefs.get("hearing.lang", "auto")); } catch (Exception ignore) {}
            try { wHearing.setHearingTranslateToEn(prefs.getBoolean("hearing.translate_to_en", false)); } catch (Exception ignore) {}
            return wHearing;
        }
    }
    // Moonshine初期化

    private void initMoonshine() {
        //TODO test
//        String path = "M:\\_work\\moonshine\\_model\\base-ja\\quantized\\base-ja";
        // モデルパスはprefsから（未設定ならデフォルト）
        String path = prefs.get("moonshine.model_path", "");
        if (path.isEmpty()) {
            // 実行ディレクトリ直下の moonshine_model を探す
            File defaultDir = new File("moonshine_model");
            if (defaultDir.isDirectory()) {
                path = defaultDir.getAbsolutePath();
            } else {
                Config.log("[Moonshine] ERROR: moonshine.model_path not set and moonshine_model/ not found. Falling back to Whisper.");
                prefs.put("recog.engine", "whisper");
                try { prefs.flush(); } catch (Exception ignore) {}
                // Whisperにフォールバック
                try {
                    this.w = new LocalWhisperCPP(new File(model_dir, this.model), "");
                    Config.log("[Moonshine] fallback: Whisper loaded OK");
                } catch (Exception ex) {
                    Config.logError("[Moonshine] fallback: Whisper load FAILED", ex);
                }
                return;
            }
        }

        // archが不明なので全部試す
        int[] archCandidates = {1, 3, 0, 2, 4, 5};
        String[] archNames = {"BASE", "BASE_STREAMING", "TINY", "TINY_STREAMING", "SMALL_STREAMING", "MEDIUM_STREAMING"};


        moonshine = new LocalMoonshineSTT();
        // ★v1.5.0: Whisperと共通の設定をMoonshineにも適用
        {
            // スレッド数（Whisperと同じロジック。MoonshineはCPU推論のためスレッド数が効く）
            int threads;
            if (!lowGpuMode) {
                threads = Math.min(
                        Math.max(8, Runtime.getRuntime().availableProcessors() / 2),
                        Config.getInt("whisper.n_threads",
                                Runtime.getRuntime().availableProcessors() / 2)
                );
            } else {
                threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 4);
            }
            moonshine.setNumThreads(threads);

            // ポーリング間隔（lowGpuMode対応。推論レイテンシ~200msのため200ms以下は空振り増加）
            int pollMs = lowGpuMode ? 400 : 200;
            pollMs = Config.getInt("moonshine.poll_interval_ms", pollMs);
            moonshine.setPollInterval(pollMs);

            Config.log("[Moonshine] config: threads=" + threads
                    + " poll=" + pollMs + "ms"
                    + " lowGpu=" + lowGpuMode
                    + " (CPU inference, Vulkan DLC not required)");
        }
        boolean loaded = false;
        for (int i = 0; i < archCandidates.length; i++) {
            Config.log("[Moonshine] trying arch=" + archCandidates[i]
                    + " (" + archNames[i] + ")...");
            if (moonshine.load(path, archCandidates[i])) {
                Config.log("[Moonshine] ★ arch=" + archCandidates[i]
                        + " (" + archNames[i] + ") SUCCESS!");
                loaded = true;
                break;
            }
            Config.log("[Moonshine] arch=" + archCandidates[i] + " failed");
        }

        if (!loaded) {
            Config.log("[Moonshine] all archs failed. Falling back to Whisper.");
            moonshine = null;
            prefs.put("recog.engine", "whisper");
            try { prefs.flush(); } catch (Exception ignore) {}
            try {
                this.w = new LocalWhisperCPP(new File(model_dir, this.model), "");
            } catch (Exception ex) {
                Config.logError("[Moonshine] fallback Whisper load FAILED", ex);
            }
            return;
        }

        // ★アクション解決
        final String strAction = prefs.get("action", "nothing");
        Action action;
        if ("paste".equals(strAction)) {
            action = Action.COPY_TO_CLIPBOARD_AND_PASTE;
        } else if ("type".equals(strAction)) {
            action = Action.TYPE_STRING;
        } else {
            action = Action.NOTHING;
        }

        // ★v1.5.0: onPartial → UIタイトル更新 + lastPartialResult更新

        // ★v1.5.0: onPartial → UIタイトル更新 + lastPartialResult更新
        moonshine.setOnPartial(text -> {
            if (text == null || text.isBlank()) return;
            String trimmed = text.trim();
            trimmed = removeCjkSpaces(trimmed);  // ★v1.5.0: CJK間スペース除去
            // ★ignoreリスト判定
            if (LocalWhisperCPP.isIgnoredStatic(trimmed)) {
                Config.logDebug("★Ignored text: " + trimmed);
                return;
            }
            // ★v1.5.0: Whisperから移植 — ガベージPartial検出（!!!!暴走等）
            if (isGarbagePartialStatic(trimmed)) {
                Config.logDebug("★Moon Partial dropped (garbage): len=" + trimmed.length());
                return;
            }
            // ★v1.5.0: Whisperから移植 — UI詰まり防止（100文字制限）
            String clamped = trimmed;
            if (clamped.length() > 100) {
                clamped = clamped.substring(0, 100) + "…";
            }
            MobMateWhisp.setLastPartial(clamped);
            lastPartialUpdateMs = System.currentTimeMillis();
            setTranscribing(true);  // ★ADD: partial到達時にtranscribing開始（全フレームではなく）
            Config.logDebug("★Partial: " + clamped);
            final String forUi = clamped;
            SwingUtilities.invokeLater(() -> {
                if (window != null) {
                    window.setTitle("[TRANS]:" + forUi);
                    window.setIconImage(imageTranscribing);
                }
            });
        });

        // ★v1.5.0: onCompleted → handleFinalText + 短文即時判定
        moonshine.setOnCompleted(text -> {
            if (text == null || text.isBlank()) return;
            String trimmed = text.trim();
            trimmed = removeCjkSpaces(trimmed);  // ★v1.5.0: CJK間スペース除去
            if (LocalWhisperCPP.isIgnoredStatic(trimmed)) {
                Config.logDebug("★Ignored text: " + trimmed);
                return;
            }
            setTranscribing(false);  // ★ADD: COMPLETE時にtranscribing解除

            // ★v1.5.0: 話者ゲート（VAD Finalで評価済みの結果を参照）
            if (prefs.getBoolean("speaker.enabled", false) && speakerProfile.isReady()) {
                if (!moonshineLastGateOk) {
                    Config.logDebug("★Moon COMPLETE: speaker gate blocked -> " + trimmed);
                    MobMateWhisp.setLastPartial("");
                    return;
                }
            }

            // ★COMPLETEが確定結果。partialはCOMPLETEを前方延長する場合のみ優先
            String lastP = MobMateWhisp.getLastPartial();
            boolean partialExtendsComplete = lastP != null && !lastP.isBlank()
                    && lastP.startsWith(trimmed)
                    && lastP.length() > trimmed.length();
            if (partialExtendsComplete) {
                Config.logDebug("★Instant FINAL (partial extends complete): " + lastP);
                handleFinalText(lastP, action, true);
            } else {
                handleFinalText(trimmed, action, false);
            }
            MobMateWhisp.setLastPartial("");
        });

        moonshine.start();
        Config.log("[Moonshine] ready (engine=moonshine)");
    }
    private static void shutdownMoonshine() {
        if (moonshine != null) {
            try {
                Config.log("[Moonshine] shutting down...");
                moonshine.close();
                Config.log("[Moonshine] shutdown OK");
            } catch (Exception ex) {
                Config.logError("[Moonshine] shutdown error: " + ex.getMessage(), ex);
            } finally {
                moonshine = null;
            }
        }
    }

    private Preferences getPrefs() {
        if (prefs == null) {
            prefs = Preferences.userRoot().node("MobMateWhispTalk");
        }
        return prefs;
    }
    public boolean isEngineMoonshine() {
        return "moonshine".equalsIgnoreCase(prefs.get("recog.engine", "whisper"));
    }
    public String getRecogEngineName() {
        return isEngineMoonshine() ? "Moonshine" : "Whisper";
    }
    // ★v1.5.0: Moonshine用 — LocalWhisperCPP.isGarbagePartial() のstatic版
    // Whisper側はインスタンスメソッドだが、Moonshineコールバックから呼ぶためstaticで公開
    private static boolean isGarbagePartialStatic(String s) {
        if (s == null) return true;
        String t = s.trim();
        if (t.isEmpty()) return true;

        // 同一文字の繰り返し（!だけ、-だけ等）
        if (t.length() >= 40) {
            char c0 = t.charAt(0);
            boolean allSame = true;
            for (int i = 1; i < t.length(); i++) {
                if (t.charAt(i) != c0) { allSame = false; break; }
            }
            if (allSame) return true;
        }

        // 記号率が高すぎる（英数かな漢字がほぼ無い）
        int good = 0, total = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isWhitespace(c)) continue;
            total++;
            if (Character.isLetterOrDigit(c)) good++;
        }
        if (total >= 20) {
            double ratio = good * 1.0 / total;
            return ratio < 0.15;
        }

        return false;
    }
    // ★v1.5.0: Moonshine用 — CJK文字間の不要スペース除去
    // Moonshineが「や り た く」のように1文字ずつスペースを挟むケースの対策
    static String removeCjkSpaces(String s) {
        if (s == null || s.length() < 3) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' && i > 0 && i < s.length() - 1) {
                char prev = s.charAt(i - 1);
                char next = s.charAt(i + 1);
                // CJK同士の間のスペースだけ除去
                if (isCjk(prev) && isCjk(next)) {
                    continue; // スペースをスキップ
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
    private static boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.HIRAGANA
                || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.HANGUL_SYLLABLES
                || b == Character.UnicodeBlock.HANGUL_JAMO
                || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }
    // ★v1.5.0: .onnx/.binファイルがある最深ディレクトリを再帰探索
    static File findDeepestModelDir(File dir) {
        File[] modelFiles = dir.listFiles((d, name) ->
                name.endsWith(".onnx") || name.endsWith(".bin"));
        if (modelFiles != null && modelFiles.length > 0) {
            return dir;
        }
        File[] subDirs = dir.listFiles(File::isDirectory);
        if (subDirs != null) {
            for (File sub : subDirs) {
                File found = findDeepestModelDir(sub);
                File[] check = found.listFiles((d, name) ->
                        name.endsWith(".onnx") || name.endsWith(".bin"));
                if (check != null && check.length > 0) {
                    return found;
                }
            }
            if (subDirs.length > 0) {
                return findDeepestModelDir(subDirs[0]);
            }
        }
        return dir;
    }

    void createTrayIcon() {
        Config.log("[TRAY] begin");
        // ★そもそもトレイ非対応環境なら即戻る（RDP/制限PCで多い）
        if (!SystemTray.isSupported()) {
            Config.log("[TRAY] SystemTray is NOT supported. skip tray.");
            return;
        }
        try {
            this.imageRecording = new ImageIcon(Objects.requireNonNull(this.getClass().getResource("recording.png"))).getImage();
            this.imageInactive = new ImageIcon(Objects.requireNonNull(this.getClass().getResource("inactive.png"))).getImage();
            this.imageTranscribing = new ImageIcon(Objects.requireNonNull(this.getClass().getResource("transcribing.png"))).getImage();
            this.trayIcon = new TrayIcon(this.imageInactive, "Press " + this.hotkey + " to record");
            this.trayIcon.setImageAutoSize(true);
            Config.log("[TRAY] getSystemTray...");
            final SystemTray tray = SystemTray.getSystemTray();
            final Frame frame = new Frame("");
            frame.setUndecorated(true);
            frame.setType(Type.UTILITY);
            Config.log("[TRAY] updateComponentTreeUI...");
            SwingUtilities.updateComponentTreeUI(frame);
            // Create a pop-up menu components
            final PopupMenu popup = createPopupMenu();
            this.trayIcon.setPopupMenu(popup);
            Config.log("[TRAY] add mouse listener...");
            this.trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    if (!e.isPopupTrigger()) {
                        stopRecording();
                    }
                }

            });
            try {
                Config.log("[TRAY] frame visible...");
                frame.setResizable(false);
                frame.setVisible(true);
                tray.add(this.trayIcon);
                Config.log("[TRAY] tray.add OK");
            } catch (AWTException ex) {
                Config.log("TrayIcon could not be added.\n" + ex.getMessage());
            }
            trayIcon.addActionListener(e -> bringToFront(window));
            Config.log("[TRAY] end OK");
        } catch (Throwable t) {
            Config.logError("[TRAY] FAILED: " + t, t);
        }
    }
    private void ensureVADCalibrationForDevice(TargetDataLine line, String deviceName) {
        String key = (deviceName == null || deviceName.isBlank()) ? "DEFAULT" : deviceName;

        if (key.equals(lastCalibratedInputDevice) && sharedNoiseProfile.isCalibrated()) {
            return;
        }

        Config.log("★Re-calibrating VAD for input device: " + key);
        sharedNoiseProfile.resetForNewDevice();

        byte[] buf = new byte[1600];

        int need = sharedNoiseProfile.CALIBRATION_FRAMES; // 50
        int got = 0;
        long start = System.currentTimeMillis();
        long timeoutMs = 3500;

        long lastDataMs = System.currentTimeMillis();
        long noDataWarnMs = 1200; // ここ超えたら一回だけログ
        boolean warned = false;

        while (got < need && (System.currentTimeMillis() - start) < timeoutMs) {
            int avail = 0;
            try { avail = line.available(); } catch (Exception ignore) {}

            if (avail <= 0) {
                long now = System.currentTimeMillis();
                if (!warned && (now - lastDataMs) > noDataWarnMs) {
                    warned = true;
                    Config.log("★VAD calibrating... no mic data yet (" + (now - start) + "ms) dev=" + key);
                }
                try { Thread.sleep(10); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                continue;
            }

            int toRead = Math.min(buf.length, avail);
            int r = line.read(buf, 0, toRead);
            if (r > 0) {
                lastDataMs = System.currentTimeMillis();

                int peak = vad.getPeak(buf, r);
                int avg  = vad.getAvg(buf, r);

                // Always collect raw samples (important for headset/AGC/noise-gate)
                sharedNoiseProfile.updateRaw(peak, avg);
                if (!sharedNoiseProfile.isCalibrated() || sharedNoiseProfile.isLowGainMic) {
                    if (sharedNoiseProfile.rawPeaks.size() >= 40) {
                        sharedNoiseProfile.forceCalibrateByPercentile();
                    }
                }

                // keep old "silent-like" path too (helps desktop mics)
                int manualPeak = sharedNoiseProfile.getManualPeakThreshold();
                boolean silentLike =
                        peak < (manualPeak * 0.40) &&
                                avg  < (manualPeak * 0.18);

                if (silentLike) {
                    sharedNoiseProfile.update(peak, avg, true);
                }
                got++;
            }

            try { Thread.sleep(10); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        // got==0 だと percentile が空でコケる可能性あるのでガード
        if (got <= 0) {
            Config.log("★VAD calibration got 0 frames (timeout). dev=" + key + " -> skip recalibration");
            // ここで reset だけ走ってるのが嫌なら、resetForNewDevice() を whileの前じゃなく
            // 「最初のr>0を取れた直後」に移すのが理想だけど、最小変更ならまずこれでOKっす。
            lastCalibratedInputDevice = key;
            return;
        }

        // NEW: percentile-based calibration (robust across devices)
        sharedNoiseProfile.forceCalibrateByPercentile();

        vad.reset();
        lastCalibratedInputDevice = key;

        Config.log("★VAD calibrated: key=" + key +
                " samples=" + got + "/" + need +
                " lowGain=" + sharedNoiseProfile.isLowGainMic +
                " peakTh=" + sharedNoiseProfile.getPeakThreshold() +
                " avgTh=" + sharedNoiseProfile.getAvgThreshold());
    }


    // ===== Tray menu: force English text (to avoid tofu/garble on some OS) =====
    private static final Object TRAY_TEXT_LOCK = new Object();
    private static final Map<String, String> TRAY_TEXT_CACHE = new HashMap<>();
    private static String trayText(String key) {
        synchronized (TRAY_TEXT_LOCK) {
            String cached = TRAY_TEXT_CACHE.get(key);
            if (cached != null) return cached;

            // current UI language (restore after fetch)
            String curSuffix = "en";
            try {
                if (MobMateWhisp.prefs != null) curSuffix = prefs.get("ui.language", "en");
            } catch (Exception ignore) {}

            Path enFile  = UiLang.resolveUiFile("en");
            Path curFile = UiLang.resolveUiFile(curSuffix);

            try {
                // temporarily load EN
                UiText.load(enFile);

                String v = UiText.t(key);
                if (v == null || v.isBlank()) v = key; // safety
                TRAY_TEXT_CACHE.put(key, v);
                return v;
            } catch (Exception e) {
                // fallback: current language
                try { return UiText.t(key); } catch (Exception ignore) { return key; }
            } finally {
                // restore current UI immediately
                try { UiText.load(curFile); } catch (Exception ignore) {}
            }
        }
    }
    private static void applyAwtFontRecursive(java.awt.MenuComponent mc, java.awt.Font f) {
        try { mc.setFont(f); } catch (Exception ignore) {}

        if (mc instanceof java.awt.Menu m) {
            for (int i = 0; i < m.getItemCount(); i++) {
                java.awt.MenuItem it = m.getItem(i);
                if (it != null) applyAwtFontRecursive(it, f);
            }
        }
    }
    private Menu engVvMenu;
    private CheckboxMenuItem engVv;
    private ItemListener engListener;
    protected PopupMenu createPopupMenu() {
        Config.log("[TRAY] popup: begin");
        final String strAction = prefs.get("action", "noting");

        Config.log("[TRAY] popup: add Required...");
        Menu requiredMenu = new Menu(trayText("menu.required"));
        final PopupMenu popup = new PopupMenu();
        CheckboxMenuItem autoPaste = new CheckboxMenuItem(trayText("menu.autoPaste"));
        autoPaste.setState(strAction.equals("paste"));
        requiredMenu.add(autoPaste);
        CheckboxMenuItem autoType = new CheckboxMenuItem(trayText("menu.autoType"));
        autoType.setState(strAction.equals("type"));
        requiredMenu.add(autoType);
        final ItemListener typeListener = new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                if (e.getSource().equals(autoPaste) && e.getStateChange() == ItemEvent.SELECTED) {
                    Config.log("itemStateChanged() PASTE " + e.toString());
                    prefs.put("action", "paste");
                    autoType.setState(false);
                } else if (e.getSource().equals(autoType) && e.getStateChange() == ItemEvent.SELECTED) {
                    Config.log("itemStateChanged() TYPE " + e.toString());
                    prefs.put("action", "type");
                    autoPaste.setState(false);
                } else {
                    prefs.put("action", "nothing");
                }
                try {
                    prefs.sync();
                } catch (BackingStoreException e1) {
                    JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + e1.getMessage());
                }
            }
        };
        autoPaste.addItemListener(typeListener);
        autoType.addItemListener(typeListener);
        CheckboxMenuItem detectSilece = new CheckboxMenuItem(trayText("menu.silenceDetection"));
        detectSilece.setState(prefs.getBoolean("silence-detection", true));
        detectSilece.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                prefs.putBoolean("silence-detection", detectSilece.getState());
                try {
                    prefs.sync();
                } catch (BackingStoreException e1) {
                    JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + e1.getMessage());
                }
            }
        });
        requiredMenu.add(detectSilece);
        // ===== Whisper translate (auto -> EN) =====
        final CheckboxMenuItem translateToEnItem =
                new CheckboxMenuItem("Translate to English (Whisper)");
        translateToEnItem.setState(prefs.getBoolean("whisper.translate_to_en", false));
        translateToEnItem.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                prefs.putBoolean("whisper.translate_to_en", translateToEnItem.getState());
                try {
                    prefs.sync();
                } catch (BackingStoreException ex) {
                    JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + ex.getMessage());
                }
            }
        });
        requiredMenu.add(translateToEnItem);
        // Shift hotkey modifier
        Menu hotkeysMenu = new Menu(trayText("menu.keyboardShortcut"));
        final CheckboxMenuItem shiftHotkeyMenuItem = new CheckboxMenuItem("SHIFT");
        shiftHotkeyMenuItem.setState(prefs.getBoolean("shift-hotkey", false));
        hotkeysMenu.add(shiftHotkeyMenuItem);
        shiftHotkeyMenuItem.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                MobMateWhisp.this.shiftHotkey = shiftHotkeyMenuItem.getState();
                prefs.putBoolean("shift-hotkey", MobMateWhisp.this.shiftHotkey);
                try {
                    prefs.sync();
                } catch (BackingStoreException e1) {
                    JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + e1.getMessage());
                }
                updateToolTip();
            }
        });
        // Ctrl hotkey modifier
        final CheckboxMenuItem ctrlHotkeyMenuItem = new CheckboxMenuItem("CTRL");
        ctrlHotkeyMenuItem.setState(prefs.getBoolean("ctrl-hotkey", false));
        hotkeysMenu.add(ctrlHotkeyMenuItem);
        ctrlHotkeyMenuItem.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                MobMateWhisp.this.ctrltHotkey = ctrlHotkeyMenuItem.getState();
                prefs.putBoolean("ctrl-hotkey", MobMateWhisp.this.ctrltHotkey);
                try {
                    prefs.sync();
                } catch (BackingStoreException e1) {
                    JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + e1.getMessage());
                }
                updateToolTip();
            }
        });
        hotkeysMenu.addSeparator();
        for (final String key : MobMateWhisp.ALLOWED_HOTKEYS) {
            final CheckboxMenuItem hotkeyMenuItem = new CheckboxMenuItem(key);
            if (this.hotkey.equals(key)) {
                hotkeyMenuItem.setState(true);
            }
            hotkeysMenu.add(hotkeyMenuItem);
            hotkeyMenuItem.addItemListener(new ItemListener() {
                @Override
                public void itemStateChanged(ItemEvent e) {
                    if (hotkeyMenuItem.getState()) {
                        MobMateWhisp.this.hotkey = key;
                        prefs.put("hotkey", MobMateWhisp.this.hotkey);
                        try {
                            prefs.sync();
                        } catch (BackingStoreException e1) {
                            JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + e1.getMessage());
                        }
                        hotkeyMenuItem.setState(false);
                        updateToolTip();
                    }
                }
            });
        }
        if (this.remoteUrl == null) {
            Menu modelMenu = new Menu(trayText("menu.models"));
            final File dir = new File("models");
            List<CheckboxMenuItem> allModels = new ArrayList<>();
            if (new File(dir, this.model).exists()) {
                for (File f : Objects.requireNonNull(dir.listFiles())) {
                    final String name = f.getName();
                    if (name.endsWith(".bin")) {
                        final boolean selected = this.model.equals(name);
                        String cleanName = name.replace(".bin", "");
                        cleanName = cleanName.replace(".bin", "");
                        cleanName = cleanName.replace("ggml", "");
                        cleanName = cleanName.replace("-", " ");
                        cleanName = cleanName.trim();
                        final CheckboxMenuItem modelItem = new CheckboxMenuItem(cleanName);
                        modelItem.setState(selected);
                        modelItem.addItemListener(new ItemListener() {
                            @Override
                            public void itemStateChanged(ItemEvent e) {
                                if (isRecording()) {
                                    stopRecording();
                                }
                                if (modelItem.getState()) {
                                    // Deselected others
                                    for (CheckboxMenuItem item : allModels) {
                                        if (item != modelItem) {
                                            item.setState(false);
                                        }
                                    }
                                    // Apply model
                                    MobMateWhisp.this.model = f.getName();
                                    setModelPref(MobMateWhisp.this.model);
                                    try {
                                        MobMateWhisp.this.w = new LocalWhisperCPP(f,"");
                                    } catch (FileNotFoundException e1) {
                                        JOptionPane.showMessageDialog(null, e1.getMessage());
                                    }
                                }
                            }
                        });
                        allModels.add(modelItem);
                        modelMenu.add(modelItem);
                    }
                }
            }
            requiredMenu.add(modelMenu);
        }
        requiredMenu.add(hotkeysMenu);
        final Menu modeMenu = new Menu(trayText("menu.keyTriggerMode"));
        final CheckboxMenuItem pushToTalkItem = new CheckboxMenuItem(trayText("menu.pushToTalk"));
        final CheckboxMenuItem pushToTalkDoubleTapItem = new CheckboxMenuItem(trayText("menu.pushToTalkDoubleTap"));
        final CheckboxMenuItem startStopItem = new CheckboxMenuItem(trayText("menu.startStop"));
        String currentMode = prefs.get("trigger-mode", START_STOP);
        pushToTalkItem.setState(PUSH_TO_TALK.equals(currentMode));
        pushToTalkDoubleTapItem.setState(PUSH_TO_TALK_DOUBLE_TAP.equals(currentMode));
        startStopItem.setState(START_STOP.equals(currentMode));
        if (!pushToTalkItem.getState() && !pushToTalkDoubleTapItem.getState() && !startStopItem.getState()) {
            pushToTalkItem.setState(true);
            prefs.put("trigger-mode", PUSH_TO_TALK);
            try {
                prefs.sync();
            } catch (BackingStoreException ex) {
                JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + ex.getMessage());
            }
        }
        final ItemListener modeListener = new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                CheckboxMenuItem source = (CheckboxMenuItem) e.getSource();
                if (source == pushToTalkItem && e.getStateChange() == ItemEvent.SELECTED) {
                    pushToTalkItem.setState(true);
                    pushToTalkDoubleTapItem.setState(false);
                    startStopItem.setState(false);
                    prefs.put("trigger-mode", PUSH_TO_TALK);
                } else if (source == pushToTalkDoubleTapItem && e.getStateChange() == ItemEvent.SELECTED) {
                    pushToTalkItem.setState(false);
                    pushToTalkDoubleTapItem.setState(true);
                    startStopItem.setState(false);
                    prefs.put("trigger-mode", PUSH_TO_TALK_DOUBLE_TAP);
                } else if (source == startStopItem && e.getStateChange() == ItemEvent.SELECTED) {
                    pushToTalkItem.setState(false);
                    pushToTalkDoubleTapItem.setState(false);
                    startStopItem.setState(true);
                    prefs.put("trigger-mode", START_STOP);
                } else {
                    // Default to push to talk
                    pushToTalkItem.setState(true);
                    pushToTalkDoubleTapItem.setState(false);
                    startStopItem.setState(false);
                    prefs.put("trigger-mode", PUSH_TO_TALK);
                }
                try {
                    prefs.sync();
                } catch (BackingStoreException ex) {
                    JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + ex.getMessage());
                }
            }
        };
        pushToTalkItem.addItemListener(modeListener);
        pushToTalkDoubleTapItem.addItemListener(modeListener);
        startStopItem.addItemListener(modeListener);
        modeMenu.add(pushToTalkItem);
        modeMenu.add(pushToTalkDoubleTapItem);
        modeMenu.add(startStopItem);
        requiredMenu.add(modeMenu);
        popup.add(requiredMenu);

        // ========================================
        // Audio Settings
        // ========================================
        Config.log("[TRAY] popup: add Audio Settings...");
        Menu audioMenu = new Menu("Audio Settings");

        // Input Devices
        final Menu audioInputsItem = new Menu(trayText("menu.audioInputs"));
        String audioDevice = prefs.get("audio.device", "");
        String previsouAudipDevice = prefs.get("audio.device.previous", "");
        List<String> mixers = getInputsMixerNames();
        if (!mixers.isEmpty()) {
            String currentAudioDevice = "";
            if (!audioDevice.isEmpty() && mixers.contains(audioDevice)) {
                currentAudioDevice = audioDevice;
            } else if (!previsouAudipDevice.isEmpty() && mixers.contains(previsouAudipDevice)) {
                currentAudioDevice = previsouAudipDevice;
            } else {
                currentAudioDevice = mixers.getFirst();
                prefs.put("audio.device", currentAudioDevice);
                try {
                    prefs.sync();
                } catch (BackingStoreException ignored) {
                }
            }
            Collections.sort(mixers);
            List<CheckboxMenuItem> all2 = new ArrayList<>();
            for (String name : mixers) {
                CheckboxMenuItem menuItem = new CheckboxMenuItem(name);
                if (currentAudioDevice.equals(name)) {
                    menuItem.setState(true);
                }
                audioInputsItem.add(menuItem);
                all2.add(menuItem);
                menuItem.addItemListener(new ItemListener() {
                    @Override
                    public void itemStateChanged(ItemEvent e) {
                        if (menuItem.getState()) {
                            for (CheckboxMenuItem m : all2) {
                                m.setState(m == menuItem);
                            }
                            prefs.put("audio.device.previous",
                                    prefs.get("audio.device", ""));
                            prefs.put("audio.device", name);
                            try {
                                prefs.sync();
                            } catch (BackingStoreException ex) {
                            }
                            SwingUtilities.invokeLater(() -> {
                                isCalibrationComplete = false;
                                button.setEnabled(false);
                                button.setText("Calibrating...");
                                window.setTitle("Input changed, calibrating...");
                                primeVadByEmptyRecording();
                            });
                        }
                        if (isRecording()) {
                            stopRecording();
                        }
                    }
                });
            }
        }
        audioMenu.add(audioInputsItem);

        // Output Devices
        Menu audioOutputsItem = new Menu(trayText("menu.audioOutputs"));
        String outputDevice = prefs.get("audio.output.device", "");
        String prevOutputDevice = prefs.get("audio.output.device.previous", "");
        List<String> outputMixers = getOutputMixerNames();
        if (!outputMixers.isEmpty()) {
            Collections.sort(outputMixers);
            List<CheckboxMenuItem> all3 = new ArrayList<>();
            for (int i = 0; i < outputMixers.size(); i++) {
                String name = outputMixers.get(i);
                String displayName = String.format("%02d: %s", i, name);
                CheckboxMenuItem item = new CheckboxMenuItem(displayName);
                item.setState(outputDevice.equals(name));
                audioOutputsItem.add(item);
                all3.add(item);
                final String mixerName = name;
                item.addItemListener(e -> {
                    if (item.getState()) {
                        for (CheckboxMenuItem m : all3) {
                            m.setState(m == item);
                        }
                        prefs.put("audio.output.device.previous",
                                prefs.get("audio.output.device", ""));
                        prefs.put("audio.output.device", mixerName);
                        try {
                            prefs.sync();
                        } catch (BackingStoreException ignored) {
                        }
                    }
                });
            }
        }
        audioMenu.add(audioOutputsItem);

        // Input Gain
        Menu inputGainMenu = new Menu(trayText("menu.inputGain"));
        boolean autogaintuner = prefs.getBoolean("audio.autoGain", true);
        float currentGain = prefs.getFloat("audio.inputGainMultiplier", 1.0f);
        CheckboxMenuItem autoGainItem = new CheckboxMenuItem(trayText("menu.autoGainTuner"));
        autoGainItem.setState(autogaintuner);
        autoGainItem.addItemListener(e -> {
            prefs.putBoolean("audio.autoGain", autoGainItem.getState());
            autoGainEnabled = autoGainItem.getState();
            try { prefs.sync(); } catch (Exception ignore) {}
            reloadAudioPrefsForMeter();
        });
        inputGainMenu.add(autoGainItem);
        inputGainMenu.addSeparator();
        List<CheckboxMenuItem> gainItems = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            float gain = 1.0f + (0.8f * i);
            String label = String.format("x %.1f", gain);
            CheckboxMenuItem item = new CheckboxMenuItem(label, gain == currentGain);
            item.addItemListener(e -> {
                for (CheckboxMenuItem it : gainItems) {
                    it.setState(false);
                }
                item.setState(true);
                prefs.putFloat("audio.inputGainMultiplier", gain);
                try { prefs.sync(); } catch (Exception ignore) {}
                if (isRecording()) {
                    stopRecording();
                }
                reloadAudioPrefsForMeter();
            });
            gainItems.add(item);
            inputGainMenu.add(item);
        }
        audioMenu.add(inputGainMenu);
        popup.add(audioMenu);

        // ========================================
        // Speaker Verification Settings
        // ========================================
        Menu speakerMenu = new Menu("Speaker Verification");

        CheckboxMenuItem speakerEnabledItem = new CheckboxMenuItem("Enable Speaker Filter");
        speakerEnabledItem.setState(prefs.getBoolean("speaker.enabled", false));
        speakerEnabledItem.addItemListener(e -> {
            boolean on = speakerEnabledItem.getState();
            prefs.putBoolean("speaker.enabled", on);
            try { prefs.sync(); } catch (Exception ignore) {}
            if (!on) {
                speakerProfile.reset();
                File spkFile = new File("speaker_profile.dat");
                if (spkFile.exists()) spkFile.delete();
                Config.log("★Speaker verification disabled, profile cleared");
            }
            updateSpeakerStatus();
        });
        speakerMenu.add(speakerEnabledItem);
        speakerMenu.addSeparator();

        // エンロールサンプル数 (5〜10)
        Menu enrollCountMenu = new Menu("Enroll Samples");
        int currentEnrollSamples = prefs.getInt("speaker.enroll_samples", 5);
        List<CheckboxMenuItem> enrollItems = new ArrayList<>();
        for (int cnt : new int[]{3, 5, 7, 10}) {
            String label = cnt + " samples" + (cnt == 5 ? " (default)" : "");
            CheckboxMenuItem item = new CheckboxMenuItem(label, cnt == currentEnrollSamples);
            final int val = cnt;
            item.addItemListener(e -> {
                for (CheckboxMenuItem it : enrollItems) it.setState(false);
                item.setState(true);
                prefs.putInt("speaker.enroll_samples", val);
                speakerProfile.updateSettings(
                        val,
                        prefs.getFloat("speaker.threshold_initial", 0.55f),
                        prefs.getFloat("speaker.threshold_target", 0.72f));
                try { prefs.sync(); } catch (Exception ignore) {}
            });
            enrollItems.add(item);
            enrollCountMenu.add(item);
        }
        speakerMenu.add(enrollCountMenu);

        // 感度（initial threshold）
        Menu sensitivityMenu = new Menu("Sensitivity");
        float currentInitTh = prefs.getFloat("speaker.threshold_initial", 0.55f);
        List<CheckboxMenuItem> sensItems = new ArrayList<>();
        for (float[] opt : new float[][]{
                {0.1f},{0.25f},{0.45f}, {0.55f}, {0.60f}, {0.70f}, {0.80f}}) {
            float th = opt[0];
            String label = String.format("%.0f%%", th * 100)
                    + (th == 0.60f ? " (default)" : "")
                    + (th <= 0.45f ? " - loose" : th >= 0.60f ? " - strict" : "");
            CheckboxMenuItem item = new CheckboxMenuItem(label,
                    Math.abs(th - currentInitTh) < 0.01f);
            item.addItemListener(e -> {
                for (CheckboxMenuItem it : sensItems) it.setState(false);
                item.setState(true);
                prefs.putFloat("speaker.threshold_initial", th);
                speakerProfile.updateSettings(
                        prefs.getInt("speaker.enroll_samples", 5),
                        th,
                        prefs.getFloat("speaker.threshold_target", 0.72f));
                try { prefs.sync(); } catch (Exception ignore) {}
            });
            sensItems.add(item);
            sensitivityMenu.add(item);
        }
        speakerMenu.add(sensitivityMenu);

        speakerMenu.addSeparator();

        // プロファイルリセット
        MenuItem resetProfileItem = new MenuItem("Reset Speaker Profile");
        resetProfileItem.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(null,
                    "Reset speaker profile?\nYou will need to re-enroll on next Start.",
                    "Speaker Verification", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                speakerProfile.reset();
                File spkFile = new File("speaker_profile.dat");
                if (spkFile.exists()) spkFile.delete();
                Config.log("★Speaker profile reset by user");
                updateSpeakerStatus();
            }
        });
        speakerMenu.add(resetProfileItem);
        popup.add(speakerMenu);

        // ========================================
        // Performance
        // ========================================
        Config.log("[TRAY] popup: add Performance...");
        Menu performanceMenu = new Menu("Performance");

        // ★v1.5.0: Recognition Engine Selection
        Menu recogEngineMenu = new Menu("Recognition Engine");
        String currentEngine = prefs.get("recog.engine", "whisper");
        CheckboxMenuItem engineWhisper = new CheckboxMenuItem("Whisper");
        CheckboxMenuItem engineMoonshine = new CheckboxMenuItem("Moonshine");
        engineWhisper.setState("whisper".equalsIgnoreCase(currentEngine));
        engineMoonshine.setState("moonshine".equalsIgnoreCase(currentEngine));

        // ★v1.5.0: setState連鎖によるsoftRestart二重発火を防止するガード
        final boolean[] updatingEngine = {false};

        engineWhisper.addItemListener(e -> {
            if (updatingEngine[0]) return;
            if (engineWhisper.getState()) {
                updatingEngine[0] = true;
                try {
                    engineMoonshine.setState(false);
                    prefs.put("recog.engine", "whisper");
                    try { prefs.flush(); } catch (Exception ignore) {}
                    Config.log("★RecogEngine changed to: Whisper → soft restart");
                    softRestart();
                } finally {
                    updatingEngine[0] = false;
                }
            } else {
                engineWhisper.setState(true);
            }
        });
        engineMoonshine.addItemListener(e -> {
            if (updatingEngine[0]) return;
            if (engineMoonshine.getState()) {
                updatingEngine[0] = true;
                try {
                    engineWhisper.setState(false);
                    prefs.put("recog.engine", "moonshine");
                    try { prefs.flush(); } catch (Exception ignore) {}
                    Config.log("★RecogEngine changed to: Moonshine → soft restart");
                    softRestart();
                } finally {
                    updatingEngine[0] = false;
                }
            } else {
                engineMoonshine.setState(true);
            }
        });

        recogEngineMenu.add(engineWhisper);
        recogEngineMenu.add(engineMoonshine);
        recogEngineMenu.addSeparator();

        // ★v1.5.0: Moonshine Model 言語選択（exe直下 moonshine_model/ を自動スキャン）
        Menu moonModelMenu = new Menu("Moonshine Model");
        String savedModelPath = prefs.get("moonshine.model_path", "");

        File moonBaseDir = new File(getExeDir(), "moonshine_model");
        File[] langDirs = moonBaseDir.isDirectory()
                ? moonBaseDir.listFiles(File::isDirectory) : null;

        List<CheckboxMenuItem> moonLangItems = new ArrayList<>();

        if (langDirs != null && langDirs.length > 0) {
            Arrays.sort(langDirs, Comparator.comparing(File::getName));
            for (File langDir : langDirs) {
                String langName = langDir.getName();
                File modelDir = findDeepestModelDir(langDir);
                String modelPath = modelDir.getAbsolutePath();

                // 現在選択中かどうか
                boolean selected = modelPath.equals(savedModelPath)
                        || (!savedModelPath.isEmpty() && savedModelPath.contains(langName)
                        && savedModelPath.contains("moonshine_model"));

                CheckboxMenuItem item = new CheckboxMenuItem(langName);
                item.setState(selected);
                moonLangItems.add(item);

                item.addItemListener(ev -> {
                    if (item.getState()) {
                        // 他を全部OFF
                        for (CheckboxMenuItem other : moonLangItems) {
                            if (other != item) other.setState(false);
                        }
                        prefs.put("moonshine.model_path", modelPath);
                        try { prefs.flush(); } catch (Exception ignore) {}
                        Config.log("★Moonshine model: " + langName + " → " + modelPath);

                        if (isEngineMoonshine()) {
                            softRestart();
                        }
                    } else {
                        // 解除防止（最低1つ選択）
                        item.setState(true);
                    }
                });
                moonModelMenu.add(item);
            }
        } else {
            MenuItem noModel = new MenuItem("(moonshine_model/ not found)");
            noModel.setEnabled(false);
            moonModelMenu.add(noModel);
        }

        // 何も選択されてない場合、先頭を自動選択
        if (langDirs != null && langDirs.length > 0) {
            boolean anySelected = moonLangItems.stream().anyMatch(CheckboxMenuItem::getState);
            if (!anySelected && !moonLangItems.isEmpty()) {
                moonLangItems.get(0).setState(true);
                File firstModel = findDeepestModelDir(langDirs[0]);
                prefs.put("moonshine.model_path", firstModel.getAbsolutePath());
                try { prefs.flush(); } catch (Exception ignore) {}
            }
        }

        moonModelMenu.addSeparator();

        // 「フォルダを開く」
        MenuItem openMoonDir = new MenuItem("Open moonshine_model/");
        openMoonDir.addActionListener(e -> {
            try {
                if (!moonBaseDir.isDirectory()) moonBaseDir.mkdirs();
                Desktop.getDesktop().open(moonBaseDir);
            } catch (Exception ex) {
                Config.logError("Failed to open moonshine_model dir", ex);
            }
        });
        moonModelMenu.add(openMoonDir);

        // 「カスタムパス（上級者向け）」
        MenuItem customPath = new MenuItem("Custom Path...");
        customPath.addActionListener(e -> {
            SwingUtilities.invokeLater(() -> {
                JFileChooser fc = new JFileChooser();
                fc.setDialogTitle("Moonshine Model Directory");
                fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                fc.setAcceptAllFileFilterUsed(false);

                String prev = prefs.get("moonshine.model_path", "");
                if (!prev.isEmpty()) {
                    File prevDir = new File(prev);
                    if (prevDir.isDirectory()) {
                        fc.setCurrentDirectory(prevDir);
                    }
                }

                if (fc.showOpenDialog(window) != JFileChooser.APPROVE_OPTION) return;
                File selected = fc.getSelectedFile();
                if (selected == null || !selected.isDirectory()) return;

                String newPath = selected.getAbsolutePath();
                prefs.put("moonshine.model_path", newPath);
                try { prefs.flush(); } catch (Exception ignore) {}
                Config.log("★Moonshine model (custom): " + newPath);

                // チェックボックスを全部外す（カスタムパスなので）
                for (CheckboxMenuItem ci : moonLangItems) ci.setState(false);

                if (isEngineMoonshine()) {
                    softRestart();
                }
            });
        });
        moonModelMenu.add(customPath);

        recogEngineMenu.add(moonModelMenu);

        performanceMenu.add(recogEngineMenu);
        performanceMenu.addSeparator();

        // GPU Selection
        Menu gpuMenu = new Menu(trayText("menu.gpuSelect"));
        int gpuIndex = prefs.getInt("vulkan.gpu.index", -1);
        List<CheckboxMenuItem> gpuItems = new ArrayList<>();
        CheckboxMenuItem autoGpuItem = new CheckboxMenuItem(trayText("menu.gpu.auto"));
        autoGpuItem.setState(gpuIndex < 0);
        gpuMenu.add(autoGpuItem);
        gpuItems.add(autoGpuItem);
        autoGpuItem.addItemListener(e -> {
            if (autoGpuItem.getState()) {
                for (CheckboxMenuItem m : gpuItems) m.setState(m == autoGpuItem);
                prefs.putInt("vulkan.gpu.index", -1);
                try { prefs.sync(); } catch (Exception ignore) {}
                JOptionPane.showMessageDialog(null, "GPU selection changed.\nPlease restart MobMate.",
                        "MobMate", JOptionPane.INFORMATION_MESSAGE);
                restartSelf(true);
            }
        });
        gpuMenu.addSeparator();
        File vulkanDir = new File(getExeDir(), "libs/vulkan");
        boolean vulkanBackendPresent = vulkanDir.exists() && new File(vulkanDir, "whisper.dll").exists();
        Config.log("[TRAY] gpuMenu: vulkanBackendPresent=" + vulkanBackendPresent);
        if (!vulkanBackendPresent) {
            MenuItem na = new MenuItem("Vulkan not available");
            na.setEnabled(false);
            gpuMenu.add(na);
        } else {
            int count = 0;
            try {
                ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "vulkan-enum");
                    t.setDaemon(true);
                    return t;
                });
                try {
                    Future<Integer> f = ex.submit(VulkanGpuUtil::getGpuCount);
                    count = f.get(1200, TimeUnit.MILLISECONDS);
                } finally {
                    ex.shutdownNow();
                }
                Config.log("[TRAY] gpuMenu: VulkanGpuUtil.getGpuCount=" + count);
            } catch (TimeoutException te) {
                Config.log("[TRAY] gpuMenu: Vulkan GPU enumeration TIMEOUT -> skip");
            } catch (Throwable t) {
                Config.logError("[TRAY] gpuMenu: Vulkan GPU enumeration FAILED: " + t, t);
                count = 0;
            }
            if (count <= 0) {
                MenuItem na = new MenuItem("Vulkan GPUs: (not detected)");
                na.setEnabled(false);
                gpuMenu.add(na);
            } else {
                for (int i = 0; i < count; i++) {
                    final int idx = i;
                    String name;
                    try {
                        name = "Vulkan " + i + ": " + VulkanGpuUtil.getGpuName(i);
                    } catch (Throwable t) {
                        name = "Vulkan " + i;
                    }
                    CheckboxMenuItem item = new CheckboxMenuItem(name);
                    item.setState(gpuIndex == idx);
                    gpuMenu.add(item);
                    gpuItems.add(item);
                    item.addItemListener(e -> {
                        if (item.getState()) {
                            for (CheckboxMenuItem m : gpuItems) m.setState(m == item);
                            prefs.putInt("vulkan.gpu.index", idx);
                            try { prefs.sync(); } catch (Exception ignore) {}
                            JOptionPane.showMessageDialog(null, "GPU selection changed.\nPlease restart MobMate.",
                                    "MobMate", JOptionPane.INFORMATION_MESSAGE);
                            restartSelf(true);
                        }
                    });
                }
            }
        }
        performanceMenu.add(gpuMenu);

        // Low GPU Mode
        CheckboxMenuItem lowGpuItem = new CheckboxMenuItem(trayText("menu.lowGpuMode"));
        lowGpuItem.setState(MobMateWhisp.lowGpuMode);
        lowGpuItem.addItemListener(e -> {
            boolean enabled = lowGpuItem.getState();
            MobMateWhisp.lowGpuMode = enabled;
            prefs.putBoolean("perf.low_gpu_mode", enabled);
            try { prefs.sync(); } catch (Exception ignore) {}
            Config.log("Low GPU mode pref set to: " + enabled);
            if (!isRecording()) {
                LocalWhisperCPP.markInitialPromptDirty();
                try {
                    this.w = new LocalWhisperCPP(new File(model_dir, this.model),"");
                } catch (FileNotFoundException ex) {
                    throw new RuntimeException(ex);
                }
            } else {
                Config.log("Low GPU mode will apply after recording stops.");
            }
            if (isRecording()) {
                stopRecording();
            }
        });
        performanceMenu.add(lowGpuItem);

        // VAD Laugh
        CheckboxMenuItem vadLaughItem = new CheckboxMenuItem(trayText("menu.vadLaugh"));
        vadLaughItem.setState(useAlternateLaugh);
        vadLaughItem.addItemListener(e -> {
            boolean enabled = vadLaughItem.getState();
            MobMateWhisp.useAlternateLaugh = enabled;
            prefs.putBoolean("silence.alternate", enabled);
            try { prefs.sync(); } catch (Exception ignore) {}
        });
        performanceMenu.add(vadLaughItem);
        popup.add(performanceMenu);

        // ========================================
        // TTS Settings
        // ========================================
        Config.log("[TRAY] popup: add TTS Settings...");
        Menu ttsMenu = new Menu("TTS Settings");

        // TTS Engine
        Menu ttsEngineMenu = new Menu("Engine");
        String enginePref = prefs.get("tts.engine", "auto");
        List<CheckboxMenuItem> engItems = new ArrayList<>();
        CheckboxMenuItem engAuto = new CheckboxMenuItem("Auto");
        CheckboxMenuItem engXtts = new CheckboxMenuItem("XTTS");
        CheckboxMenuItem engWin  = new CheckboxMenuItem("Windows");
        CheckboxMenuItem engVgWav = new CheckboxMenuItem("Voiceger(WavToWav)");
        Menu engVgTtsMenu = new Menu("Voiceger(TTS)");
        CheckboxMenuItem engVgTts = new CheckboxMenuItem("Use Voiceger(TTS)");
        engVvMenu = new Menu("VOICEVOX");
        engVv = new CheckboxMenuItem("Use VOICEVOX");
        engAuto.setState("auto".equalsIgnoreCase(enginePref));
        engVv.setState("voicevox".equalsIgnoreCase(enginePref));
        engXtts.setState("xtts".equalsIgnoreCase(enginePref));
        engWin.setState("windows".equalsIgnoreCase(enginePref));
        String eng = enginePref == null ? "auto" : enginePref.toLowerCase(Locale.ROOT);
        engVgWav.setState("voiceger".equals(eng) || "voiceger_w2w".equals(eng) || "voiceger_vc".equals(eng));
        engVgTts.setState("voiceger_tts".equals(eng));
        engItems.add(engAuto);
        engItems.add(engVv);
        engItems.add(engXtts);
        engItems.add(engWin);
        engItems.add(engVgWav);
        engItems.add(engVgTts);
        engListener = e -> {
            CheckboxMenuItem src = (CheckboxMenuItem) e.getSource();
            if (!src.getState()) return;
            for (CheckboxMenuItem m : engItems) m.setState(m == src);
            String prev = prefs.get("tts.engine", "auto");
            String v = "auto";
            if (src == engVv) v = "voicevox";
            else if (src == engXtts) v = "xtts";
            else if (src == engWin) v = "windows";
            else if (src == engVgWav) v = "voiceger_vc";
            else if (src == engVgTts) v = "voiceger_tts";
            prefs.put("tts.engine", v);
            try { prefs.sync(); } catch (Exception ignore) {}
            String pv = (prev == null) ? "" : prev.toLowerCase(Locale.ROOT);
            String nv = v.toLowerCase(Locale.ROOT);
            if (!nv.equals(pv) && nv.startsWith("voiceger")) {
                restartVoicegerApiAsync();
            }
        };
        engAuto.addItemListener(engListener);
        engVv.addItemListener(engListener);
        engXtts.addItemListener(engListener);
        engWin.addItemListener(engListener);
        engVgWav.addItemListener(engListener);
        engVgTts.addItemListener(engListener);
        engVvMenu.add(engVv);
        engVvMenu.addSeparator();
        engVgTtsMenu.add(engVgTts);
        engVgTtsMenu.addSeparator();
        String curLang = prefs.get("voiceger.tts.lang", "all_ja");
        List<CheckboxMenuItem> vgLangItems = new ArrayList<>();
        boolean anySelected = false;
        String[][] langs = {
                {"Japanese", "all_ja"},
                {"English", "en"},
                {"Chinese", "all_zh"},
                {"Korean", "all_ko"},
                {"Cantonese", "all_yue"},
        };
        for (String[] it : langs) {
            String label = it[0];
            String code  = it[1];
            CheckboxMenuItem mi = new CheckboxMenuItem(label);
            boolean selected = code.equalsIgnoreCase(curLang);
            mi.setState(selected);
            if (selected) anySelected = true;
            vgLangItems.add(mi);
            engVgTtsMenu.add(mi);
            mi.addItemListener(ev -> {
                if (!mi.getState()) return;
                for (CheckboxMenuItem m : vgLangItems) m.setState(m == mi);
                prefs.put("voiceger.tts.lang", code);
                try { prefs.sync(); } catch (Exception ignore) {}
                if (!engVgTts.getState()) {
                    engVgTts.setState(true);
                }
                engListener.itemStateChanged(
                        new ItemEvent(engVgTts, ItemEvent.ITEM_STATE_CHANGED, engVgTts, ItemEvent.SELECTED)
                );
            });
        }
        if (!anySelected) {
            prefs.put("voiceger.tts.lang", "all_ja");
            try { prefs.sync(); } catch (Exception ignore) {}
        }
        ttsEngineMenu.add(engAuto);
        ttsEngineMenu.addSeparator();
        ttsEngineMenu.add(engVvMenu);
        ttsEngineMenu.add(engXtts);
        ttsEngineMenu.add(engWin);
        ttsEngineMenu.addSeparator();
        ttsEngineMenu.add(engVgWav);
        ttsEngineMenu.add(engVgTtsMenu);
        ttsMenu.add(ttsEngineMenu);

        // Voice Selection
        Menu ttsVoicesItem = new Menu(trayText("menu.voices"));
        String voicePref = prefs.get("tts.windows.voice", "auto");
        List<CheckboxMenuItem> voiceAll = new ArrayList<>();
        CheckboxMenuItem autoVoiceItem = new CheckboxMenuItem(trayText("menu.voice.auto"));
        autoVoiceItem.setState("auto".equals(voicePref));
        ttsVoicesItem.add(autoVoiceItem);
        voiceAll.add(autoVoiceItem);
        autoVoiceItem.addItemListener(e -> {
            if (autoVoiceItem.getState()) {
                for (CheckboxMenuItem m : voiceAll) m.setState(m == autoVoiceItem);
                prefs.put("tts.windows.voice", "auto");
                try { prefs.sync(); } catch (Exception ignore) {}
            }
        });
        ttsVoicesItem.addSeparator();
        List<String> voices = getWindowsVoices();
        Collections.sort(voices);
        for (int i = 0; i < voices.size(); i++) {
            String voice = voices.get(i);
            String label = String.format("%02d: %s", i, voice);
            CheckboxMenuItem item = new CheckboxMenuItem(label);
            item.setState(voice.equals(voicePref));
            ttsVoicesItem.add(item);
            voiceAll.add(item);
            item.addItemListener(e -> {
                if (item.getState()) {
                    for (CheckboxMenuItem m : voiceAll) m.setState(m == item);
                    prefs.put("tts.windows.voice", voice);
                    try { prefs.sync(); } catch (Exception ignore) {}
                }
            });
        }
        ttsVoicesItem.addSeparator();
        ttsMenu.add(ttsVoicesItem);

        // Auto Emotion (VOICEVOX)
        CheckboxMenuItem autoEmotionItem = new CheckboxMenuItem(trayText("menu.voicevox.autoEmotion"));
        boolean currentAutoEmotion = prefs.getBoolean("voicevox.auto_emotion", true);
        autoEmotionItem.setState(currentAutoEmotion);
        autoEmotionItem.addItemListener(e -> {
            boolean enabled = autoEmotionItem.getState();
            prefs.putBoolean("voicevox.auto_emotion", enabled);
            try { prefs.sync(); } catch (Exception ignore) {}
            Config.log("VOICEVOX auto emotion set to: " + enabled);
        });
        ttsMenu.add(autoEmotionItem);
        popup.add(ttsMenu);

        // ========================================
        // Radio Chat
        // ========================================
        Config.log("[TRAY] popup: add Radio Chat...");
        Menu radioMenu = new Menu("Radio Chat");

        // Hotkey Settings
        Menu radioHotkeyMenu = new Menu("Hotkey");
        Menu radioModMenu = new Menu("Modifier");
        final String[] MODS = { "NONE", "SHIFT", "CTRL", "SHIFT+CTRL" };
        final int[] MODMASK = { 0, 1, 2, 3 };
        final int curModMask = prefs.getInt("radio.modMask", 0);
        final java.util.List<CheckboxMenuItem> modItems = new java.util.ArrayList<>();
        for (int idx = 0; idx < MODS.length; idx++) {
            final String label = MODS[idx];
            final int mask = MODMASK[idx];
            final CheckboxMenuItem it = new CheckboxMenuItem(label);
            it.setState(mask == curModMask);
            modItems.add(it);
            radioModMenu.add(it);
            it.addItemListener(ev -> {
                if (!it.getState()) return;
                for (CheckboxMenuItem other : modItems) other.setState(other == it);
                int recMask = getRecordingModMask();
                String recKeyName = MobMateWhisp.this.hotkey;
                int curKeyCode = prefs.getInt("radio.keyCode",
                        com.github.kwhat.jnativehook.keyboard.NativeKeyEvent.VC_F18);
                String curKeyName2 = com.github.kwhat.jnativehook.keyboard.NativeKeyEvent.getKeyText(curKeyCode);
                if (mask == recMask && curKeyName2.equalsIgnoreCase(recKeyName)) {
                    it.setState(false);
                    JOptionPane.showMessageDialog(null,
                            "This hotkey conflicts with Recording hotkey.\nChoose another modifier or key.",
                            "MobMate", JOptionPane.WARNING_MESSAGE);
                    for (CheckboxMenuItem other : modItems) {
                        if (prefs.getInt("radio.modMask", 0) == mask) other.setState(true);
                    }
                    return;
                }
                prefs.putInt("radio.modMask", mask);
                try { prefs.sync(); } catch (Exception ignore) {}
                MobMateWhisp.this.radioModMask = mask;
                Config.log("Radio modMask set: " + mask + " (" + label + ")");
            });
        }
        radioHotkeyMenu.add(radioModMenu);
        Menu radioKeyMenu = new Menu("Key");
        final int curKeyCode = prefs.getInt("radio.keyCode",
                com.github.kwhat.jnativehook.keyboard.NativeKeyEvent.VC_F18);
        final java.util.List<CheckboxMenuItem> keyItems = new java.util.ArrayList<>();
        for (int i = 1; i <= 18; i++) {
            final int keyCode = vcForF(i);
            final String label = "F" + i;
            final CheckboxMenuItem it = new CheckboxMenuItem(label);
            it.setState(keyCode == curKeyCode);
            keyItems.add(it);
            radioKeyMenu.add(it);
            it.addItemListener(ev -> {
                if (!it.getState()) return;
                int mask = prefs.getInt("radio.modMask", 0);
                int recMask = getRecordingModMask();
                String recKeyName = MobMateWhisp.this.hotkey;
                if (mask == recMask && label.equalsIgnoreCase(recKeyName)) {
                    it.setState(false);
                    JOptionPane.showMessageDialog(null,
                            "This hotkey conflicts with Recording hotkey.\nChoose another key or add a modifier.",
                            "MobMate", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                for (CheckboxMenuItem other : keyItems) other.setState(other == it);
                prefs.putInt("radio.keyCode", keyCode);
                try { prefs.sync(); } catch (Exception ignore) {}
                MobMateWhisp.this.radioKeyCode = keyCode;
                Config.log("Radio key set: " + label + " (keyCode=" + keyCode + ")");
            });
        }
        radioHotkeyMenu.add(radioKeyMenu);
        radioMenu.add(radioHotkeyMenu);

        // Overlay Settings
        Menu overlaySettingsMenu = new Menu("Overlay Settings");
        CheckboxMenuItem overlayEnableItem = new CheckboxMenuItem("Enable Overlay");
        overlayEnableItem.setState(overlayEnable);
        overlayEnableItem.addItemListener(e -> {
            boolean enabled = overlayEnableItem.getState();
            setRadioOverlayEnabled(enabled);
        });
        overlaySettingsMenu.add(overlayEnableItem);
        overlaySettingsMenu.addSeparator();
        Menu overlayPosMenu = new Menu("Position");
        String currentPos = (overlayPosition == null ? "TOP_LEFT" : overlayPosition).toUpperCase();
        String[] positions = {"TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT"};
        List<CheckboxMenuItem> posItems = new ArrayList<>();
        for (String pos : positions) {
            CheckboxMenuItem item = new CheckboxMenuItem(pos.replace("_", " "));
            item.setState(pos.equals(currentPos));
            posItems.add(item);
            item.addItemListener(e -> {
                if (!item.getState()) return;
                for (CheckboxMenuItem other : posItems) other.setState(other == item);
                setRadioOverlayPosition(pos);
            });
            overlayPosMenu.add(item);
        }
        overlaySettingsMenu.add(overlayPosMenu);
        Menu overlayDisplayMenu = new Menu("Display");
        int currentDisplay = overlayDisplay;
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] screens = ge.getScreenDevices();
        List<CheckboxMenuItem> displayItems = new ArrayList<>();
        for (int i = 0; i < screens.length; i++) {
            final int displayIndex = i;
            String label = "Display " + (i + 1);
            try {
                DisplayMode dm = screens[i].getDisplayMode();
                label += String.format(" (%dx%d)", dm.getWidth(), dm.getHeight());
            } catch (Exception ignore) {}
            CheckboxMenuItem item = new CheckboxMenuItem(label);
            item.setState(i == currentDisplay);
            displayItems.add(item);
            item.addItemListener(e -> {
                if (!item.getState()) return;
                for (CheckboxMenuItem other : displayItems) other.setState(other == item);
                setRadioOverlayDisplay(displayIndex);
            });
            overlayDisplayMenu.add(item);
        }
        overlaySettingsMenu.add(overlayDisplayMenu);
        Menu overlayFontMenu = new Menu("Font Size");
        int currentFontSize = overlayFontSize;
        List<CheckboxMenuItem> fontItems = new ArrayList<>();
        for (int size : new int[]{12, 14, 16, 18, 20, 24}) {
            final int fontSize = size;
            CheckboxMenuItem item = new CheckboxMenuItem(size + "pt");
            item.setState(size == currentFontSize);
            fontItems.add(item);
            item.addItemListener(e -> {
                if (!item.getState()) return;
                for (CheckboxMenuItem other : fontItems) other.setState(other == item);
                setRadioOverlayFontSize(fontSize);
            });
            overlayFontMenu.add(item);
        }
        overlaySettingsMenu.add(overlayFontMenu);
        Menu overlayOpacityMenu = new Menu("Opacity");
        int currentOpacity = (int) (overlayOpacity * 100.0f);
        List<CheckboxMenuItem> opacityItems = new ArrayList<>();
        for (int op : new int[]{50, 65, 78, 85, 95, 100}) {
            final int opacity = op;
            CheckboxMenuItem item = new CheckboxMenuItem(op + "%");
            item.setState(op == currentOpacity);
            opacityItems.add(item);
            item.addItemListener(e -> {
                if (!item.getState()) return;
                for (CheckboxMenuItem other : opacityItems) other.setState(other == item);
                setRadioOverlayOpacity(opacity / 100.0f);
            });
            overlayOpacityMenu.add(item);
        }
        overlaySettingsMenu.add(overlayOpacityMenu);
        overlaySettingsMenu.addSeparator();
        Menu overlayThemeMenu = new Menu("Theme");
        String[][] colors = {
                {"Green", "green", "30,90,50"},
                {"Blue", "blue", "30,50,90"},
                {"Gray", "gray", "40,40,40"},
                {"Dark Red", "red", "70,30,30"}
        };
        String currentBgHex = toHex(overlayBg);
        List<CheckboxMenuItem> themeItems = new ArrayList<>();
        for (String[] c : colors) {
            final String label = c[0];
            final String bgHex = rgbCsvToHex(c[2]);
            CheckboxMenuItem item = new CheckboxMenuItem(label);
            item.setState(bgHex.equalsIgnoreCase(currentBgHex));
            themeItems.add(item);
            item.addItemListener(e -> {
                if (!item.getState()) return;
                for (CheckboxMenuItem other : themeItems) other.setState(other == item);
                setRadioOverlayColors(bgHex, "#FFFFFF");
            });
            overlayThemeMenu.add(item);
        }
        overlaySettingsMenu.add(overlayThemeMenu);
        Menu overlayLinesMenu = new Menu("Max Lines");
        int currentMaxLines = overlayMaxLines;
        List<CheckboxMenuItem> lineItems = new ArrayList<>();
        for (int lines : new int[]{4, 6, 8, 10, 12, 16, 20}) {
            final int maxLines = lines;
            CheckboxMenuItem item = new CheckboxMenuItem(lines + " lines");
            item.setState(lines == currentMaxLines);
            lineItems.add(item);
            item.addItemListener(e -> {
                if (!item.getState()) return;
                for (CheckboxMenuItem other : lineItems) other.setState(other == item);
                setRadioOverlayMaxLines(maxLines);
            });
            overlayLinesMenu.add(item);
        }
        overlaySettingsMenu.add(overlayLinesMenu);
        radioMenu.add(overlaySettingsMenu);
        popup.add(radioMenu);

        // ========================================
        // Appearance
        // ========================================
        Config.log("[TRAY] popup: add Appearance...");
        Menu appearanceMenu = new Menu("Appearance");
        Menu fontSizeMenu = new Menu(trayText("menu.fontSize"));
        int currentSize = prefs.getInt("ui.font.size", 16);
        List<CheckboxMenuItem> fontItems2 = new ArrayList<>();
        for (int size = 12; size <= 24; size += 2) {
            String label = size + " px";
            CheckboxMenuItem item = new CheckboxMenuItem(label);
            item.setState(size == currentSize);
            final int fontSize = size;
            item.addItemListener(e -> {
                if (item.getState()) {
                    for (CheckboxMenuItem m : fontItems2) {
                        m.setState(m == item);
                    }
                    prefs.putInt("ui.font.size", fontSize);
                    try { prefs.sync(); } catch (Exception ignore) {}
                    applyUIFont(fontSize);
                    SwingUtilities.updateComponentTreeUI(window);
                    window.invalidate();
                    window.validate();
                    window.repaint();
                    if (historyFrame != null) {
                        SwingUtilities.updateComponentTreeUI(historyFrame);
                        historyFrame.refresh();
                    }
                    if (window != null) {
                        SwingUtilities.updateComponentTreeUI(window);
                        // フォント変更後にサイズを再計算させる
                        window.invalidate();
                        window.pack();
                        window.setSize(window.getPreferredSize());

                        window.validate();
                        window.repaint();
                    }
                }
            });
            fontItems2.add(item);
            fontSizeMenu.add(item);
        }
        appearanceMenu.add(fontSizeMenu);

        // ===== Theme Toggle =====
        MenuItem themeToggleItem = new MenuItem("Toggle Dark/Light Theme");
        appearanceMenu.add(themeToggleItem);
        themeToggleItem.addActionListener(e -> {
            // 現在のテーマを反転
            boolean currentDark = prefs.getBoolean("ui.theme.dark", true);
            prefs.putBoolean("ui.theme.dark", !currentDark);
            try {
                prefs.sync();
            } catch (BackingStoreException ignored) {
            }

            // テーマを即座に切り替え
            SwingUtilities.invokeLater(() -> {
                try {
                    if (!currentDark) {
                        com.formdev.flatlaf.FlatDarkLaf.setup();
                    } else {
                        com.formdev.flatlaf.FlatLightLaf.setup();
                    }
                    // 全ウィンドウを更新
                    for (Window w : Window.getWindows()) {
                        SwingUtilities.updateComponentTreeUI(w);
                    }
                } catch (Exception ignored) {
                }
            });
        });
        popup.add(appearanceMenu);

        // ========================================
        // Advanced
        // ========================================
        Config.log("[TRAY] popup: add Advanced...");
        Menu advancedMenu = new Menu("Advanced");

        // Wizard
        MenuItem openWizardItem = new MenuItem(trayText("menu.wizard.open"));
        openWizardItem.addActionListener(e -> {
            SwingUtilities.invokeLater(() -> {
                final String beforeEngine = prefs.get("recog.engine", "whisper"); // ★v1.5.0
                FirstLaunchWizard wizard = null;
                try {
                    try {
                        wizard = new FirstLaunchWizard(window, MobMateWhisp.this);
                    } catch (FileNotFoundException | NativeHookException ex) {
                        throw new RuntimeException(ex);
                    }
                    wizard.setLocationRelativeTo(window);
                    wizard.setVisible(true);
                } finally {
                    try {
                        if (wizard != null) wizard.stopAllWizardTests();
                    } catch (Throwable ignore) {}
                }
                // ★v1.5.0: エンジンが変わっていたらsoftRestart
                String afterEngine = prefs.get("recog.engine", "whisper");
                if (!java.util.Objects.equals(beforeEngine, afterEngine)) {
                    Config.log("★Wizard(CC): engine changed " + beforeEngine
                            + " → " + afterEngine + " → soft restart");
                    softRestart();
                }
            });
        });
        advancedMenu.add(openWizardItem);

        // Debug Menu
        boolean readyToZip = Config.getBool("log.debug", false);
        Menu debugMenu = new Menu("MobMateWhispTalk v" + Version.APP_VERSION);
        if (!readyToZip) {
            MenuItem enableDebugItem = new MenuItem(trayText("menu.debug.enableLog"));
            enableDebugItem.addActionListener(e -> {
                try {
                    Config.appendOutTts("log.debug=true");
                    Config.appendOutTts("log.vad.detailed=true");
                    JOptionPane.showMessageDialog(null,
                            "Debug logging enabled temporarily.\n" +
                                    "Restart the app and reproduce the issue.\n" +
                                    "(Automatically turns off after ~500 messages.)",
                            "MobMate", JOptionPane.INFORMATION_MESSAGE);
                    restartSelf(true);
                } catch (Exception ex) {
                    Config.log("Failed to Debug Log Output ON..");
                }
            });
            debugMenu.add(enableDebugItem);
        } else {
            MenuItem zipLogsItem = new MenuItem(trayText("menu.debug.createZip"));
            zipLogsItem.addActionListener(e -> {
                try {
                    File zip = DebugLogZipper.createZip();
                    JOptionPane.showMessageDialog(null,
                            "Debug logs have been collected.\n\n" +
                                    "Please attach this zip file and send it to support.\n\n" +
                                    zip.getAbsolutePath(),
                            "MobMate", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    Config.log("Failed to Debug Log Output Zipping..");
                }
            });
            debugMenu.add(zipLogsItem);
        }
        debugMenu.addSeparator();
        advancedMenu.add(debugMenu);
        popup.add(advancedMenu);


        // ========================================
        // Other Items
        // ========================================
        popup.addSeparator();
        MenuItem hearingBetaItem = new MenuItem("Hearing (β)");
        hearingBetaItem.addActionListener(e -> {
            SwingUtilities.invokeLater(() -> {
                try {
                    showHearingWindow();
                } catch (Throwable t) {
                    Config.logError("[Hearing] showHearingWindow failed", t);
                }
            });
        });
        popup.add(hearingBetaItem);

        popup.addSeparator();
        // ===== Soft Restart =====
        MenuItem softRestartItem = new MenuItem("Soft Restart");
        popup.add(softRestartItem);
        softRestartItem.addActionListener(e -> softRestart());

        popup.addSeparator();
        MenuItem exitItem = new MenuItem(trayText("menu.exit"));
        popup.add(exitItem);
        exitItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try { VoicegerManager.stopIfRunning(MobMateWhisp.prefs); } catch (Throwable ignore) {}
                Config.mirrorAllToCloud();
                System.exit(0);
            }
        });

        Font awtFont = new Font(UIManager.getFont("Menu.font").getFamily(), Font.PLAIN,
                prefs.getInt("ui.font.size", 12));
        applyAwtFontRecursive(popup, awtFont);

        startDemoAnnounceTimer();
        Config.log("[TRAY] popup: end");
        return popup;
    }


    private void rebuildVoiceVoxSpeakerMenu(Menu engVvMenu, CheckboxMenuItem engVv, ItemListener engListener, Preferences prefs) {
        engVvMenu.removeAll();

        if (isVoiceVoxAvailable()) {
            String vvVoicePref = prefs.get("tts.voice", "auto");
            List<CheckboxMenuItem> vvAll = new ArrayList<>();

            List<VoiceVoxSpeaker> speakers = getVoiceVoxSpeakers(); // id + name
            for (VoiceVoxSpeaker sp : speakers) {
                String key = "" + sp.id();
                String label = key + ":" + sp.name();
                CheckboxMenuItem item = new CheckboxMenuItem(label);
                item.setState(key.equals(vvVoicePref));
                engVvMenu.add(item);
                vvAll.add(item);

                item.addItemListener(ev -> {
                    if (item.getState()) {
                        // 話者を選んだら VOICEVOXエンジンも有効化（迷いを減らす）
                        engVv.setState(true);
                        engListener.itemStateChanged(new ItemEvent(engVv, 0, engVv, ItemEvent.SELECTED));

                        for (CheckboxMenuItem m : vvAll) m.setState(m == item);
                        prefs.put("tts.voice", key);
                        try { prefs.sync(); } catch (Exception ignore) {}
                    }
                });
            }
        } else {
            MenuItem na = new MenuItem("VOICEVOX not available (starting...)");
            na.setEnabled(false);
            engVvMenu.add(na);
        }
    }


    // ===== [ADD] RadioCmd cache (file -> memory map) =====
    private volatile long radioCmdLastLoadedMtime = 0L;
    private volatile java.util.Map<String, String> radioCmdCache = java.util.Collections.emptyMap();
    // ===== [ADD] radiocmd hot-reload =====
    // Radio hotkey (global) cache
    private volatile int radioModMask = 0; // 0:NONE 1:SHIFT 2:CTRL 3:SHIFT+CTRL
    private volatile int radioKeyCode = com.github.kwhat.jnativehook.keyboard.NativeKeyEvent.VC_F18;
    // ===== [ADD] Radio hold state =====
    private volatile boolean radioHeld = false;
    private volatile boolean radioConsumed = false;   // ホールド中に何か押したか
    private volatile int radioPage = 0;               // 0..2 (とりあえず3ページ)
    private static final int RADIO_PAGE_MAX = 3;
    private void loadRadioHotkeyFromPrefs() {
        // prefs は既に初期化されている前提
        this.radioModMask = prefs.getInt("radio.modMask", java.awt.event.KeyEvent.VK_Q);
        this.radioKeyCode = prefs.getInt("radio.keyCode",
                com.github.kwhat.jnativehook.keyboard.NativeKeyEvent.VC_F18);
        Config.log("Radio hotkey loaded: modMask=" + radioModMask + " keyCode=" + radioKeyCode);
    }
    private int getRecordingModMask() {
        // 既存の shiftHotkey / ctrltHotkey を使う（変数名はあなたの実装に合わせて）っす
        return (shiftHotkey ? 1 : 0) | (ctrltHotkey ? 2 : 0);
    }
    // F1..F18 -> NativeKeyEvent VC
    private static int vcForF(int i) {
        return switch (i) {
            case 1 -> NativeKeyEvent.VC_F1;
            case 2 -> NativeKeyEvent.VC_F2;
            case 3 -> NativeKeyEvent.VC_F3;
            case 4 -> NativeKeyEvent.VC_F4;
            case 5 -> NativeKeyEvent.VC_F5;
            case 6 -> NativeKeyEvent.VC_F6;
            case 7 -> NativeKeyEvent.VC_F7;
            case 8 -> NativeKeyEvent.VC_F8;
            case 9 -> NativeKeyEvent.VC_F9;
            case 10 -> NativeKeyEvent.VC_F10;
            case 11 -> NativeKeyEvent.VC_F11;
            case 12 -> NativeKeyEvent.VC_F12;
            case 13 -> NativeKeyEvent.VC_F13;
            case 14 -> NativeKeyEvent.VC_F14;
            case 15 -> NativeKeyEvent.VC_F15;
            case 16 -> NativeKeyEvent.VC_F16;
            case 17 -> NativeKeyEvent.VC_F17;
            default -> NativeKeyEvent.VC_F18;
        };
    }
    // ===== [ADD] helpers =====
    private boolean isRadioHoldKeyEvent(NativeKeyEvent e) {
        // e.getModifiers() は現状の録音ホットキー実装と同じく 0/1/2/3 (NONE/SHIFT/CTRL/SHIFT+CTRL) 前提っす
        int m = (e.getModifiers() & 3);
        return (e.getKeyCode() == this.radioKeyCode) && (m == this.radioModMask);
    }
    private static int digitFromKeyCode(int keyCode) {
        return switch (keyCode) {
            case NativeKeyEvent.VC_1 -> 1;
            case NativeKeyEvent.VC_2 -> 2;
            case NativeKeyEvent.VC_3 -> 3;
            case NativeKeyEvent.VC_4 -> 4;
            case NativeKeyEvent.VC_5 -> 5;
            case NativeKeyEvent.VC_6 -> 6;
            case NativeKeyEvent.VC_7 -> 7;
            case NativeKeyEvent.VC_8 -> 8;
            case NativeKeyEvent.VC_9 -> 9;
            case NativeKeyEvent.VC_0 -> 0;
            default -> -1;
        };
    }
    private final java.util.concurrent.BlockingQueue<String> radioSpeakQueue =
            new java.util.concurrent.LinkedBlockingQueue<>();
    private volatile boolean radioSpeakWorkerStarted = false;
    private void ensureRadioSpeakWorker() {
        if (radioSpeakWorkerStarted) return;
        radioSpeakWorkerStarted = true;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    String s = radioSpeakQueue.take();
                    speak(s);
                    SwingUtilities.invokeLater(() -> {
                        addHistory(s);
                    });
                } catch (Exception ex) {
                    Config.logError("RadioSpeak worker error: " + ex.getMessage(), ex);
                }
            }
        }, "RadioSpeakWorker");
        t.setDaemon(true);
        t.start();
    }
    // slot: 1..9 (0はページ切替に使う暫定)
    private void handleRadioDigit(int digit) {
        String text = getRadioCmdText(radioPage, digit);
        if (text.isEmpty()) {
            Config.log("Radio slot empty: page=" + radioPage + " digit=" + digit);
            return;
        }
        Config.log("Radio speak: page=" + radioPage + " digit=" + digit + " => " + text);
        new Thread(() -> {
            try {
                ensureRadioSpeakWorker();
                radioSpeakQueue.offer(text);
            } catch (Exception ex) {
                Config.logError("Radio speak failed: " + ex, ex);
            }
        }).start();
    }
    // ===== [ADD] load _radiocmd.txt into memory cache =====
    private void reloadRadioCmdCacheIfUpdated() {
        File f = new File("_radiocmd.txt");
        if (!f.exists()) return;

        long mt = f.lastModified();
        if (mt <= 0) return;
        if (mt == radioCmdLastLoadedMtime) return;

        java.util.Map<String, String> next = new java.util.HashMap<>();
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(
                    f.toPath(), java.nio.charset.StandardCharsets.UTF_8
            );

            for (String line : lines) {
                if (line == null) continue;
                String s = line.trim();
                if (s.isEmpty()) continue;
                if (s.startsWith("#") || s.startsWith("//") || s.startsWith(";")) continue;

                int eq = s.indexOf('=');
                if (eq <= 0) continue;

                String key = s.substring(0, eq).trim();      // p0.1 形式
                String val = s.substring(eq + 1).trim();

                // "..." / '...' を剥がす
                if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                    if (val.length() >= 2) val = val.substring(1, val.length() - 1);
                }

                // 事故防止：p0.1～p2.9 っぽいものだけ拾う
                if (!key.matches("^p[0-2]\\.[1-9]$")) continue;

                next.put(key, val);
            }

            // 一括で差し替え（参照側はロック不要）
            this.radioCmdCache = java.util.Collections.unmodifiableMap(next);
            this.radioCmdLastLoadedMtime = mt;
            Config.log("RadioCmd cache loaded: " + next.size() + " entries (" + f.getName() + ")");
        } catch (Exception e) {
            Config.logError("RadioCmd cache load failed: " + e.getMessage(), e);
        }
    }
    private String getRadioCmdText(int page, int digit) {
        String k = "p" + page + "." + digit; // 例: p0.1
        String v = this.radioCmdCache.get(k);
        return (v == null) ? "" : v.trim();
    }
    // ★UI(History)から呼ぶ：_radiocmd.txt とメモリMapへ反映
    public void upsertRadioCmd(int page, int digit, String text) {
        if (text == null) return;
        String t = text.trim();
        if (t.isEmpty()) return;
        if (page < 0 || page > 9) return;
        if (digit < 1 || digit > 9) return;

        String key = "p" + page + "." + digit;
        File f = new File("_radiocmd.txt");

        try {
            if (!f.exists()) f.createNewFile();

            java.nio.charset.Charset cs = java.nio.charset.StandardCharsets.UTF_8;
            java.util.List<String> lines = java.nio.file.Files.readAllLines(f.toPath(), cs);

            // 置換 or 追記
            boolean replaced = false;
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("^\\s*" + java.util.regex.Pattern.quote(key) + "\\s*=.*$");
            String escaped = t.replace("\\", "\\\\").replace("\"", "\\\"");
            String newLine = key + " = \"" + escaped + "\"";

            for (int i = 0; i < lines.size(); i++) {
                if (p.matcher(lines.get(i)).matches()) {
                    lines.set(i, newLine);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                if (!lines.isEmpty() && !lines.getLast().trim().isEmpty()) lines.add("");
                lines.add(newLine);
            }

            java.nio.file.Files.write(
                    f.toPath(),
                    lines,
                    cs,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.CREATE
            );

            // ★メモリMapも即更新（コピーして差し替え）
            java.util.Map<String, String> cur = this.radioCmdCache;
            java.util.HashMap<String, String> next = new java.util.HashMap<>(cur);
            next.put(key, t);
            this.radioCmdCache = java.util.Collections.unmodifiableMap(next);

            // 次回のmtime判定もズレないように更新
            this.radioCmdLastLoadedMtime = f.lastModified();

            Config.log("RadioCmd upsert: " + key + " = " + t);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
    // ★UI用：現在登録されているRadioCmdテキストを返す（メモリMap参照）
    //  - _radiocmd.txtが更新されていればここで自動リロードされる
    public String peekRadioCmdText(int page, int digit) {
        try {
            reloadRadioCmdCacheIfUpdated();
        } catch (Exception ignore) {}
        try {
            return getRadioCmdText(page, digit);
        } catch (Exception ignore) {
            return "";
        }
    }
    // === Radio Overlay config (from _outtts.txt) ===
    private volatile boolean overlayEnable = true;
    private volatile String overlayPosition = "TOP_LEFT"; // TOP_LEFT / TOP_RIGHT / BOTTOM_LEFT / BOTTOM_RIGHT
    private volatile int overlayDisplay = 0;              // 0=primary, 1..=nth monitor
    private volatile int overlayFontSize = 16;
    private volatile float overlayOpacity = 0.78f;        // background alpha
    private volatile int overlayMargin = 12;
    private volatile int overlayMaxLines = 12;
    private volatile Color overlayBg = new Color(0x1D, 0x6F, 0x5A);
    private volatile Color overlayFg = Color.WHITE;
    // page change key (default: "0")
    private volatile String overlayPageChangeName = "0";
    private volatile int overlayPageChangeKeyCode = NativeKeyEvent.VC_0;
    // keep panel ref to restyle
    private javax.swing.JPanel radioOverlayPanel;
    // ===== [ADD] Radio overlay (top-left help) =====
    private javax.swing.JWindow radioOverlay;
    private javax.swing.JTextArea radioOverlayArea;
    // === [ADD] Radio Overlay config write-back helpers ===
    private static String rgbCsvToHex(String rgbCsv) {
        try {
            String[] parts = rgbCsv.split(",");
            int r = Integer.parseInt(parts[0].trim());
            int g = Integer.parseInt(parts[1].trim());
            int b = Integer.parseInt(parts[2].trim());
            r = Math.max(0, Math.min(255, r));
            g = Math.max(0, Math.min(255, g));
            b = Math.max(0, Math.min(255, b));
            return String.format("#%02X%02X%02X", r, g, b);
        } catch (Exception ignore) {
            return "#000000";
        }
    }
    private static String toHex(Color c) {
        if (c == null) return "#000000";
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }
    private static Color parseHexColorSafe(String s, Color def) {
        try {
            if (s == null) return def;
            s = s.trim();
            if (s.startsWith("#")) s = s.substring(1);
            if (s.length() != 6) return def;
            int rgb = Integer.parseInt(s, 16);
            return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        } catch (Exception ignore) {
            return def;
        }
    }

    /**
     * _outtts.txt の key=value を upsert する（コメント行や空行は保持）
     * 既存キーがあれば置換、なければ末尾に追加。
     */
    private static void upsertConfigLine(File file, String key, String value) {
        try {
            List<String> lines = new ArrayList<>();
            if (file.exists()) {
                lines = Files.readAllLines(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            }

            String kv = key + "=" + value;
            boolean replaced = false;

            for (int i = 0; i < lines.size(); i++) {
                String raw = lines.get(i);
                String t = raw.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("#")) continue;
                int eq = t.indexOf('=');
                if (eq <= 0) continue;

                String k = t.substring(0, eq).trim();
                if (k.equals(key)) {
                    // 元のインデント/空白を雑に維持したい場合はここを工夫できるけど、まずは単純置換でOK
                    lines.set(i, kv);
                    replaced = true;
                    break;
                }
            }

            if (!replaced) {
                // 末尾に追加（1行空けるのは好み。ここは最小でそのまま）
                lines.add(kv);
            }

            Files.write(file.toPath(), lines, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            Config.logError("[Overlay] Failed to write config: " + key + "=" + value, ex);
        }
    }

    /**
     * 現在の overlay* 変数を _outtts.txt へ保存して、Config.reload() して、表示中なら更新。
     */
    private void saveOverlayConfigToOutttsAndRefresh() {
        File outtts = new File("_outtts.txt");

        // 値の正規化（事故防止）
        overlayPosition = (overlayPosition == null) ? "TOP_LEFT" : overlayPosition.trim().toUpperCase(Locale.ROOT);
        overlayDisplay = Math.max(0, overlayDisplay);
        overlayFontSize = Math.max(10, overlayFontSize);
        overlayOpacity = clamp01(overlayOpacity);
        overlayMargin = Math.max(0, overlayMargin);
        overlayMaxLines = Math.max(4, overlayMaxLines);

        // page change key は Name と KeyCode の両方整合させたいが、ここでは Name を保存（KeyCodeは load 側で決め打ち可能）
        if (overlayPageChangeName == null || overlayPageChangeName.isBlank()) overlayPageChangeName = "0";

        upsertConfigLine(outtts, "overlay.enable", String.valueOf(overlayEnable));
        upsertConfigLine(outtts, "overlay.position", overlayPosition);
        upsertConfigLine(outtts, "overlay.display", String.valueOf(overlayDisplay));
        upsertConfigLine(outtts, "overlay.font_size", String.valueOf(overlayFontSize));
        upsertConfigLine(outtts, "overlay.opacity", String.valueOf(overlayOpacity));
        upsertConfigLine(outtts, "overlay.margin", String.valueOf(overlayMargin));
        upsertConfigLine(outtts, "overlay.max_lines", String.valueOf(overlayMaxLines));
        upsertConfigLine(outtts, "overlay.bg", toHex(overlayBg));
        upsertConfigLine(outtts, "overlay.fg", toHex(overlayFg));
        upsertConfigLine(outtts, "overlay.page_change", overlayPageChangeName);

        try {
            Config.reload(); // Configが_outtts.txtをキャッシュしてる前提なので再読込
        } catch (Exception ignore) {}

        // 表示中なら即反映
        try {
            updateRadioOverlay();
        } catch (Exception ignore) {}
    }

    // === [ADD] Public-ish setters for menu wiring (missing method 対策) ===
    public void setRadioOverlayEnabled(boolean on) {
        overlayEnable = on;
        saveOverlayConfigToOutttsAndRefresh();
    }
    public void setRadioOverlayPosition(String pos) {
        overlayPosition = (pos == null) ? "TOP_LEFT" : pos;
        saveOverlayConfigToOutttsAndRefresh();
    }
    public void setRadioOverlayDisplay(int displayIndex) {
        overlayDisplay = Math.max(0, displayIndex);
        saveOverlayConfigToOutttsAndRefresh();
    }
    public void setRadioOverlayFontSize(int px) {
        overlayFontSize = Math.max(10, px);
        saveOverlayConfigToOutttsAndRefresh();
    }
    public void setRadioOverlayOpacity(float alpha) {
        overlayOpacity = clamp01(alpha);
        saveOverlayConfigToOutttsAndRefresh();
    }
    public void setRadioOverlayMargin(int px) {
        overlayMargin = Math.max(0, px);
        saveOverlayConfigToOutttsAndRefresh();
    }
    public void setRadioOverlayMaxLines(int lines) {
        overlayMaxLines = Math.max(4, lines);
        saveOverlayConfigToOutttsAndRefresh();
    }
    public void setRadioOverlayColors(String bgHex, String fgHex) {
        overlayBg = parseHexColorSafe(bgHex, overlayBg);
        overlayFg = parseHexColorSafe(fgHex, overlayFg);
        saveOverlayConfigToOutttsAndRefresh();
    }
    public void setRadioOverlayPageChangeKeyName(String keyName) {
        overlayPageChangeName = (keyName == null || keyName.isBlank()) ? "0" : keyName.trim();
        // KeyCodeの決定ルールがあるならここで overlayPageChangeKeyCode も更新してOK
        saveOverlayConfigToOutttsAndRefresh();
    }
    private void safeSetWindowOpacity(java.awt.Window w, float opacity) {
        if (w == null) return;
        float op = clamp01(opacity);
        try {
            w.setOpacity(op);
        } catch (Throwable ignore) {
            // OS/WMで非対応でも落とさない（その場合は不透明表示になる）
        }
    }
    private void loadOverlayConfigFromOuttts() {
        // _outtts.txt から読む（Config は内部キャッシュなので軽いっす）
        overlayEnable = Config.getBool("overlay.enable", true);

        overlayPosition = Config.getString("overlay.position", "TOP_LEFT").trim().toUpperCase(Locale.ROOT);
        overlayDisplay  = Math.max(0, Config.getInt("overlay.display", 0));
        overlayFontSize = Math.max(10, Config.getInt("overlay.font_size", 16));
        overlayOpacity  = clamp01(Config.getFloat("overlay.opacity", 0.78f));
        overlayMargin   = Math.max(0, Config.getInt("overlay.margin", 12));
        overlayMaxLines = Math.max(4, Config.getInt("overlay.max_lines", 12));

        overlayBg = parseHexColor(Config.getString("overlay.bg", "#1D6F5A"), new Color(0x1D, 0x6F, 0x5A));
        overlayFg = parseHexColor(Config.getString("overlay.fg", "#FFFFFF"), Color.WHITE);

        String pc = Config.getString("overlay.page_change", "0");
        if (pc != null) pc = pc.trim();
        if (pc == null || pc.isEmpty()) pc = "0";
        overlayPageChangeName = pc;

        overlayPageChangeKeyCode = toNativeKeyCode(pc, NativeKeyEvent.VC_0);
    }
    private float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
    private Color parseHexColor(String s, Color def) {
        if (s == null) return def;
        s = s.trim();
        if (s.isEmpty()) return def;
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return def;
        try {
            int rgb = Integer.parseInt(s, 16);
            return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        } catch (Exception e) {
            return def;
        }
    }
    /**
     * Accept:
     *  - "0".."9"
     *  - "Q" / "q"
     *  - "SPACE", "TAB", "ESC", "ENTER"
     *  - "F1".."F24"
     */
    private int toNativeKeyCode(String name, int def) {
        if (name == null) return def;
        String n = name.trim().toUpperCase(Locale.ROOT);

        // digit
        if (n.length() == 1 && n.charAt(0) >= '0' && n.charAt(0) <= '9') {
            return switch (n.charAt(0)) {
                case '0' -> NativeKeyEvent.VC_0;
                case '1' -> NativeKeyEvent.VC_1;
                case '2' -> NativeKeyEvent.VC_2;
                case '3' -> NativeKeyEvent.VC_3;
                case '4' -> NativeKeyEvent.VC_4;
                case '5' -> NativeKeyEvent.VC_5;
                case '6' -> NativeKeyEvent.VC_6;
                case '7' -> NativeKeyEvent.VC_7;
                case '8' -> NativeKeyEvent.VC_8;
                case '9' -> NativeKeyEvent.VC_9;
                default -> def;
            };
        }

        // common names
        if ("SPACE".equals(n)) return NativeKeyEvent.VC_SPACE;
        if ("TAB".equals(n)) return NativeKeyEvent.VC_TAB;
        if ("ESC".equals(n) || "ESCAPE".equals(n)) return NativeKeyEvent.VC_ESCAPE;
        if ("ENTER".equals(n) || "RETURN".equals(n)) return NativeKeyEvent.VC_ENTER;

        // F keys
        if (n.startsWith("F")) {
            try {
                int f = Integer.parseInt(n.substring(1));
                // jnativehook has VC_F1..VC_F24 in many builds
                java.lang.reflect.Field fld = NativeKeyEvent.class.getField("VC_F" + f);
                return fld.getInt(null);
            } catch (Exception ignore) {}
        }

        // A-Z
        if (n.length() == 1 && n.charAt(0) >= 'A' && n.charAt(0) <= 'Z') {
            try {
                java.lang.reflect.Field fld = NativeKeyEvent.class.getField("VC_" + n);
                return fld.getInt(null);
            } catch (Exception ignore) {}
        }

        return def;
    }
    private void ensureRadioOverlay() {
        if (radioOverlay != null) return;

        // 設定読み込み
        loadOverlayConfigFromOuttts();

        javax.swing.SwingUtilities.invokeLater(() -> {
            if (radioOverlay != null) return;

            radioOverlay = new javax.swing.JWindow();
            radioOverlay.setAlwaysOnTop(true);
            radioOverlay.setFocusableWindowState(false);
            radioOverlay.setAutoRequestFocus(false);
            try { radioOverlay.setType(java.awt.Window.Type.UTILITY); } catch (Exception ignore) {}
            try { radioOverlay.setBackground(new Color(0, 0, 0, 0)); } catch (Exception ignore) {}

            radioOverlayPanel = new javax.swing.JPanel(new java.awt.BorderLayout());
            radioOverlayPanel.setOpaque(true);
            radioOverlayPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 12, 10, 12));

            radioOverlayArea = new javax.swing.JTextArea();
            radioOverlayArea.setEditable(false);
            radioOverlayArea.setOpaque(false);
            radioOverlayArea.setLineWrap(false);
            radioOverlayArea.setWrapStyleWord(false);

            radioOverlayPanel.add(radioOverlayArea, java.awt.BorderLayout.CENTER);
            radioOverlay.setContentPane(radioOverlayPanel);

            applyOverlayStyle();     // ★色/フォント反映
            radioOverlay.pack();
            moveRadioOverlay();      // ★位置/ディスプレイ反映
        });
    }
    private void applyOverlayStyle() {
        if (radioOverlay == null || radioOverlayPanel == null || radioOverlayArea == null) return;
        // ★透明度はウィンドウに適用（背景色にαを混ぜない）
        safeSetWindowOpacity(radioOverlay, overlayOpacity);
        // ★背景は不透明で塗る（積層しない）
        radioOverlayPanel.setOpaque(true);
        radioOverlayPanel.setBackground(overlayBg);

        radioOverlayArea.setForeground(overlayFg);
        radioOverlayArea.setFont(new java.awt.Font(pickCjkFont(), java.awt.Font.PLAIN, overlayFontSize));
    }
    /** CJK対応フォントを環境に合わせて選択 */
    private static String pickCjkFont() {
        String[] candidates = {
                "Meiryo UI",          // Windows日本語
                "Yu Gothic UI",       // Windows 10+
                "Microsoft YaHei UI", // Windows中国語
                "Malgun Gothic",      // Windows韓国語
                "Noto Sans CJK JP",   // Linux / 手動インストール
                "Hiragino Sans",      // macOS
                "SansSerif"           // 最終フォールバック（Java論理フォント）
        };
        java.awt.GraphicsEnvironment ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
        java.util.Set<String> available = new java.util.HashSet<>(java.util.Arrays.asList(ge.getAvailableFontFamilyNames()));
        for (String name : candidates) {
            if (available.contains(name)) return name;
        }
        return "SansSerif";
    }
    private void moveRadioOverlay() {
        if (radioOverlay == null) return;
        try {
            GraphicsDevice[] devs = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            int idx = overlayDisplay;
            if (idx < 0) idx = 0;
            if (idx >= devs.length) idx = 0;

            Rectangle b = devs[idx].getDefaultConfiguration().getBounds();
            int m = overlayMargin;

            // いったんpack後のサイズで角に吸着
            Dimension sz = radioOverlay.getSize();
            int x = b.x + m;
            int y = b.y + m;

            switch (overlayPosition) {
                case "TOP_RIGHT" -> { x = b.x + b.width - sz.width - m; y = b.y + m; }
                case "BOTTOM_LEFT" -> { x = b.x + m; y = b.y + b.height - sz.height - m; }
                case "BOTTOM_RIGHT" -> { x = b.x + b.width - sz.width - m; y = b.y + b.height - sz.height - m; }
                case "TOP_LEFT" -> { /* default */ }
                default -> { /* fallback TOP_LEFT */ }
            }
            radioOverlay.setLocation(x, y);
        } catch (Exception ignore) {}
    }
    private void moveRadioOverlayTopLeft() {
        if (radioOverlay == null) return;
        try {
            java.awt.Rectangle b = java.awt.GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration()
                    .getBounds();
            // 左上に少し余白
            radioOverlay.setLocation(b.x + 12, b.y + 12);
        } catch (Exception ignore) {}
    }
    private String buildRadioOverlayText(int page) {
        java.util.List<String> lines = new java.util.ArrayList<>();

        lines.add("Radio Chat  P" + page + "/" + (RADIO_PAGE_MAX - 1));
        lines.add(" " + overlayPageChangeName + " = Next Page");
        lines.add("--------------------------------");

        for (int d = 1; d <= 9; d++) {
            String t = peekRadioCmdText(page, d);
            if (t == null) t = "";
            t = t.trim();
            if (t.isEmpty()) t = "—";
            lines.add(d + ": " + t);
        }

        // ★max_linesで安全に切る
        // ★Radioコマンド一覧は 3(header)+9(options)=12 行が最低必要
//        int max = Math.max(12, overlayMaxLines);
        int max = Math.max(4, 3 + overlayMaxLines);
        if (lines.size() > max) {
            lines = lines.subList(0, max);
            // 最後の行を省略表記に
            int last = lines.size() - 1;
            lines.set(last, lines.get(last) + " ...");
        }

        StringBuilder sb = new StringBuilder();
        for (String ln : lines) sb.append(ln).append("\n");
        return sb.toString();
    }
    private void showRadioOverlay() {
        loadOverlayConfigFromOuttts();
        if (!overlayEnable) return;
        ensureRadioOverlay();
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (radioOverlay == null) return;
            reloadRadioCmdCacheIfUpdated();

            applyOverlayStyle();
            radioOverlayArea.setText(buildRadioOverlayText(radioPage));
            radioOverlayArea.setCaretPosition(0);

            radioOverlay.pack();
            moveRadioOverlay();
            radioOverlay.setVisible(true);
        });
    }
    private void updateRadioOverlay() {
        loadOverlayConfigFromOuttts();
        if (!overlayEnable) return;

        javax.swing.SwingUtilities.invokeLater(() -> {
            if (radioOverlay == null || !radioOverlay.isVisible()) return;
            reloadRadioCmdCacheIfUpdated();

            applyOverlayStyle();
            radioOverlayArea.setText(buildRadioOverlayText(radioPage));
            radioOverlayArea.setCaretPosition(0);

            radioOverlay.pack();
            moveRadioOverlay();
        });
    }
    private void hideRadioOverlay() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (radioOverlay != null) radioOverlay.setVisible(false);
        });
    }
    private void handleRadioPageChange() {
        radioPage = (radioPage + 1) % RADIO_PAGE_MAX;
        Config.log("Radio page -> " + (radioPage + 1) + "/" + RADIO_PAGE_MAX);
        updateRadioOverlay();
    }


    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        // ===== [ADD] Radio hold begin / digits while held =====
        if (isRadioHoldKeyEvent(e)) {
            reloadRadioCmdCacheIfUpdated(); // ★ここで更新分だけ読む
            radioHeld = true;
            radioConsumed = false;
            Config.logDebug("Radio hold ON");
            showRadioOverlay(); // ★左上にp0一覧を即表示
            return; // ここで止めて、録音ホットキー側に流さない
        }
        if (radioHeld) {
            // ★ページ切替キー（0以外も可）
            if (e.getKeyCode() == overlayPageChangeKeyCode) {
                radioConsumed = true;
                handleRadioPageChange();
                return;
            }
            int d = digitFromKeyCode(e.getKeyCode());
            if (d >= 1 && d <= 9) {
                radioConsumed = true;
                handleRadioDigit(d);
                return;
            }
        }
        // ===== existing recording hotkey =====
        if (this.hotkeyPressed) {
            return;
        }
        int modifier = 0;
        if (this.shiftHotkey) {
            modifier += 1;
        }
        if (this.ctrltHotkey) {
            modifier += 2;
        }
        if (e.getModifiers() != modifier) {
            return;
        }
        final int length = MobMateWhisp.ALLOWED_HOTKEYS_CODE.length;
        for (int i = 0; i < length; i++) {
            if (MobMateWhisp.ALLOWED_HOTKEYS_CODE[i] == e.getKeyCode() && this.hotkey.equals(MobMateWhisp.ALLOWED_HOTKEYS[i])) {
                this.hotkeyPressed = true;
                SwingUtilities.invokeLater(this::toggleRecordingFromUI);
                break;
            }
        }
    }
    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        // ===== [ADD] Radio hold end =====
        if (isRadioHoldKeyEvent(e)) {
            radioHeld = false;
            Config.logDebug("Radio hold OFF (consumed=" + radioConsumed + ")");
            hideRadioOverlay(); // ★離したら消す
            return; // 録音キーのrelease処理に流さない（別機能なので）
        }
        String currentMode = prefs.get("trigger-mode", START_STOP);
        if (START_STOP.equals(currentMode)) {
            hotkeyPressed = false;
            return;
        }
        int modifier = 0;
        if (this.shiftHotkey) modifier += 1;
        if (this.ctrltHotkey) modifier += 2;
        if (e.getModifiers() != modifier) return;
        for (int i = 0; i < ALLOWED_HOTKEYS_CODE.length; i++) {
            if (ALLOWED_HOTKEYS_CODE[i] == e.getKeyCode()
                    && this.hotkey.equals(ALLOWED_HOTKEYS[i])) {
                hotkeyPressed = false;
                SwingUtilities.invokeLater(() -> {
                    if (PUSH_TO_TALK.equals(currentMode)) {
                        stopRecording();
                    } else if (PUSH_TO_TALK_DOUBLE_TAP.equals(currentMode)) {
                        long delta = System.currentTimeMillis() - recordingStartTime;
                        if (delta > 300) {
                            stopRecording();
                        }
                    }
                });
                break;
            }
        }
    }
    @Override
    public void nativeKeyTyped(NativeKeyEvent e) {
        // Not used but required by the interface
    }





    public int getUIFontSize() {
        return prefs.getInt("ui.font.size", 16); // default 16
    }
    public static void applyUIFont(int size) {
        Font base = UIManager.getFont("Label.font");
        Font newFont = base.deriveFont((float) size);
        Enumeration<Object> keys = UIManager.getDefaults().keys();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            Object value = UIManager.get(key);
            if (value instanceof Font) {
                UIManager.put(key, newFont);
            }
        }
    }

    protected void updateToolTip() {
        String tooltip = "Press ";
        if (MobMateWhisp.this.shiftHotkey) {
            tooltip += "Shift + ";
        }
        if (MobMateWhisp.this.ctrltHotkey) {
            tooltip += "Ctrl + ";
        }
        tooltip += MobMateWhisp.this.hotkey + " to record";
        if (this.trayIcon != null) {
            MobMateWhisp.this.trayIcon.setToolTip(tooltip);
        }
        Config.log(tooltip);
    }

    public List<String> getInputsMixerNames() {
        final List<String> names = new ArrayList<>();
        final Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (Mixer.Info mixerInfo : mixers) {
            final Mixer mixer = AudioSystem.getMixer(mixerInfo);
            final Line.Info[] targetLines = mixer.getTargetLineInfo();
            boolean ok = false;
            for (Line.Info lineInfo : targetLines) {
                if (lineInfo.getLineClass().getName().contains("TargetDataLine")) {
                    ok = true;
                    break;
                }
            }
            if (ok) {
                names.add(mixerInfo.getName());
            }
        }
        return names;
    }

    public List<String> getOutputMixerNames() {
        List<String> names = new ArrayList<>();
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (Mixer.Info mixerInfo : mixers) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            Line.Info[] sourceLines = mixer.getSourceLineInfo();
            for (Line.Info lineInfo : sourceLines) {
                if (lineInfo.getLineClass().getName().contains("SourceDataLine")) {
                    names.add(mixerInfo.getName());
                    break;
                }
            }
        }
        return names;
    }

    private TargetDataLine getFirstTargetDataLine() {
        final Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (Mixer.Info mixerInfo : mixers) {
            final Mixer mixer = AudioSystem.getMixer(mixerInfo);
            final Line.Info[] targetLines = mixer.getTargetLineInfo();
            for (Line.Info lineInfo : targetLines) {
                if (lineInfo != null && lineInfo.getLineClass().getName().contains("TargetDataLine")) {
                    try {
                        return (TargetDataLine) mixer.getLine(lineInfo);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return null;
    }
    private TargetDataLine getTargetDataLine(String audioDevice) {
        if (audioDevice == null || audioDevice.isEmpty()) {
            return null;
        }
        final Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (Mixer.Info mixerInfo : mixers) {
            final Mixer mixer = AudioSystem.getMixer(mixerInfo);
            final Line.Info[] targetLines = mixer.getTargetLineInfo();
            Line.Info lInfo = null;
            for (Line.Info lineInfo : targetLines) {
                if (lineInfo != null && lineInfo.getLineClass().getName().contains("TargetDataLine")) {
                    lInfo = lineInfo;
                    if (mixerInfo.getName().equals(audioDevice)) {
                        try {
                            return (TargetDataLine) mixer.getLine(lInfo);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
        return null;
    }
    private void toggleRecordingFromUI() {
        final String strAction = prefs.get("action", "nothing");
        Action action = Action.NOTHING;
        if ("paste".equals(strAction)) {
            action = Action.COPY_TO_CLIPBOARD_AND_PASTE;
        } else if ("type".equals(strAction)) {
            action = Action.TYPE_STRING;
        }
        if (!isRecording()) {
            recordingStartTime = System.currentTimeMillis();
            startRecording(action);
        } else {
            stopRecording();
        }
        Config.reload();
        Config.loadGoodWords();
        Config.loadDictionary();
        LocalWhisperCPP.dictionaryDirty = false;

        // ★ステータスバーを再構築（最新情報を反映）
        refreshStatusBar();
    }
    // ===== ステータスバー再構築 =====
    private void refreshStatusBar() {
        if (window == null || statusBar == null) return;

        SwingUtilities.invokeLater(() -> {
            // 既存のステータスバーを削除
            Container parent = statusBar.getParent();
            if (parent != null) {
                parent.remove(statusBar);

                // 新しいステータスバーを作成
                statusBar = createStatusBar();

                // 再追加
                parent.add(statusBar, BorderLayout.SOUTH);

                // 再描画
                parent.revalidate();
                parent.repaint();
            }
        });
    }
    /** ★話者照合ステータスをステータスバーに反映 */
    private void updateSpeakerStatus() {
        if (speakerStatusLabel == null) return;
        SwingUtilities.invokeLater(() -> {
            if (!prefs.getBoolean("speaker.enabled", false)) {
                speakerStatusLabel.setText("○SPK");
                speakerStatusLabel.setForeground(Color.GRAY);
                speakerStatusLabel.setToolTipText("Speaker Filter: OFF");
            } else if (speakerProfile == null || !speakerProfile.isReady()) {
                int cnt = (speakerProfile != null) ? speakerProfile.getEnrollCount() : 0;
                int need = (speakerProfile != null) ? speakerProfile.getRequiredSamples() : 0;
                if (cnt > 0) {
                    speakerStatusLabel.setText("◎SPK " + cnt + "/" + need + " plz speak something!");
                } else {
                    speakerStatusLabel.setText("◎SPK" + " plz speak something!");
                }
                speakerStatusLabel.setForeground(new Color(255, 193, 7)); // yellow
                speakerStatusLabel.setToolTipText("Speaker Filter: Enrolling...");
            } else {
                speakerStatusLabel.setText("●SPK");
                speakerStatusLabel.setForeground(new Color(76, 175, 80)); // green
                speakerStatusLabel.setToolTipText(String.format(
                        "Speaker Filter: ON (pass=%d reject=%d thr=%.0f%%)",
                        speakerProfile.getTotalAccepted(),
                        speakerProfile.getTotalRejected(),
                        speakerProfile.getThreshold() * 100));
            }
        });
    }

    void primeVadByEmptyRecording() {
        if (isPriming) {
            primeAgain = true;
            Config.logDebug("VAD calibration already running; queued another calibration.");
            return;
        }
        isPriming = true;
        try {
            Config.reload();
            prefs.sync();
        } catch (Exception e) {
            Config.logDebug("Settings reload failed: " + e.getMessage());
        }
        SwingUtilities.invokeLater(() -> {
            window.setTitle("[CALIBRATING]...");
            button.setEnabled(false);
            button.setText("[CALIBRATING]");
            isCalibrationComplete = false;
        });

        executorService.submit(() -> {
            try {
                Config.log("Starting VAD calibration...");
                String audioDevice = prefs.get("audio.device", "");
                Config.log("Audio device setting: '" + audioDevice + "'");
                Config.log("AudioFormat: " + audioFormat);
                Config.log("CALIB: get TargetDataLine...");
                TargetDataLine line = null;
                try {
                    line = audioDevice.isEmpty() ? getFirstTargetDataLine() : getTargetDataLine(audioDevice);
                    Config.log("CALIB: got TargetDataLine=" + (line == null ? "null" : line.getLineInfo().toString()));
                } catch (Exception e) {
                    Config.log("★CALIB: getTargetDataLine FAILED: " + e);
                    throw e;
                }
                if (line == null) {
                    Config.log("No audio input device available for calibration");
                    SwingUtilities.invokeLater(() -> {
                        window.setTitle(trayText("ui.calibrating.skipped"));
                        button.setEnabled(true);
                        button.setText(UiText.t("ui.main.start"));
                        isCalibrationComplete = true;
                    });
                    return;
                }
                Config.log("CALIB: line.open...");
                try {
                    line.open(audioFormat);
                    Config.log("CALIB: line.open OK (bufferSize=" + line.getBufferSize()
                            + " open=" + line.isOpen() + ")");
                } catch (Exception e) {
                    Config.log("★CALIB: line.open FAILED: " + e);
                    throw e;
                }
                Config.log("CALIB: line.start...");
                try {
                    line.start();
                    Config.log("CALIB: line.start OK (running=" + line.isRunning()
                            + " active=" + line.isActive() + ")");
                } catch (Exception e) {
                    Config.log("★CALIB: line.start FAILED: " + e);
                    throw e;
                }

                long noDataSinceMs = System.currentTimeMillis();
                boolean noDataWarned = false;
                byte[] buffer = new byte[1600];
                for (int i = 0; i < sharedNoiseProfile.CALIBRATION_FRAMES; i++) {

                    int bytesRead = 0;
                    int avail = 0;
                    try { avail = line.available(); } catch (Exception ignore) {}

                    if (avail <= 0) {
                        long now = System.currentTimeMillis();
                        if (!noDataWarned && (now - noDataSinceMs) > 1200) {
                            noDataWarned = true;
                            Config.log("★CALIB: no mic data yet (avail=0 for " + (now - noDataSinceMs) + "ms)"
                                    + " open=" + line.isOpen()
                                    + " running=" + line.isRunning()
                                    + " active=" + line.isActive());
                        }
                        try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                        i--; // サンプル数を稼ぎたい
                        continue;
                    } else {
                        // データが来たら時計をリセット
                        noDataSinceMs = System.currentTimeMillis();
                    }

                    bytesRead = line.read(buffer, 0, Math.min(buffer.length, avail));

                    if (bytesRead > 0) {
                        int peak = vad.getPeak(buffer, bytesRead);
                        int avg = vad.getAvg(buffer, bytesRead);
                        boolean reallySilent =
                                peak < (sharedNoiseProfile.getManualPeakThreshold() * 0.25) &&
                                        avg  < (sharedNoiseProfile.getManualPeakThreshold() * 0.10);
                        if (reallySilent) {
                            sharedNoiseProfile.update(peak, avg, true);
                        }
                    }
                    final int progress = (i * 100) / sharedNoiseProfile.CALIBRATION_FRAMES;
                    SwingUtilities.invokeLater(() -> window.setTitle("[CALIBRATING]... " + progress + "%"));
                    try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
                line.stop();
                line.close();

                sharedNoiseProfile.forceCalibrate();
                vadPrimed = true;
                Config.log("VAD calibration completed");
                SwingUtilities.invokeLater(() -> {
                    isCalibrationComplete = true;
                    button.setEnabled(true);
                    button.setText(UiText.t("ui.main.start"));
                    window.setTitle(UiText.t("ui.calibrating.complete"));
                    updateToolTip();
                });
            } catch (Exception e) {
                Config.log("Calibration failed: " + e.getMessage());
                SwingUtilities.invokeLater(() -> {
                    window.setTitle("[CALIBRATING] failed - " + e.getMessage());
                    button.setEnabled(true);
                    button.setText(UiText.t("ui.main.start"));
                    isCalibrationComplete = true;
                });
            } finally {
                isPriming = false;

                boolean isLow = vad.getNoiseProfile().isLowGainMic;
                w.setLowGainMic(isLow);
                Config.log("LowGainMic detected = " + isLow);

                if (autoGainEnabled) {
                    autoGainMultiplier = isLow ? 1.8f : 1.0f;
                }

                // ★追加：キャリブ中に変更要求が来てたら、終わった直後にもう一回回す
                if (primeAgain) {
                    primeAgain = false;
                    SwingUtilities.invokeLater(this::primeVadByEmptyRecording);
                }
            }
        });
    }


    // ★Mic debug wav rotation (0..4)
    private static final int MIC_DUMP_ROTATE = 5;
    private static final java.util.concurrent.atomic.AtomicInteger MIC_DUMP_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(0);
    // ★発話単位ID（同じ発話内の partial-rescue と final を結びつける）
    private volatile long utteranceSeqGen = 0L;
    private volatile long currentUtteranceSeq = 0L;
    // ★この発話で partial-rescue した内容（同一発話の final を抑制するため）
    private volatile long lastRescueUtteranceSeq = -1L;
    private volatile long lastRescueAtMs = 0L;
    private volatile String lastRescueNorm = "";
    boolean isSpeaking = false;
    long speechStartTime = 0;
    long lastSpeechEndTime = 0;
    private void startRecording(Action action) {
        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            JOptionPane.showMessageDialog(null, "Cannot save preferences");
        }

        if (!isCalibrationComplete) {
            Config.log("Recording blocked - calibration not complete");
            return;
        }

        Config.log("MobMateWhispTalk.startRecording()" + action);

        // すでに録音中 or 開始処理中なら何もしない（固まり防止）
        if (isRecording() || isStartingRecording.get()) {
            if (isRecording()) {
                stopRecording();
            }
            return;
        }
        if (!isStartingRecording.compareAndSet(false, true)) {
            if (isRecording()) {
                stopRecording();
            }
            return;
        }

        // UI: 開始準備中
        SwingUtilities.invokeLater(() -> {
            button.setEnabled(false);
            button.setText("Starting...");
            window.setTitle("[STARTING]...");
            MobMateWhisp.this.window.setIconImage(imageInactive);
        });

        javax.swing.Timer watchdog = new javax.swing.Timer(2500, ev -> {
            if (isStartingRecording.get() && !isRecording()) {
                isStartingRecording.set(false);
                button.setEnabled(true);
                button.setText(UiText.t("ui.main.start"));
                window.setTitle(UiText.t("ui.title.idle"));
                SwingUtilities.invokeLater(() -> {
                    // ★録音開始できたので Stop を押せるように戻す
                    button.setEnabled(true);
                });
            }
        });
        watchdog.setRepeats(false);
        watchdog.start();

        final String audioDevice = prefs.get("audio.device", "");
        final String previsouAudipDevice = prefs.get("audio.device.previous", "");

        try {
            this.audioService.execute(() -> {
                final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                final ByteArrayOutputStream laughBuffer = new ByteArrayOutputStream();
                final int LAUGH_WINDOW_BYTES = 16000 / 2;

                TargetDataLine targetDataLine = null;
                boolean finalAlreadySent = false;

                try {
                    // --- デバイス解決 ---
                    targetDataLine = getTargetDataLine(audioDevice);
                    if (targetDataLine == null) {
                        targetDataLine = getTargetDataLine(previsouAudipDevice);
                        if (targetDataLine == null) {
                            targetDataLine = getFirstTargetDataLine();
                        } else {
                            Config.logDebug("Using previous audio device : " + previsouAudipDevice);
                        }
                        if (targetDataLine == null) {
                            SwingUtilities.invokeLater(() ->
                                    JOptionPane.showMessageDialog(null, UiText.t("dialog.error.noAudioDevice")));
                            return;
                        } else {
                            Config.logDebug("Using default audio device");
                        }
                    } else {
                        Config.logDebug("Using audio device : " + audioDevice);
                    }

                    // --- 実際にopen/startできたら録音ONにする（ここがポイント） ---
                    targetDataLine.open(MobMateWhisp.this.audioFormat);
                    targetDataLine.start();
                    // ★ADD: mic warm-up (AGC/NoiseSuppressionの立ち上がりで0埋めになる環境対策)
                    try {
                        byte[] warm = new byte[4096];
                        long until = System.currentTimeMillis() + 250; // 250ms捨て
                        while (System.currentTimeMillis() < until) {
                            int nn = targetDataLine.read(warm, 0, warm.length);
                            if (nn <= 0) break;
                        }
                        Config.logDebug("★Mic warm-up done (250ms discard)");
                    } catch (Throwable t) {
                        // ignore
                    }
                    probeMicOnce(targetDataLine);

                    isStartingRecording.set(false);
                    setRecording(true); // ここで初めて録音状態へ（UIもここで切り替わる）
                    try {
                        // --- VAD再キャリブレーション ---
                        String effectiveDevice =
                                (audioDevice != null && !audioDevice.isBlank()) ? audioDevice :
                                        (previsouAudipDevice != null && !previsouAudipDevice.isBlank()) ? previsouAudipDevice :
                                                "DEFAULT";

                        SwingUtilities.invokeLater(() -> window.setTitle("[CALIBRATING](" + effectiveDevice + ")..."));
                        ensureVADCalibrationForDevice(targetDataLine, effectiveDevice);

                        // ★話者エンロール（speaker.enabled=true時のみ）
                        if (prefs.getBoolean("speaker.enabled", false) && !speakerProfile.isReady()) {
                            // 既存プロファイルがあれば復元を試みる
                            File spkFile = new File("speaker_profile.dat");
                            if (spkFile.exists() && speakerProfile.loadFromFile(spkFile)) {
                                Config.log("★Speaker profile restored from file");
                                updateSpeakerStatus();
                            } else {
                                // エンロール実施
                                int need = speakerProfile.getRequiredSamples();
                                Config.log("★Speaker enrollment starting: need " + need + " samples");
//                                SwingUtilities.invokeLater(() ->
//                                        window.setTitle("[ENROLLING] plz speak something..."));
                                updateSpeakerStatus();

                                byte[] enrollBuf = new byte[4096];
                                ByteArrayOutputStream enrollChunk = new ByteArrayOutputStream();
                                int enrolled = 0;
                                int enrollSilence = 0;
                                boolean enrollSpeaking = false;
                                long enrollStart = System.currentTimeMillis();
                                long enrollTimeoutMs = prefs.getInt("speaker.enroll_timeout_sec", 30) * 1000L;
                                int minEnrollBytes = 16000 * 2; // ★最低1秒分の発話（短すぎると精度低下）

                                while (enrolled < need
                                        && (System.currentTimeMillis() - enrollStart) < enrollTimeoutMs
                                        && isRecording()) {
                                    int r = targetDataLine.read(enrollBuf, 0, enrollBuf.length);
                                    if (r <= 0) continue;

                                    int peak = vad.getPeak(enrollBuf, r);
                                    boolean speech = (vad instanceof ImprovedVAD)
                                            ? ((ImprovedVAD) vad).isSpeech(enrollBuf, r, enrollChunk.size())
                                            : vad.isSpeech(enrollBuf, r);

                                    if (speech) {
                                        if (!enrollSpeaking) {
                                            enrollSpeaking = true;
                                            enrollChunk.reset();
                                            enrollSilence = 0;
                                        }
                                        enrollChunk.write(enrollBuf, 0, r);
                                    } else if (enrollSpeaking) {
                                        enrollSilence++;
                                        if (enrollSilence >= 3) { // 発話終了
                                            byte[] chunk = enrollChunk.toByteArray();
                                            if (chunk.length >= minEnrollBytes) {
                                                speakerProfile.enrollSample(chunk);
                                                enrolled++;
                                                final int cnt = enrolled;
                                                SwingUtilities.invokeLater(() ->
                                                        window.setTitle("[ENROLLING] " + cnt + "/" + need));
                                                updateSpeakerStatus();
                                                Config.logDebug("★Speaker enroll accepted: "
                                                        + enrolled + "/" + need
                                                        + " bytes=" + chunk.length);
                                            } else {
                                                Config.logDebug("★Speaker enroll skipped (too short): "
                                                        + chunk.length + " bytes");
                                            }
                                            enrollSpeaking = false;
                                            enrollChunk.reset();
                                            enrollSilence = 0;
                                        }
                                    }
                                }

                                if (speakerProfile.isReady()) {
                                    speakerProfile.saveToFile(spkFile);
                                    Config.log("★Speaker enrollment complete: "
                                            + enrolled + " samples enrolled");
                                } else {
                                    Config.log("★Speaker enrollment incomplete: "
                                            + enrolled + "/" + need
                                            + " (timeout or stopped)");
                                }
                                updateSpeakerStatus();
                            }
                        }
                        SwingUtilities.invokeLater(() -> window.setTitle(UiText.t("ui.title.rec")));

                        // --- ここから先は元の処理（録音ループ） ---
                        byte[] data = new byte[4096];
                        boolean detectSilence = prefs.getBoolean("silence-detection", true);

                        if (detectSilence) {
                            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                            ByteArrayOutputStream partialBuf = new ByteArrayOutputStream();
                            ByteArrayOutputStream preRoll = new ByteArrayOutputStream();
                            int silenceFrames = 0;
                            int SILENCE_FRAMES_FOR_FINAL = 2;

                            if (vad instanceof ImprovedVAD) {
                                SILENCE_FRAMES_FOR_FINAL =
                                        Math.max(1, ((ImprovedVAD) vad)
                                                .getAdjustedSilenceFrames(SILENCE_FRAMES_FOR_FINAL));
                            }
                            if (vad.getNoiseProfile().isLowGainMic) {
                                SILENCE_FRAMES_FOR_FINAL = Math.max(2, SILENCE_FRAMES_FOR_FINAL);
                            }
                            Config.logDebug("★調整後 SILENCE_FRAMES_FOR_FINAL: " + SILENCE_FRAMES_FOR_FINAL);

                            boolean firstPartialSent = false;
                            final int PREROLL_BYTES = (int) (16000 * 3 / 10);
                            final int MIN_AUDIO_DATA_LENGTH = 16000;
                            long lastFinalMs = 0;
                            final long FINAL_COOLDOWN_MS = 200;
                            final long FORCE_FINAL_MS = 2500;
                            long lastPartialMs = 0;
                            isSpeaking = false;
                            speechStartTime = 0;
                            lastSpeechEndTime = 0;
                            int frameCount = 0;
                            boolean forceFinalizePending = false;
                            final LaughDetector laughDet = new LaughDetector();
                            int strongNoSpeechFrames = 0;
                            final long recordLoopStartMs = System.currentTimeMillis();
                            long primingSinceMs = 0L;
                            boolean micWarned = false;
                            long lastAudibleMs = System.currentTimeMillis();
                            final boolean micDump = Config.getBool("log.debug", false);
                            int micDumpSec = Config.getInt("mic.debug_dump_wav_sec", 2);
                            final int micDumpMaxBytes = (int)(audioFormat.getSampleRate() * audioFormat.getFrameSize() * micDumpSec);
                            ByteArrayOutputStream micDumpBuf = micDump ? new ByteArrayOutputStream(micDumpMaxBytes + 1024) : null;
                            boolean micDumpDone = false;

                            while (isRecording()) {
                                int n = targetDataLine.read(data, 0, data.length);
                                if (n <= 0) continue;

                                // ---- [ADD] VAD/Whisper用にだけ増幅したPCMを用意 ----
                                float userGain = prefs.getFloat("audio.inputGainMultiplier", 1.0f);
                                float gain = userGain * autoGainMultiplier;

                                byte[] pcm = data; // デフォは生PCM
                                if (gain > 1.01f) {
                                    pcm = new byte[n];
                                    System.arraycopy(data, 0, pcm, 0, n);
                                    applyPcmGain16leInPlace(pcm, n, gain);
                                }
                                // ★ADD: mic debug wav 用に、生PCM（or 増幅後pcm）を溜める
                                if (micDump && !micDumpDone && micDumpBuf != null) {
                                    try {
                                        micDumpBuf.write(pcm, 0, n);
                                    } catch (Exception ignore) {}
                                }

                                preRoll.write(pcm, 0, n);
                                if (preRoll.size() > PREROLL_BYTES) {
                                    byte[] pr = preRoll.toByteArray();
                                    preRoll.reset();
                                    preRoll.write(pr, pr.length - PREROLL_BYTES, PREROLL_BYTES);
                                }
                                // ★v1.5.0: Moonshineモードのときだけ音声を流す
                                if (isEngineMoonshine() && moonshine != null && moonshine.isLoaded()) {
                                    final float[] fpcm = pcm16leToFloat(pcm, n);
                                    moonshine.addAudio(fpcm, 16000);
                                    // ★REMOVE: setTranscribing(true)はonPartialで行う
                                }

                                int peak = vad.getPeak(pcm, n);
                                int avg  = vad.getAvg(pcm, n);
                                // ===== [ADD] mic sanity warn (OSノイズ抑制/ミュート/誤デバイスの切り分け) =====
                                long nowMs2 = System.currentTimeMillis();
                                if (peak > 120 || avg > 30) { // ここは雑でOK。とにかく「音が来てる」判定
                                    lastAudibleMs = nowMs2;
                                } else if (!micWarned && (nowMs2 - recordLoopStartMs) > 1500 && (nowMs2 - lastAudibleMs) > 1500) {
                                    micWarned = true;
                                    Config.log("★MIC: almost silent for 1.5s.");
                                }
                                if (vad instanceof ImprovedVAD) {
                                    AdaptiveNoiseProfile np = ((ImprovedVAD) vad).getNoiseProfile();
                                    boolean low = np.isLowGainMic;
                                    int peakTh = np.getPeakThreshold();
                                    int minAbs = low
                                            ? Config.getInt("laugh.min_peak.low", 260)   // BTヘッドセット想定
                                            : Config.getInt("laugh.min_peak", 1200);     // デスクトップマイク想定
                                    int scaled = (int) (peakTh * (low ? 3.0 : 1.8));
                                    int maxAbs = Config.getInt("laugh.min_peak.max", 6500);
                                    int minPeakForBurst = Math.max(minAbs, Math.min(maxAbs, scaled));
                                    laughDet.setMinPeakForBurst(minPeakForBurst);
                                    laughDet.setLowGain(low);
                                }

                                boolean alt = prefs.getBoolean("silence.alternate", false);
                                final long MIN_SPEECH_MS_FOR_LAUGH = alt ? 450 : 220;
                                long now2 = System.currentTimeMillis();

                                // ★修正：speech中かつ最小時間経過後にのみ検知
                                if (useAlternateLaugh && isSpeaking &&
                                        (now2 - speechStartTime) > MIN_SPEECH_MS_FOR_LAUGH) {

                                    // ★直前に言語 partial があるなら笑い検知しない
                                    String lastP = MobMateWhisp.getLastPartial();
                                    boolean hasSpeechText =
                                            lastP != null && lastP.trim().length() >= 2;

                                    boolean laughHit = false;
                                    if (!hasSpeechText) {
                                        laughHit = laughDet.updateAndDetectLaugh(
                                                data, n, peak, now2, true);
                                    }

                                    if (laughHit && !laughAlreadyHandled) {
                                        synchronized (laughLock) {
                                            if (!laughAlreadyHandled) {
                                                laughAlreadyHandled = true;
                                                String laughText = getLaughOutputLabel();
                                                pendingLaughText = laughText;

                                                Config.logDebug("★★★ LAUGH DETECTED ★★★ peak=" + peak +
                                                        ", speechMs=" + (now2 - speechStartTime) +
                                                        ", text=" + laughText);

                                                // 即座に再生＆履歴追加
                                                speak(laughText);
                                                SwingUtilities.invokeLater(() -> {
                                                    addHistory("[VAD] "+laughText);
                                                });
                                            }
                                        }
                                    }
                                }

                                // ★修正：isPriming中のフレームカウント処理
                                if (isPriming) {

                                    if (primingSinceMs == 0L) primingSinceMs = System.currentTimeMillis();

                                    // ===== [ADD] primingが長引いたら解除して通常録音へ =====
                                    if (System.currentTimeMillis() - primingSinceMs > 4500) {
                                        Config.logDebug("★Priming timeout -> force disable priming and continue recording");
                                        isPriming = false;
                                        primingSinceMs = 0L;
                                        // ここで continue しない（通常ルートへ落とす）
                                    } else {
                                        int manualPeak = (vad instanceof ImprovedVAD) ?
                                                ((ImprovedVAD) vad).getNoiseProfile().getManualPeakThreshold() : 2000;
                                        boolean silentLike = peak < (manualPeak / 2) && avg < (manualPeak / 8);
                                        if (vad instanceof ImprovedVAD) {
                                            if (silentLike) {
                                                ((ImprovedVAD) vad).getNoiseProfile().update(peak, avg, true);
                                            }
                                            AdaptiveNoiseProfile profile = ((ImprovedVAD) vad).getNoiseProfile();
                                            int currentSamples = profile.noiseSamples.size();
                                            int totalSamples = profile.CALIBRATION_FRAMES;
                                            if (frameCount % 5 == 0) {
                                                SwingUtilities.invokeLater(() ->
                                                        window.setTitle(String.format(UiText.t("ui.calibrating.stayQuiet"),
                                                                currentSamples, totalSamples))
                                                );
                                            }
                                        }
                                        Thread.sleep(10);
                                        frameCount++;
                                        continue;
                                    }
                                }

                                if (frameCount == 0) {
                                    SwingUtilities.invokeLater(() ->
                                            window.setTitle(UiText.t("ui.title.rec"))
                                    );
                                }

                                boolean speech = (vad instanceof ImprovedVAD)
                                        ? ((ImprovedVAD) vad).isSpeech(pcm, n, buffer.size())
                                        : vad.isSpeech(pcm, n);

                                // ★ADD: TTS再生中はVADをマスク（ループバック防止）
                                if (isTtsSpeaking && speech) {
                                    Config.logDebug("★VAD suppressed (TTS playing)");
                                    speech = false;
                                }
                                // ===== 話者照合はfinalChunkレベルで実施（フレーム単位は精度不足）=====

                                // ===== [ADD] strong signal but speech=false guard =====
                                if (vad instanceof ImprovedVAD) {
                                    AdaptiveNoiseProfile np = ((ImprovedVAD) vad).getNoiseProfile();
                                    int peakTh = np.getPeakThreshold();

                                    // 「閾値近い強い音が来てるのに speech がずっと false」なら救済
                                    boolean strongInput = peak >= (int)(peakTh * 0.90);
                                    if (!speech && strongInput) strongNoSpeechFrames++;
                                    else strongNoSpeechFrames = 0;

                                    if (strongNoSpeechFrames == 45) { // 約45フレーム続いた時点でログ
                                        Config.logDebug(String.format(
                                                "★VAD maybe stuck: peak=%d avg=%d peakTh=%d avgTh=%d lowGain=%s bgm=%s",
                                                peak, avg, np.getPeakThreshold(), np.getAvgThreshold(),
                                                np.isLowGainMic, ((ImprovedVAD) vad).looksLikeBGM(peak, avg)
                                        ));
                                    }
                                    if (strongNoSpeechFrames >= 60) { // さらに続いたら強制で speech 扱いにして発話開始へ
                                        speech = true;
                                        Config.logDebug("★VAD override: strongNoSpeechFrames>=60 -> speech=true");
                                        strongNoSpeechFrames = 0;
                                    }
                                }

                                // VAD結果を保持（メーターの「無音で0へ戻る」に使う）
                                lastVadSpeech = speech;
                                if (speech) lastSpeechAtMs = System.currentTimeMillis();
                                // ★メーター更新は常時（ただし間引きは維持）
                                if (METER_UPDATE_INTERVAL == 0 || meterSkipCounter.getAndIncrement() % METER_UPDATE_INTERVAL == 0) {
                                    updateInputLevelWithGain(data, n, speech);
                                }

                                if (vad.getNoiseProfile().isLowGainMic) {
                                    if (peak > vad.getNoiseProfile().getPeakThreshold() * 0.6) {
                                        speech = true;
                                    }
                                }
                                if (!vad.getNoiseProfile().isLowGainMic && vad.looksLikeBGM(peak, avg)) {
                                    speech = false;
                                    Config.logDebug("VAD: BGM-like stable sound suppressed");
                                }
                                if (vad instanceof ImprovedVAD) {
                                    silenceFrames = ((ImprovedVAD) vad).getConsecutiveSilenceFrames();
                                }

                                if (vad.getNoiseProfile().isLowGainMic) {
                                } else {
                                    if (isSpeaking && earlyFinalizeRequested) {
                                        long nowEf = System.currentTimeMillis();
                                        if (nowEf <= earlyFinalizeUntilMs) {
                                            if (silenceFrames >= 1) {
                                                forceFinalizePending = true;
                                                Config.logDebug("★EarlyFinalize -> forceFinalizePending (silenceFrames=" + silenceFrames + ")");
                                            }
                                        } else {
                                            earlyFinalizeRequested = false;
                                        }
                                    }
                                }

                                long now = System.currentTimeMillis();
                                // ★Finalが直前に迫っているときはrescueスキップ（暴発防止）
                                // → finalが空でもFinalブロック内のdoRescueFromPartialが拾う
                                boolean finalImminent = isSpeaking && !isProcessingFinal.get() &&
                                        (silenceFrames >= SILENCE_FRAMES_FOR_FINAL || forceFinalizePending);
                                if (!finalImminent) {
                                    maybeRescueSpeakFromPartial(action, now, currentUtteranceSeq);
                                }

                                laughDet.notifySpeechState(speech, now);
                                if (frameCount % 30 == 0) {
                                    Config.logDebug(String.format("★VAD状況: speech=%s, silence=%d, peak=%d, bufSize=%d",
                                            speech, silenceFrames, peak, buffer.size()));
                                }

                                if (speech) {
                                    if (!isSpeaking) {
                                        isSpeaking = true;
                                        speechStartTime = now;
                                        laughAlreadyHandled = false;
                                        pendingLaughAppend = false;
                                        pendingLaughText = null;
                                        laughDet.reset();
                                        buffer.reset();
                                        buffer.write(preRoll.toByteArray());
                                        if (vad instanceof ImprovedVAD) {
                                            ((ImprovedVAD) vad).reset();
                                        }
                                        silenceFrames = 0;
                                        firstPartialSent = false;
                                        forceFinalizePending = false;
                                        currentUtteranceSeq = ++utteranceSeqGen;
                                        MobMateWhisp.setLastPartial("");
                                        lastPartialUpdateMs = 0L; // partial更新時刻もリセット
                                        Config.logDebug("★発話開始");
                                        // ★ADD: Moonshineストリームを発話単位でリセット（コンテキスト汚染防止）
                                        if (isEngineMoonshine() && moonshine != null && moonshine.isLoaded()) {
                                            moonshine.resetStream();
                                            // preRollをnewStreamに流す（発声冒頭の欠落防止）
                                            byte[] pr = preRoll.toByteArray();
                                            if (pr.length > 0) {
                                                final float[] fpr = pcm16leToFloat(pr, pr.length);
                                                moonshine.addAudio(fpr, 16000);
                                            }
                                        }
                                        SwingUtilities.invokeLater(() -> window.setTitle(UiText.t("ui.title.rec")));
                                    }
                                    buffer.write(pcm, 0, n);
                                    partialBuf.write(pcm, 0, n);
                                    lastSpeechEndTime = now;

                                    PARTIAL_INTERVAL_MS = (!lowGpuMode) ?300: 700;
                                    if (now - lastPartialMs >= PARTIAL_INTERVAL_MS) {
                                        lastPartialMs = now;
                                        kickPartialTranscribe(buffer);
                                        SwingUtilities.invokeLater(() -> {
                                            window.setTitle("[TRANS]...");
                                            MobMateWhisp.this.window.setIconImage(imageTranscribing);
                                        });
                                        // ★ すでに結果が来てる場合だけ具体テキストに更新
                                        String p = lastPartialResult.get(); // getLastPartial() じゃなく、AtomicReferenceを見る
                                        if (p != null && !p.isBlank()) {
                                            final String show = p;
                                            SwingUtilities.invokeLater(() -> {
                                                window.setTitle("[TRANS]:" + show);
                                                MobMateWhisp.this.window.setIconImage(imageTranscribing);
                                            });
                                        }
                                        // ===== [ADD] alternate=false のときだけ partial の laughs.detect を即発話 =====
                                        if (!alt) {
                                            String cached = MobMateWhisp.getLastPartial();
                                            if (cached != null && !cached.isBlank()) {

                                                if (containsLaughTokenByConfig(cached)) {
                                                    long nowMs = System.currentTimeMillis();

                                                    // 時間が空きすぎたらカウントリセット
                                                    if (nowMs - lastLaughPartialMs > LAUGH_PARTIAL_WINDOW_MS) {
                                                        laughPartialCount = 0;
                                                    }

                                                    lastLaughPartialMs = nowMs;
                                                    laughPartialCount++;

                                                    Config.logDebug("★Laugh partial count = " + laughPartialCount + " : " + cached);

                                                    // ★ 2回目で笑い確定
                                                    if (laughPartialCount >= 2) {

                                                        String laughOut = normalizeLaugh(cached).trim();

                                                        if (!LocalWhisperCPP.isIgnoredStatic(laughOut)) {
                                                            Config.logDebug("★Instant laugh by accumulated partial: " + laughOut);

                                                            speak(laughOut);
                                                            MobMateWhisp.setLastPartial("");
                                                            SwingUtilities.invokeLater(() -> {
                                                                addHistory(laughOut);
                                                                window.setTitle(UiText.t("ui.title.rec"));
                                                            });
                                                        }
                                                        laughPartialCount = 0;
                                                        lastLaughPartialMs = 0;
                                                        buffer.reset();
                                                        partialBuf.reset();
                                                        preRoll.reset();
                                                        isSpeaking = false;
                                                        silenceFrames = 0;
                                                        forceFinalizePending = false;
                                                        firstPartialSent = false;
                                                        speechStartTime = 0;
                                                        lastSpeechEndTime = 0;
                                                        incompleteHoldUntilMs = 0L;
                                                        MobMateWhisp.setLastPartial("");
                                                        vad.reset();
                                                        laughDet.reset();

                                                        continue;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else if (isSpeaking) {
                                    buffer.write(data, 0, n);
                                }

                                if (isSpeaking && !forceFinalizePending) {
                                    if (speechStartTime > 0 && (now - speechStartTime > FORCE_FINAL_MS)) {
                                        forceFinalizePending = true;
                                        Config.logDebug("★タイムアウト設定 - 強制Final準備");
                                    }
                                    if (vad instanceof ImprovedVAD &&
                                            ((ImprovedVAD) vad).shouldForceFinalize(buffer.size(), silenceFrames, SILENCE_FRAMES_FOR_FINAL)) {
                                        forceFinalizePending = true;
                                        Config.logDebug("★バッファ上限 - 強制Final準備");
                                    }
                                }
                                // ★ low gain mic + short utterance 即時speak救済
                                if (!isSpeaking &&
                                        vad.getNoiseProfile().isLowGainMic &&
                                        silenceFrames >= 2) {

                                    String p = MobMateWhisp.getLastPartial();

                                    if (shouldInstantSpeakShortPartialForLowGain(p)) {
                                        Config.logDebug("★Instant short speak (low gain): " + p);

                                        // 状態リセット
                                        MobMateWhisp.setLastPartial("");
                                        silenceFrames = 0;
                                        forceFinalizePending = false;
                                        incompleteHoldUntilMs = 0L;

                                        handleFinalText(p, action, true);
                                    }
                                }
                                if (!alt && isSpeaking) {
                                    String cached = lastPartialResult.get();
                                    if (!alt && vad.shouldFinalizeEarlyByText(cached)) {
                                        if (buffer.size() >= MIN_AUDIO_DATA_LENGTH / 2) {
                                            forceFinalizePending = true;
                                            silenceFrames = SILENCE_FRAMES_FOR_FINAL;
                                        }
                                    }
                                }
                                // ===== Final =====
                                if (vad.getNoiseProfile().isLowGainMic) {
                                    INCOMPLETE_GRACE_MS = 220;
                                }
                                if (isSpeaking && !isProcessingFinal.get() &&
                                        (silenceFrames >= SILENCE_FRAMES_FOR_FINAL || forceFinalizePending)) {

                                    long nowMs = System.currentTimeMillis();
                                    String latestText = lastPartialResult.get();

                                    // --- incomplete suffix grace ---
                                    if (vad.endsWithIncompleteSuffix(latestText)) {
                                        if (incompleteHoldUntilMs == 0L) {
                                            incompleteHoldUntilMs = nowMs + INCOMPLETE_GRACE_MS;
                                            Config.logDebug("★Incomplete suffix detected, waiting +200ms");
                                            continue;
                                        }
                                        if (nowMs < incompleteHoldUntilMs) {
                                            continue;
                                        }
                                        incompleteHoldUntilMs = 0L;
                                    }

                                    int minFinalBytes = vad.getNoiseProfile().isLowGainMic
                                            ? 16000        // 約.5秒
                                            : MIN_AUDIO_DATA_LENGTH;
                                    boolean meetsMinBytes = buffer.size() >= minFinalBytes;

                                    // ★救済: short-final を Partial 文字列から起こす
                                    boolean enableRescue = Config.getBool("vad.final_rescue_from_partial", true);
                                    boolean hasPartialText = (latestText != null && !latestText.trim().isEmpty());
                                    boolean doRescueFromPartial = enableRescue && !meetsMinBytes && hasPartialText;

                                    // ★BGM判定を強化（BGMで始まるテキストは完全除外）
                                    if (doRescueFromPartial) {
                                        String t = latestText.trim().toLowerCase();
                                        // ★「BGM」「bgm」で始まる or 全体が「BGM」のみ → 救済しない
                                        if (t.startsWith("bgm") || t.equals("bgm、") || t.equals("bgm,")) {
                                            doRescueFromPartial = false;
                                            Config.logDebug("★BGM text blocked from rescue: " + latestText);
                                        }
                                    }

                                    boolean shouldSendFinal = meetsMinBytes || doRescueFromPartial;

                                    if (shouldSendFinal && nowMs - lastFinalMs >= FINAL_COOLDOWN_MS) {

                                        lastFinalExecutionTime.set(nowMs);
                                        isProcessingFinal.set(true);
                                        if (w != null) w.finalPriorityMode = true; // ★追加

                                        final boolean useRescueText = doRescueFromPartial;
                                        final String rescueText = useRescueText ? latestText.trim() : null;

                                        // ★救済テキストもBGMチェック
                                        if (useRescueText) {
                                            String rt = rescueText.toLowerCase();
                                            if (rt.startsWith("bgm") || rt.equals("bgm、") || rt.equals("bgm,")) {
                                                Config.logDebug("★Final rescue blocked (BGM): " + rescueText);
                                                isProcessingFinal.set(false);
                                                buffer.reset();
                                                partialBuf.reset();
                                                isSpeaking = false;
                                                silenceFrames = 0;
                                                forceFinalizePending = false;
                                                lastFinalMs = nowMs;
                                                speechStartTime = 0;
                                                lastSpeechEndTime = 0;
                                                incompleteHoldUntilMs = 0L;
                                                vad.reset();
                                                MobMateWhisp.setLastPartial("");
                                                SwingUtilities.invokeLater(() -> {
                                                    window.setTitle(UiText.t("ui.main.ready"));
                                                    MobMateWhisp.this.window.setIconImage(imageInactive);
                                                });
                                                continue;
                                            }
                                            MobMateWhisp.setLastPartial("");
                                        }

                                        final byte[] finalChunk = buffer.toByteArray();
                                        // ---- 状態リセット ----
                                        buffer.reset();
                                        partialBuf.reset();
                                        isSpeaking = false;
                                        silenceFrames = 0;
                                        forceFinalizePending = false;
                                        lastFinalMs = nowMs;
                                        speechStartTime = 0;
                                        lastSpeechEndTime = 0;
                                        incompleteHoldUntilMs = 0L;
                                        vad.reset();
                                        MobMateWhisp.setLastPartial("");

                                        executorService.submit(() -> {
                                            try {
                                                if (useRescueText) {
                                                    Config.logDebug("★Final rescue from partial: " + rescueText);
                                                    handleFinalText(rescueText, action, true);
                                                } else {
                                                    final long uttSeq = currentUtteranceSeq;

                                                    // 話者ゲート（speaker.enabled時のみ）
                                                    boolean speakerOk = true;
                                                    if (prefs.getBoolean("speaker.enabled", false)
                                                            && speakerProfile.isReady()) {
                                                        speakerOk = speakerProfile.isMatchingSpeaker(finalChunk);
                                                        if (!speakerOk) {
                                                            Config.logDebug("★Speaker REJECTED bytes=" + finalChunk.length);
                                                        }
                                                        updateSpeakerStatus();
                                                    }
                                                    // ★v1.5.0: Moonshine用にgate結果をキャッシュ（onCompletedが参照）
                                                    if (isEngineMoonshine()) {
                                                        moonshineLastGateOk = speakerOk;
                                                    }
                                                    // ★v1.5.0: Moonshineモードはspeaker gate外でmic_debugを書く
                                                    // （COMPLETEはonCompletedで既にspeakされており、gateはWAVの記録に無関係）
                                                    if (isEngineMoonshine() && micDump && finalChunk.length > 0) {
                                                        try {
                                                            int idx = MIC_DUMP_SEQ.getAndIncrement() % MIC_DUMP_ROTATE;
                                                            File out = new File("mic_debug_" + idx + ".wav");
                                                            writePcm16leToWav(finalChunk, audioFormat, out);
                                                            Config.logDebug("★MIC: wrote " + out.getAbsolutePath()
                                                                    + " bytes=" + finalChunk.length + " (moonshine-utterance)");
                                                        } catch (Exception ex) {
                                                            Config.logDebug("★MIC: per-utterance dump failed: " + ex);
                                                        }
                                                    }

                                                    if (speakerOk) {
                                                        Config.logDebug("★Final send");
                                                        transcribe(finalChunk, action, true, uttSeq);

                                                        // ★話者プロファイル微調整＆定期保存
                                                        if (prefs.getBoolean("speaker.enabled", false)
                                                                && speakerProfile.isReady()) {
                                                            boolean shouldSave = speakerProfile.refineSample(finalChunk);
                                                            if (shouldSave) {
                                                                speakerProfile.saveToFile(new File("speaker_profile.dat"));
                                                            }
                                                        }

                                                        // ★Mic debug wav (既存処理そのまま)
                                                        if (!isEngineMoonshine() && micDump && finalChunk.length > 0) {
                                                            try {
                                                                int idx = MIC_DUMP_SEQ.getAndIncrement() % MIC_DUMP_ROTATE;
                                                                File out = new File("mic_debug_" + idx + ".wav");
                                                                writePcm16leToWav(finalChunk, audioFormat, out);
                                                                Config.logDebug("★MIC: wrote " + out.getAbsolutePath()
                                                                        + " bytes=" + finalChunk.length + " (per-utterance)");
                                                            } catch (Exception ex) {
                                                                Config.logDebug("★MIC: per-utterance dump failed: " + ex);
                                                            }
                                                        }
                                                    } else {
                                                        // 話者不一致 → transcribeスキップ
                                                        Config.logDebug("★Speaker gate: utterance blocked (not matching speaker)");
                                                        SwingUtilities.invokeLater(() ->
                                                                window.setTitle(UiText.t("ui.title.rec")));
                                                    }
                                                }
                                            } catch (Exception ex) {
                                                Config.logError("Final error", ex);
                                            } finally {
                                                isProcessingFinal.set(false);
                                                if (w != null) w.finalPriorityMode = false; // ★追加
                                                SwingUtilities.invokeLater(() -> window.setTitle(UiText.t("ui.title.rec")));
                                            }
                                        });
                                        continue;
                                    }

                                    // ★Final条件未満 → BGMっぽいものは即捨て
                                    if (!meetsMinBytes && !doRescueFromPartial) {
                                        // ★partial が BGM で始まっていたら即リセット
                                        String cached = lastPartialResult.get();
                                        if (cached != null && !cached.trim().isEmpty()) {
                                            String cl = cached.trim().toLowerCase();
                                            if (cl.startsWith("bgm") || cl.equals("bgm、") || cl.equals("bgm,")) {
                                                Config.logDebug("★BGM partial detected, immediate reset: " + cached);
                                                buffer.reset();
                                                partialBuf.reset();
                                                isSpeaking = false;
                                                silenceFrames = 0;
                                                forceFinalizePending = false;
                                                speechStartTime = 0;
                                                lastSpeechEndTime = 0;
                                                incompleteHoldUntilMs = 0L;
                                                vad.reset();
                                                MobMateWhisp.setLastPartial("");
                                                SwingUtilities.invokeLater(() -> {
                                                    window.setTitle(UiText.t("ui.title.idle"));
                                                    MobMateWhisp.this.window.setIconImage(imageInactive);
                                                });
                                                continue;
                                            }
                                        }

                                        Config.logDebug("★Final dropped (too short, no partial): buf=" + buffer.size());
                                        buffer.reset();
                                        partialBuf.reset();
                                        isSpeaking = false;
                                        silenceFrames = 0;
                                        forceFinalizePending = false;
                                        speechStartTime = 0;
                                        lastSpeechEndTime = 0;
                                        incompleteHoldUntilMs = 0L;
                                        vad.reset();
                                        MobMateWhisp.setLastPartial("");
                                        SwingUtilities.invokeLater(() -> {
                                            window.setTitle(UiText.t("ui.title.idle"));
                                            MobMateWhisp.this.window.setIconImage(imageInactive);
                                        });
                                        continue;
                                    }
                                }
                                frameCount++;
                                Thread.sleep(5);
                            }
                        } else {
                            while (isRecording()) {
                                int numBytesRead = targetDataLine.read(data, 0, data.length);
                                if (numBytesRead > 0) {
                                    byteArrayOutputStream.write(data, 0, numBytesRead);
                                }
                            }
                        }
                    } catch (Exception ex) {
                        Config.log("startRecording submit failed: " + ex);
                        setRecording(false);
                    }

                } catch (LineUnavailableException e) {
                    Config.logDebug("Audio input device not available (used by an other process?)");
                } catch (Exception ignored) {
                } finally {
                    isStartingRecording.set(false);

                    try {
                        if (targetDataLine != null) {
                            targetDataLine.stop();
                        }
                    } catch (Exception ignore) {
                    }
                    try {
                        if (targetDataLine != null) {
                            targetDataLine.close();
                        }
                    } catch (Exception ignore) {
                    }

                    SwingUtilities.invokeLater(() -> {
                        window.setTitle(UiText.t("ui.title.idle"));
                        MobMateWhisp.this.window.setIconImage(imageInactive);
                    });
                    MobMateWhisp.setLastPartial("");

                    setRecording(false);
                    setTranscribing(false);
                }
            });

        } catch (java.util.concurrent.RejectedExecutionException rex) {
            // executor が死んでる/詰んでる時の保険
            isStartingRecording.set(false);
            setRecording(false);
            SwingUtilities.invokeLater(() -> {
                button.setEnabled(true);
                button.setText(UiText.t("ui.main.start"));
                window.setTitle(UiText.t("ui.title.idle"));
            });
            JOptionPane.showMessageDialog(null, "Audio thread is not available: " + rex.getMessage());
        } catch (Exception e) {
            isStartingRecording.set(false);
            setRecording(false);
            SwingUtilities.invokeLater(() -> {
                button.setEnabled(true);
                button.setText(UiText.t("ui.main.start"));
                window.setTitle(UiText.t("ui.title.idle"));
            });
            JOptionPane.showMessageDialog(null, "Error starting recording: " + e.getMessage());
        }
    }
    private void probeMicOnce(TargetDataLine line) {
        try {
            byte[] b = new byte[4096];
            long end = System.currentTimeMillis() + 200;
            int maxAbs = 0;
            int sumAbs = 0;
            int cnt = 0;

            while (System.currentTimeMillis() < end) {
                int n = line.read(b, 0, b.length);
                if (n <= 0) continue;
                for (int i = 0; i + 1 < n; i += 2) {
                    int s = (short)((b[i+1] << 8) | (b[i] & 0xFF));
                    int a = Math.abs(s);
                    if (a > maxAbs) maxAbs = a;
                    sumAbs += a;
                    cnt++;
                }
            }
            int avgAbs = (cnt == 0) ? 0 : (sumAbs / cnt);
            Config.logDebug("★Mic probe 200ms: maxAbs=" + maxAbs + " avgAbs=" + avgAbs + " cnt=" + cnt
                    + " running=" + line.isRunning() + " active=" + line.isActive());
        } catch (Throwable t) {
            Config.logDebug("★Mic probe failed: " + t);
        }
    }
    private static void applyPcmGain16leInPlace(byte[] pcm, int len, float gain) {
        for (int i = 0; i + 1 < len; i += 2) {
            int sample = (short) (((pcm[i + 1] & 0xFF) << 8) | (pcm[i] & 0xFF));
            int amplified = Math.round(sample * gain);
            if (amplified > 32767) amplified = 32767;
            else if (amplified < -32768) amplified = -32768;
            pcm[i]     = (byte) (amplified & 0xFF);
            pcm[i + 1] = (byte) ((amplified >> 8) & 0xFF);
        }
    }
    // 16bit LE PCM → float32 変換（Moonshine用）
    private static float[] pcm16leToFloat(byte[] pcm, int len) {
        int samples = len / 2;
        float[] out = new float[samples];
        for (int i = 0; i < samples; i++) {
            short s = (short)(((pcm[i*2+1] & 0xFF) << 8) | (pcm[i*2] & 0xFF));
            out[i] = s / 32768.0f;
        }
        return out;
    }

    // ★ short utterance 用の強制Final
    private void forceFinal(String text, Action action) {
        if (text == null || text.isBlank()) return;
        String s = text.trim();
        // NGワードは無視
        if (LocalWhisperCPP.isIgnoredStatic(s)) {
            Config.logDebug("★forceFinal ignored: " + s);
            return;
        }
        Config.logDebug("★forceFinal: " + s);
        // partial完全クリア
        MobMateWhisp.setLastPartial("");
        // 状態遷移
        setTranscribing(false);
        // 履歴
        SwingUtilities.invokeLater(() -> {
            addHistory(s);
        });
        // 発声（Hearing経路だけ抑制）
        if (action != Action.NOTHING_NO_SPEAK) {
            CompletableFuture.runAsync(() -> {
                speak(s);
                Config.appendOutTts(s);
            });
        } else {
            Config.logDebug("★forceFinal speak skipped (hearing): " + s);
        }
    }
    public static String getLastPartial() {
        return lastPartialResult.get();
    }
    public static void setLastPartial(String s) {
        lastPartialResult.set(s == null ? "" : s);
    }
    private static byte[] tailBytes(byte[] src, int tailBytes) {
        if (src == null) return new byte[0];
        if (src.length <= tailBytes) return src;
        int start = src.length - tailBytes;
        return Arrays.copyOfRange(src, start, src.length);
    }
    private volatile boolean partialInFlight = false;
    private void onPartialResultArrived(String p) {
        partialInFlight = false;

        if (p != null && !p.isBlank()) {
            setTranscribing(true);
            final String show = p;
            SwingUtilities.invokeLater(() -> {
                window.setTitle("[TRANS]:" + show);
                MobMateWhisp.this.window.setIconImage(imageTranscribing);
            });
        } else {
            SwingUtilities.invokeLater(() -> window.setTitle(UiText.t("ui.title.rec")));
        }
    }
    private void kickPartialTranscribe(ByteArrayOutputStream buffer) {
        if (isEngineMoonshine()) return;  // ★v1.5.0: Moonshineは自前poll
        if (isProcessingFinal.get()) return;
        if (!isProcessingPartial.compareAndSet(false, true)) return;
        if (lowGpuMode && isProcessingFinal.get()) { return; }
        byte[] all = buffer.toByteArray();
        boolean lowGain = vad.getNoiseProfile().isLowGainMic;
        int minPartialBytes = lowGain
                ? Math.max(6400, Config.getInt("vad.min_partial_bytes.low", 3200))  // ★12000→9600（約0.6秒）
                : Math.max(12000, Config.getInt("vad.min_partial_bytes", 12000));
        if (all.length < minPartialBytes) {
            isProcessingPartial.set(false);
            return;
        }
        int tail = PARTIAL_TAIL_BYTES;
        if (lowGpuMode) {
            tail = PARTIAL_TAIL_BYTES / 2;
        }
        final byte[] chunk = tailBytes(all, tail);
        executorService.submit(() -> {
            try {
                String p = MobMateWhisp.getLastPartial();
                onPartialResultArrived(p);
                transcribe(chunk, Action.NOTHING, false);
            } catch (Exception ex) {
                Config.logError("Partial error", ex);
            } finally {
                isProcessingPartial.set(false);
            }
        });
    }
    // === Final確定の共通処理（rescueでも必ずここを通す） ===
    private String lastSpeakStr = "";
    private long lastSpeakMs = 0;
    private void handleFinalText(String finalStr, Action action, boolean flgRescue) {
        if (finalStr == null) return;
        String s = finalStr.replace('\n',' ').replace('\r',' ').replace('\t',' ').trim();
        if (s.isEmpty()) return;

        // ★繰り返しスパム検出（問答無用で削除）
        if (isRepeatingSpam(s)) {
            Config.logDebug("★Final skip (repeating spam): " + (s.length() > 100 ? s.substring(0, 100) + "..." : s));
            return;
        }

        // ★BGMチェック
        String lower = s.toLowerCase();
        if (lower.startsWith("bgm") || lower.equals("bgm、") || lower.equals("bgm,")) {
            Config.logDebug("★handleFinalText: BGM blocked: " + s);
            return;
        }

        // ignore判定
        if (LocalWhisperCPP.isIgnoredStatic(s)) {
            Config.logDebug("★Final skip (NG): " + s);
            return;
        }

        // ★同一文の連続を捨てすぎない(内部二重発火だけ潰す)
        // ★★修正: 前回発話と同じ、または前回発話で始まる場合もブロック
        long now = System.currentTimeMillis();
        if (!lastSpeakStr.isEmpty() && (now - lastSpeakMs) <= 3000) {  // ★3秒以内
            // 完全一致 → 捨てる
            if (lastSpeakStr.equals(s)) {
                Config.logDebug("★Final skip (exact dup <3s): " + s);
                return;
            }
            // 前回が今回で始まる(前回の方が短い) → 前回を上書きして今回を処理
            if (lastSpeakStr.startsWith(s)) {
                Config.logDebug("★Final skip (shorter <3s): prev=[" + lastSpeakStr + "] new=[" + s + "]");
                return;
            }
            // 今回が前回で始まる(今回の方が長い) → 前回を上書きして今回を処理
            if (s.startsWith(lastSpeakStr)) {
                Config.logDebug("★Final update (longer <3s): prev=[" + lastSpeakStr + "] new=[" + s + "]");
                // ここでは return しない → 下で lastSpeakStr を更新して処理続行
            }
        }
        lastSpeakMs = now;
        lastSpeakStr = s;

        final String out = s;

        // アクション(タイプ/貼り付け)
        SwingUtilities.invokeLater(() -> {
            if (action == Action.TYPE_STRING) {
                try { new RobotTyper().typeString(out, 11); } catch (Exception ignore) {}
            } else if (action == Action.COPY_TO_CLIPBOARD_AND_PASTE) {
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                Transferable prev = null;
                try { prev = clipboard.getContents(null); } catch (Exception ignore) {}
                final Transferable prevF = prev;
                clipboard.setContents(new StringSelection(out), null);
                try {
                    Robot robot = new Robot();
                    robot.keyPress(KeyEvent.VK_CONTROL);
                    robot.keyPress(KeyEvent.VK_V);
                    Thread.sleep(20);
                    robot.keyRelease(KeyEvent.VK_V);
                    robot.keyRelease(KeyEvent.VK_CONTROL);
                } catch (Exception ignore) {}
                if (prevF != null) {
                    clipExecutor.schedule(() -> {
                        try { clipboard.setContents(prevF, null); } catch (Exception ignore) {}
                    }, 120, TimeUnit.MILLISECONDS);
                }
            }
        });
        // ★読み上げ(Hearing経路だけ抑制)
        if (action != Action.NOTHING_NO_SPEAK) {
            // 履歴
            SwingUtilities.invokeLater(() -> addHistory(out));

            CompletableFuture.runAsync(() -> {
                speak(out);
                Config.appendOutTts(out);
                Config.logDebug("speak(final): " + out);
            });
        } else {
            Config.logDebug("speak skipped (hearing): " + out);
        }
    }
    // ★繰り返しスパム検出: 同じフレーズが3回以上繰り返されてたらtrue
    private boolean isRepeatingSpam(String text) {
        if (text == null || text.length() < 20) return false;

        // 2-20文字のパターンが3回以上繰り返される = (.{2,20})\1{2,}
        try {
//            Pattern p = Pattern.compile("(.{2,20})\\1{2,}");
            Pattern p = Pattern.compile("(.{7,20})\\1{1,}"); // x2
            return p.matcher(text).find();
        } catch (Exception e) {
            return false;
        }
    }
    private boolean containsLaughTokenByConfig(String text) {
        if (text == null || text.isBlank()) return false;
        if (!Config.getBool("laughs.enable", true)) return false;

        String[] tokens = getLaughDetectTokens();
        if (tokens.length == 0) return false;

        String lower = text.toLowerCase(Locale.ROOT);
        for (String t : tokens) {
            if (t == null) continue;
            String tt = t.trim();
            if (tt.isEmpty()) continue;

            // 大文字小文字吸収（日本語はそのまま）
            if (text.contains(tt) || lower.contains(tt.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
    private boolean isUsablePartial(String p) {
        if (p == null) return false;
        String s = p.trim();
        if (s.isEmpty()) return false;

        if (w.isIgnored(s)) {
            return false;
        }

        return true;
    }
    // ★ partial救済（transcribeが二度と呼ばれないケース対策）
    private static final ThreadLocal<Long> TL_FINAL_UTTERANCE_SEQ =
            ThreadLocal.withInitial(() -> -1L);
    private volatile long lastPartialUpdateMs = 0L;
    private volatile long lastRescueSpeakMs = 0L;
    private static final long PARTIAL_RESCUE_STALE_MS = 90;      // partial更新が止まった判
    private static final long PARTIAL_RESCUE_MIN_SILENCE_MS = 40; // 無音が続いてる判定
    private static final long PARTIAL_RESCUE_COOLDOWN_MS = 400;    // 連発防止
    void maybeRescueSpeakFromPartial(Action action, long nowMs, long utteranceSeq) {
        // ★ADD: Final処理中はrescueしない（セーフティネット）
        if (isProcessingFinal.get()) return;

        String p = MobMateWhisp.getLastPartial();
        if (p == null) return;
        String s = p.trim();
        if (s.isEmpty()) return;

        // ★追加: 発話が切り替わったら古いpartialは捨てる
        if (utteranceSeq != currentUtteranceSeq) {
            Config.logDebug("★Partial rescue blocked (utterance changed): old=" + currentUtteranceSeq + " new=" + utteranceSeq);
            MobMateWhisp.setLastPartial(""); // 古いpartialをクリア
            return;
        }

        Config.logDebug("★Partial rescue -> maybeRescueSpeakFromPartial: " + s);

        // ★超即時 short-partial → lowGainMicのみ（normal gainでは暴発の原因になる）
        if (vad.getNoiseProfile().isLowGainMic
                && shouldInstantSpeakShortPartialForLowGain(s)
                && (nowMs - lastPartialUpdateMs) <= 80
                && (nowMs - lastRescueSpeakMs) >= 500) {
            lastRescueSpeakMs = nowMs;
            MobMateWhisp.setLastPartial("");
            Config.logDebug("★Instant short partial speak (lowGain): " + s);
            if (utteranceSeq > 0) {
                lastRescueUtteranceSeq = utteranceSeq;
                lastRescueAtMs = nowMs;
                lastRescueNorm = normForRescueDup(s);
            }
            handleFinalText(s, action, true);
            return;
        }

        // NG・BGM系は救済しない
        if (LocalWhisperCPP.isIgnoredStatic(s)) return;
        if (s.equalsIgnoreCase("BGM") || s.startsWith("BGM")) return;

        // 最低限:partial更新が止まっていて、かつ無音が続いている
        // ★短文（≤10文字）は900ms待ってfinalに任せる / 長文は従来通り90ms
        int cpLen = s.codePointCount(0, s.length());
        long staleThreshold = (cpLen <= 10) ? 900 : PARTIAL_RESCUE_STALE_MS;
        boolean stale = (nowMs - lastPartialUpdateMs) >= staleThreshold;
        boolean silent = (lastSpeechEndTime > 0) && ((nowMs - lastSpeechEndTime) >= PARTIAL_RESCUE_MIN_SILENCE_MS);
        boolean cooldownOk = (nowMs - lastRescueSpeakMs) >= PARTIAL_RESCUE_COOLDOWN_MS;

        if (!stale || !silent || !cooldownOk) return;

        // ここで確定発話
        lastRescueSpeakMs = nowMs;
        MobMateWhisp.setLastPartial("");

        Config.logDebug("★Partial rescue -> handleFinalText: " + s);

        if (utteranceSeq > 0) {
            lastRescueUtteranceSeq = utteranceSeq;
            lastRescueAtMs = nowMs;
            lastRescueNorm = normForRescueDup(s);
        }
        handleFinalText(s, action, true);
    }
    // Hearing(ループバック)経路用：Historyにも speak にも流さない
    public void instantFinalFromWhisperHearing(String text) {
        if (text == null) return;
        String s = text.trim();
        if (s.isEmpty()) return;

        // overlay用（HearingFrameが getLastPartial() 参照してる前提）
        MobMateWhisp.setLastPartial(s);

        Config.logDebug("★Instant FINAL(Hearing) -> handleFinalText(no-history/no-speak): " + s);
        handleFinalText(s, Action.NOTHING_NO_SPEAK, true);
    }
    // LocalWhisperCPP の「短文=即FINAL」から呼ばれるやつ
    public void instantFinalFromWhisper(String text, Action action) {
        if (text == null) return;
        String s = text.trim();
        if (s.isEmpty()) return;

        // NG/BGMは弾く
        if (LocalWhisperCPP.isIgnoredStatic(s)) return;
        String lower = s.toLowerCase();
        if (lower.startsWith("bgm") || lower.equals("bgm、") || lower.equals("bgm,")) return;

        long now = System.currentTimeMillis();

        // rescue条件に依存しないで、即確定ルートに入れる
        lastPartialUpdateMs = now;
        lastSpeechEndTime = now;          // 「いま無音に入った」扱い
        MobMateWhisp.setLastPartial("");  // 持ち越し防止

        Config.logDebug("★Instant FINAL -> handleFinalText: " + s);
        handleFinalText(s, action, true);
    }
    private boolean shouldInstantSpeakShortPartialForLowGain(String p) {
        if (p == null) return false;
        String s = p.trim();
        if (s.isEmpty()) return false;

        // NG・BGM系は除外
        if (LocalWhisperCPP.isIgnoredStatic(s)) return false;
        if (s.equalsIgnoreCase("BGM") || s.startsWith("BGM")) return false;

        // 短語・短文（日本語想定）
        if (s.length() <= 6) return true;              // ちょっと / こっち
        if (s.split("[ 　]").length <= 2) return true; // 単語2つまで

        return false;
    }
    private boolean isDuplicateFinalOfRescue(String finalStr, long uttSeq) {
        if (uttSeq <= 0) return false;
        if (uttSeq != lastRescueUtteranceSeq) return false;

        long dt = System.currentTimeMillis() - lastRescueAtMs;
        if (dt < 0 || dt > 12000) return false; // 念のため上限（12秒）

        String n = normForRescueDup(finalStr);
        return !n.isEmpty() && n.equals(lastRescueNorm);
    }
    private static String normForRescueDup(String s) {
        if (s == null) return "";
        String t = s.replace('\n',' ')
                .replace('\r',' ')
                .replace('\t',' ')
                .trim()
                .toLowerCase(Locale.ROOT);
        t = t.replaceAll("\\s+", " ");
        t = t.replaceAll("[。．\\.！!？?]+$", ""); // 末尾の句読点ゆれを潰す
        return t;
    }


    // ★ADD: PCM16LEが「ほぼ無音」か判定（軽量）
    private static boolean isMostlySilentPcm16le(byte[] pcm) {
        // 0.5秒(16000 bytes)程度を想定。軽くサンプリングする
        int step = 32; // 16bitなので2bytes、ここではサンプル間引き
        int maxAbs = 0;
        long sumAbs = 0;
        int cnt = 0;

        for (int i = 0; i + 1 < pcm.length; i += step) {
            int s = (short)((pcm[i+1] << 8) | (pcm[i] & 0xFF));
            int a = Math.abs(s);
            if (a > maxAbs) maxAbs = a;
            sumAbs += a;
            cnt++;
            if (maxAbs > 300) return false; // これ超えたら無音じゃない扱いで早期終了
        }
        int avgAbs = (cnt == 0) ? 0 : (int)(sumAbs / cnt);

        // ここは安全寄りの閾値（無音・強ノイキャン・ミュート時に当たりやすい）
        return maxAbs < 120 && avgAbs < 8;
    }
    public String transcribe(byte[] audioData, final Action action, boolean isEndOfCapture, long utteranceSeq) throws Exception {
        TL_FINAL_UTTERANCE_SEQ.set(utteranceSeq);
        try {
            return transcribe(audioData, action, isEndOfCapture);
        } finally {
            TL_FINAL_UTTERANCE_SEQ.remove();
        }
    }
    public String transcribe(byte[] audioData, final Action action, boolean isEndOfCapture) throws Exception {
        // ★v1.5.0: Moonshineモード時はWhisperを使わない
        if (isEngineMoonshine()) {
            return "";
        }
        // ★ADD: silent chunk short-circuit (partialの無駄撃ちを抑える)
        if (!isEndOfCapture && audioData != null && audioData.length > 0) {
            if (isMostlySilentPcm16le(audioData)) {
                Config.logDebug("★TRANSCRIBE SKIP (mostly silent) bytes=" + audioData.length);
                return "";
            }
        }

        // ★ADD: Final処理中のpartialは実行しない（レース防止）
        // kickPartialTranscribeの投入時点ではisProcessingFinal=falseでも、
        // executorで実行される頃にはFinalが始まっている可能性がある
        if (!isEndOfCapture && isProcessingFinal.get()) {
            Config.logDebug("★Partial transcribe aborted (final in progress)");
            return "";
        }

        String str = "";
        // ★追加ログ: 何秒の音声を投げてるか
        int bytes = (audioData == null) ? 0 : audioData.length;
        double sec = bytes / (16000.0 * 2.0);
        Config.logDebug("★TRANSCRIBE REQ end=" + isEndOfCapture + " bytes=" + bytes + " sec=" + String.format(java.util.Locale.ROOT, "%.3f", sec));
        // ★softRestart中は w=null → 安全にスキップ
        if (w == null) {
            Config.logDebug("★TRANSCRIBE SKIP: whisper not available (restarting?)");
            return "";
        }
        if (MobMateWhisp.this.remoteUrl == null) {

            // ★busy時の扱い：
            // - partial(end=false) はDROPしてOK（詰まり防止）
            // - final(end=true) は少し待ってでも通す（取りこぼし防止）
            if (!transcribeBusy.compareAndSet(false, true)) {
                if (!isEndOfCapture) {
                    Config.logDebug("★TRANSCRIBE BUSY -> DROP(partial) end=" + isEndOfCapture + " bytes=" + bytes);
                    return "";
                }
                // finalだけ待つ（最大10秒）
                long until = System.currentTimeMillis() + 10_000L;
                while (System.currentTimeMillis() < until && transcribeBusy.get()) {
                    try { Thread.sleep(10); } catch (InterruptedException ie) { break; }
                }
                if (!transcribeBusy.compareAndSet(false, true)) {
                    Config.logDebug("★TRANSCRIBE BUSY -> DROP(final timeout) end=" + isEndOfCapture + " bytes=" + bytes);
                    return "";
                }
            }

            long t0 = System.currentTimeMillis();
            try {
                setTranscribing(true);
                str = w.transcribeRaw(Objects.requireNonNull(audioData), action, MobMateWhisp.this, isEndOfCapture);
                long dt = System.currentTimeMillis() - t0;
                Config.logDebug("★TRANSCRIBE DONE ms=" + dt + " raw=" + (str == null ? "null" : ("len=" + str.length() + " text=" + str.replace("\n"," ").replace("\r"," "))));
                if (str == null) return "";
            } catch (Throwable t) {
                Config.logError("[Whisper][RUN] crashed ms=" + (System.currentTimeMillis()-t0), t);
                throw t;
            } finally {
                setTranscribing(false);
                transcribeBusy.set(false);
            }
        }


        str = str.replace('\n', ' ');
        str = str.replace('\r', ' ');
        str = str.replace('\t', ' ');
        str = str.trim();

        // ★BGMチェックを最優先（Empty判定前）
        if (str.toLowerCase().startsWith("bgm") ||
                str.equalsIgnoreCase("bgm、") ||
                str.equalsIgnoreCase("bgm,")) {
            Config.logDebug("★BGM detected, blocked: " + str);
            return "";
        }

        if (!isEndOfCapture) {
            if (!str.isEmpty()) {
                if (isUsablePartial(str)) {
                    MobMateWhisp.setLastPartial(str);
                    lastPartialUpdateMs = System.currentTimeMillis();
                    // ★追加: partial更新時に現在の発話IDを記録
                    // (maybeRescueSpeakFromPartialで古い発話のpartialを弾くため)
                    setTranscribing(true);  // ★ADD: Moonshine partial検出でtranscribing状態に
                    Config.logDebug("★Cached partial (uttSeq=" + currentUtteranceSeq + "): " + str);
                } else {
                    str = "";
                }
            }
        }

        // ★以下は Final 時のみ実行
        if (str.isEmpty()) {
            Config.logDebug("★Final empty, skipped");
            return "";
        }

        if (str.matches("^\\[.*\\]$")) {
            Config.logDebug("★Noise filtered (brackets): " + str);
            return "";
        }

        if (str.matches("^[A-Za-z0-9& ]+$")) {
            if (str.length() <= 2) {
                Config.logDebug("★Noise filtered (short alphanumeric): " + str);
                return "";
            }
        }

        final String finalStr = str;

        if (useAlternateLaugh && laughAlreadyHandled) {
            String lower = finalStr.toLowerCase();
            for (String token : getLaughDetectTokens()) {
                if (token.isEmpty()) continue;
                if (lower.contains(token.toLowerCase())) {
                    Config.logDebug("★Whisper laugh suppressed (alternate mode): " + finalStr);
                    return "";
                }
            }
        }
        if (isEndOfCapture) {
            if (LocalWhisperCPP.isIgnoredStatic(finalStr)) {
                Config.logDebug("★History skip (NG): " + finalStr);
                return "";
            }
            long uttSeq = TL_FINAL_UTTERANCE_SEQ.get();
            if (uttSeq > 0 && isDuplicateFinalOfRescue(finalStr, uttSeq)) {
                Config.logDebug("★Final speak suppressed (dup of partial rescue): " + finalStr);
            } else {
                instantFinalFromWhisper(finalStr, action);
            }
        } else {
            if (vad instanceof ImprovedVAD) {
                if (vad.getNoiseProfile().isLowGainMic) {
                    if (((ImprovedVAD) vad).shouldFinalizeEarlyByText(finalStr)) {
                        earlyFinalizeRequested = true;
                        earlyFinalizeUntilMs = System.currentTimeMillis() + 800;
                    }
                } else {
                    if (((ImprovedVAD) vad).shouldFinalizeEarlyByText(finalStr)) {
                        earlyFinalizeRequested = true;
                        earlyFinalizeUntilMs = System.currentTimeMillis() + EARLY_FINAL_WINDOW_MS;
                        Config.logDebug("★EarlyFinalize requested by text: " + finalStr);
                    }
                }
            }
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (action.equals(Action.TYPE_STRING)) {
                    try {
                        RobotTyper typer = new RobotTyper();
                        Config.logDebug("Typing : " + finalStr);
                        typer.typeString(finalStr, 11);
                    } catch (AWTException ignored) {
                    }
                } else if (action.equals(Action.COPY_TO_CLIPBOARD_AND_PASTE)) {
                    Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                    Transferable previous;
                    try {
                        previous = clipboard.getContents(null);
                    } catch (Exception e) {
                        previous = null;
                        try {
                            GlobalScreen.registerNativeHook();
                        } catch (NativeHookException ignored) {
                        }
                        Config.logDebug("Warning : cannot get previous clipboard content");
                    }
                    final Transferable toPaste = previous;
                    clipboard.setContents(new StringSelection(finalStr), null);
                    try {
                        Robot robot = new Robot();
                        Config.logDebug("Pasting : " + finalStr);
                        robot.keyPress(KeyEvent.VK_CONTROL);
                        robot.keyPress(KeyEvent.VK_V);
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException ignored) {
                        }
                        robot.keyRelease(KeyEvent.VK_V);
                        robot.keyRelease(KeyEvent.VK_CONTROL);
                        Config.logDebug("Pasting : " + finalStr + " DONE");
                    } catch (AWTException ignored) {
                    }
                    if (toPaste != null) {
                        clipExecutor.schedule(() -> {
                            try {
                                Config.logDebug("Restoring previous clipboard content");
                                clipboard.setContents(toPaste, null);
                            } catch (Exception ignore) {}
                        }, 120, TimeUnit.MILLISECONDS);
                    }
                }
                if (isEndOfCapture) {
                    instantFinalFromWhisper(finalStr, action);
                }
            }
        });

        SwingUtilities.invokeLater(() -> {
            window.setTitle(cpugpumode);
            // ★エンジン情報をステータスバーにも表示
            if (engineLabel != null) {
                engineLabel.setText(cpugpumode);
            }
        });

        setTranscribing(false);
        return finalStr;
    }

    protected synchronized void setTranscribing(boolean b) {
        this.transcribing = b;
        updateIcon();
    }
    public synchronized boolean isTranscribing() {
        return this.transcribing;
    }
    public synchronized boolean isRecording() {
        return this.recording;
    }
    public synchronized void setRecording(boolean b) {
        this.recording = b;
        // ★録音開始時に時刻を記録
        if (b) {
            recordingStartTime2 = System.currentTimeMillis();
        } else {
            recordingStartTime2 = 0;
        }
        updateIcon();
    }
    private void updateIcon() {
        SwingUtilities.invokeLater(() -> {
            boolean rec = isRecording();
            boolean tr  = isTranscribing();

            // ★ここが本命：Start押下でdisableしたままにならないよう復帰させる
            //   キャリブ中(!isCalibrationComplete) と開始準備中(isStartingRecording)だけ無効化
            boolean enableMainButton = isCalibrationComplete && !isStartingRecording.get();
            if (MobMateWhisp.this.button != null) {
                MobMateWhisp.this.button.setEnabled(enableMainButton);
            }

            if (MobMateWhisp.this.window != null) {
                if (rec) {
                    MobMateWhisp.this.button.setText(UiText.t("ui.main.stop"));
                    LocalWhisperCPP.markInitialPromptDirty();

                } else {
                    MobMateWhisp.this.button.setText(UiText.t("ui.main.start"));
                    LocalWhisperCPP.markInitialPromptDirty();
                }

                if (tr) {
                    MobMateWhisp.this.label.setText(UiText.t("ui.main.transcribing"));
                } else if (rec) {
                    MobMateWhisp.this.label.setText(UiText.t("ui.main.recording"));
                } else {
                    MobMateWhisp.this.label.setText(UiText.t("ui.main.ready"));
                }

                // ★ステータスバーも更新
                if (statusLabel != null) {
                    if (tr) {
                        statusLabel.setText("◉ TRANS");
                        statusLabel.setForeground(new Color(255, 152, 0)); // オレンジ
                    } else if (rec) {
                        statusLabel.setText("● [REC]");
                        statusLabel.setForeground(new Color(220, 53, 69)); // 赤
                    } else {
                        statusLabel.setText("▪ Ready");
                        statusLabel.setForeground(UIManager.getColor("Label.foreground")); // 通常色
                    }
                }

                Image img = tr ? MobMateWhisp.this.imageTranscribing
                        : (rec ? MobMateWhisp.this.imageRecording
                        : MobMateWhisp.this.imageInactive);
                MobMateWhisp.this.window.setIconImage(img);
            }

            if (MobMateWhisp.this.trayIcon != null) {
                Image img = tr ? MobMateWhisp.this.imageTranscribing
                        : (rec ? MobMateWhisp.this.imageRecording
                        : MobMateWhisp.this.imageInactive);
                MobMateWhisp.this.trayIcon.setImage(img);
            }

            if (MobMateWhisp.this.historyFrame != null) {
                MobMateWhisp.this.historyFrame.updateIcon(
                        rec, tr,
                        MobMateWhisp.this.imageRecording,
                        MobMateWhisp.this.imageTranscribing,
                        MobMateWhisp.this.imageInactive
                );
            }
        });
    }

    // ===== [ADD] helper: PCM16LE -> WAV =====
    private static void writePcm16leToWav(byte[] pcm, AudioFormat format, File outFile) throws Exception {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(pcm);
             AudioInputStream ais = new AudioInputStream(bais, format, pcm.length / format.getFrameSize())) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, outFile);
        }
    }
    private String processRemote(File out, Action action) throws IOException {
        long t1 = System.currentTimeMillis();
        String string = new RemoteWhisperCPP(this.remoteUrl).transcribe(out, 0.0, 0.01);
        long t2 = System.currentTimeMillis();
        Config.logDebug("Response from remote whisper.cpp (" + (t2 - t1) + " ms): " + string);
        return string.trim();
    }
    private void stopRecording() {
        if (!this.isRecording()) {
            return;
        }
        setRecording(false);

        // ★話者プロファイルを保存（学習結果の永続化）
        if (prefs.getBoolean("speaker.enabled", false) && speakerProfile.isReady()) {
            speakerProfile.saveToFile(new File("speaker_profile.dat"));
            Config.logDebug("★Speaker profile saved on stop (accepted="
                    + speakerProfile.getTotalAccepted() + ")");
        }
        updateSpeakerStatus();
    }
    public void setModelPref(String name) {
        prefs.put("model", name);
        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            JOptionPane.showMessageDialog(null, "Cannot save preferences");
        }
    }
    public void addHistoryListener(ChangeListener l) {
        this.historyListeners.add(l);
    }
    public void removeHistoryListener(ChangeListener l) {
        this.historyListeners.remove(l);
    }
    public void clearHistory() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalAccessError("Must be called from EDT");
        }
        this.history.clear();
        fireHistoryChanged();
    }
    public void fireHistoryChanged() {
        for (ChangeListener l : this.historyListeners) {
            l.stateChanged(new ChangeEvent(this));
        }
    }
    public List<String> getHistory() {
        return this.history;
    }
    public void addHistory(String s) {
        if (s == null) return;
        s = s.trim();
        if (s.isEmpty()) return;
        this.history.add(s);
        // ★古い順に捨てる
        int over = this.history.size() - HistoryFrame.HISTORY_MAX_LINES;
        if (over > 0) {
            this.history.subList(0, over).clear();
        }
        fireHistoryChanged();
    }

    public static void main(String[] args) {
        SteamHelper.init();
        System.setProperty("jna.encoding", "UTF-8");
        ensureInitialConfig();

        Thread keepAlive = new Thread(() -> {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException ignored) {
            }
        });
        keepAlive.setDaemon(true);
        keepAlive.start();
        SwingUtilities.invokeLater(() -> {
            // ===== FlatLaf Look&Feel =====
            Preferences p = Preferences.userRoot().node("MobMateWhispTalk");
            prefs = p;
            try {
                // 設定からテーマを読み込み（デフォルト：ダーク）
                boolean isDark = p.getBoolean("ui.theme.dark", true);
                if (isDark) {
                    com.formdev.flatlaf.FlatDarkLaf.setup();
                } else {
                    com.formdev.flatlaf.FlatLightLaf.setup();
                }
            } catch (Exception e) {
                // フォールバック：システムデフォルト
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {
                }
            }
            FontBootstrap.registerBundledFonts();
            String suffix = p.get("ui.language", "en");
            UiFontApplier.applyDefaultUIFontBySuffix(suffix);
            if (!new File("_radiocmd.txt").exists()) {
                copyPreset("libs/preset/_radiocmd_" + suffix + ".txt", "_radiocmd.txt");
            }
            loadRadioCmdFileToPrefs(p, new File("_radiocmd.txt"));

            try {
                boolean debug = false;
                String url = null;
                boolean forceOpenWindow = false;
                for (int i = 0; i < args.length; i++) {
                    final String arg = args[i];
                    if (!arg.startsWith("-D")) {
                        if (arg.startsWith("http")) {
                            url = arg;
                        } else if (arg.equals("--window")) {
                            forceOpenWindow = true;
                        } else if (arg.equals("--debug")) {
                            debug = true;
                        }
                    }
                }
                final MobMateWhisp r = new MobMateWhisp(url);
                r.debug = debug;
                r.autoStartVoiceVox();
                r.startPsServer();

                String engine = prefs.get("tts.engine", "auto").toLowerCase(Locale.ROOT);
                if ("voiceger_tts".equals(engine)) {
                    VoicegerManager.ensureRunningIfEnabledAsync(MobMateWhisp.prefs);
                }
                if ("voiceger_vc".equals(engine) || "voiceger".equals(engine)) {
                    VoicegerManager.ensureRunningIfEnabledAsync(MobMateWhisp.prefs);
                }
                // Exit時にVoiceger APIを止める（System.exit / ウィンドウ終了どっちでも効く）
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try { VoicegerManager.stopIfRunning(MobMateWhisp.prefs); } catch (Throwable ignore) {}
                    try { if (hearingFrame != null) hearingFrame.shutdownForExit(); } catch (Throwable ignore) {}
                    // ★Whisperネイティブメモリ解放（large-v3-q8_0で3GB+のリーク防止）
//                    try { LocalWhisperCPP.freeAllContexts(); } catch (Throwable ignore) {}
                }, "voiceger-shutdown"));
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    SteamHelper.shutdown();
                    executorService.shutdownNow();
                    audioService.shutdownNow();
                    shutdownMoonshine();
                }));



                boolean openWindow = prefs.getBoolean("open-window", true);
                if (forceOpenWindow) {
                    openWindow = true;
                }
                Config.log("[BOOT] openWindow(pref)=" + openWindow + " force=" + forceOpenWindow);

                boolean trayOk = false;
                try {
                    Config.log("[BOOT] createTrayIcon...");
                    r.createTrayIcon();
                    trayOk = true;
                    Config.log("[BOOT] createTrayIcon OK");
                } catch (Throwable t) {
                    // ★printStackTraceだと_log.txtに出ないので、必ずログへ
                    Config.logError("[BOOT] createTrayIcon FAILED: " + t, t);
                }
                if (!trayOk) openWindow = true;
                if (openWindow) {
                    try {
                        Config.log("[BOOT] openWindow...");
                        r.openWindow();
                        Config.log("[BOOT] openWindow OK");
                    } catch (Throwable t) {
                        Config.logError("[BOOT] openWindow FAILED: " + t, t);
                        JOptionPane.showMessageDialog(null, "Error :\n" + t.getMessage());
                    }
                } else {
                    Config.log("[BOOT] window skipped (tray only)");
                }
            } catch (Throwable e) {
                JOptionPane.showMessageDialog(null, "Error :\n" + e.getMessage());
            }
        });
    }


    // 共有されるのでvolatile推奨
    private volatile int currentInputLevel = 0;
    private volatile double currentInputDb = -60.0f;
    private volatile float autoGainMultiplier = 1.0f;
    private volatile boolean autoGainEnabled;
//    private JLabel gainLabel;
    private GainMeter gainMeter;
    private volatile long lastSpeechAtMs = 0;
    private volatile boolean lastVadSpeech = false;
    private Timer meterUpdateTimer;
    private final AtomicInteger meterSkipCounter = new AtomicInteger(0);
    private static final int METER_UPDATE_INTERVAL = 3; // 3フレームに1回
    private void openWindow() {
        this.window = new JFrame("MobMateWhispTalk");
        this.window.setUndecorated(true); // ★タイトルバーを消す
        this.window.setIconImage(this.imageInactive);
        this.window.setFocusable(true);
        this.window.setFocusableWindowState(true);
        SwingUtilities.updateComponentTreeUI(this.window);

        // ★ウィンドウドラッグ移動を有効化
        enableWindowDragging(this.window);

        // ===== メインコンテンツパネル（コンパクト化） =====
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.X_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6)); // 上下左右の余白を削減

        MobMateWhisp.this.window.setTitle(cpugpumode);

        // ===== カスタムタイトルバー（ボタン類） =====
        JPanel titleBar = new JPanel();
        titleBar.setLayout(new BoxLayout(titleBar, BoxLayout.X_AXIS));
        titleBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)
        ));

        // 左側：レベルメーター（広く取る）
        gainMeter = new GainMeter();
        gainMeter.setFont(titleBar.getFont());
        titleBar.add(gainMeter);
        titleBar.add(Box.createHorizontalStrut(8));

        // 中央：ボタン類
        titleBar.add(this.button);

        final JButton historyButton = new JButton(UiText.t("ui.main.history"));
        titleBar.add(Box.createHorizontalStrut(4));
        titleBar.add(historyButton);

        final JButton prefButton = new JButton(UiText.t("ui.main.prefs"));
        titleBar.add(Box.createHorizontalStrut(4));
        titleBar.add(prefButton);

        // 右側：×ボタン
        titleBar.add(Box.createHorizontalStrut(8));
        JButton closeButton = createCloseButton();
        titleBar.add(closeButton);

        // ===== Ctrl+Shift+R でソフトリスタート =====
        window.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ctrl shift R"), "softRestart");
        window.getRootPane().getActionMap().put("softRestart", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                softRestart();
            }
        });

        // ===== ステータスバー =====
        statusBar = createStatusBar();

        // ===== メインレイアウト =====
        JPanel contentPane = new JPanel();
        contentPane.setLayout(new BoxLayout(contentPane, BoxLayout.Y_AXIS));
        contentPane.add(Box.createVerticalStrut(6));
        contentPane.add(titleBar, BorderLayout.CENTER);
        contentPane.add(Box.createVerticalStrut(4));
        contentPane.add(statusBar, BorderLayout.SOUTH);

        this.window.setContentPane(contentPane);
        this.label.setText(UiText.t("ui.main.transcribing"));

        this.window.pack();
        this.window.setMinimumSize(
                new Dimension(510, this.window.getHeight())
        );
        this.label.setText(UiText.t("ui.main.stop"));
        this.window.setResizable(false);

        Rectangle fallback = new Rectangle(15, 15, this.window.getWidth(), this.window.getHeight());
        restoreBounds(this.window, "ui.main", fallback);
        installBoundsSaver(this.window, "ui.main");

        window.setVisible(true);
        // ===== WAVドラッグ&ドロップ テスト用 =====
        window.setTransferHandler(new javax.swing.TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            }
            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    @SuppressWarnings("unchecked")
                    java.util.List<File> files = (java.util.List<File>)
                            support.getTransferable().getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                    for (File f : files) {
                        if (!f.getName().toLowerCase().endsWith(".wav")) continue;
                        executorService.submit(() -> transcribeWavFile(f));
                    }
                    return true;
                } catch (Exception ex) {
                    Config.logError("[DnD] failed", ex);
                    return false;
                }
            }
        });

        this.window.toFront();
        this.window.requestFocus();

        final PopupMenu popup = createPopupMenu();
        prefButton.add(popup);
        this.button.addActionListener(e -> toggleRecordingFromUI());
        historyButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showHistory();
            }
        });
        prefButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                rebuildVoiceVoxSpeakerMenu(engVvMenu, engVv, engListener, prefs);
                popup.show((Component) e.getSource(), 0, 0);
            }
        });
        this.window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowActivated(WindowEvent e) {
                bringToFront(window);
            }
        });

        this.window.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        this.window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (isRecording()) {
                    stopRecording();
                }
                if (meterUpdateTimer != null) {
                    meterUpdateTimer.stop();
                }
                // ★ステータス更新タイマーも停止
                if (statusUpdateTimer != null) {
                    statusUpdateTimer.stop();
                }
                Config.mirrorAllToCloud();
                try {
                    if (hearingFrame != null) hearingFrame.shutdownForExit();
                } catch (Throwable ignore) {}
                System.exit(0);
            }
        });
        SwingUtilities.updateComponentTreeUI(this.window);
        this.window.invalidate();
        this.window.validate();
        this.window.repaint();

        // ★メーター更新タイマー開始
        startMeterUpdateTimer();

        // ★ステータスバー更新タイマー開始
        startStatusUpdateTimer();

        SwingUtilities.invokeLater(() -> {
            Timer timer = new Timer(1000, e -> {
                button.setEnabled(false);
                button.setText("[CALIBRATING]");
                window.setTitle(UiText.t("ui.calibrating"));
                primeVadByEmptyRecording();
            });
            timer.setRepeats(false);
            timer.start();
        });

        // === First launch wizard ===
//        prefs.putBoolean("wizard.never", false);
//        prefs.putBoolean("wizard.completed", false);
        SwingUtilities.invokeLater(() -> {
            //TODO 整理してから表示する
//            if (TipsDialog.shouldShow()) {
//                TipsDialog tips = new TipsDialog(window);
//                tips.setVisible(true);
//                TipsDialog.markShown();
//            }
            try {
                boolean completed = prefs.getBoolean("wizard.completed", false);
                boolean never = prefs.getBoolean("wizard.never", false);
                if (completed || never) return;

                Object[] opts = { UiText.t("wizard.show"), UiText.t("wizard.later"), UiText.t("wizard.never") };
                int res = JOptionPane.showOptionDialog(
                        window,
                        UiText.t("wizard.confirm.desc"),
                        UiText.t("wizard.confirm.title"),
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.INFORMATION_MESSAGE,
                        null,
                        opts,
                        opts[0]
                );

                if (res == 2) {
                    // never
                    prefs.putBoolean("wizard.never", true);
                    prefs.putBoolean("wizard.completed", true);
                    try { prefs.sync(); } catch (Exception ignore) {}
                    return;
                }
                if (res != 0) {
                    // later
                    return;
                }

                // ★ Wizard前後で device を比較して、変わってたら再キャリブレーション
                final String beforeIn = prefs.get("audio.device", "");
                final String beforeOut = prefs.get("audio.output.device", "");
                final String beforeEngine = prefs.get("recog.engine", "whisper"); // ★v1.5.0

                FirstLaunchWizard wizard = null;
                try {
                    wizard = new FirstLaunchWizard(window, MobMateWhisp.this);
                    wizard.setLocationRelativeTo(window);
                    wizard.setVisible(true); // modal想定
                } finally {
                    // ★×で閉じても、監視/タイマー/ラインを必ず止める
                    try {
                        if (wizard != null) wizard.stopAllWizardTests(); // ある実装前提（無ければ下の(2)を先に入れる）
                    } catch (Throwable ignore) {}
                }

                // ★ウィザード終了後、PopupMenuを再構築
                prefButton.removeAll();
                PopupMenu newPopup = createPopupMenu();
                prefButton.add(newPopup);
                // prefButtonのActionListenerも更新（既存のままだと古いpopupを参照）
                for (ActionListener al : prefButton.getActionListeners()) {
                    prefButton.removeActionListener(al);
                }
                PopupMenu finalNewPopup = newPopup;
                prefButton.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        rebuildVoiceVoxSpeakerMenu(engVvMenu, engVv, engListener, prefs);
                        finalNewPopup.show((Component) e.getSource(), 0, 0);
                    }
                });

                // ★ウィザード終了後、PopupMenuを再構築
                prefButton.removeAll();
                newPopup = createPopupMenu();
                prefButton.add(newPopup);
                // prefButtonのActionListenerも更新（既存のままだと古いpopupを参照）
                for (ActionListener al : prefButton.getActionListeners()) {
                    prefButton.removeActionListener(al);
                }
                PopupMenu finalNewPopup1 = newPopup;
                prefButton.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        rebuildVoiceVoxSpeakerMenu(engVvMenu, engVv, engListener, prefs);
                        finalNewPopup1.show((Component) e.getSource(), 0, 0);
                    }
                });

//                prefs.putBoolean("wizard.completed", true);
                try { prefs.sync(); } catch (Exception ignore) {}

                final String afterIn = prefs.get("audio.device", "");
                final String afterOut = prefs.get("audio.output.device", "");

                boolean inChanged = !java.util.Objects.equals(beforeIn, afterIn);
                boolean outChanged = !java.util.Objects.equals(beforeOut, afterOut);

                // ★v1.5.0: エンジンが変わっていたらsoftRestart
                final String afterEngine = prefs.get("recog.engine", "whisper");
                if (!java.util.Objects.equals(beforeEngine, afterEngine)) {
                    Config.log("★Wizard: engine changed " + beforeEngine
                            + " → " + afterEngine + " → soft restart");
                    softRestart();
                    return; // softRestartで全再構築されるのでデバイス個別処理は不要
                }

                if (inChanged) {
                    // ★入力が変わったなら必ず再キャリブレーション（ここが無いと Start が死ぬ）
                    SwingUtilities.invokeLater(() -> {
                        isCalibrationComplete = false;
                        button.setEnabled(false);
                        button.setText(UiText.t("ui.calibrating"));
                        window.setTitle(UiText.t("ui.calibrating.stayQuiet"));
                    });
                    primeVadByEmptyRecording();
                }

                // 出力変更は、ここでは必須じゃないけどログ出しだけしてもOK
                if (outChanged) {
                    Config.logDebug("Output device changed by wizard: " + beforeOut + " -> " + afterOut);
                }

            } catch (Throwable t) {
                Config.log("Wizard failed: " + t.getMessage());
            }

        });
    }
    private void transcribeWavFile(File wavFile) {
        Config.log("[WAV-TEST] Loading: " + wavFile.getName());
        try {
            javax.sound.sampled.AudioInputStream in =
                    javax.sound.sampled.AudioSystem.getAudioInputStream(wavFile);
            javax.sound.sampled.AudioFormat targetFmt =
                    new javax.sound.sampled.AudioFormat(16000f, 16, 1, true, false);
            javax.sound.sampled.AudioInputStream converted =
                    javax.sound.sampled.AudioSystem.getAudioInputStream(targetFmt, in);

            byte[] pcm = converted.readAllBytes();
            converted.close();
            in.close();

            double seconds = pcm.length / 32000.0;
            if (seconds > 30.0) {
                Config.log("[WAV-TEST] REJECTED: too long (" + String.format("%.1f", seconds) + "s > 30s)");
                JOptionPane.showMessageDialog(window,
                        "WAV too long: " + String.format("%.1f", seconds) + "s\nMax 30s for benchmark.",
                        "WAV Test", JOptionPane.WARNING_MESSAGE);
                return;
            }

            Config.log("[WAV-TEST] PCM bytes=" + pcm.length
                    + " (" + String.format("%.2f", seconds) + "s)");

            long t0 = System.currentTimeMillis();
            // ★ 既存のtranscribeフローに完全に乗せる
            transcribe(pcm, getActionFromPrefs(), true);
            long dt = System.currentTimeMillis() - t0;

            Config.log("[WAV-TEST] DONE ms=" + dt
                    + " (" + String.format("%.2f", dt / 1000.0) + "s)");

        } catch (Exception ex) {
            Config.logError("[WAV-TEST] error: " + ex.getMessage(), ex);
        }
    }
    private Action getActionFromPrefs() {
        String strAction = prefs.get("action", "noting");
        return switch (strAction) {
            case "paste"     -> Action.COPY_TO_CLIPBOARD_AND_PASTE;
            case "type"      -> Action.TYPE_STRING;
            case "translate" -> Action.TRANSLATE_AND_SPEAK;
            default          -> Action.NOTHING;
        };
    }
    // ===== ウィンドウドラッグ移動を有効化 =====
    private void enableWindowDragging(JFrame frame) {
        final Point[] dragOffset = new Point[1];

        frame.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mousePressed(java.awt.event.MouseEvent e) {
                dragOffset[0] = e.getPoint();
            }
        });

        frame.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseDragged(java.awt.event.MouseEvent e) {
                if (dragOffset[0] != null) {
                    Point current = frame.getLocationOnScreen();
                    frame.setLocation(
                            current.x + e.getX() - dragOffset[0].x,
                            current.y + e.getY() - dragOffset[0].y
                    );
                }
            }
        });
    }
    // ===== ×ボタン作成 =====
    private JButton createCloseButton() {
        JButton closeBtn = new JButton("×");
        closeBtn.setFont(closeBtn.getFont().deriveFont(16f).deriveFont(java.awt.Font.BOLD));
        closeBtn.setFocusPainted(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setPreferredSize(new Dimension(32, 24));
        closeBtn.setToolTipText("Close");

        // ホバー時の背景色
        closeBtn.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                closeBtn.setContentAreaFilled(true);
                closeBtn.setBackground(new Color(232, 17, 35)); // 赤
                closeBtn.setForeground(Color.WHITE);
            }
            public void mouseExited(java.awt.event.MouseEvent evt) {
                closeBtn.setContentAreaFilled(false);
                closeBtn.setForeground(UIManager.getColor("Button.foreground"));
            }
        });

        closeBtn.addActionListener(e -> {
            if (isRecording()) {
                stopRecording();
            }
            if (meterUpdateTimer != null) {
                meterUpdateTimer.stop();
            }
            // ★ステータス更新タイマーも停止
            if (statusUpdateTimer != null) {
                statusUpdateTimer.stop();
            }
            Config.mirrorAllToCloud();
            try {
                if (hearingFrame != null) hearingFrame.shutdownForExit();
            } catch (Throwable ignore) {}
            System.exit(0);
        });

        return closeBtn;
    }

    // ===== ステータスバー作成（充実版） =====
    private JPanel createStatusBar() {
        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
        ));

        // エンジン情報
        engineLabel = new JLabel(cpugpumode);
        engineLabel.setFont(engineLabel.getFont().deriveFont(11f));

        // 状態表示（アイコン＋英語）
        statusLabel = new JLabel("▪ Ready");
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
        statusLabel.setForeground(UIManager.getColor("Label.foreground"));

        // ★ラジオチャットホットキー表示（変更）
        JLabel hotkeyLabel = new JLabel("R: " + getRadioHotkeyString());
        hotkeyLabel.setFont(hotkeyLabel.getFont().deriveFont(11f));
        hotkeyLabel.setToolTipText("Radio Chat Hotkey");

        // ★利用モデル（v1.5.0: エンジンに応じた表示）
        String modelName;
        if (isEngineMoonshine()) {
            // moonshine.model_path の末尾ディレクトリ名を表示
            String mp = prefs.get("moonshine.model_path", "");
            if (mp.isEmpty()) {
                modelName = "auto";
            } else {
                // パス末尾から意味のある名前を取る（例: "base-ja" or "quantized/base-ja"）
                java.nio.file.Path p = java.nio.file.Paths.get(mp);
                java.nio.file.Path parent = p.getParent();
                if (parent != null && parent.getFileName() != null) {
                    modelName = parent.getFileName() + "/" + p.getFileName();
                } else {
                    modelName = p.getFileName().toString();
                }
            }
        } else {
            modelName = (model != null) ? model.replace("ggml-", "").replace(".bin", "") : "N/A";
        }
        String engineTag = isEngineMoonshine() ? "Moon" : "Whi";
        modelLabel = new JLabel(engineTag + ":" + modelName);
        modelLabel.setFont(modelLabel.getFont().deriveFont(11f));
        modelLabel.setToolTipText(getRecogEngineName() + " Model: " + modelName);

        // ★話者照合ステータス
        boolean spkEnabled = prefs.getBoolean("speaker.enabled", false);
        boolean spkReady = spkEnabled && speakerProfile != null && speakerProfile.isReady();
        speakerStatusLabel = new JLabel(spkReady ? "●SPK" : (spkEnabled ? "◎SPK" : "○SPK"));
        speakerStatusLabel.setFont(speakerStatusLabel.getFont().deriveFont(11f));
        speakerStatusLabel.setForeground(spkReady ? new Color(76, 175, 80) : (spkEnabled ? new Color(255, 193, 7) : Color.GRAY));
        speakerStatusLabel.setToolTipText("Speaker Filter");

        // ★VoiceVox接続状態
        vvStatusLabel = new JLabel(voiceVoxAlive ? "●VV" : "○VV");
        vvStatusLabel.setFont(vvStatusLabel.getFont().deriveFont(11f));
        vvStatusLabel.setForeground(voiceVoxAlive ? new Color(76, 175, 80) : Color.GRAY);
        vvStatusLabel.setToolTipText("VoiceVox Status");

        // ★Voiceger接続状態（追加）
        boolean vgAlive = isVoicegerAlive();
        vgStatusLabel = new JLabel(vgAlive ? "●VG" : "○VG");
        vgStatusLabel.setFont(vgStatusLabel.getFont().deriveFont(11f));
        vgStatusLabel.setForeground(vgAlive ? new Color(76, 175, 80) : Color.GRAY);
        vgStatusLabel.setToolTipText("Voiceger Status");

        // ★録音時間
        durationLabel = new JLabel("0:00");
        durationLabel.setFont(durationLabel.getFont().deriveFont(11f));
        durationLabel.setToolTipText("Recording Duration");

        // ★メモリ使用量
        Runtime runtime = Runtime.getRuntime();
        long usedMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        memLabel = new JLabel("Mem: " + usedMB + "MB");
        memLabel.setFont(memLabel.getFont().deriveFont(11f));
        memLabel.setToolTipText("Memory Usage");

        // ★GPU VRAM表示
//        gpuMemLabel = new JLabel("GPU: ...");
//        gpuMemLabel.setFont(gpuMemLabel.getFont().deriveFont(11f));
//        gpuMemLabel.setForeground(Color.GRAY);
//        gpuMemLabel.setToolTipText("GPU VRAM (estimated model usage / total)");

        // バージョン
        versionLabel = new JLabel("v" + Version.APP_VERSION);
        versionLabel.setFont(versionLabel.getFont().deriveFont(11f));

        // 区切り線（8本に増やす）
        JSeparator sep1 = new JSeparator(SwingConstants.VERTICAL);
        sep1.setPreferredSize(new Dimension(1, 12));
        JSeparator sep2 = new JSeparator(SwingConstants.VERTICAL);
        sep2.setPreferredSize(new Dimension(1, 12));
        JSeparator sep3 = new JSeparator(SwingConstants.VERTICAL);
        sep3.setPreferredSize(new Dimension(1, 12));
        JSeparator sep4 = new JSeparator(SwingConstants.VERTICAL);
        sep4.setPreferredSize(new Dimension(1, 12));
        JSeparator sep5 = new JSeparator(SwingConstants.VERTICAL);
        sep5.setPreferredSize(new Dimension(1, 12));
        JSeparator sep6 = new JSeparator(SwingConstants.VERTICAL);
        sep6.setPreferredSize(new Dimension(1, 12));
        JSeparator sep7 = new JSeparator(SwingConstants.VERTICAL);
        sep7.setPreferredSize(new Dimension(1, 12));
        JSeparator sep8 = new JSeparator(SwingConstants.VERTICAL);
        sep8.setPreferredSize(new Dimension(1, 12));

        // 追加順序
        statusBar.add(durationLabel);
        statusBar.add(sep7);
        statusBar.add(statusLabel);
        statusBar.add(sep1);
        statusBar.add(speakerStatusLabel);
        JSeparator sepSpk = new JSeparator(SwingConstants.VERTICAL);
        sepSpk.setPreferredSize(new Dimension(1, 12));
        statusBar.add(sepSpk);
        statusBar.add(vvStatusLabel);
        statusBar.add(sep5);
        statusBar.add(vgStatusLabel);
        statusBar.add(sep6);
//        statusBar.add(engineLabel);
//        statusBar.add(sep2);
        statusBar.add(hotkeyLabel);
        statusBar.add(sep3);
        statusBar.add(modelLabel);
        statusBar.add(sep4);
        statusBar.add(memLabel);
        // ★GPU VRAMラベル追加
//        statusBar.add(gpuMemLabel);
//        JSeparator sepGpu = new JSeparator(SwingConstants.VERTICAL);
//        sepGpu.setPreferredSize(new Dimension(1, 12));
        statusBar.add(sep8);
        statusBar.add(versionLabel);

        return statusBar;
    }

    // ===== ホットキー文字列取得 =====
    private String getHotkeyString() {
        StringBuilder sb = new StringBuilder();
        if (ctrltHotkey) sb.append("Ctrl+");
        if (shiftHotkey) sb.append("Shift+");
        sb.append(hotkey);
        return sb.toString();
    }
    // ===== ラジオチャットホットキー文字列取得（★追加） =====
    private String getRadioHotkeyString() {
        StringBuilder sb = new StringBuilder();

        // Modifier
        switch (radioModMask) {
            case 1: sb.append("Shift+"); break;
            case 2: sb.append("Ctrl+"); break;
            case 3: sb.append("Ctrl+Shift+"); break;
        }

        // Key
        String keyName = NativeKeyEvent.getKeyText(radioKeyCode);
        sb.append(keyName);

        return sb.toString();
    }
    // ===== Voiceger生存確認（★追加） =====
    private boolean isVoicegerAlive() {
        long now = System.currentTimeMillis();
        return now < vgHealthOkUntilMs;
    }
    // ===== ステータスバー更新タイマー =====
    private void startStatusUpdateTimer() {
        if (statusUpdateTimer != null) {
            statusUpdateTimer.stop();
        }

        statusUpdateTimer = new Timer(500, e -> updateStatusBar()); // 0.5秒ごとに更新
        statusUpdateTimer.start();
        queryGpuVramAsync();  // ★GPU VRAM情報を非同期取得
    }
    private void updateStatusBar() {
        SwingUtilities.invokeLater(() -> {
            // VoiceVox接続状態を更新
            if (vvStatusLabel != null) {
                boolean alive = voiceVoxAlive;
                vvStatusLabel.setText(alive ? "●VV" : "○VV");
                vvStatusLabel.setForeground(alive ? new Color(76, 175, 80) : Color.GRAY);
            }
            // ★Voiceger接続状態を更新（追加）
            if (vgStatusLabel != null) {
                boolean vgAlive = isVoicegerAlive();
                vgStatusLabel.setText(vgAlive ? "●VG" : "○VG");
                vgStatusLabel.setForeground(vgAlive ? new Color(76, 175, 80) : Color.GRAY);
            }

            // 録音時間を更新
            if (durationLabel != null) {
                if (isRecording() && recordingStartTime2 > 0) {
                    long elapsed = (System.currentTimeMillis() - recordingStartTime2) / 1000;
                    long minutes = elapsed / 60;
                    long seconds = elapsed % 60;
                    durationLabel.setText(String.format("%d:%02d", minutes, seconds));
                } else {
                    durationLabel.setText("0:00");
                }
            }

            // メモリ使用量を更新
            if (memLabel != null) {
                Runtime runtime = Runtime.getRuntime();
                long usedMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
                memLabel.setText("Mem: " + usedMB + "MB");
            }
            // ★GPU VRAM推定使用量を更新
//            if (gpuMemLabel != null) {
//                boolean isVulkan = cpugpumode != null && cpugpumode.contains("Vulkan");
//                if (isVulkan && gpuVramTotalBytes > 0) {
//                    File mf = new File(model_dir, model);
//                    long modelBytes = mf.exists() ? mf.length() : 0;
//                    // モデル + KVキャッシュ + 推論バッファで約1.2倍
//                    long estMB = (long)(modelBytes * 1.2 / 1024 / 1024);
//                    long totalMB = gpuVramTotalBytes / 1024 / 1024;
//
//                    String est = (estMB >= 1024)
//                            ? String.format("%.1fG", estMB / 1024.0)
//                            : estMB + "M";
//                    String tot = (totalMB >= 1024)
//                            ? String.format("%.0fG", totalMB / 1024.0)
//                            : totalMB + "M";
//
//                    gpuMemLabel.setText("GPU: " + est + "/" + tot);
//
//                    double ratio = (double) estMB / totalMB;
//                    if (ratio > 0.90) {
//                        gpuMemLabel.setForeground(new Color(244, 67, 54));   // 赤：VRAM不足
//                    } else if (ratio > 0.70) {
//                        gpuMemLabel.setForeground(new Color(255, 152, 0));   // オレンジ：余裕少ない
//                    } else {
//                        gpuMemLabel.setForeground(new Color(76, 175, 80));   // 緑：十分
//                    }
//
//                    gpuMemLabel.setToolTipText(String.format(
//                            "Estimated VRAM: %dMB / %dMB (%.0f%%)",
//                            estMB, totalMB, ratio * 100));
//                } else if (!isVulkan) {
//                    gpuMemLabel.setText("GPU: --");
//                    gpuMemLabel.setForeground(Color.GRAY);
//                    gpuMemLabel.setToolTipText("CPU mode (no GPU)");
//                }
//                // else: Vulkanだけどまだクエリ中 → "GPU: ..." のまま
//            }
        });
    }
    /**
     * ★レジストリからGPU VRAMを非同期で1回だけ取得。
     * HardwareInformation.qwMemorySize は64bit値なので4GB超のGPUも正しく取れる。
     */
    private void queryGpuVramAsync() {
        new Thread(() -> {
            try {
                int gpuIdx = prefs.getInt("vulkan.gpu.index", -1);
                long maxVram = -1;
                long selectedVram = -1;

                String regBase = "HKLM\\SYSTEM\\ControlSet001\\Control\\Class\\"
                        + "{4d36e968-e325-11ce-bfc1-08002be10318}\\";

                for (int i = 0; i < 4; i++) {
                    try {
                        ProcessBuilder pb = new ProcessBuilder("reg", "query",
                                regBase + String.format("%04d", i),
                                "/v", "HardwareInformation.qwMemorySize");
                        pb.redirectErrorStream(true);
                        Process p = pb.start();
                        String out = new String(
                                p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        if (!p.waitFor(3, TimeUnit.SECONDS)) {
                            p.destroyForcibly();
                            continue;
                        }

                        int hx = out.indexOf("0x");
                        if (hx < 0) continue;
                        String hex = out.substring(hx + 2).trim().split("\\s+")[0];
                        long vram = Long.parseUnsignedLong(hex, 16);

                        if (i == gpuIdx) selectedVram = vram;
                        if (vram > maxVram) maxVram = vram;
                    } catch (Exception ignore) {}
                }

                // 指定GPUがあればそれ、なければ最大VRAM（= おそらくdGPU）
                gpuVramTotalBytes = (selectedVram > 0) ? selectedVram : maxVram;

                if (gpuVramTotalBytes > 0) {
                    Config.log("★ GPU VRAM detected: "
                            + (gpuVramTotalBytes / 1024 / 1024) + " MB"
                            + (selectedVram > 0 ? " (gpu index " + gpuIdx + ")" : " (max)"));
                } else {
                    Config.log("★ GPU VRAM not detected from registry");
                }
            } catch (Exception e) {
                Config.logError("GPU VRAM query failed", e);
            }
        }, "gpu-vram-query").start();
    }
    private void showHearingWindow() {
        if (hearingFrame == null) {
            hearingFrame = new HearingFrame(prefs, this.imageInactive, this);
        } else {
            hearingFrame.refreshOutputDevices();
        }
        hearingFrame.showWindow();
    }
    public void setHearingLanguage(String lang) {
        if (lang == null || lang.trim().isEmpty()) lang = "auto";
        try {
            if (prefs != null) prefs.put("hearing.lang", lang);
        } catch (Exception ignore) {}

        // 既にHearing用Whisperが居れば即反映
        try {
            LocalWhisperCPP cur = wHearing;
            if (cur != null) cur.setLanguage(lang);
        } catch (Exception ignore) {}
    }
    public void setHearingTranslateToEn(boolean v) {
        try {
            if (prefs != null) prefs.putBoolean("hearing.translate_to_en", v);
            try { prefs.sync(); } catch (Exception ignore) {}
        } catch (Exception ignore) {}

        // 既にHearing用Whisperが居れば即反映
        try {
            LocalWhisperCPP cur = wHearing;
            if (cur != null) cur.setHearingTranslateToEn(v);
        } catch (Exception ignore) {}
    }
    private final java.util.concurrent.Semaphore hearingSem = new java.util.concurrent.Semaphore(1);
    LocalWhisperCPP hw;
    public String transcribeHearingRaw(byte[] pcm16k16mono) {
        if (!hearingSem.tryAcquire()) return ""; // 混雑時は捨てる
        try {
            if (hw == null) {
                hw = getOrCreateHearingWhisper();
            }
            if (hw == null) return "";
            return hw.transcribeRawHearing(pcm16k16mono, Action.NOTHING_NO_SPEAK, this);
        } catch (Exception ex) {
            Config.logError("[Hearing] transcribe failed", ex);
            return "";
        } finally {
            hearingSem.release();
        }
    }


    private void startMeterUpdateTimer() {
        meterUpdateTimer = new Timer(33, e -> {
            updateGainMeter(currentInputLevel, currentInputDb);
        });
        meterUpdateTimer.setCoalesce(true);
        meterUpdateTimer.start();
    }
    private void updateGainMeter(int level, double db) {
        if (gainMeter == null) return;

        // prefsから都度読む（UI表示用）
        boolean autoGainEnabledNow = prefs.getBoolean("audio.autoGain", false);
        float userGain = prefs.getFloat("audio.inputGainMultiplier", 1.0f);

        // 「最近喋ってたか？」判定：ここが無音で0へ戻すコア
        long now = System.currentTimeMillis();
        boolean recentSpeech = (now - lastSpeechAtMs) <= 180; // 180msは体感いい感じ

        gainMeter.setValue(level, db, autoGainEnabledNow, autoGainMultiplier, userGain, recentSpeech);
    }
    private void updateInputLevelWithGain(byte[] pcm, int len, boolean speech) {
        if (pcm == null || len <= 0) return;

        float userGain = prefs.getFloat("audio.inputGainMultiplier", 1.0f);
        boolean autoGainEnabledNow = prefs.getBoolean("audio.autoGain", true);

        float gain = userGain * autoGainMultiplier;
        boolean applyGain = gain > 1.01f;

        // ★波形（min/max）をGainMeterへ流す：UIは増やさず「ここ」に出す
        if (gainMeter != null) {
            gainMeter.pushWaveformPcm16le(pcm, len, gain, applyGain);
        }

        int peak = 0;
        long sum = 0;

        for (int i = 0; i + 1 < len; i += 2) {
            int sample = (short)(((pcm[i+1] & 0xFF) << 8) | (pcm[i] & 0xFF));
            if (applyGain) {
                int amplified = Math.round(sample * gain);
                if (amplified > 32767) amplified = 32767;
                else if (amplified < -32768) amplified = -32768;
                sample = amplified;
            }
            int abs = sample < 0 ? -sample : sample;
            if (abs > peak) peak = abs;
            sum += abs;
        }

        int count = Math.max(1, len / 2);
        int avg = (int)(sum / count);

        // ノイズ床：無音時にピクピク残るの嫌対策（好みで調整）
        if (!speech && peak < 600 && avg < 220) { // ざっくり門番
            peak = 0;
            avg = 0;
        }

        int level = (int)((peak * 0.7 + avg * 0.3) / 327.68);
        currentInputLevel = Math.max(0, Math.min(100, level));
        currentInputDb = peak > 0
                ? Math.max(-60.0, 20.0 * Math.log10(peak / 32768.0))
                : -60.0;

        // ★AutoGainは「喋ってる時だけ」追従（無音ノイズで勝手に動くの防止）
        if (autoGainEnabledNow && speech) {
            final int target = 55;
            int diff = target - currentInputLevel;
            if (currentInputLevel > 5 && currentInputLevel < 90) {
                float step = diff * 0.0025f;
                autoGainMultiplier += step;
            }
            if (currentInputLevel >= 80) {
                autoGainMultiplier *= 0.95f;
            }

            if (autoGainMultiplier < 0.25f) autoGainMultiplier = 0.25f;
            if (autoGainMultiplier > 9.0f) autoGainMultiplier = 9.0f;
        }
    }
    public static void bringToFront(JFrame frame) {
        SwingUtilities.invokeLater(() -> {
            frame.setVisible(true);
            frame.setExtendedState(JFrame.NORMAL);
            frame.setAlwaysOnTop(true);
            frame.toFront();
            frame.requestFocus();
            new Timer(50, ev -> {
                frame.setAlwaysOnTop(false);
            }).start();
        });
    }
    private static String k(String prefix, String suffix) {
        return prefix + "." + suffix;
    }
    private boolean isOnAnyScreen(Rectangle r) {
        GraphicsEnvironment ge =
                GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (GraphicsDevice gd : ge.getScreenDevices()) {
            Rectangle b = gd.getDefaultConfiguration().getBounds();
            if (b.intersects(r)) return true;
        }
        return false;
    }
    private void restoreWindowBounds(Window window) {
        int x = prefs.getInt(PREF_WIN_X, 15);
        int y = prefs.getInt(PREF_WIN_Y, 15);
        int w = prefs.getInt(PREF_WIN_W, -1);
        int h = prefs.getInt(PREF_WIN_H, -1);

        if (x != Integer.MIN_VALUE && y != Integer.MIN_VALUE && w > 0 && h > 0) {
            Rectangle bounds = new Rectangle(x, y, w, h);
            if (isOnAnyScreen(bounds)) {
                window.setBounds(bounds);
                return;
            }
        }
        window.setLocationRelativeTo(null);
    }
    private void restoreBounds(Window w, String prefix, Rectangle fallback) {
        int x = prefs.getInt(k(prefix, "x"), Integer.MIN_VALUE);
        int y = prefs.getInt(k(prefix, "y"), Integer.MIN_VALUE);
        int ww = prefs.getInt(k(prefix, "w"), -1);
        int hh = prefs.getInt(k(prefix, "h"), -1);

        if (x != Integer.MIN_VALUE && y != Integer.MIN_VALUE && ww > 0 && hh > 0) {
            Rectangle r = new Rectangle(x, y, ww, hh);
            if (isOnAnyScreen(r)) {
                w.setBounds(r);
                return;
            }
        }
        if (fallback != null) {
            w.setBounds(fallback);
            if (!isOnAnyScreen(w.getBounds())) {
                w.setLocationRelativeTo(null);
            }
        } else {
            w.setLocationRelativeTo(null);
        }
    }
    private void installBoundsSaver(Window w, String prefix) {
        w.addComponentListener(new ComponentAdapter() {
            private long lastSaveMs = 0;

            private void save() {
                long now = System.currentTimeMillis();
                if (now - lastSaveMs < 400) return; // 間引き
                lastSaveMs = now;

                Rectangle b = w.getBounds();
                if (b.width <= 0 || b.height <= 0) return;

                prefs.putInt(k(prefix, "x"), b.x);
                prefs.putInt(k(prefix, "y"), b.y);
                prefs.putInt(k(prefix, "w"), b.width);
                prefs.putInt(k(prefix, "h"), b.height);
                try { prefs.sync(); } catch (Exception ignore) {}
            }

            @Override public void componentMoved(ComponentEvent e)  { save(); }
            @Override public void componentResized(ComponentEvent e){ save(); }
        });
    }
    public void showHistory() {
        if (historyFrame != null && historyFrame.isShowing()) {
            historyFrame.toFront();
            historyFrame.requestFocus();
            return;
        }
        historyFrame = new HistoryFrame(this);
        historyFrame.setMinimumSize(new Dimension(510, 200));
        historyFrame.setSize(510, 400);
        SwingUtilities.updateComponentTreeUI(historyFrame);

        Rectangle fb = null;
        if (window != null) {
            fb = new Rectangle(window.getX() + 15, window.getY() + 80, 510, 400);
        } else {
            fb = new Rectangle(15, 80, 510, 400);
        }
        restoreBounds(historyFrame, "ui.history", fb);
        installBoundsSaver(historyFrame, "ui.history");

        historyFrame.refresh();
        historyFrame.setVisible(true);
        SwingUtilities.updateComponentTreeUI(historyFrame);
        historyFrame.updateIcon(
                isRecording(),
                isTranscribing(),
                imageRecording,
                imageTranscribing,
                imageInactive
        );
    }
    public void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        text = normalizeLaugh(text);
        text = Config.applyDictionary(text);

        String engine = prefs.get("tts.engine", "auto").toLowerCase(Locale.ROOT);

        // --- forced route ---
        if ("voiceger_tts".equals(engine)) {
            speakVoiceger(text);      // 今の実装をそのまま：/tts優先→VCフォールバック
            return;
        }
        if ("voiceger_vc".equals(engine) || "voiceger".equals(engine)) {
            speakVoicegerVcOnly(text); // 新規：VCのみ
            return;
        }
        if ("voicevox".equals(engine)) {
            if (isVoiceVoxAvailable()) {
                String api = Config.getString("voicevox.api", "");
                int base = Integer.parseInt(prefs.get("tts.voice", "3"));
                String uiLang = prefs.get("ui.language", "en");
                boolean autoEmotion = prefs.getBoolean("voicevox.auto_emotion", true);

                int styleId = autoEmotion
                        ? VoiceVoxStyles.pickStyleId(text, base, api, uiLang)
                        : base;

                speakVoiceVox(text, String.valueOf(styleId), api);
                return;
            }
            // fallthrough to auto
        }
        if ("xtts".equals(engine)) {
            if (isXttsAvailable()) {
                try { speakXtts(text); } catch (Exception ignore) {}
                return;
            }
            // fallthrough
        }
        if ("windows".equals(engine)) {
            speakWindows(text);
            return;
        }

        // --- auto (existing behavior) ---
        if (isVoiceVoxAvailable()) {
            String api = Config.getString("voicevox.api", "");
            int base = Integer.parseInt(prefs.get("tts.voice", "3"));
            String uiLang = prefs.get("ui.language", "en");
            boolean autoEmotion = prefs.getBoolean("voicevox.auto_emotion", true);

            int styleId = autoEmotion
                    ? VoiceVoxStyles.pickStyleId(text, base, api, uiLang)
                    : base;

            speakVoiceVox(text, String.valueOf(styleId), api);
        } else if (isXttsAvailable()) {
            try { speakXtts(text); } catch (Exception ignore) {}
        } else {
            speakWindows(text);
        }
    }

    // ===== Voiceger TTS pipelining =====
    private final ExecutorService vgTtsExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vg-tts-prefetch");
        t.setDaemon(true);
        return t;
    });

    // ===== Voiceger restart (tray selection) =====
    private static final AtomicLong vgRestartDebounceUntilMs = new AtomicLong(0);
    private static final long VG_RESTART_DEBOUNCE_MS = 1500;

    private void restartVoicegerApiAsync() {
        // 連打・多重イベント対策（1.5秒以内は無視）
        long now = System.currentTimeMillis();
        long until = vgRestartDebounceUntilMs.get();
        if (now < until) return;
        vgRestartDebounceUntilMs.set(now + VG_RESTART_DEBOUNCE_MS);

        new Thread(() -> {
            try {
                String dir = prefs.get("voiceger.dir", "").trim();
                if (dir.isEmpty()) {
                    Config.logDebug("[VG] restart skipped: voiceger.dir empty");
                    return;
                }

                Config.logDebug("[VG] restart requested (tray)");
                try { VoicegerManager.stopIfRunning(prefs); } catch (Throwable ignore) {}
                try { Thread.sleep(250); } catch (Exception ignore) {}

                // enabledがfalseでも「選んだ＝使う」なので起動を試みる（dirがある場合）
                try {
                    VoicegerManager.ensureRunning(prefs, Duration.ofSeconds(20));
                    Config.logDebug("[VG] restart done (ready)");
                } catch (Throwable e) {
                    Config.logDebug("[VG] restart failed: " + e);
                }
            } catch (Throwable e) {
                Config.logDebug("[VG] restart thread error: " + e);
            }
        }, "voiceger-restart").start();
    }
    private final ExecutorService vgPlayExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vg-tts-player");
        t.setDaemon(true);
        return t;
    });
    private static List<String> splitSentences(String s) {
        if (s == null) return Collections.emptyList();
        String t = s.trim();
        if (t.isEmpty()) return Collections.emptyList();

        // ざっくり：日本語・英語の句点/終端で分ける
        String[] arr = t.split("(?<=[。！？!?])\\s*");
        List<String> out = new ArrayList<>();
        for (String a : arr) {
            String x = a.trim();
            if (!x.isEmpty()) out.add(x);
        }
        return out.isEmpty() ? Collections.singletonList(t) : out;
    }
    private static boolean voicegerHealthOkCached(String baseUrl) {
        long now = System.currentTimeMillis();
        if (now < vgHealthOkUntilMs) return true;

        boolean ok = httpOk(baseUrl + "health");
        if (ok) vgHealthOkUntilMs = now + VG_HEALTH_CACHE_MS;
        return ok;
    }
    // ===== Voiceger route =====
    private static String cut200(String s) {
        if (s == null) return "null";
        s = s.replace("\r", "\\r").replace("\n", "\\n");
        return (s.length() <= 200) ? s : s.substring(0, 200) + "...";
    }
    // WindowsTTS(wav) -> bytes -> Voiceger VC -> bytes -> Java再生（outファイル書き出し無し）
    private void speakVoiceger(String text) {
        long t0 = System.nanoTime();
        try {
            // ① まず /tts を試す：文分割して「再生中に次文を先読み」する
            List<String> parts = splitSentences(text);
            if (!parts.isEmpty()) {
                // 最初の文は同期で取りに行く（ここだけは待つ）
                byte[] first = applyVoicegerTtsBytesIfEnabled(parts.get(0), prefs);
                if (first != null && first.length >= 256) {
                    // 2文目以降は先読みして、再生キューに流す
                    List<Future<byte[]>> futures = new ArrayList<>();
                    for (int i = 1; i < parts.size(); i++) {
                        final String p = parts.get(i);
                        futures.add(vgTtsExec.submit(() -> applyVoicegerTtsBytesIfEnabled(p, prefs)));
                    }

                    // 再生は直列（事故防止）。再生中に次が生成されるのが狙い
                    vgPlayExec.submit(() -> {
                        try {
                            playViaPowerShellBytesAsync(first);
                            for (Future<byte[]> f : futures) {
                                byte[] b = null;
                                try { b = f.get(350, TimeUnit.SECONDS); } catch (Exception ignore) {}
                                if (b != null && b.length >= 256) {
                                    playViaPowerShellBytesAsync(b);
                                } else {
                                    // 先読みが失敗したら、残りはまとめてフォールバックでも良い（最小は何もしない）
                                }
                            }
                        } catch (Exception ignore) {}
                    });
                    return;
                }
            }

            // ② 失敗したら従来の WindowsTTS→VC にフォールバック
            byte[] inBytes = synthWindowsToWavBytesViaAgent(text);
            long t1 = System.nanoTime();
            Config.logDebug("[VG] tts_ms=" + ((t1 - t0) / 1_000_000));

            byte[] outBytes = applyVoicegerVcBytesIfEnabled(inBytes, prefs);
            long t2 = System.nanoTime();
            Config.logDebug("[VG] vc_ms=" + ((t2 - t1) / 1_000_000));

            boolean usingVc = (outBytes != null && outBytes.length >= 256);
            byte[] play = usingVc ? outBytes : inBytes;
            playViaPowerShellBytesAsync(play);
            long t3 = System.nanoTime();
            Config.logDebug("[VG] play_send_ms=" + ((t3 - t2) / 1_000_000));

        } catch (Exception e) {
            Config.logDebug("[VG] speakVoiceger(bytes) exception: " + e);
            e.printStackTrace();
        }
    }
    private void speakVoicegerVcOnly(String text) {
        long t0 = System.nanoTime();
        try {
            // ② 失敗したら従来の WindowsTTS→VC にフォールバック
            byte[] inBytes = synthWindowsToWavBytesViaAgent(text);
            long t1 = System.nanoTime();
            Config.logDebug("[VG] tts_ms=" + ((t1 - t0) / 1_000_000));

            byte[] outBytes = applyVoicegerVcBytesIfEnabled(inBytes, prefs);
            long t2 = System.nanoTime();
            Config.logDebug("[VG] vc_ms=" + ((t2 - t1) / 1_000_000));

            boolean usingVc = (outBytes != null && outBytes.length >= 256);
            byte[] play = usingVc ? outBytes : inBytes;
            playViaPowerShellBytesAsync(play);
            long t3 = System.nanoTime();
            Config.logDebug("[VG] play_send_ms=" + ((t3 - t2) / 1_000_000));

        } catch (Exception e) {
            Config.logDebug("[VG] speakVoiceger(bytes) exception: " + e);
        }
    }
    private byte[] synthWindowsToWavBytesViaAgent(String text) throws Exception {
        if (text == null || text.isBlank()) return null;

        synchronized (psLock) {
            ensurePsServerLocked();

            String voice = prefs.get("tts.windows.voice", "auto");

            String id = Long.toHexString(System.nanoTime());
            byte[] txtBytes = text.getBytes(StandardCharsets.UTF_8);
            String b64Text = Base64.getEncoder().encodeToString(txtBytes);

            // SYNTHB64 <voiceOrAuto> <id>
            psWriter.write("SYNTHB64 " + voice + " " + id + "\n");

            final int CHUNK = 12000;
            for (int i = 0; i < b64Text.length(); i += CHUNK) {
                int end = Math.min(b64Text.length(), i + CHUNK);
                psWriter.write("TDATA " + id + " " + b64Text.substring(i, end) + "\n");
            }
            psWriter.write("TEND " + id + "\n");
            psWriter.flush();

            // 返り：ODATA... が複数行 → OEND → DONE
            StringBuilder b64 = new StringBuilder(256000);
            while (true) {
                String line = psReader.readLine();
                if (line == null) throw new IOException("tts_agent pipe closed");
                if ("DONE".equals(line)) break;

                if (line.startsWith("ODATA ")) {
                    b64.append(line.substring("ODATA ".length()));
                } else if ("OEND".equals(line)) {
                    // nop
                } else if (line.startsWith("ERR")) {
                    throw new IOException("tts_agent synth error: " + line);
                } else {
                    throw new IOException("tts_agent bad response: " + line);
                }
            }

            if (b64.length() < 64) return null;
            return Base64.getDecoder().decode(b64.toString());
        }
    }
    private static String normalizeVoicegerLang(String raw) {
        if (raw == null || raw.isBlank()) return "ja";
        String l = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (l.startsWith("all_")) l = l.substring(4);

        // よくある表記ゆれ吸収（必要なら追加）
        if (l.equals("jp") || l.equals("jpn")) return "ja";
        if (l.equals("eng")) return "en";
        if (l.equals("cn") || l.equals("zho") || l.equals("zh-cn") || l.equals("zh-hans")) return "zh";
        if (l.equals("kr") || l.equals("kor")) return "ko";
        if (l.equals("cantonese") || l.equals("hk")) return "yue";

        // 許可リスト
        switch (l) {
            case "ja":
            case "en":
            case "zh":
            case "ko":
            case "yue":
            case "auto":
                return l;
            default:
                return "ja";
        }
    }
    private byte[] applyVoicegerVcBytesIfEnabled(byte[] inWavBytes, Preferences prefs) {
        try {
            // 優先：tts.engine で管理（Voiceger(WavToWav)選択時だけ有効）
            String engine = prefs.get("tts.engine", "auto")
                    .toLowerCase(java.util.Locale.ROOT);
            boolean vcByEngine = "voiceger_vc".equals(engine) || "voiceger_w2w".equals(engine);

            // 互換：古いフラグ（徐々に削れる）
            boolean enabledLegacy = prefs.getBoolean("voiceger.enabled", false);

            boolean enabled = vcByEngine || enabledLegacy;
            if (!enabled) return null;

            // キー統一：voiceger.api.base
            String baseUrl = prefs.get("voiceger.api.base", null);
            if (baseUrl == null || baseUrl.isBlank()) {
                // 互換：旧キー
                baseUrl = prefs.get("voiceger.api_base", "http://127.0.0.1:8501");
            }
            if (!baseUrl.endsWith("/")) baseUrl += "/";

            Config.logDebug("[VG] vcEnabled=" + true + " engine=" + engine + " api_base=" + baseUrl);

            if (inWavBytes == null || inWavBytes.length < 256) {
                Config.logDebug("[VG] /vc/bytes in_wav too small: " + (inWavBytes == null ? -1 : inWavBytes.length));
                return null;
            }

            if (!voicegerHealthOkCached(baseUrl)) {
                Config.logDebug("[VG] healthOk=false");
                return null;
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "vc/bytes"))
                    .timeout(Duration.ofSeconds(300))
                    .header("Content-Type", "audio/wav")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(inWavBytes))
                    .build();

            HttpResponse<byte[]> res = VG_HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());

            int status = res.statusCode();
            Config.logDebug("[VG] /vc/bytes status=" + status);

            if (status != 200) {
                // 失敗時はテキストボディの先頭だけログ（過多防止）
                String err = "";
                try {
                    err = new String(res.body(), java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception ignore) {}
                Config.logDebug("[VG] /vc/bytes body(head)=" + cut200(err));
                return null;
            }

            byte[] out = res.body();
            Config.logDebug("[VG] /vc/bytes out_size=" + (out == null ? -1 : out.length));
            if (out == null || out.length < 256) return null;

            return out;

        } catch (Exception e) {
            Config.logDebug("[VG] applyVc(bytes) exception: " + e);
            return null;
        }
    }
    byte[] applyVoicegerTtsBytesIfEnabled(String text, Preferences prefs) {
        try {
            // 優先：tts.engine で管理（Voiceger(TTS)選択時だけ有効）
            String engine = prefs.get("tts.engine", "auto")
                    .toLowerCase(java.util.Locale.ROOT);
            boolean ttsByEngine = "voiceger_tts".equals(engine);

            // 互換：古いフラグが残ってても動くようにする（徐々に消せる）
            boolean enabledLegacy = prefs.getBoolean("voiceger.enabled", false);
            boolean ttsEnabledLegacy = prefs.getBoolean("voiceger.tts.enabled", false);

            boolean ttsEnabled = ttsByEngine || (enabledLegacy && ttsEnabledLegacy);
            if (!ttsEnabled) return null;

            // キー統一：voiceger.api.base
            String baseUrl = prefs.get("voiceger.api.base", null);
            if (baseUrl == null || baseUrl.isBlank()) {
                // 互換：旧キー
                baseUrl = prefs.get("voiceger.api_base", "http://127.0.0.1:8501");
            }
            if (!baseUrl.endsWith("/")) baseUrl += "/";

            Config.logDebug("[VG] ttsEnabled=" + true + " engine=" + engine + " api_base=" + baseUrl);

            if (!voicegerHealthOkCached(baseUrl)) {
                Config.logDebug("[VG] /tts healthOk=false");
                return null;
            }

            // 内部コードを許容：all_ja/en/all_zh/all_ko/all_yue/auto
            String rawLang = prefs.get("voiceger.tts.lang", "all_ja");
            String lang = normalizeVoicegerLang(rawLang);

            String safeText = (text == null) ? "" : text;

            String json = "{"
                    + "\"text\":" + toJsonString(safeText) + ","
                    + "\"language\":" + toJsonString(lang)
                    + "}";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "tts"))
                    .timeout(Duration.ofSeconds(300))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<byte[]> resp = VG_HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int status = resp.statusCode();
            Config.logDebug("[VG] /tts status=" + status + " lang(raw)=" + rawLang + " lang(send)=" + lang);

            if (status != 200) {
                byte[] body = resp.body();
                String head = "";
                try {
                    head = new String(body, 0, Math.min(body.length, 200), StandardCharsets.UTF_8);
                } catch (Exception ignore) {}
                Config.logDebug("[VG] /tts body(head)=" + head);
                return null;
            }

            byte[] out = resp.body();
            Config.logDebug("[VG] /tts out_size=" + (out == null ? 0 : out.length));
            return out;

        } catch (Exception e) {
            Config.logDebug("[VG] applyTts(bytes) exception: " + e);
            return null;
        }
    }
    // JSON文字列化（既に同等のがあればそれを使ってOK）
    private static String toJsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int)c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }


    private boolean isVoiceVoxAvailable() {
        long now = System.currentTimeMillis();
        if (now - lastVoiceVoxCheckMs < VOICEVOX_CHECK_INTERVAL_MS) {
            return voiceVoxAlive;
        }
        lastVoiceVoxCheckMs = now;

        String base = Config.getString("voicevox.api", "").trim();
        if (base.isEmpty()) {
            voiceVoxAlive = false;
            return false;
        }

        try {
            URL url = new URL(base + "/speakers");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(800);  // ★100 → 800
            conn.setReadTimeout(800);     // ★100 → 800
            int code = conn.getResponseCode();
            voiceVoxAlive = (code == 200);

            Config.logDebug("[VV] /speakers code=" + code + " base=" + base);
            return voiceVoxAlive;

        } catch (Exception e) {
            Config.logDebug("[VV] /speakers failed base=" + base + " err=" + e.getClass().getSimpleName() + ": " + e.getMessage());
            voiceVoxAlive = false;
            return false;
        }
    }
    public boolean isXttsAvailable() {
        try {
            URL url = new URL(Config.getString("xtts.apichk", ""));
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(500);
            con.setReadTimeout(500);
            con.setRequestMethod("GET");
            return con.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
    public void speakXtts(String text) throws Exception {
        String api = Config.getString("xtts.api", "");
        String lang = Config.getString("xtts.language", "en");
        String json = String.format(
                "{\"text\":\"%s\",\"language\":\"%s\"}",
                text.replace("\"", "\\\""),
                lang
        );
        HttpURLConnection con = (HttpURLConnection) new URL(api).openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = con.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        byte[] wavBytes;
        try (InputStream is = con.getInputStream()) {
            wavBytes = is.readAllBytes();
            speakViaTtsAgent(wavBytes);
        } catch (Exception ignored) {}
    }
    private void speakViaTtsAgent(byte[] wavBytes) throws Exception {
        try {
            Path tmp = Files.createTempFile("xtts_", ".wav");
            Files.write(tmp, wavBytes);
            playViaPowerShellAsync(tmp.toFile());
        } catch (Exception e) {}
    }


    private void speakVoiceVox(String text, String speakerId, String base) {
        try {
            String queryUrl = base + "/audio_query?text=" +
                    URLEncoder.encode(text, StandardCharsets.UTF_8) +
                    "&speaker=" + speakerId;
            HttpURLConnection q = (HttpURLConnection) new URL(queryUrl).openConnection();
            q.setRequestMethod("POST");
            q.setDoOutput(true);
            q.getOutputStream().write(new byte[0]);
            String queryJson = new String(q.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String synthUrl = base + "/synthesis?speaker=" + speakerId;
            HttpURLConnection s = (HttpURLConnection) new URL(synthUrl).openConnection();
            s.setRequestMethod("POST");
            s.setDoOutput(true);
            s.setRequestProperty("Content-Type", "application/json");
            s.getOutputStream().write(queryJson.getBytes(StandardCharsets.UTF_8));
            byte[] wavBytes;
            try (InputStream in = s.getInputStream()) {
                wavBytes = in.readAllBytes();
            }

            if (!looksLikeWav(wavBytes)) {
                Config.logDebug("[VV] synth bytes not wav");
                return;
            }
            Config.logDebug("[VV] bytes size=" + wavBytes.length);
            playViaPowerShellBytesAsync(wavBytes);
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                } catch (Exception ignore) {}
            }).start();
        } catch (Exception ex) {
            Config.logDebug("[VV ERROR] " + ex.getMessage());
        }
    }
    public record VoiceVoxSpeaker(int id, String name) {}
    private List<VoiceVoxSpeaker> getVoiceVoxSpeakers() {
        List<VoiceVoxSpeaker> list = new ArrayList<>();
        try {
            String base = Config.getString("voicevox.api", "");
            if (base.isEmpty()) return list;
            URL url = new URL(base + "/speakers");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(500);
            conn.setReadTimeout(1000);
            conn.connect();
            if (conn.getResponseCode() != 200) return list;
            String json = new String(
                    conn.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.size(); i++) {
                JSONObject sp = arr.getJSONObject(i);
                String speakerName = sp.getString("name");
                JSONArray styles = sp.getJSONArray("styles");
                for (int j = 0; j < styles.size(); j++) {
                    JSONObject st = styles.getJSONObject(j);
                    int id = st.getInt("id");
                    String styleName = st.getString("name");
                    list.add(new VoiceVoxSpeaker(
                            id,
                            speakerName + " (" + styleName + ")"
                    ));
                }
            }
        } catch (Exception e) {
            Config.log("[VV] getVoiceVoxSpeakers failed: " + e.getMessage());
        }
        return list;
    }
    // VOICEVOX /speakers から speaker->(styleName->styleId) を構築してキャッシュ
    // VOICEVOX: text内容からスタイル（感情）を自動選択する
    // - 依存追加なし（org.jsonのみ）
    // - baseStyleId は prefs "tts.voice" の値（ユーザーが選んだ style_id）を想定
    private static final class VoiceVoxStyles {

        private enum Emotion {
            NORMAL, SWEET, TSUN, WHISPER, SAD, ANGRY, JOY, CALM
        }

        private static final class StyleGroup {
            final Map<String, Integer> nameToId = new HashMap<>();
            final EnumMap<Emotion, Integer> emoToId = new EnumMap<>(Emotion.class);
            int normalId = 0;
        }

        private static final Object LOCK = new Object();
        private static volatile long lastLoadMs = 0;
        private static final long TTL_MS = 600_000;

        // style_id -> group で引けるようにする（baseStyleId がノーマルじゃなくても対応）
        private static volatile Map<Integer, StyleGroup> styleIdToGroup = Map.of();
        public static int pickStyleId(String text, int baseStyleId, String apiBase, String uiLang) {
            try {
                StyleGroup g = getGroup(apiBase, baseStyleId);
                if (g == null) return baseStyleId;

                Emotion e = inferEmotion(text, uiLang);

                // ★ 基本は設定IDを尊重
                if (e == Emotion.NORMAL) return baseStyleId;

                Integer id = g.emoToId.get(e);

                // ★ここが肝：感情カテゴリがズレても、近いスタイルへ寄せる（キャラ差吸収）
                if (id == null) {
                    if (e == Emotion.ANGRY) {          // 怒り→ツンツンがあるならそれ
                        id = firstNonNull(g.emoToId.get(Emotion.TSUN), g.emoToId.get(Emotion.ANGRY));
                    } else if (e == Emotion.JOY) {     // テンション→元気が無ければ甘々へ寄せる
                        id = firstNonNull(g.emoToId.get(Emotion.JOY), g.emoToId.get(Emotion.SWEET));
                    } else if (e == Emotion.SAD) {     // 悲しみ→落ち着き/ささやきへ寄せる
                        id = firstNonNull(g.emoToId.get(Emotion.SAD), g.emoToId.get(Emotion.CALM), g.emoToId.get(Emotion.WHISPER));
                    } else if (e == Emotion.WHISPER) { // ささやき→CALMへ寄せる
                        id = firstNonNull(g.emoToId.get(Emotion.WHISPER), g.emoToId.get(Emotion.CALM));
                    } else if (e == Emotion.SWEET) {   // 甘々→無ければCALM
                        id = firstNonNull(g.emoToId.get(Emotion.SWEET), g.emoToId.get(Emotion.CALM));
                    }
                }

                int chosen = (id != null) ? id : baseStyleId;

                // ★動いてるか分からん問題のためのログ（必要なときだけ出る）
                if (chosen != baseStyleId) {
                    Config.logDebug("[VV_STYLE] base=" + baseStyleId + " -> " + chosen + " emo=" + e + " text=" + shorten(text, 40));
                }

                return chosen;
            } catch (Exception ex) {
                return baseStyleId;
            }
        }
        private static String shorten(String s, int max) {
            if (s == null) return "";
            String t = s.replace("\n", " ").trim();
            return (t.length() <= max) ? t : t.substring(0, max) + "...";
        }
        private static StyleGroup getGroup(String apiBase, int baseStyleId) throws Exception {
            long now = System.currentTimeMillis();
            if (now - lastLoadMs > TTL_MS || styleIdToGroup.isEmpty()) {
                synchronized (LOCK) {
                    long now2 = System.currentTimeMillis();
                    if (now2 - lastLoadMs > TTL_MS || styleIdToGroup.isEmpty()) {
                        styleIdToGroup = load(apiBase);
                        lastLoadMs = now2;
                    }
                }
            }
            return styleIdToGroup.get(baseStyleId);
        }

        private static Map<Integer, StyleGroup> load(String apiBase) throws Exception {
            if (apiBase == null || apiBase.isEmpty()) return Map.of();

            String url = apiBase.endsWith("/") ? (apiBase + "speakers") : (apiBase + "/speakers");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(500);
            conn.setReadTimeout(1000);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() != 200) {
                Config.logDebug("[VV] /speakers status=" + conn.getResponseCode());
                return Map.of();
            }

            String json = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(json);

            Map<Integer, StyleGroup> index = new HashMap<>();

            for (int i = 0; i < arr.size(); i++) {
                JSONObject sp = arr.getJSONObject(i);
                JSONArray styles = sp.getJSONArray("styles");

                StyleGroup g = new StyleGroup();
                List<Integer> idsInGroup = new ArrayList<>();

                for (int j = 0; j < styles.size(); j++) {
                    JSONObject st = styles.getJSONObject(j);
                    int id = st.getInt("id");
                    String name = st.getString("name"); // 多くは日本語だが、念のため英字も考慮

                    g.nameToId.put(name, id);
                    idsInGroup.add(id);

                    Emotion emo = emotionFromStyleName(name);
                    // 先に入ってたら上書きしない（キャラによって似た名前が複数ある事故を避ける）
                    g.emoToId.putIfAbsent(emo, id);

                    if (isNormalStyleName(name)) {
                        g.normalId = id;
                        g.emoToId.put(Emotion.NORMAL, id);
                    }
                }

                // 「ノーマル」が無いキャラ向け：NORMALの代替を用意
                if (g.emoToId.get(Emotion.NORMAL) == null) {
                    // まず CALM/JOY 以外の何かを NORMAL 扱いに（雑だけど事故りにくい）
                    Integer fallback = firstNonNull(
                            g.emoToId.get(Emotion.CALM),
                            g.emoToId.get(Emotion.JOY),
                            idsInGroup.isEmpty() ? null : idsInGroup.get(0)
                    );
                    if (fallback != null) g.emoToId.put(Emotion.NORMAL, fallback);
                }

                // この group 内の全 style_id が group に紐づくように登録
                for (int id : idsInGroup) {
                    index.put(id, g);
                }
            }

            Config.logDebug("[VV] styles cached groups=" + arr.size() + " indexSize=" + index.size());
            return Collections.unmodifiableMap(index);
        }

        // -------- 感情推定（多言語） --------
        private static Emotion inferEmotion(String text, String uiLang) {
            if (text == null) return Emotion.NORMAL;
            String t = stripLeadingFillers(text).trim();

            // 怒り・悲しみは最優先（VC事故防止）
            if (containsAny(t, TOK_ANGRY)) return Emotion.ANGRY;
            if (containsAny(t, TOK_SAD))   return Emotion.SAD;

            // ささやき（秘密/小声）系
            if (containsAny(t, TOK_WHISPER) || t.contains("...") || t.contains("…")) return Emotion.WHISPER;

            // 謝罪・感謝は SWEET に寄せる（無い場合はノーマルへフォールバック）
            if (containsAny(t, TOK_SORRY) || containsAny(t, TOK_THANKS)) return Emotion.SWEET;

            // テンション/笑い
            if (containsAny(t, TOK_JOY)) return Emotion.JOY;

            // 疑問文は基本ノーマル（変に演技させない）
            if (t.endsWith("?") || t.endsWith("？") || containsAny(t, TOK_QUESTION)) return Emotion.NORMAL;

            // UI言語で追加ルールを分けたい場合のフック（今は軽く）
            if ("ko".equalsIgnoreCase(uiLang) && t.contains("ㅋㅋ")) return Emotion.JOY;
            if (uiLang != null && uiLang.startsWith("zh") && (t.contains("哈哈") || t.contains("么") || t.contains("嗎"))) return Emotion.JOY;

            return Emotion.NORMAL;
        }
        private static String stripLeadingFillers(String s) {
            String t = (s == null) ? "" : s.trim();

            // 最大3回、前置き（フィラー）を剥がす
            for (int i = 0; i < 3; i++) {
                String t2 = removeOneLeadingFiller(t);
                if (t2.equals(t)) break;
                t = t2.trim();
            }
            return t;
        }
        private static String removeOneLeadingFiller(String t) {
            if (t == null) return "";
            String s = t.trim();
            // 句読点/区切りの後に来るフィラーを想定（「お、」「um,」「呃，」など）
            // 言語自動判定（ざっくり）
            if (containsCjk(s)) {
                // 日本語（ひらがな/カタカナがあればJP寄り）
                if (containsJapaneseKana(s)) {
                    return s.replaceFirst("^(お|え|あ|う|ん|ま|えー|あの|その|えっと|まじで|てか)[、,。\\.\\s]+", "");
                }
                // 中国語（簡/繁）
                return s.replaceFirst("^(呃|嗯|那个|那個|就是|其實|其实|啊|唉)[，,。\\.\\s]+", "");
            } else {
                // 英語/その他（とりあえず英語フィラー）
                return s.replaceFirst("^(um|uh|er|hmm|like|you\\s+know|well|so)[,\\.\\s]+", "");
            }
        }
        private static boolean containsCjk(String s) {
            // CJK統合漢字 + ひらがな/カタカナが含まれるか
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3040 && c <= 0x30FF)) return true;
            }
            return false;
        }
        private static boolean containsJapaneseKana(String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c >= 0x3040 && c <= 0x30FF) return true;
            }
            return false;
        }
        // -------- スタイル名 → 感情カテゴリ（VOICEVOX側の名称に寄せる） --------
        private static Emotion emotionFromStyleName(String name) {
            if (name == null) return Emotion.NORMAL;
            String n = name.toLowerCase(Locale.ROOT);

            if (isNormalStyleName(name)) return Emotion.NORMAL;

            if (containsAny(n, ALIAS_SWEET))   return Emotion.SWEET;
            if (containsAny(n, ALIAS_TSUN))    return Emotion.TSUN;    // 使う側で ANGRY と近い扱いにしてOK
            if (containsAny(n, ALIAS_WHISPER)) return Emotion.WHISPER;
            if (containsAny(n, ALIAS_SAD))     return Emotion.SAD;
            if (containsAny(n, ALIAS_ANGRY))   return Emotion.ANGRY;
            if (containsAny(n, ALIAS_JOY))     return Emotion.JOY;
            if (containsAny(n, ALIAS_CALM))    return Emotion.CALM;

            return Emotion.NORMAL;
        }
        private static boolean isNormalStyleName(String name) {
            if (name == null) return false;
            return "ノーマル".equals(name) || "normal".equalsIgnoreCase(name.trim());
        }
        // -------- util --------
        private static boolean containsAny(String s, String[] keys) {
            if (s == null) return false;
            for (String k : keys) {
                if (k != null && !k.isEmpty() && s.contains(k)) return true;
            }
            return false;
        }
        private static Integer firstNonNull(Integer... xs) {
            for (Integer x : xs) if (x != null) return x;
            return null;
        }
        // text側（多言語）キーワード
        private static final String[] TOK_THANKS = {
                "ありがとう", "感謝", "助かる", "thank", "thanks", "thx", "謝謝", "多谢", "谢谢", "고마워", "감사"
        };
        private static final String[] TOK_SORRY = {
                "ごめん", "すみません", "申し訳", "sorry", "apolog", "對不起", "不好意思", "죄송", "미안"
        };
        private static final String[] TOK_JOY = {
                "!", "！", "w", "笑", "www", "lol", "haha", "xd", "草", "哈哈", "ㅋㅋ"
        };
        private static final String[] TOK_ANGRY = {
                "怒", "ムカ", "ふざけ", "やめろ", "くそ", "クソ", "angry", "mad", "wtf", "氣死", "气死", "짜증"
        };
        private static final String[] TOK_SAD = {
                "つら", "泣", "かなしい", "悲", "しんど", "sad", "tired", "難過", "难过", "슬퍼"
        };
        private static final String[] TOK_WHISPER = {
                "小声", "内緒", "ひそひそ", "ささや", "whisper", "secret", "悄悄", "小聲", "속삭"
        };
        private static final String[] TOK_QUESTION = {
                "かな", "ですか", "？", "吗", "嗎", "呢", "니", "냐"
        };
        // style名側（VOICEVOXの styles.name に合わせる：日本語中心 + 保険で英字）
        private static final String[] ALIAS_SWEET   = { "あまあま", "甘々", "sweet", "amaama", "デレ", "でれ" };
        private static final String[] ALIAS_TSUN    = { "ツンツン", "tsun" , "ツン", "つん" };
        private static final String[] ALIAS_WHISPER = { "ささやき", "ひそひそ", "ヒソヒソ", "whisper", "囁", "ささや" };
        private static final String[] ALIAS_SAD     = { "せつない", "泣", "悲", "sad" };
        private static final String[] ALIAS_ANGRY   = { "怒", "angry", "おこ", "キレ" };
        private static final String[] ALIAS_JOY     = { "元気", "テンション", "ハイ", "joy", "happy", "たのしい", "楽しい" };
        private static final String[] ALIAS_CALM    = { "のんびり", "落ち着", "calm", "まじめ", "真面目" };
    }


    public void speakWindows(String text) {
        if (text == null || text.isBlank()) return;
        try {
            byte[] wavBytes = synthWindowsToWavBytesViaAgent(text);
            if (!looksLikeWav(wavBytes)) {
                Config.logDebug("[WIN] synth bytes not wav");
                return;
            }
            playViaPowerShellBytesAsync(wavBytes);
        } catch (Exception ex) {
            Config.logDebug("[WIN ERROR] " + ex.getMessage());
        }
    }
    private List<String> getWindowsVoices() {
        List<String> voices = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(
                    "powershell",
                    "-Command",
                    "Add-Type -AssemblyName System.Speech; " +
                            "[System.Speech.Synthesis.SpeechSynthesizer]::new().GetInstalledVoices() | " +
                            "ForEach-Object { $_.VoiceInfo.Name }"
            ).start();

            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank()) voices.add(line.trim());
                }
            }
        } catch (Exception ignored) {
        }
        return voices;
    }
    private final ExecutorService playExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "tts-play-thread");
                t.setDaemon(true);
                return t;
            });


    private int psHealthCounter = 0;
    private static final int PS_HEALTH_CHECK_INTERVAL = 3;
    public void playViaPowerShellAsync(File wavFile) {

        // ★ファイルを読み込んでバイト配列に変換
        try {
            byte[] wavBytes = Files.readAllBytes(wavFile.toPath());
            playViaPowerShellBytesAsync(wavBytes); // ★こっちを呼べば自動的にメーター更新される
        } catch (Exception e) {
            Config.logDebug("Failed to play wav file: " + e.getMessage());
        }

        playExecutor.submit(() -> {
            synchronized (psLock) {
                try {
                    ensurePsServerLocked();

                    int devIndex = findOutputDeviceIndex(
                            prefs.get("audio.output.device", "")
                    );

                    psWriter.write(
                            "PLAY \"" + wavFile.getAbsolutePath() + "\" " + devIndex + "\n"
                    );
                    psWriter.flush();

                    String resp = psReader.readLine();
                    if (!"DONE".equals(resp)) {
                        psHealthCounter = 0;
                        throw new IOException("tts_agent bad response: " + resp);
                    }
                    psHealthCounter++;
                    if (psHealthCounter >= PS_HEALTH_CHECK_INTERVAL) {
                        psHealthCounter = 0;
                    }

                } catch (Exception e) {
                    Config.log("TTS agent error: " + e.getMessage());
                    if (e instanceof IOException) {
                        Config.log("TTS pipe dead → restarting agent");
                        stopPsServerLocked();
                    }
                }
            }
        });
    }
    public void playViaPowerShellBytesAsync(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length < 256) return;

        // ★スピーカー出力レベルを計算してメーターに反映
        updateSpeakerMeterFromWav(wavBytes);

        isTtsSpeaking = true;  // ★ADD: 再生開始前にフラグON
        playExecutor.submit(() -> {
            synchronized (psLock) {
                try {
                    ensurePsServerLocked();

                    int devIndex = findOutputDeviceIndex(
                            prefs.get("audio.output.device", "")
                    );

                    String id = Long.toHexString(System.nanoTime());
                    String b64 = Base64.getEncoder().encodeToString(wavBytes);

                    psWriter.write("PLAYB64 " + devIndex + " " + id + "\n");

                    final int CHUNK = 12000;
                    for (int i = 0; i < b64.length(); i += CHUNK) {
                        int end = Math.min(b64.length(), i + CHUNK);
                        psWriter.write("DATA " + id + " " + b64.substring(i, end) + "\n");
                    }
                    psWriter.write("END " + id + "\n");
                    psWriter.flush();

                    String resp = psReader.readLine();
                    if (!"DONE".equals(resp)) {
                        psHealthCounter = 0;
                        throw new IOException("tts_agent bad response: " + resp);
                    }
                    psHealthCounter++;
                    if (psHealthCounter >= PS_HEALTH_CHECK_INTERVAL) psHealthCounter = 0;

                } catch (Exception e) {
                    Config.log("TTS agent error: " + e.getMessage());
                    if (e instanceof IOException) {
                        Config.log("TTS pipe dead → restarting agent");
                        stopPsServerLocked();
                    }
                } finally {
                    isTtsSpeaking = false;  // ★ADD: DONE受信後（または例外後）にフラグOFF
                }
            }
        });
    }
    // ===== スピーカー出力レベル計算 =====
    private void updateSpeakerMeterFromWav(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length < 44) return;
        if (gainMeter == null) return;

        try {
            // WAVヘッダーをスキップ（44バイト）
            int dataStart = 44;
            int dataLength = wavBytes.length - dataStart;

            if (dataLength <= 0) return;

            // RMS計算（16bit PCM想定）
            long sumSquares = 0;
            int sampleCount = 0;

            for (int i = dataStart; i + 1 < wavBytes.length; i += 2) {
                // 16bit little-endian
                int sample = (short) ((wavBytes[i + 1] << 8) | (wavBytes[i] & 0xFF));
                sumSquares += (long) sample * sample;
                sampleCount++;
            }

            if (sampleCount == 0) return;

            // RMS値を計算
            double rms = Math.sqrt((double) sumSquares / sampleCount);

            // dB計算（-60dB ～ 0dB）
            double db = -60.0;
            if (rms > 0) {
                db = 20.0 * Math.log10(rms / 32768.0);
                db = Math.max(-60.0, Math.min(0.0, db));
            }

            // レベル計算（0-100）
            // -60dB = 0, 0dB = 100
            int level = (int) ((db + 60.0) / 60.0 * 100.0);
            level = Math.max(0, Math.min(100, level));

            // メーターに反映
            gainMeter.setSpeakerValue(level, db);

            // 音声の長さに応じて自動的にリセット（再生終了後にメーターを下げる）
            int durationMs = (sampleCount * 1000) / 16000; // 16kHz想定
            Timer resetTimer = new Timer(durationMs, e -> {
                if (gainMeter != null) {
                    gainMeter.setSpeakerValue(0, -60.0);
                }
            });
            resetTimer.setRepeats(false);
            resetTimer.start();

        } catch (Exception e) {
            Config.logDebug("Failed to update speaker meter: " + e.getMessage());
        }
    }

    private static boolean looksLikeWav(byte[] b) {
        if (b == null || b.length < 12) return false;
        return (b[0]=='R' && b[1]=='I' && b[2]=='F' && b[3]=='F'
                && b[8]=='W' && b[9]=='A' && b[10]=='V' && b[11]=='E');
    }
    private void ensurePsServerLocked() throws Exception {
        if (psProcess != null && psProcess.isAlive()
                && psWriter != null && psReader != null) {
            return;
        }
        stopPsServerLocked();
        startPsServer();
    }
    private void playLaughSound(String path) {
        try {
            Path base = Paths.get(System.getProperty("user.dir"));
            Path p = base.resolve(path);
            File f = p.toFile();
            if (!f.exists()) {
                Config.log("NOT FOUND: " + f.getAbsolutePath());
                return;
            }
            playViaPowerShellAsync(f);
        } catch (Exception ignored) {
        }
    }
    private static String escapeJson(String s) {
        return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
    private String escapePs(String s) {
        return s.replace("'", "''");
    }
    public void logToHistory(String msg) {
        if (debug) {
            Config.log(msg);
            SwingUtilities.invokeLater(() -> {
                addHistory(msg);
            });
        }
    }
    private void autoStartVoiceVox() {
        String vvExe = Config.getString("voicevox.exe", "").trim();
        if (vvExe.isEmpty()) {
            Config.logDebug("VOICEVOX dir not set.");
            return;
        }
        File f = new File(vvExe);
        if (!f.exists()) {
            Config.logDebug("VOICEVOX exe not found: " + vvExe);
            return;
        }
        try {
            Config.logDebug("Starting VOICEVOX: " + vvExe);
            String vvExePath = f.getAbsolutePath();
            ProcessBuilder pb = new ProcessBuilder(
                    "cmd", "/c", "start", "\"\"", "\""+ vvExePath + "\""
            );
            pb.directory(f.getParentFile());
            pb.start();
            Config.logDebug("VOICEVOX started.");
        } catch (Exception ignored) {
        }
    }
    private boolean waitForValidWav(File f, int timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] header = new byte[12];
                int read = fis.read(header);
                if (read == 12) {
                    String riff = new String(header, 0, 4);
                    String wave = new String(header, 8, 4);
                    if ("RIFF".equals(riff) && "WAVE".equals(wave)) {
                        return true;
                    }
                }
            } catch (Exception ignore) {}

            try { Thread.sleep(50); } catch (InterruptedException ignore) {}
        }
        return false;
    }
    private int findOutputDeviceIndex(String deviceName) {
        List<String> outputMixers = getOutputMixerNames();
        for (int i = 0; i < outputMixers.size(); i++) {
            if (outputMixers.get(i).equals(deviceName)) {
                return i -1;
            }
        }
        return -1;
    }
    private void startPsServer() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "powershell",
                "-NoLogo",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-File", "tts_agent.ps1"
        );
        pb.redirectErrorStream(true);
        psProcess = pb.start();
        psWriter = new BufferedWriter(
                new OutputStreamWriter(psProcess.getOutputStream(), StandardCharsets.UTF_8)
        );
        psReader = new BufferedReader(
                new InputStreamReader(psProcess.getInputStream(), StandardCharsets.UTF_8)
        );
    }
    private void ensurePsServer() throws Exception {
        synchronized (psLock) {
            if (psProcess != null && psProcess.isAlive() && psWriter != null && psReader != null) return;
            if (psStarting) return;
            psStarting = true;
            try {
                stopPsServerLocked();
                startPsServer();
            } finally {
                psStarting = false;
            }
        }
    }
    private void stopPsServerLocked() {
        try { if (psWriter != null) psWriter.close(); } catch (Exception ignore) {}
        try { if (psReader != null) psReader.close(); } catch (Exception ignore) {}
        try { if (psProcess != null) psProcess.destroy(); } catch (Exception ignore) {}
        psWriter = null;
        psReader = null;
        psProcess = null;
    }

    private String normalizeLaugh(String s) {
        if (s == null || s.isEmpty()) return s;
        if (!Config.getBool("laughs.enable", true)) return s;
        String[] replace = Config.splitCsv(Config.get("laughs.replace"));
        if (replace.length == 0) return s;
        String[] tokens = getLaughDetectTokens();
        if (tokens.length == 0) return s;
        String lower = s.toLowerCase(Locale.ROOT);
        boolean hit = false;
        for (String t : tokens) {
            if (t == null || t.isBlank()) continue;
            if (s.contains(t) || lower.contains(t.toLowerCase(Locale.ROOT))) {
                hit = true;
                break;
            }
        }
        if (!hit) return s;
        String pick = replace[rnd.nextInt(replace.length)];
        if (pick.contains("/") || pick.contains("\\")) {
            playLaughSound(pick);
            pick = getLaughOutputLabel();
        }
        List<String> sortedTokens = Arrays.stream(tokens)
                .filter(t -> t != null && !t.isBlank())
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .toList();
        String out = s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            String tt = tokens[i];
            if (tt == null) continue;
            tt = tt.trim();
            if (tt.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append("|");
            sb.append(Pattern.quote(tt));
        }
        Pattern ptn = Pattern.compile("(?i)(" + sb + ")");
        out = ptn.matcher(out).replaceAll(Matcher.quoteReplacement(pick));
        out = out.replaceAll("(?i)" + Pattern.quote(pick) + "[\\p{IsHiragana}\\p{IsKatakana}ー]*", pick);
        return out;
    }
    private String getLaughOutputLabel() {
        String cfg = Config.loadSetting("laughs.detect", "lol");
        if (cfg == null || cfg.isEmpty()) return "lol";
        int idx = cfg.indexOf(',');
        return (idx >= 0) ? cfg.substring(0, idx).trim() : cfg.trim();
    }
    private String[] getLaughDetectTokens() {
        String cfg = Config.loadSetting("laughs.detect", "lol");
        if (cfg == null || cfg.isBlank()) return new String[] { "lol" };
        String[] raw = cfg.split("[,、]");
        return Arrays.stream(raw)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .toArray(String[]::new);
    }

    boolean hasVirtualAudioDevice() {
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (Mixer.Info info : mixers) {
            String name = info.getName().toLowerCase();
            if (name.contains("virtual") || name.contains("sonar")) {
                return true;
            }
        }
        return false;
    }
    private void loadWhisperNative() {

        File exeDir = getExeDir();
        File libsDir = new File(exeDir, "libs");
        Config.log("ExeDir = " + exeDir);
        Config.log("LibsDir = " + libsDir);
        Backend[] backends = new Backend[]{
                new Backend("CUDA", new File(libsDir, "cuda"),
                        new String[]{
                                "cublas64_12.dll",
                                "cublasLt64_12.dll",
                                "cudart64_12.dll",
                                "ggml-cuda.dll",
                                "ggml-base.dll",
                                "ggml-cpu.dll",
                                "ggml.dll",
                        },
                        "whisper.dll"
                ),
                new Backend("Vulkan", new File(libsDir, "vulkan"),
                        new String[]{
                                "ggml-vulkan.dll",
                                "ggml-base.dll",
                                "ggml-cpu.dll",
                                "ggml.dll",
//                                "vulkan-1.dll",
                        },
                        "whisper.dll"
                ),
                new Backend("CPU", new File(libsDir, "cpu"),
                        new String[]{
                                "ggml-base.dll",
                                "ggml-cpu.dll",
                                "ggml.dll"
                        },
                        "whisper.dll"
                )
        };
        // ★ Vulkan GPU selection must be set BEFORE any DLL load
        int gpuIndex = prefs.getInt("vulkan.gpu.index", -1);
        if (gpuIndex >= 0) {
            setNativeEnv("GGML_VULKAN_DEVICE", String.valueOf(gpuIndex));
            // ★ ggml側が参照する可能性のある変数も念のため設定
            setNativeEnv("GGML_VK_VISIBLE_DEVICES", String.valueOf(gpuIndex));
            Config.log("Using Vulkan GPU index: " + gpuIndex);
        } else {
            setNativeEnv("GGML_VULKAN_DEVICE", null);
            setNativeEnv("GGML_VK_VISIBLE_DEVICES", null);
            Config.log("Using Vulkan GPU auto selection");
        }

        boolean vulkanDlcOwned = SteamHelper.hasVulkanDlc();
        Config.log("[Steam] Vulkan DLC owned: " + vulkanDlcOwned);

        for (Backend b : backends) {
            Config.log("Checking backend: " + b.name + " in " + b.dir);
            // ★ADD: Vulkan loaderは同梱ではなくシステム側を確認する
            if ("Vulkan".equals(b.name) && !isSystemVulkanLoaderPresent()) {
                Config.log(" → vulkan-1.dll not found in System32. Skip Vulkan backend.");
                continue;
            }

            // ★ DLCチェック追加（ここだけ追加）
            if (("Vulkan".equals(b.name) || "CUDA".equals(b.name)) && !vulkanDlcOwned) {
                Config.log(" → " + b.name + " DLC not owned. Falling back to CPU.");
                continue;
            }

            // ★ADD: backendファイル欠けの見える化（DLC未導入/破損/AV隔離の切り分け）
            java.util.List<String> missing = new java.util.ArrayList<>();
            for (String dep : b.deps) {
                File f = new File(b.dir, dep);
                if (!f.exists()) missing.add(dep);
            }
            File mainSrc = new File(b.dir, b.mainDll);
            if (!mainSrc.exists()) missing.add(b.mainDll);

            Config.log(" → dir.exists=" + b.dir.exists() + " missing=" + missing);

            if (!missing.isEmpty()) {
                Config.log(" → Missing backend files. Skip: " + b.name);
                continue;
            }
            if (!b.dir.exists()) {
                Config.log(" → Directory does not exist, skipping.");
                continue;
            }
            Config.log("Copying backend DLLs to exe folder...");
            List<File> copied = copyDllsToExeDir(b, exeDir);
            try{ Thread.sleep(300); } catch (Exception e) {}
            Config.log("Trying to load backend: " + b.name);
            File mainInExe = new File(exeDir, b.mainDll);
            if (safeLoad(mainInExe.getAbsolutePath())) {
                System.out.println("Loaded " + b.name + " backend successfully.");
                if (!"CPU".equals(b.name)) {
                    cpugpumode = b.name + " MODE";
                }
                return;
            }
        }
        JOptionPane.showMessageDialog(
                null,
                "Cannot load Whisper backend.\n\n" +
                        "MobMate requires CPU / CUDA / Vulkan backend files.\n" +
                        "libsDir = " + libsDir,
                "MobMate Error",
                JOptionPane.ERROR_MESSAGE
        );
        Runtime.getRuntime().halt(1);
    }
    private static boolean isSystemVulkanLoaderPresent() {
        try {
            String win = System.getenv("WINDIR");
            if (win == null || win.isEmpty()) return false;
            File f = new File(win, "System32\\vulkan-1.dll");
            return f.exists() && f.isFile();
        } catch (Throwable t) {
            return false;
        }
    }
    private static void setNativeEnv(String name, String value) {
        try {
            com.sun.jna.platform.win32.Kernel32 k =
                    com.sun.jna.platform.win32.Kernel32.INSTANCE;
            k.SetEnvironmentVariable(name, value); // null渡しで削除
            Config.log("★setNativeEnv: " + name + "=" + value);
        } catch (Throwable t) {
            // JNA失敗時はSystem.setPropertyにフォールバック（効果は薄いが無害）
            Config.log("★setNativeEnv failed: " + t.getMessage());
            if (value != null) System.setProperty(name, value);
            else System.clearProperty(name);
        }
    }
    public File getExeDir() {
        try {
            String path = MobMateWhisp.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
                    .getPath();
            File f = new File(path);
            if (f.getName().toLowerCase().endsWith(".exe")) {
                return f.getParentFile();
            }
            if (f.getName().toLowerCase().endsWith(".jar")) {
                return f.getParentFile();
            }
            return new File(System.getProperty("user.dir"));
        } catch (Exception e) {
            return new File(System.getProperty("user.dir"));
        }
    }

    static class Backend {
        String name;
        File dir;
        String[] deps;
        String mainDll;
        Backend(String name, File dir, String[] deps, String mainDll) {
            this.name = name;
            this.dir = dir;
            this.deps = deps;
            this.mainDll = mainDll;
        }
    }
    private boolean safeLoad(String path) {
        File f = new File(path);
        Config.log("safeLoad: " + path);
        if (!f.exists()) return false;
        try {
            System.load(f.getAbsolutePath());
            return true;
        } catch (UnsatisfiedLinkError e) {
            Config.logError("Failed to load: " + path + " (" + e.getMessage() + ")", e);
            return false;
        }
    }
    private List<File> copyDllsToExeDir(Backend b, File exeDir) {
        List<File> copied = new ArrayList<>();
        for (String dep : b.deps) {
            File src = new File(b.dir, dep);
            File dst = new File(exeDir, dep);
            if (dst.exists()) dst.delete();
            if (src.exists()) {
                try {
                    Files.copy(src.toPath(), dst.toPath());
                    copied.add(dst);
                } catch (Exception e) {
                    Config.logError("Copy failed: " + e.getMessage(),e);
                }
            }
        }
        File mainSrc = new File(b.dir, b.mainDll);
        File mainDst = new File(exeDir, b.mainDll);
        if (mainDst.exists()) mainDst.delete();
        try {
            Files.copy(mainSrc.toPath(), mainDst.toPath());

            copied.add(mainDst);
        } catch (Exception e) {
            Config.logError("Copy failed: " + e.getMessage(), e);
        }
        return copied;
    }

    private static void ensureInitialConfig() {
        File outtts = new File("_outtts.txt");
        if (outtts.exists()) return;
        // まず暫定でフォント適用（言語選択ダイアログが豆腐になるのを防ぐ）
        FontBootstrap.registerBundledFonts();
        UiFontApplier.applyDefaultUIFontFromSystemLocale();

        int choice = showLanguageSelectDialog();
        if (choice < 0) {
            System.exit(0);
        }
        String suffix;
        switch (choice) {
            case 1: suffix = "ja"; break;
            case 2: suffix = "zh_cn"; break;
            case 3: suffix = "zh_tw"; break;
            case 4: suffix = "ko"; break;
            case 0:
            default: suffix = "en"; break;
        }
        UiFontApplier.applyDefaultUIFontBySuffix(suffix);
        prefs = Preferences.userRoot().node("MobMateWhispTalk");
        prefs.put("ui.language", suffix);
        FontBootstrap.registerBundledFonts();
        UiFontApplier.applyDefaultUIFontBySuffix(suffix);
        UiText.load(UiLang.resolveUiFile(suffix));
        copyPreset("libs/preset/_outtts_" + suffix + ".txt", "_outtts.txt");
        copyPreset("libs/preset/_dictionary_" + suffix + ".txt", "_dictionary.txt");
        copyPreset("libs/preset/_ignore_" + suffix + ".txt", "_ignore.txt");
        copyPreset("libs/preset/_radiocmd_" + suffix + ".txt", "_radiocmd.txt");
        JOptionPane.showMessageDialog(
                null,
                "Initial configuration created.\nThe application will restart.",
                "MobMate",
                JOptionPane.INFORMATION_MESSAGE
        );
        restartSelf(false);
    }
    private static void copyPreset(String srcName, String dstName) {
        try {
            Path src = Paths.get(srcName);
            Path dst = Paths.get(dstName);
            if (!Files.exists(src)) return;
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }
    // ★_radiocmd.txt を読み込んで prefs に反映（存在するものは上書き）
    private static void loadRadioCmdFileToPrefs(Preferences p, File file) {
        if (p == null || file == null || !file.exists()) return;

        int count = 0;
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(
                    file.toPath(), java.nio.charset.StandardCharsets.UTF_8
            );

            for (String line : lines) {
                if (line == null) continue;
                String s = line.trim();
                if (s.isEmpty()) continue;
                if (s.startsWith("#") || s.startsWith("//") || s.startsWith(";")) continue;

                int eq = s.indexOf('=');
                if (eq <= 0) continue;

                String key = s.substring(0, eq).trim();
                String val = s.substring(eq + 1).trim();

                // 末尾コメントを軽く除去（"..." の外だけ想定の簡易版）
                int hash = val.indexOf(" #");
                if (hash >= 0) val = val.substring(0, hash).trim();
                int sl = val.indexOf(" //");
                if (sl >= 0) val = val.substring(0, sl).trim();

                // "..." / '...' のクォートを外す
                if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                    if (val.length() >= 2) val = val.substring(1, val.length() - 1);
                }

                // radio.p0.1 形式だけ取り込む（事故防止）
                if (!key.startsWith("radio.p")) continue;

                p.put(key, val);
                count++;
            }

            if (count > 0) {
                try { p.sync(); } catch (Exception ignore) {}
                Config.log("RadioCmd loaded: " + count + " entries from " + file.getName());
            } else {
                Config.logDebug("RadioCmd: no valid entries in " + file.getName());
            }
        } catch (Exception e) {
            Config.logError("RadioCmd load failed: " + e.getMessage(), e);
        }
    }


    // ===== ソフトリスタート（プロセス内再初期化） =====
    private void softRestart() {
        Config.log("[SOFT_RESTART] ========== BEGIN ==========");
        SwingUtilities.invokeLater(() -> {
            try {
                softRestartInternal();
            } catch (Throwable t) {
                Config.logError("[SOFT_RESTART] FAILED: " + t, t);
                JOptionPane.showMessageDialog(window,
                        "Soft restart failed:\n" + t.getMessage()
                                + "\n\nFalling back to full restart.",
                        "MobMate", JOptionPane.WARNING_MESSAGE);
                restartSelf(true);
            }
        });
    }

    private void softRestartInternal() throws Exception {
        // ===== Phase 1: クリーンアップ =====
        Config.log("[SOFT_RESTART] Phase 1: cleanup");

        // (0) 後で復元するためにHearingの開閉状態を記憶
        final boolean hearingWasOpen =
                (hearingFrame != null && hearingFrame.isVisible());
        Config.log("[SOFT_RESTART] hearingWasOpen=" + hearingWasOpen);

        // (1) 録音停止
        if (isRecording()) {
            Config.log("[SOFT_RESTART] stopping recording...");
            stopRecording();
        }

        // (2) タイマー停止
        if (meterUpdateTimer != null) {
            meterUpdateTimer.stop();
            meterUpdateTimer = null;
        }
        if (statusUpdateTimer != null) {
            statusUpdateTimer.stop();
            statusUpdateTimer = null;
        }
        Config.log("[SOFT_RESTART] timers stopped");

        // (3) PSサーバー停止
        synchronized (psLock) {
            stopPsServerLocked();
        }
        Config.log("[SOFT_RESTART] PSServer stopped");

        // (4) HearingFrame停止
        try {
            if (hearingFrame != null) {
                hearingFrame.shutdownForExit();
                hearingFrame.setVisible(false);
                hearingFrame.dispose();
                hearingFrame = null;
                hearingActive = false;
            }
        } catch (Throwable ignore) {}

        // (5) Hearing用Whisper解放
        synchronized (hearingWhisperLock) {
            wHearing = null;
        }
        hw = null;  // ★transcribeHearingRaw用キャッシュもクリア

        // ★推論スレッドの完了を待ってからネイティブコンテキスト解放
        // （推論中にwhisper_free()するとSEGFAULT→OSクラッシュ）
        Config.log("[SOFT_RESTART] waiting for inference threads to drain...");
        boolean drained = false;
        for (int i = 0; i < 80; i++) {  // ★最大8秒待機（旧2秒では足りない場合あり）
            if (!transcribeBusy.get() && !isProcessingFinal.get() && !isProcessingPartial.get()) {
                drained = true;
                break;
            }
            try { Thread.sleep(100); } catch (InterruptedException ignore) {}
        }
        if (!drained) {
            Config.logError("[SOFT_RESTART] inference threads did NOT drain in 8s!"
                    + " busy=" + transcribeBusy.get()
                    + " final=" + isProcessingFinal.get()
                    + " partial=" + isProcessingPartial.get(), null);
            // フラグを強制リセット（スレッドはもう応答しない前提）
            transcribeBusy.set(false);
            isProcessingFinal.set(false);
            isProcessingPartial.set(false);
        }
        // ★Phase 1 では native free しない（背景executorがまだアクセスする可能性あり）
        // 代わりに w=null でstaleスレッドをNPEで安全に殺す
        // native free は Phase 2 の LocalWhisperCPP コンストラクタ内 close() に任せる
        Config.log("[SOFT_RESTART] inference drained=" + drained + ", nulling whisper refs");
        // ★v1.5.0: Moonshine解放
        shutdownMoonshine();
        LocalWhisperCPP old = this.w;
        this.w = null;
        try {
            if (old != null) {
//                LocalWhisperCPP.freeAllContexts();
            }
        } catch (Exception e) {
            Config.log("[SOFT_RESTART] close error: " + e.getMessage());
        }
        System.gc();
        try {
            Thread.sleep(1000); // 200〜500msでOK
        } catch (InterruptedException ignored) {}

        // (6) HistoryFrame閉じる
        if (historyFrame != null) {
            try {
                historyFrame.setVisible(false);
                historyFrame.dispose();
            } catch (Throwable ignore) {}
            historyFrame = null;
        }

        // (7) トレイアイコン削除
        if (trayIcon != null && SystemTray.isSupported()) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (Throwable ignore) {}
            trayIcon = null;
        }

        // (8) ウィンドウ位置保存 → dispose
        if (window != null) {
            saveBoundsNow(window, "ui.main");
            window.setVisible(false);
            window.dispose();
            window = null;
        }
        Config.log("[SOFT_RESTART] UI disposed");

        // (9) VAD / ノイズプロファイル リセット
        sharedNoiseProfile = new AdaptiveNoiseProfile();
        vad = new ImprovedVAD(sharedNoiseProfile);
        vadPrimed = false;
        isCalibrationComplete = false;
        lastCalibratedInputDevice = "";

        // (9.5) ラジオオーバーレイ破棄 + 一時状態リセット
        if (radioOverlay != null) {
            try {
                radioOverlay.setVisible(false);
                radioOverlay.dispose();
            } catch (Throwable ignore) {}
            radioOverlay = null;
            radioOverlayArea = null;
            radioOverlayPanel = null;
        }
        radioHeld = false;
        radioConsumed = false;
        radioPage = 0;
        Config.log("[SOFT_RESTART] RadioOverlay disposed, state reset");

        // (10) 各種フラグリセット
        recording = false;
        transcribing = false;
        isPriming = false;
        hotkeyPressed = false;
        transcribeBusy.set(false);
        isStartingRecording.set(false);
        isProcessingFinal.set(false);
        isProcessingPartial.set(false);
        lastPartialResult.set("");
        earlyFinalizeRequested = false;
        pendingLaughAppend = false;
        pendingLaughText = null;
        lastOutput = null;
        Config.log("[SOFT_RESTART] Phase 1 complete");

        // ===== Phase 2: 再初期化 =====
        Config.log("[SOFT_RESTART] Phase 2: reinitialize");

        // (1) Config再読み込み
        Config.syncAllFromCloud();

        // (2) UiText再読み込み
        UiText.load(UiLang.resolveUiFile(prefs.get("ui.language", "en")));

        // (3) フォント再適用（順序重要：family設定→サイズ上書き）
        FontBootstrap.registerBundledFonts();
        String suffix = prefs.get("ui.language", "en");
        UiFontApplier.applyDefaultUIFontBySuffix(suffix);  // family設定（size=12固定）
        int fontSize = prefs.getInt("ui.font.size", 14);   // ★デフォルト16に修正
        applyUIFont(fontSize);                              // ★ユーザーサイズで上書き

        // (4) ★v1.5.0: エンジン選択に応じて再初期化
        if (isRecording()) {
            stopRecording();
        }
        if (isEngineMoonshine()) {
            // Moonshineモード → Whisperは不要、Moonshine再初期化
            Config.log("[SOFT_RESTART] Engine = Moonshine");
            this.w = null;
            initMoonshine();
        } else {
            // Whisperモード → Moonshineは不要、Whisper再読み込み
            Config.log("[SOFT_RESTART] Engine = Whisper");
            try {
                File dir = new File(model_dir);
                MobMateWhisp.this.model = prefs.get("model", model);
                model = prefs.get("model", model);
                File modelFile = new File(dir, model);
                setModelPref(MobMateWhisp.this.model);
                Config.log("[SOFT_RESTART] Model : " + model);
                try {
                    MobMateWhisp.this.w = new LocalWhisperCPP(modelFile, "");
                    Config.log("[SOFT_RESTART] Whisper model reloaded: " + model);
                } catch (FileNotFoundException e1) {
                    Config.logError("[SOFT_RESTART] Model not found: " + modelFile, null);
                }
            } catch (Throwable t) {
                Config.logError("[SOFT_RESTART] Whisper reload failed: " + t, t);
            }
        }

        // (5) SpeakerProfile再作成
        this.speakerProfile = new SpeakerProfile(
                prefs.getInt("speaker.enroll_samples", 5),
                prefs.getFloat("speaker.threshold_initial", 0.60f),
                prefs.getFloat("speaker.threshold_target", 0.82f)
        );

        // (6) 各種設定再読み込み
        String laughSetting = Config.getString("laughs", "ワハハハハハ");
        laughOptions = laughSetting.split(",");
        for (int i = 0; i < laughOptions.length; i++) {
            laughOptions[i] = laughOptions[i].trim();
        }
        this.hotkey = prefs.get("hotkey", "F9");
        this.shiftHotkey = prefs.getBoolean("shift-hotkey", false);
        this.ctrltHotkey = prefs.getBoolean("ctrl-hotkey", false);
        lowGpuMode = prefs.getBoolean("perf.low_gpu_mode", true);
        useAlternateLaugh = prefs.getBoolean("silence.alternate", false);
        autoGainEnabled = prefs.getBoolean("audio.autoGain", true);
        reloadAudioPrefsForMeter();
        loadRadioHotkeyFromPrefs();

        // (6.5) ラジオチャット設定再読み込み
        loadRadioCmdFileToPrefs(prefs, new File("_radiocmd.txt"));
        radioCmdLastLoadedMtime = 0L;   // 強制リロード
        reloadRadioCmdCacheIfUpdated();
        loadOverlayConfigFromOuttts();
        Config.log("[SOFT_RESTART] RadioChat config reloaded");

        // (7) ボタン・ラベル再作成
        this.button = new JButton(UiText.t("ui.main.start"));
        label.setText(UiText.t("ui.main.ready"));

        // (8) VoiceVoxステータスリセット（再起動はしない＝既に動いてる可能性）
        lastVoiceVoxCheckMs = 0;
        vgHealthOkUntilMs = 0L;

        // (9) PSサーバー再起動
        try {
            startPsServer();
        } catch (Throwable t) {
            Config.logError("[SOFT_RESTART] PSServer restart failed: " + t, t);
        }

        // (10) Voiceger再確認
        String engine = prefs.get("tts.engine", "auto").toLowerCase(Locale.ROOT);
        if (engine.contains("voiceger")) {
            VoicegerManager.ensureRunningIfEnabledAsync(prefs);
        }

        // (11) トレイアイコン再作成
        try {
            createTrayIcon();
        } catch (Throwable t) {
            Config.logError("[SOFT_RESTART] TrayIcon failed: " + t, t);
        }

        // (12) ウィンドウ再作成
        openWindow();

        // (13) HearingFrameが開いていたなら復元
        if (hearingWasOpen) {
            try {
                showHearingWindow();
                Config.log("[SOFT_RESTART] HearingFrame restored");
            } catch (Throwable t) {
                Config.logError("[SOFT_RESTART] HearingFrame restore failed: " + t, t);
            }
        }

        Config.log("[SOFT_RESTART] ========== COMPLETE ==========");
    }

    /** ウィンドウ位置を即座にprefsへ保存（dispose前用） */
    private void saveBoundsNow(JFrame frame, String prefix) {
        if (frame == null) return;
        Rectangle b = frame.getBounds();
        if (b.width <= 0 || b.height <= 0) return;
        prefs.putInt(k(prefix, "x"), b.x);
        prefs.putInt(k(prefix, "y"), b.y);
        prefs.putInt(k(prefix, "w"), b.width);
        prefs.putInt(k(prefix, "h"), b.height);
        try { prefs.flush(); } catch (Exception ignore) {}
    }

    private static void restartSelf(boolean issyncneed) {
        try {
            String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
            File current = new File(
                    MobMateWhisp.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()
            );
            ProcessBuilder pb;
            if (current.getName().endsWith(".exe")) {
                pb = new ProcessBuilder(current.getAbsolutePath());
            } else {
                pb = new ProcessBuilder(
                        javaBin,
                        "-jar",
                        current.getAbsolutePath()
                );
            }
            pb.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (issyncneed) {
            Config.mirrorAllToCloud();
        }
        System.exit(0);
    }
    private static int showLanguageSelectDialog() {
        String[] options = {
                "English",
                "日本語 (Japanese)",
                "中文・简体 (Chinese Simplified)",
                "中文・繁體 (Chinese Traditional)",
                "한국어 (Korean)"
        };
        JComboBox<String> combo = new JComboBox<>(options);
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(new JLabel("Select your language / 言語選択"), BorderLayout.NORTH);
        panel.add(combo, BorderLayout.CENTER);
        int result = JOptionPane.showConfirmDialog(
                null,
                panel,
                "MobMate Initial Setup",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );
        if (result != JOptionPane.OK_OPTION) {
            return -1;
        }
        return combo.getSelectedIndex();
    }
}



class GainMeter extends JComponent {
    // ★マイク入力用
    private int targetLevel;         // 0-100
    private double targetDb;         // -60..0
    private boolean auto;
    private float autoMul;
    private float userMul;

    private float dispLevel = 0f;    // 表示用（スムージング後）
    private double dispDb = -60.0;

    private int peakHold = 0;        // 0-100
    private long peakHoldAt = 0;

    // ★スピーカー出力用（追加）
    private int targetSpeakerLevel = 0;
    private double targetSpeakerDb = -60.0;

    private float dispSpeakerLevel = 0f;
    private double dispSpeakerDb = -60.0;

    private int speakerPeakHold = 0;
    private long speakerPeakHoldAt = 0;

    private final Timer animTimer;

    // ===== Waveform overlay (pixel-column ring) =====
    private final Object waveLock = new Object();
    private static final int WAVE_COLS = 640;
    private static final int WAVE_WINDOW_SAMPLES = 1024;
    private final short[] waveMinCol = new short[WAVE_COLS];
    private final short[] waveMaxCol = new short[WAVE_COLS];
    private int waveColWrite = 0;
    private int winCount = 0;
    private int winMin = 32767;
    private int winMax = -32768;
    public GainMeter() {
        setPreferredSize(new Dimension(260, 22));
        setMinimumSize(new Dimension(160, 18));
        setOpaque(false);

        // 60fpsアニメ（Swing Timer）
        animTimer = new Timer(16, e -> animateStep());
        animTimer.setCoalesce(true);
        animTimer.start();
    }

    /**
     * 16bit little-endian PCM を受け取り、波形表示用に min/max をリングへ溜める
     * ※音量メーターと同じ “適用後ゲイン” で描画したいので gain/applyGain を受け取る
     * ※オーディオスレッドから呼ばれる想定なので同期してる
     */
    public void pushWaveformPcm16le(byte[] pcm, int len, float gain, boolean applyGain) {
        if (pcm == null || len <= 0) return;

        synchronized (waveLock) {
            for (int i = 0; i + 1 < len; i += 2) {
                int s = (short) (((pcm[i + 1] & 0xFF) << 8) | (pcm[i] & 0xFF));

                if (applyGain) {
                    int amplified = Math.round(s * gain);
                    if (amplified > 32767) amplified = 32767;
                    else if (amplified < -32768) amplified = -32768;
                    s = amplified;
                }

                if (s < winMin) winMin = s;
                if (s > winMax) winMax = s;
                winCount++;

                if (winCount >= WAVE_WINDOW_SAMPLES) {
                    waveMinCol[waveColWrite] = (short) winMin;
                    waveMaxCol[waveColWrite] = (short) winMax;

                    waveColWrite++;
                    if (waveColWrite >= WAVE_COLS) waveColWrite = 0;

                    winCount = 0;
                    winMin = 32767;
                    winMax = -32768;
                }
            }
        }
        long now = System.nanoTime();
        if (now - lastRepaintNs > REPAINT_INTERVAL_NS) {
            lastRepaintNs = now;
            // EDTで描画（スレッド安全）
            SwingUtilities.invokeLater(this::repaint);
        }
    }


    /**
     * recentSpeech=false のときは強制的に0へ落とす（中途半端停止を潰す）
     */
    public void setValue(int level, double db, boolean auto, float autoMul, float userMul, boolean recentSpeech) {
        this.auto = auto;
        this.autoMul = autoMul;
        this.userMul = userMul;

        if (!recentSpeech) {
            this.targetLevel = 0;
            this.targetDb = -60.0;
        } else {
            this.targetLevel = Math.max(0, Math.min(100, level));
            this.targetDb = db;
        }
    }
    // ★スピーカー出力レベル設定（追加）
    public void setSpeakerValue(int level, double db) {
        this.targetSpeakerLevel = Math.max(0, Math.min(100, level));
        this.targetSpeakerDb = db;
    }

    private void animateStep() {
        // 攻撃(上がる)は速く、リリース(下がる)はゆっくり
        float attack = 0.35f;
        float release = 0.12f;

        // ★マイク入力のアニメーション
        float t = targetLevel;
        float a = (t > dispLevel) ? attack : release;
        dispLevel += (t - dispLevel) * a;

        double td = targetDb;
        double ad = (td > dispDb) ? 0.35 : 0.12;
        dispDb += (td - dispDb) * ad;

        if (targetLevel == 0 && dispLevel < 0.6f) dispLevel = 0f;
        if (targetDb <= -59.9 && dispDb < -59.5) dispDb = -60.0;

        // ★スピーカー出力のアニメーション（追加）
        float ts = targetSpeakerLevel;
        float as = (ts > dispSpeakerLevel) ? attack : release;
        dispSpeakerLevel += (ts - dispSpeakerLevel) * as;

        double tsd = targetSpeakerDb;
        double asd = (tsd > dispSpeakerDb) ? 0.35 : 0.12;
        dispSpeakerDb += (tsd - dispSpeakerDb) * asd;

        if (targetSpeakerLevel == 0 && dispSpeakerLevel < 0.6f) dispSpeakerLevel = 0f;
        if (targetSpeakerDb <= -59.9 && dispSpeakerDb < -59.5) dispSpeakerDb = -60.0;

        // ★マイクピークホールド
        long now = System.currentTimeMillis();
        int lvlInt = Math.round(dispLevel);
        if (lvlInt >= peakHold) {
            peakHold = lvlInt;
            peakHoldAt = now;
        } else {
            long dt = now - peakHoldAt;
            if (dt > 700) {
                peakHold = Math.max(lvlInt, peakHold - 1);
            }
        }

        // ★スピーカーピークホールド（追加）
        int speakerLvlInt = Math.round(dispSpeakerLevel);
        if (speakerLvlInt >= speakerPeakHold) {
            speakerPeakHold = speakerLvlInt;
            speakerPeakHoldAt = now;
        } else {
            long dt = now - speakerPeakHoldAt;
            if (dt > 700) {
                speakerPeakHold = Math.max(speakerLvlInt, speakerPeakHold - 1);
            }
        }

        repaint();
    }

    private volatile long lastRepaintNs = 0;
    private static final long REPAINT_INTERVAL_NS = 33_000_000L; // 約30fps
    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            // 背景
            g2.setColor(new Color(30, 30, 30, 200));
            g2.fillRoundRect(0, 0, w, h, 10, 10);

            int pad = 3;
            int barW = w - pad * 2;
            int barH = h - pad * 2;

            // ★波形オーバーレイ（min/max縦線）
            // 先に描いて、後段のバー・文字の上に “うっすら残る” 感じにする
            // ★波形オーバーレイ（列固定リング：形が泳がない）
            // ★波形オーバーレイ（列固定リング：最新側を表示。泳がない）
            {
                int midY = pad + barH / 2;
                float scale = (barH * 0.48f) / 32768f;

                g2.setColor(new Color(80, 220, 255, 170)); // 波形色

                int step = 2; // 1=高精細, 2=軽量
                int colsToDraw = Math.min(WAVE_COLS, Math.max(1, barW / step));

                synchronized (waveLock) {
                    // ★「最新側」を表示する：write位置の直前から colsToDraw 分を描く
                    int start = waveColWrite - colsToDraw;
                    if (start < 0) start += WAVE_COLS;

                    // ★右端に最新が来るように寄せる（見た目が自然）
                    int startX = pad + Math.max(0, barW - colsToDraw * step);

                    for (int i = 0; i < colsToDraw; i++) {
                        int x = startX + i * step;
                        if (x >= pad + barW) break;

                        int idx = start + i;
                        if (idx >= WAVE_COLS) idx -= WAVE_COLS;

                        int mn = waveMinCol[idx];
                        int mx = waveMaxCol[idx];

                        int y1 = midY - Math.round(mx * scale);
                        int y2 = midY - Math.round(mn * scale);

                        if (y1 < pad) y1 = pad;
                        if (y1 > pad + barH) y1 = pad + barH;
                        if (y2 < pad) y2 = pad;
                        if (y2 > pad + barH) y2 = pad + barH;

                        g2.drawLine(x, y1, x, y2);
                    }
                }
            }


            // ★左半分：マイク入力レベル
            int lvl = Math.max(0, Math.min(100, Math.round(dispLevel)));
            int halfW = barW / 2;
            int fillW = (int) (halfW * (lvl / 100.0));
            Color c = colorFor(lvl);
            g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 90));
            g2.fillRoundRect(pad, pad, Math.max(0, fillW), barH, 8, 8);

            // ★右半分：スピーカー出力レベル
            // ★右半分：スピーカー出力レベル（ピンク系）
            int speakerLvl = Math.max(0, Math.min(100, Math.round(dispSpeakerLevel)));
            int speakerFillW = (int) (halfW * (speakerLvl / 100.0));
            Color speakerC = speakerColorFor(speakerLvl); // ★ピンク系の色に変更
            g2.setColor(new Color(speakerC.getRed(), speakerC.getGreen(), speakerC.getBlue(), 90));
            // 右側から左に向かって描画
            int speakerStartX = pad + barW - speakerFillW;
            g2.fillRoundRect(speakerStartX, pad, Math.max(0, speakerFillW), barH, 8, 8);

            // ★中央の区切り線
            int centerX = pad + halfW;
            g2.setColor(new Color(255, 255, 255, 60));
            g2.drawLine(centerX, pad, centerX, pad + barH);

            // 目盛り
            g2.setColor(new Color(255, 255, 255, 35));
            for (int i = 1; i < 10; i++) {
                int x = pad + (barW * i) / 10;
                g2.drawLine(x, pad + 2, x, pad + barH - 2);
            }

            // ★マイクピークホールド線（左半分）
            int peakX = pad + (halfW * peakHold) / 100;
            g2.setColor(new Color(255, 255, 255, 170));
            g2.drawLine(peakX, pad, peakX, pad + barH);

            // ★スピーカーピークホールド線（右半分）
            int speakerPeakX = pad + halfW + (halfW * speakerPeakHold) / 100;
            g2.setColor(new Color(255, 255, 255, 170));
            g2.drawLine(speakerPeakX, pad, speakerPeakX, pad + barH);

            // 枠線
            g2.setColor(new Color(255, 255, 255, 60));
            g2.drawRoundRect(0, 0, w - 1, h - 1, 10, 10);

            // ★マイク入力のdB + gain（左側）
            String micDbStr = String.format("%+.1f dB", dispDb);
            float effective = userMul * autoMul;
            String gainStr;
            if (auto) {
                gainStr = String.format("A-x%.2f", effective);
            } else {
                gainStr = (userMul > 1.01f) ? String.format("x%.1f", userMul) : "";
            }
            String micText = gainStr.isEmpty() ? micDbStr : (micDbStr + " " + gainStr);

            // ★スピーカー出力のdB（右側）
            String speakerText = String.format("%+.1f dB", dispSpeakerDb);

            g2.setFont(getFont().deriveFont(Font.BOLD, Math.max(11f, h * 0.55f)));

            // ★dBテキスト：左下寄せ＆一回り小さく
            Font base = getFont();
            Font small = base.deriveFont(Math.max(9f, base.getSize2D() * 0.75f)); // さらに小さめ
            g2.setFont(small);

            FontMetrics fm = g2.getFontMetrics();
            int ty = pad + barH - 4; // 下寄せ

            // ★マイク入力テキスト（左下）
            int micTx = pad + 4;
            g2.setColor(new Color(0, 0, 0, 140));
            g2.drawString(micText, micTx + 1, ty + 1);
            g2.setColor(new Color(235, 235, 235, 220));
            g2.drawString(micText, micTx, ty);

            // ★スピーカー出力テキスト（右下）
            int speakerTextW = fm.stringWidth(speakerText);
            int speakerTx = pad + barW - speakerTextW - 4;
            g2.setColor(new Color(0, 0, 0, 140));
            g2.drawString(speakerText, speakerTx + 1, ty + 1);
            g2.setColor(new Color(235, 235, 235, 220));
            g2.drawString(speakerText, speakerTx, ty);

            g2.setComposite(AlphaComposite.SrcOver);

        } finally {
            g2.dispose();
        }
    }

    private static Color colorFor(int level) {
        if (level >= 80) return new Color(220, 60, 60);
        if (level >= 60) return new Color(255, 150, 40);
        if (level >= 30) return new Color(60, 180, 90);
        return new Color(120, 120, 120);
    }
    // ★スピーカー出力用：ピンク系グラデーション
    private static Color speakerColorFor(int level) {
        if (level >= 80) return new Color(220, 20, 100);   // クリムゾンピンク（高音量）
        if (level >= 60) return new Color(255, 20, 147);   // ディープピンク
        if (level >= 30) return new Color(255, 105, 180);  // ホットピンク
        return new Color(180, 140, 160);                   // グレーピンク（低音量）
    }
}

final class FontBootstrap {
    private FontBootstrap() {}

    public static void registerBundledFonts() {
        register("/fonts/NotoSansCJKsc-Regular.otf");
        register("/fonts/NotoSansCJKkr-Regular.otf");
        register("/fonts/NotoSansCJKtc-Regular.otf");
        register("/fonts/NotoSansCJKjp-Regular.otf");
    }

    private static void register(String resourcePath) {
        try (InputStream is = FontBootstrap.class.getResourceAsStream(resourcePath)) {
            if (is == null) throw new IllegalStateException("Font not found: " + resourcePath);
            Font font = Font.createFont(Font.TRUETYPE_FONT, is);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
        } catch (Exception e) {
            // フォント登録失敗してもアプリは動かす
            System.err.println("[FontBootstrap] Failed to register: " + resourcePath + " : " + e);
        }
    }
}
final class UiFontApplier {
    private UiFontApplier() {}

    /** Preferences の ui.language (en/ja/zh_cn/zh_tw/ko) で適用 */
    public static void applyDefaultUIFontBySuffix(String suffix) {
        String prefix = switch (suffix) {
            case "zh_cn" -> "Noto Sans CJK SC";
            case "zh_tw" -> "Noto Sans CJK TC";
            case "ko"    -> "Noto Sans CJK KR";
            case "ja"    -> "Noto Sans CJK JP";
            default      -> null;
        };

        int size = 12;
        Font base = pickByPrefixOrFallback(prefix, size);
        setAllUIDefaultFonts(base);

        // デバッグ（任意）
        System.out.println("[UiFontApplier] suffix=" + suffix + " base=" + base.getFamily());
        System.out.println("[UiFontApplier] Menu.font=" + UIManager.getFont("Menu.font"));
    }

    /** ui.language がまだ無い初回用：OS Locale から推定して適用 */
    public static void applyDefaultUIFontFromSystemLocale() {
        Locale loc = Locale.getDefault();
        String lang = loc.getLanguage();   // "zh","ja","ko","en"...
        String country = loc.getCountry(); // "CN","TW","HK"...

        String suffix;
        if ("zh".equalsIgnoreCase(lang)) {
            // 中国語は国/地域で雑に判定（TW/HKなら繁体、それ以外は簡体）
            suffix = ("TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country))
                    ? "zh_tw"
                    : "zh_cn";
        } else if ("ko".equalsIgnoreCase(lang)) {
            suffix = "ko";
        } else if ("ja".equalsIgnoreCase(lang)) {
            suffix = "ja";
        } else {
            suffix = "en";
        }

        applyDefaultUIFontBySuffix(suffix);
    }

    /** 既に作ったUIへ反映（Frame作成後に呼ぶ） */
    public static void refreshAllUI(Window root) {
        SwingUtilities.updateComponentTreeUI(root);
        root.invalidate();
        root.validate();
        root.repaint();
    }

    // ---- internal helpers ----

    private static Font pickByPrefixOrFallback(String familyPrefix, int size) {
        if (familyPrefix != null) {
            String found = findFamilyStartsWith(familyPrefix);
            if (found != null) return new Font(found, Font.PLAIN, size);
        }

        // OSフォントfallback
        List<String> fallbacks = List.of(
                "Microsoft YaHei UI", "Microsoft YaHei", "SimHei",
                "Malgun Gothic",
                "Arial Unicode MS"
        );
        for (String fam : fallbacks) {
            Font f = new Font(fam, Font.PLAIN, size);
            if (!f.getFamily().equalsIgnoreCase("Dialog")) return f;
        }
        return new Font(Font.DIALOG, Font.PLAIN, size);
    }

    private static String findFamilyStartsWith(String prefix) {
        String[] fams = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames(Locale.ENGLISH);
        for (String f : fams) {
            if (f.startsWith(prefix)) return f;
        }
        return null;
    }

    private static void setAllUIDefaultFonts(Font base) {
        FontUIResource fuir = new FontUIResource(base);

        // LAF defaults と app defaults の両方へ
        UIDefaults[] targets = { UIManager.getLookAndFeelDefaults(), UIManager.getDefaults() };
        for (UIDefaults d : targets) {
            for (Object key : Collections.list(d.keys())) {
                Object val = d.get(key);
                if (val instanceof FontUIResource) {
                    UIManager.put(key, fuir);
                }
            }
        }
    }
}

final class VoicegerManager {
    private static volatile Process proc;
    private static final Object LOCK = new Object();

    private VoicegerManager() {}

    // ===== Stop API on exit =====
    public static void stopIfRunning(Preferences prefs) {
//        if (prefs != null && !prefs.getBoolean("voiceger.enabled", false)) return;

        synchronized (LOCK) {
            int port = (prefs != null) ? prefs.getInt("voiceger.port", 8501) : 8501;

            // MobMateが掴んでるプロセスがあればまず止める
            if (proc != null) {
                try {
                    if (proc.isAlive()) {
                        proc.destroy();
                        try { proc.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Exception ignore) {}
                    }
                } catch (Exception ignore) {
                } finally {
                    proc = null;
                }
            }

            // 念のため LISTENING を潰す（孤児対策）
            killListeningPortWindows(port);
        }
    }

    private static void killListeningPortWindows(int port) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            if (!os.contains("win")) return;

            Process p = new ProcessBuilder("cmd", "/c", "netstat -ano -p tcp")
                    .redirectErrorStream(true)
                    .start();

            String out;
            try (java.io.InputStream is = p.getInputStream()) {
                out = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            try { p.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Exception ignore) {}

            String needle = ":" + port;
            java.util.HashSet<Integer> pids = new java.util.HashSet<>();

            for (String line : out.split("\\r?\\n")) {
                if (!line.contains(needle)) continue;
                if (!line.toUpperCase(java.util.Locale.ROOT).contains("LISTENING")) continue;

                String[] parts = line.trim().split("\\s+");
                if (parts.length < 5) continue;

                // 最後のトークンがPID
                try {
                    int pid = Integer.parseInt(parts[parts.length - 1]);
                    if (pid > 0) pids.add(pid);
                } catch (Exception ignore) {}
            }

            for (int pid : pids) {
                try {
                    Config.logDebug("[VG] stop: taskkill pid=" + pid + " port=" + port);
                    new ProcessBuilder("taskkill", "/T", "/F", "/PID", String.valueOf(pid))
                            .redirectErrorStream(true)
                            .start()
                            .waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
    }

    /** MobMate起動時：enabledなら裏で起動→Ready待ち */
    public static void ensureRunningIfEnabledAsync(Preferences prefs) {
        if (!prefs.getBoolean("voiceger.enabled", false)) return;

        Thread t = new Thread(() -> {
            try {
                ensureRunning(prefs, Duration.ofSeconds(20));
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }, "voiceger-autostart");
        t.setDaemon(true);
        t.start();
    }

    /** Wizardの起動テスト用：起動→Readyまで待って結果文字列を返す */
    public static String startTest(Preferences prefs, Duration timeout) {
        try {
            ensureRunning(prefs, timeout);
            return "OK: Voiceger ready";
        } catch (Throwable e) {
            return "NG: " + e.getMessage();
        }
    }

    /** 起動済みならそのまま、未起動なら起動してReady待ち */
    public static void ensureRunning(Preferences prefs, Duration timeout) throws Exception {
        synchronized (LOCK) {
            int port = prefs.getInt("voiceger.port", 8501);
            String baseUrl = "http://127.0.0.1:" + port;

            // 既に生きてるならOK
            if (isUp(baseUrl)) return;

            // 残骸があれば掃除
            if (proc != null && proc.isAlive()) {
                try { proc.destroy(); } catch (Exception ignore) {}
            }

            Path dir = Paths.get(prefs.get("voiceger.dir", "")).toAbsolutePath();
            if (dir.toString().isBlank() || !Files.isDirectory(dir)) {
                throw new FileNotFoundException("voiceger.dir not found: " + dir);
            }

            // Wizardと同じ：embedded pythonで _internal/voiceger_api.py を直接起動する
            Path apiPy = resolveVoicegerApiPy(dir);
            if (!Files.exists(apiPy)) {
                throw new FileNotFoundException("voiceger_api.py not found: " + apiPy);
            }

            Path py = detectEmbeddedPython(dir);
            if (py == null || !Files.exists(py)) {
                throw new FileNotFoundException("embedded python not found under: " + dir);
            }

            Path log = dir.resolve("voiceger_api.log");
            ProcessBuilder pb = new ProcessBuilder(
                    py.toAbsolutePath().toString(),
                    apiPy.toAbsolutePath().toString()
            );
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));

// env（Wizard同様）
            Map<String, String> env = pb.environment();
            env.putIfAbsent("NUMBA_DISABLE_CACHING", "1");
            env.putIfAbsent("NUMBA_DISABLE_JIT", "1");

            proc = pb.start();


            long end = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < end) {
                if (isUp(baseUrl)) return;
                try { Thread.sleep(300); } catch (InterruptedException ignore) {}
            }
            throw new TimeoutException("Voiceger not ready. See: " + log);
        }
    }
    private static Path resolveVoicegerApiPy(Path voicegerDir) {
        return voicegerDir.resolve("_internal").resolve("voiceger_api.py");
    }
    private static Path detectEmbeddedPython(Path voicegerDir) {
        // まずは定番
        // voicegerDir = VOICEGER_DIR を想定
        Path p1 = voicegerDir.resolve("_internal").resolve("py").resolve("python.exe");
        if (Files.exists(p1)) return p1;

        // 旧/別配置の保険（あれば）
        Path p2 = voicegerDir.resolve("voiceger_v2").resolve("_internal").resolve("py").resolve("python.exe");
        if (Files.exists(p2)) return p2;

        return null;
    }



    private static Path detectLauncher(Path dir) {
        // まずは “それっぽい” のみ（後で厳密化OK）
        String[] names = {
                "start_voiceger.bat", "start.bat", "run.bat", "voiceger.bat",
                "start.ps1", "run.ps1"
        };
        for (String n : names) {
            Path p = dir.resolve(n);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    private static List<String> buildWindowsCommand(Path launcher) {
        String name = launcher.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".bat") || name.endsWith(".cmd")) {
            return Arrays.asList("cmd", "/c", launcher.toAbsolutePath().toString());
        }
        if (name.endsWith(".ps1")) {
            return Arrays.asList("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                    launcher.toAbsolutePath().toString());
        }
        return List.of(launcher.toAbsolutePath().toString());
    }

    private static boolean isUp(String baseUrl) {
        // Streamlit想定： / や /health が200ならOK扱い（緩く）
        return httpOk(baseUrl + "/") || httpOk(baseUrl + "/health") || httpOk(baseUrl + "/docs");
    }

    static boolean httpOk(String urlStr) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(urlStr).openConnection();
            con.setConnectTimeout(300);
            con.setReadTimeout(300);
            con.setRequestMethod("GET");
            int code = con.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception ignore) {
            return false;
        } finally {
            if (con != null) try { con.disconnect(); } catch (Exception ignore) {}
        }
    }

    // ===== Conversion bridge (file-based) =====

    /**
     * MobMateが生成したwav(bytes)を Voiceger変換して返す。
     * 変換失敗時は inWav をそのまま返す（壊さない）
     */
    public static byte[] maybeConvertWavByBridge(byte[] inWav, java.util.prefs.Preferences prefs) {
        if (inWav == null || inWav.length < 64) return inWav;
        if (!prefs.getBoolean("voiceger.enabled", false)) return inWav;
        if (!prefs.getBoolean("voiceger.use_on_output", true)) return inWav;

        Path dir = Paths.get(prefs.get("voiceger.dir", "")).toAbsolutePath();
        if (dir.toString().isBlank() || !Files.isDirectory(dir)) return inWav;

        try {
            ensureConvertBridge(prefs);

            Path inFile  = dir.resolve("mobmate_in.wav");
            Path outFile = dir.resolve("mobmate_out.wav");
            Files.write(inFile, inWav);

            Path bat = dir.resolve("mobmate_voiceger_convert.bat");
            if (!Files.exists(bat)) return inWav;

            ProcessBuilder pb = new ProcessBuilder("cmd", "/c",
                    bat.toAbsolutePath().toString(),
                    inFile.toAbsolutePath().toString(),
                    outFile.toAbsolutePath().toString());
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("voiceger_convert.log").toFile()));

            Process p = pb.start();

            // タイムアウト（長すぎるとUX死ぬので短め）
            boolean ok = p.waitFor(25, java.util.concurrent.TimeUnit.SECONDS);
            if (!ok) {
                try { p.destroy(); } catch (Exception ignore) {}
                return inWav;
            }
            if (p.exitValue() != 0) return inWav;

            if (!Files.exists(outFile)) return inWav;

            byte[] out = Files.readAllBytes(outFile);
            return (out.length > 64) ? out : inWav;

        } catch (Exception e) {
            return inWav;
        }
    }

    public static void ensureConvertBridge(Preferences prefs) {
        Path dir = getDir(prefs);
        if (dir == null) return;

        try {
            Files.createDirectories(dir);
            Path bat = dir.resolve("mobmate_voiceger_convert.bat");
            if (!Files.exists(bat)) {
                // ★最小のダミー：in.wav を out.wav にコピー（経路確認用）
                // 実運用ではこの中身を「voiceger変換コマンド」に置き換える
                String s =
                        "@echo off\r\n" +
                                "set IN=%1\r\n" +
                                "set OUT=%2\r\n" +
                                "if \"%IN%\"==\"\" exit /b 2\r\n" +
                                "if \"%OUT%\"==\"\" exit /b 2\r\n" +
                                "copy /y \"%IN%\" \"%OUT%\" >nul\r\n" +
                                "exit /b %ERRORLEVEL%\r\n";
                Files.writeString(bat, s, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
    }

    /** wavファイルを voiceger ブリッジで変換して、出力wav(File)を返す。失敗時は入力を返す。 */
    public static File convertWavFile(File inWav, Preferences prefs) {
        if (inWav == null || !inWav.exists()) return inWav;

        // routeがVoicegerでも、enabled falseなら変換はしない（壊さない）
        if (!prefs.getBoolean("voiceger.enabled", false)) return inWav;

        Path dir = getDir(prefs);
        if (dir == null) return inWav;

        ensureConvertBridge(prefs);

        Path bat = dir.resolve("mobmate_voiceger_convert.bat");
        if (!Files.exists(bat)) return inWav;

        Path out = null;
        try {
            out = Files.createTempFile("voiceger_out_", ".wav");

            ProcessBuilder pb = new ProcessBuilder(
                    "cmd", "/c",
                    bat.toAbsolutePath().toString(),
                    inWav.getAbsolutePath(),
                    out.toAbsolutePath().toString()
            );
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("voiceger_convert.log").toFile()));

            Process p = pb.start();
            boolean ok = p.waitFor(25, TimeUnit.SECONDS);
            if (!ok) {
                try { p.destroy(); } catch (Exception ignore) {}
                safeDelete(out);
                return inWav;
            }
            if (p.exitValue() != 0) {
                safeDelete(out);
                return inWav;
            }
            if (!isProbablyWav(out)) {
                safeDelete(out);
                return inWav;
            }
            return out.toFile();

        } catch (Exception e) {
            safeDelete(out);
            return inWav;
        }
    }

    private static Path getDir(Preferences prefs) {
        String s = prefs.get("voiceger.dir", "").trim();
        if (s.isEmpty()) return null;
        Path dir = Paths.get(s).toAbsolutePath();
        if (!Files.isDirectory(dir)) return null;
        return dir;
    }

    private static boolean isProbablyWav(Path p) {
        try {
            if (p == null || !Files.exists(p) || Files.size(p) < 64) return false;
            byte[] h = Files.readAllBytes(p);
            if (h.length < 12) return false;
            String riff = new String(h, 0, 4, StandardCharsets.US_ASCII);
            String wave = new String(h, 8, 4, StandardCharsets.US_ASCII);
            return "RIFF".equals(riff) && "WAVE".equals(wave);
        } catch (Exception e) {
            return false;
        }
    }

    private static void safeDelete(Path p) {
        try { if (p != null) Files.deleteIfExists(p); } catch (Exception ignore) {}
    }

    static java.util.List<String> checkVoicegerFiles(java.nio.file.Path voicegerDir) {
        java.util.List<String> miss = new java.util.ArrayList<>();

        java.nio.file.Path internal = voicegerDir.resolve("_internal");
        java.nio.file.Path apiPy = internal.resolve("voiceger_api.py");
        if (!java.nio.file.Files.exists(apiPy)) miss.add("_internal/voiceger_api.py");

        // voiceger_api.py の仕様に合わせた必須構成
        java.nio.file.Path gptSoVits = voicegerDir.resolve("GPT-SoVITS");
        if (!java.nio.file.Files.isDirectory(gptSoVits)) miss.add("GPT-SoVITS/");

        // ffmpeg.exe (GPT-SoVITS直下)
        if (!java.nio.file.Files.exists(gptSoVits.resolve("ffmpeg.exe"))) miss.add("GPT-SoVITS/ffmpeg.exe");

        // pretrained_models
        java.nio.file.Path pretrained = gptSoVits.resolve("GPT_SoVITS").resolve("pretrained_models");
        if (!java.nio.file.Files.isDirectory(pretrained)) miss.add("GPT-SoVITS/GPT_SoVITS/pretrained_models/");
        if (!java.nio.file.Files.isDirectory(pretrained.resolve("chinese-roberta-wwm-ext-large")))
            miss.add(".../pretrained_models/chinese-roberta-wwm-ext-large/");
        if (!java.nio.file.Files.isDirectory(pretrained.resolve("chinese-hubert-base")))
            miss.add(".../pretrained_models/chinese-hubert-base/");

        // G2PWModel
        java.nio.file.Path g2pw = gptSoVits.resolve("GPT_SoVITS").resolve("text").resolve("G2PWModel");
        if (!java.nio.file.Files.isDirectory(g2pw)) miss.add("GPT-SoVITS/GPT_SoVITS/text/G2PWModel/");

        // RVC assets
        java.nio.file.Path rvc = gptSoVits.resolve("Retrieval-based-Voice-Conversion-WebUI");
        java.nio.file.Path hubert = rvc.resolve("assets").resolve("hubert").resolve("hubert_base.pt");
        if (!java.nio.file.Files.exists(hubert)) miss.add(".../assets/hubert/hubert_base.pt");

        java.nio.file.Path rmvpe = rvc.resolve("assets").resolve("rmvpe");
        if (!java.nio.file.Files.isDirectory(rmvpe)) miss.add(".../assets/rmvpe/");

        java.nio.file.Path weights = rvc.resolve("assets").resolve("weights");
        if (!java.nio.file.Files.isDirectory(weights)) {
            miss.add(".../assets/weights/");
        } else {
            try (var s = java.nio.file.Files.list(weights)) {
                boolean hasPth = s.anyMatch(p -> p.getFileName().toString().toLowerCase().endsWith(".pth"));
                if (!hasPth) miss.add(".../assets/weights/*.pth");
            } catch (Exception ignore) {
                miss.add(".../assets/weights/*.pth");
            }
        }

        // reference（TTS用：無くてもVCだけなら動くが、API側はデフォルトで参照を読むので置いとくのが安全）
        java.nio.file.Path refDir = voicegerDir.resolve("reference");
        if (!java.nio.file.Files.exists(refDir.resolve("reference.wav"))) miss.add("reference/reference.wav");
        if (!java.nio.file.Files.exists(refDir.resolve("ref_text.txt"))) miss.add("reference/ref_text.txt");

        return miss;
    }
}
