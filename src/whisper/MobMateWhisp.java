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

    private static final AtomicBoolean shutdownHookOnce = new AtomicBoolean(false);
    private static final PiperPlusPersistentSessionManager piperPlusSessionManager = new PiperPlusPersistentSessionManager();
    private static final String OUTTTS_MARKER = "↑Settings↓Logs below";
    private static final long PIPER_PLUS_DOWNLOAD_DECLINE_COOLDOWN_MS = 10 * 60 * 1000L;
    private static final long PIPER_PLUS_SESSION_PREWARM_COOLDOWN_MS = 45_000L;
    private static LinkedHashMap<String, String> createPresetKeys() {
        LinkedHashMap<String, String> keys = new LinkedHashMap<>();
        keys.put("audio.device", "");
        keys.put("audio.output.device", "");
        keys.put("audio.prefilter.mode", "normal");
        keys.put("recog.engine", "whisper");
        keys.put("model", "");
        keys.put("moonshine.model_path", "");
        keys.put("hearing.moonshine.model_path", "");
        keys.put("talk.lang", "auto");
        keys.put("talk.translate.target", "OFF");
        keys.put("tts.engine", "auto");
        keys.put("piper.plus.model_id", "");
        keys.put("tts.windows.voice", "auto");
        keys.put("tts.voice", "3");
        keys.put("voicevox.auto_emotion", "true");
        keys.put("tts.reflect_emotion", "true");
        keys.put("tts.reflect_emotion.user_touched", "false");
        keys.put("tts.reflect.contour_strength", "normal");
        keys.put("tts.reflect.tone_emphasis", "normal");
        keys.put("voiceger.tts.lang", "all_ja");
        keys.put("hearing.lang", "auto");
        keys.put("hearing.engine", "whisper");
        keys.put("hearing.translate.target", "OFF");
        keys.put("hearing.translate.queue_mode", HEARING_TRANSLATE_MODE_REALTIME);
        keys.put("hearing.initial_prompt", "");
        keys.put("hearing.output", "");
        keys.put("ui.hearing.visible", "false");
        keys.put("hearing.overlay.font_size", "18");
        keys.put("hearing.overlay.bg_color", "green");
        keys.put("hearing.overlay.opacity", "72");
        keys.put("hearing.overlay.history_size", "6");
        keys.put("hearing.overlay.translated_keep_ms", "12000");
        keys.put("hearing.overlay.chunk_merge_ms", "2000");
        keys.put("hearing.overlay.position", "bottom_left");
        keys.put("hearing.overlay.flow", "vertical_up");
        keys.put("hearing.overlay.display", "0");
        keys.put("hearing.overlay.custom_x", "");
        keys.put("hearing.overlay.custom_y", "");
        return keys;
    }
    private static LinkedHashMap<String, String> createPresetConfigKeys() {
        LinkedHashMap<String, String> keys = new LinkedHashMap<>();
        keys.put("language", "auto");
        keys.put("initial_prompt", "");
        keys.put("voicevox.exe", "");
        keys.put("voicevox.api", "");
        keys.put("piper.plus.model_id", "");
        keys.put("piper.plus.license", "");
        keys.put("piper.plus.source_url", "");
        keys.put("xtts.api", "");
        keys.put("xtts.apichk", "");
        keys.put("xtts.language", "");
        keys.put("ignore.mode", "simple");
        return keys;
    }
    private static Set<String> createPresetQuotedConfigKeys() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add("voicevox.exe");
        return Collections.unmodifiableSet(keys);
    }

    private static final class WindowsVoiceInfo {
        final String name;
        final String culture;

        WindowsVoiceInfo(String name, String culture) {
            this.name = (name == null) ? "" : name.trim();
            this.culture = normalizeCulture(culture);
        }

        private static String normalizeCulture(String value) {
            if (value == null) return "";
            return value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    private static final class TalkPreset {
        final String name;
        final Path path;
        final LinkedHashMap<String, String> values;

        TalkPreset(String name, Path path, LinkedHashMap<String, String> values) {
            this.name = (name == null) ? "" : name.trim();
            this.path = path;
            this.values = values;
        }
    }

    private static final class HearingTranslateRequest {
        final String text;
        final String sourceLang;
        final String targetLang;
        final java.util.function.Consumer<String> onTranslated;
        final long seq;

        HearingTranslateRequest(String text,
                                String sourceLang,
                                String targetLang,
                                java.util.function.Consumer<String> onTranslated,
                                long seq) {
            this.text = text;
            this.sourceLang = sourceLang;
            this.targetLang = targetLang;
            this.onTranslated = onTranslated;
            this.seq = seq;
        }
    }

    private static final class PendingConfirmItem {
        final long id;
        final String sourceText;
        final String outputText;
        final String historyText;
        final Action action;
        final long acceptedAtMs;
        final long dueAtMs;
        final String originTag;
        final TtsProsodyProfile ttsProsodyProfile;

        PendingConfirmItem(long id,
                           String sourceText,
                           String outputText,
                           String historyText,
                           Action action,
                           long acceptedAtMs,
                           long dueAtMs,
                           String originTag,
                           TtsProsodyProfile ttsProsodyProfile) {
            this.id = id;
            this.sourceText = (sourceText == null) ? "" : sourceText;
            this.outputText = (outputText == null) ? "" : outputText;
            this.historyText = (historyText == null) ? this.outputText : historyText;
            this.action = action;
            this.acceptedAtMs = acceptedAtMs;
            this.dueAtMs = dueAtMs;
            this.originTag = (originTag == null || originTag.isBlank()) ? "confirm" : originTag;
            this.ttsProsodyProfile = ttsProsodyProfile;
        }
    }

    // ローカル翻訳
    private LocalTranslator localTranslator = null;
    private final Object talkTranslatorLock = new Object();
    private volatile boolean talkTranslatorInitAttempted = false;
    private volatile boolean talkTranslatorUnavailableLogged = false;
    private PostProcessor talkPostProcessor = null;
    private final Object talkPostProcessorLock = new Object();
    private volatile boolean talkPostProcessorInitAttempted = false;
    private final ExecutorService talkTranslateExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mobmate-talk-translate");
        t.setDaemon(true);
        return t;
    });
    private static final boolean TALK_PARTIAL_TRANSLATION_ENABLED = false;
    private final AtomicBoolean talkPartialTranslateBusy = new AtomicBoolean(false);
    private final AtomicLong talkPartialPreviewSeq = new AtomicLong(0);
    private static final long TALK_SHORT_REPEAT_SUPPRESS_MS = 2500L;
    private static final int TALK_SHORT_REPEAT_MAX_CP = 12;
    private final LinkedHashMap<String, Long> recentTalkShortCaptionMs = new LinkedHashMap<>();
    private LocalTranslator hearingTranslator = null;
    private final Object hearingTranslatorLock = new Object();
    private volatile boolean hearingTranslatorInitAttempted = false;
    private volatile boolean hearingTranslatorUnavailableLogged = false;
    private final AtomicLong hearingMoonshineBlankFallbackCount = new AtomicLong(0L);
    private final ExecutorService hearingTranslateExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mobmate-hearing-translate");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean hearingTranslateBusy = new AtomicBoolean(false);
    static final String HEARING_TRANSLATE_MODE_LATEST = "latest_caption";
    static final String HEARING_TRANSLATE_MODE_PREFER_NEW = "prefer_new_while_waiting";
    static final String HEARING_TRANSLATE_MODE_REALTIME = "realtime";
    private final AtomicReference<HearingTranslateRequest> hearingTranslateQueue = new AtomicReference<>(null);
    private final AtomicLong hearingTranslateSeq = new AtomicLong(0L);
    private static final long HEARING_TRANSLATE_STUCK_MS = 15_000L;
    private static final long HEARING_MOONSHINE_AUTO_SWITCH_COOLDOWN_MS = 2500L;
    private final AtomicLong hearingTranslateStartedMs = new AtomicLong(0L);
    private volatile String hearingTranslateActiveText = "";
    private static final String PREF_WIN_X = "ui.window.x";
    private static final String PREF_WIN_Y = "ui.window.y";
    private static final String PREF_WIN_W = "ui.window.w";
    private static final String PREF_WIN_H = "ui.window.h";
    static final int DEFAULT_UI_FONT_SIZE = 16;

    public static Preferences prefs;
    private static volatile String audioPrefilterMode = "normal";
    private static volatile String hearingAudioPrefilterMode = null;
    private String lastOutput = null;
    private final Object windowsVoiceCacheLock = new Object();
    private volatile List<WindowsVoiceInfo> windowsVoiceInfoCache = Collections.emptyList();
    private volatile long windowsVoiceInfoCacheLoadedAtMs = 0L;
    private static final long WINDOWS_VOICE_CACHE_TTL_MS = 60_000L;
    private static final String PRESET_DIR_NAME = "preset";
    private static final String PRESET_EXT = ".txt";
    private static final String PREF_SELECTED_PRESET = "ui.preset.selected";
    private static final LinkedHashMap<String, String> PRESET_KEYS = createPresetKeys();
    private static final LinkedHashMap<String, String> PRESET_CONFIG_KEYS = createPresetConfigKeys();
    private static final Set<String> PRESET_CONFIG_QUOTED_KEYS = createPresetQuotedConfigKeys();
    private boolean presetComboUpdating = false;
    private String cpugpumode = "";
    public String getCpuGpuMode() {
        return cpugpumode != null ? cpugpumode : "CPU MODE";
    }
    private final Random rnd = new Random();
    private String[] laughOptions;
    private HistoryFrame historyFrame;
    private boolean suppressHistoryCloseCallbacks = false;
    private Boolean pendingHistoryRestore = null;
    private MobMateSettingsFrame settingsCenterFrame;
    private static final int CONFIRM_DEFAULT_SEC = 3;
    private static final int CONFIRM_QUEUE_MAX = 4;
    private static final int RADIO_CONFIRM_PAGE = -1;
    private volatile boolean ttsConfirmMode = false;
    private final Object confirmLock = new Object();
    private final AtomicLong pendingConfirmSeq = new AtomicLong(0L);
    private final ArrayDeque<PendingConfirmItem> pendingConfirmQueue = new ArrayDeque<>();
    private Timer confirmCountdownTimer;
    private JPanel confirmPanel;
    private JLabel confirmCountdownLabel;
    private JLabel confirmTextLabel;
    private JToggleButton confirmModeToggle;

    // Moonshine
    private static LocalMoonshineSTT moonshine;
    // Whisper
    volatile LocalWhisperCPP w;
    private volatile LocalWhisperCPP moonshineFallbackWhisper;
    private final Object moonshineFallbackWhisperLock = new Object();
    private volatile LocalWhisperCPP wHearing;
    private volatile LocalMoonshineSTT hearingMoonshine;
    private final Object hearingWhisperLock = new Object();
    private final Object hearingMoonshineLock = new Object();
    private final ExecutorService hearingMoonshineReloadExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mobmate-hearing-moonshine-reload");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong hearingMoonshineReloadSeq = new AtomicLong(0L);
    private volatile String hearingMoonshineAutoSessionLang = "en";
    private volatile long hearingMoonshineLastReloadMs = 0L;
    private volatile String hearingWhisperAutoSessionLang = "auto";
    private volatile long hearingWhisperLastShiftMs = 0L;
    private final Map<String, Long> piperPlusDownloadDeclinedUntil = new ConcurrentHashMap<>();
    private final Map<String, Long> piperPlusSessionPrewarmUntil = new ConcurrentHashMap<>();
    private final Set<String> piperPlusCompatibilityNotified = ConcurrentHashMap.newKeySet();
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
    private static final ExecutorService piperPlusPrewarmExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "piper-plus-prewarm");
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
    public static final class HistoryEntry {
        private final String displayText;
        private final String rawText;

        public HistoryEntry(String displayText, String rawText) {
            this.displayText = (displayText == null) ? "" : displayText;
            this.rawText = (rawText == null || rawText.isBlank()) ? this.displayText : rawText;
        }

        public String displayText() {
            return displayText;
        }

        public String rawText() {
            return rawText;
        }
    }
    public List<HistoryEntry> history = new ArrayList<>();
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
    private JLabel latencyLabel;
//    private JLabel gpuMemLabel;        // ★GPU VRAM使用量
    private volatile long gpuVramTotalBytes = -1;
    private JLabel modelLabel;         // 利用モデル（★追加）
    private JLabel speakerStatusLabel; // ★話者照合ステータス
    private Timer statusUpdateTimer;   // ステータスバー更新タイマー
    private long recordingStartTime2 = 0; // 録音開始時刻
    private JPanel statusBar;          // ★ステータスバー本体（再構築用）
    private volatile boolean startupBusy = true;
    private volatile String startupStatusText = "BOOT";
    private volatile boolean ignorePreloadPending = false;

    private Process psProcess;
    private BufferedWriter psWriter;
    private BufferedReader psReader;

    private volatile boolean isPriming = false;
    private boolean vadPrimed = false;
    private AdaptiveNoiseProfile sharedNoiseProfile = new AdaptiveNoiseProfile();
    private ImprovedVAD vad = new ImprovedVAD(sharedNoiseProfile);

    private volatile boolean pendingLaughAppend = false;
    private final AudioPrefilter.State talkRealtimePrefilterState = new AudioPrefilter.State();
    private final AudioPrefilter.State talkVadPrefilterState = new AudioPrefilter.State();

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
    private static final long HEARING_TTS_ACTIVE_BLOCK_MS = 250L;
    private static final long HEARING_TTS_COOLDOWN_MS = 350L;
    private volatile boolean isTtsSpeaking = false;  // TTS再生中フラグ（VADループバック防止
    private volatile long ttsStartedMs = 0L;
    private volatile long ttsEndedMs = 0L;
    private volatile long ttsVadSuppressUntilMs = 0L;
    private static final long TTS_VAD_SUPPRESS_MS = 250L;
    private volatile boolean moonshineLastGateOk = true;
    /** Moonshine連続無出力カウント（Partial/COMPLETEが来たら0リセット） */
    private volatile int moonshineNoOutputCount = 0;
    private volatile long moonshineLastOutputMs = 0L;
    private volatile long lastMoonshineOutputUtteranceSeq = -1L;
    private static final long MOONSHINE_NO_OUTPUT_FALLBACK_WAIT_MS = 650L;
    private static final long MOONSHINE_CARRY_JOIN_WINDOW_MS = 1800L;
    private static final int MOONSHINE_CARRY_JOIN_SILENCE_MS = 120;
    private static final int MOONSHINE_CARRY_MAX_BYTES = 16000 * 2 * 6;
    private volatile byte[] pendingMoonshineCarryPcm = new byte[0];
    private volatile long pendingMoonshineCarryAtMs = 0L;
    // ★強制Final直後のfinal-only COMPLETE回収待ち
    private volatile long moonResetDeferredUntilMs = 0L;
    private static final long MOON_FINAL_DRAIN_MS = 300L;
    // 10/20回は短すぎるのでかなり伸ばす
    private static final int MOONSHINE_NO_OUTPUT_RELOAD = 60;
    // さらに「直近で何か出ていた」ならreloadしない
    private static final long MOONSHINE_NO_OUTPUT_RELOAD_GRACE_MS = 15_000L;

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
    // ★VADに入る前の短い笑い声バーストを Moonshine に先行投入するためのカウンタ
    private int preSpeechMoonFeedFrames = 0;
    private static final int PRE_SPEECH_MOON_FEED_MAX = 12; // 約 16 * 128ms = 2048ms まで

    // ===== Voiceger HTTP (reuse) =====
    private static final HttpClient VG_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1) // ローカルAPIならこれで十分
            .build();

    // health の結果キャッシュ（毎回叩かない）
    private static volatile long vgHealthOkUntilMs = 0L;
    private static final long VG_HEALTH_CACHE_MS = 3000; // 3秒だけ信じる

    private static final AtomicLong latVadStartMs   = new AtomicLong(0);
    private static final AtomicLong latFirstPartMs  = new AtomicLong(0);
    private static final AtomicLong latFinalMs      = new AtomicLong(0);
    private static final AtomicLong latTtsStartMs   = new AtomicLong(0);

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
        if (!demoAnnounceStarted.compareAndSet(false, true)) return;

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
        migrateTtsReflectEmotionDefaultIfNeeded();
        audioPrefilterMode = normalizeAudioPrefilterMode(prefs.get("audio.prefilter.mode", "normal"));
        hearingAudioPrefilterMode = loadHearingAudioPrefilterMode(prefs);
        // ★話者プロファイル初期化（prefsから設定値を取得）
        this.speakerProfile = new SpeakerProfile(
                prefs.getInt("speaker.enroll_samples", 5),
                prefs.getFloat("speaker.threshold_initial", 0.60f),
                prefs.getFloat("speaker.threshold_target", prefs.getFloat("speaker.threshold_initial", 0.60f))
        );
        UiText.load(UiLang.resolveUiFile(prefs.get("ui.language", "en")));
        Config.syncAllFromCloud();
        Config.log("JVM: " + System.getProperty("java.vm.name"));
        Config.log("JVM vendor: " + System.getProperty("java.vm.vendor"));
        loadWhisperNative();
        this.button = new JButton(UiText.t("ui.main.start"));
        Config.logDebug("Locale=" + java.util.Locale.getDefault() + " / user.language=" + System.getProperty("user.language"));

        int size = prefs.getInt("ui.font.size", DEFAULT_UI_FONT_SIZE);
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
                try { this.w.setLanguage(getTalkLanguage()); } catch (Exception ignore) {}
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
            try { cur.setLanguage(getHearingWhisperRuntimeLang()); } catch (Exception ignore) {}
            try { cur.setHearingTranslateToEn(false); } catch (Exception ignore) {}
            return cur;
        }
        synchronized (hearingWhisperLock) {
            if (wHearing != null) {
                // ★二重チェック側でも同様に反映
                try { wHearing.setLanguage(getHearingWhisperRuntimeLang()); } catch (Exception ignore) {}
                try { wHearing.setHearingTranslateToEn(false); } catch (Exception ignore) {}
                return wHearing;
            }
            wHearing = new LocalWhisperCPP(new File(this.model_dir, this.model), "Hearing");
            try { wHearing.setLanguage(getHearingWhisperRuntimeLang()); } catch (Exception ignore) {}
            try { wHearing.setHearingTranslateToEn(false); } catch (Exception ignore) {}
            return wHearing;
        }
    }

    private LocalTranslator getOrCreateTalkTranslator() {
        synchronized (talkTranslatorLock) {
            if (localTranslator != null && localTranslator.isLoaded()) {
                talkTranslatorUnavailableLogged = false;
                return localTranslator;
            }
            if (talkTranslatorInitAttempted) {
                return null;
            }
            talkTranslatorInitAttempted = true;
            File exeDir = getExeDir();
            File modelDir = null;
            LocalTranslator translator = new LocalTranslator(modelDir, 2);
            if (!translator.load(exeDir)) {
                return null;
            }
            localTranslator = translator;
            talkTranslatorUnavailableLogged = false;
            return localTranslator;
        }
    }

    private boolean isTalkTranslatorUnavailableForSession() {
        synchronized (talkTranslatorLock) {
            boolean unavailable = talkTranslatorInitAttempted
                    && (localTranslator == null || !localTranslator.isLoaded());
            if (unavailable && !talkTranslatorUnavailableLogged) {
                Config.log("[Talk][Translator] disabled for this session because translator init failed");
                talkTranslatorUnavailableLogged = true;
            }
            return unavailable;
        }
    }

    public String getTalkTranslateTarget() {
        String v = "OFF";
        try {
            if (prefs != null) v = prefs.get("talk.translate.target", "OFF");
        } catch (Exception ignore) {}
        return LanguageOptions.normalizeTranslationTarget(v);
    }

    public void setTalkTranslateTarget(String target) {
        target = LanguageOptions.normalizeTranslationTarget(target);
        try {
            if (prefs != null) {
                prefs.put("talk.translate.target", target);
                try { prefs.sync(); } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
        talkPartialPreviewSeq.incrementAndGet();
        syncVoicegerTtsLangWithTalkOutput();
        LocalWhisperCPP.markIgnoreDirty();
        if ("OFF".equals(target)) {
            synchronized (talkTranslatorLock) {
                try {
                    if (localTranslator != null) localTranslator.unload();
                } catch (Throwable ignore) {}
                localTranslator = null;
                talkTranslatorInitAttempted = false;
                talkTranslatorUnavailableLogged = false;
            }
        }
    }

    public String getTalkOutputLanguage() {
        String target = getTalkTranslateTarget();
        if (!"OFF".equals(target)) {
            return target.toLowerCase(Locale.ROOT);
        }
        return getTalkLanguage();
    }

    private String mapTalkLangToVoicegerPref(String lang) {
        if (lang == null || lang.isBlank()) return null;
        String normalized = lang.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "ja" -> "all_ja";
            case "en" -> "en";
            case "zh", "zh-cn", "zh-tw" -> "all_zh";
            case "ko" -> "all_ko";
            case "yue" -> "all_yue";
            default -> null;
        };
    }

    private void syncVoicegerTtsLangWithTalkOutput() {
        String preferred = mapTalkLangToVoicegerPref(getTalkOutputLanguage());
        if (preferred == null) {
            Config.logDebug("[Talk][Voiceger] skip sync: unsupported output lang=" + getTalkOutputLanguage());
            return;
        }
        try {
            if (prefs != null) {
                String current = prefs.get("voiceger.tts.lang", "all_ja");
                if (!Objects.equals(current, preferred)) {
                    prefs.put("voiceger.tts.lang", preferred);
                    try { prefs.sync(); } catch (Exception ignore) {}
                    Config.logDebug("[Talk][Voiceger] synced tts lang: " + current + " -> " + preferred);
                }
            }
        } catch (Exception ignore) {}
        if (settingsCenterFrame != null && settingsCenterFrame.isDisplayable()) {
            settingsCenterFrame.refreshLinkedSelections();
        }
    }
    public boolean isTalkTranslateEnabled() {
        return !"OFF".equals(getTalkTranslateTarget());
    }

    private void preloadIgnoreForLangsIfNeeded(java.util.Collection<String> langs, String ownerTag) {
        java.util.LinkedHashSet<String> normalizedLangs = new java.util.LinkedHashSet<>();
        if (langs != null) {
            for (String lang : langs) {
                if (lang == null) continue;
                String normalized = lang.trim().toLowerCase(Locale.ROOT);
                if (normalized.isBlank() || "auto".equals(normalized)) continue;
                normalizedLangs.add(normalized);
            }
        }
        if (normalizedLangs.isEmpty()) {
            return;
        }
        ignorePreloadPending = true;
        updateIcon();
        try {
            LocalWhisperCPP.preloadIgnoreWordsForLangs(normalizedLangs);
        } catch (Throwable t) {
            Config.logError("[Ignore] preload failed (" + ownerTag + ")", t);
        } finally {
            ignorePreloadPending = false;
            updateIcon();
        }
    }

    public void preloadIgnoreForTalkStartIfNeeded() {
        if (!isTalkTranslateEnabled()) {
            return;
        }
        preloadIgnoreForLangsIfNeeded(java.util.Collections.singletonList(getTalkLanguage()), "talk");
    }

    public void preloadIgnoreForHearingStartIfNeeded() {
        if (!isHearingTranslateEnabled()) {
            return;
        }
        preloadIgnoreForLangsIfNeeded(java.util.Collections.singletonList(getHearingSourceLang()), "hearing");
    }

    public String[] getTalkLanguageOptions() {
        if (!isEngineMoonshine()) {
            return LanguageOptions.whisperLangs();
        }
        LinkedHashMap<String, File> modelMap = scanMoonshineModelMap();
        if (modelMap.isEmpty()) {
            return new String[]{"ja"};
        }
        return modelMap.keySet().toArray(new String[0]);
    }

    private static String normalizeDynamicLanguage(String value, String[] allowed, String fallback) {
        String normalizedFallback = (fallback == null || fallback.isBlank()) ? "auto" : fallback.trim().toLowerCase(Locale.ROOT);
        Set<String> allowedSet = new LinkedHashSet<>();
        if (allowed != null) {
            for (String s : allowed) {
                if (s != null && !s.isBlank()) {
                    allowedSet.add(s.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (!allowedSet.contains(normalizedFallback) && !allowedSet.isEmpty()) {
            normalizedFallback = allowedSet.iterator().next();
        }
        if (value == null || value.isBlank()) {
            return normalizedFallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return allowedSet.contains(normalized) ? normalized : normalizedFallback;
    }

    private String resolveMoonshineLanguageKey(LinkedHashMap<String, File> modelMap) {
        String savedPath = "";
        try {
            if (prefs != null) savedPath = prefs.get("moonshine.model_path", "");
        } catch (Exception ignore) {}
        String key = findMoonshineModelKey(savedPath, modelMap);
        if (key != null && !key.isBlank()) {
            return key.trim().toLowerCase(Locale.ROOT);
        }
        if (!modelMap.isEmpty()) {
            return modelMap.keySet().iterator().next().trim().toLowerCase(Locale.ROOT);
        }
        return "ja";
    }

    public String getTalkLanguage() {
        String saved = "";
        try {
            if (prefs != null) saved = prefs.get("talk.lang", "");
        } catch (Exception ignore) {}
        if (isEngineMoonshine()) {
            LinkedHashMap<String, File> modelMap = scanMoonshineModelMap();
            String fallback = resolveMoonshineLanguageKey(modelMap);
            return normalizeDynamicLanguage(saved, getTalkLanguageOptions(), fallback);
        }
        String fallback = LanguageOptions.normalizeWhisperLang(Config.loadSetting("language", "auto"), "auto");
        String source = (saved == null || saved.isBlank()) ? fallback : saved;
        return LanguageOptions.normalizeWhisperLang(source, fallback);
    }

    public boolean applyTalkLanguageSelection(String requestedLang) {
        if (isEngineMoonshine()) {
            LinkedHashMap<String, File> modelMap = scanMoonshineModelMap();
            String lang = normalizeDynamicLanguage(requestedLang, getTalkLanguageOptions(), resolveMoonshineLanguageKey(modelMap));
            File modelDir = modelMap.get(lang);
            if (modelDir == null) {
                return false;
            }
            try {
                if (prefs != null) {
                    prefs.put("talk.lang", lang);
                    prefs.put("moonshine.model_path", modelDir.getAbsolutePath());
                    try { prefs.sync(); } catch (Exception ignore) {}
                }
            } catch (Exception ignore) {}
            upsertOutttsKey("language", lang);
            syncVoicegerTtsLangWithTalkOutput();
            return true;
        }

        String lang = LanguageOptions.normalizeWhisperLang(requestedLang, getTalkLanguage());
        try {
            if (prefs != null) {
                prefs.put("talk.lang", lang);
                try { prefs.sync(); } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
        upsertOutttsKey("language", lang);
        try {
            if (w != null) w.setLanguage(lang);
        } catch (Exception ignore) {}
        syncVoicegerTtsLangWithTalkOutput();
        LocalWhisperCPP.markIgnoreDirty();
        return true;
    }

    public boolean requestTalkLanguageChange(Component parent, String requestedLang) {
        String current = getTalkLanguage();
        String next = isEngineMoonshine()
                ? normalizeDynamicLanguage(requestedLang, getTalkLanguageOptions(), current)
                : LanguageOptions.normalizeWhisperLang(requestedLang, current);
        if (Objects.equals(current, next)) {
            return true;
        }
        String engineName = isEngineMoonshine() ? "Moonshine" : "Whisper";
        int result = JOptionPane.showConfirmDialog(
                parent,
                "Changing Talk language for " + engineName + " requires a soft restart.\nApply this change now?",
                "MobMate",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );
        if (result != JOptionPane.OK_OPTION) {
            return false;
        }
        if (!applyTalkLanguageSelection(next)) {
            JOptionPane.showMessageDialog(
                    parent,
                    "Failed to apply Talk language: " + next,
                    "MobMate",
                    JOptionPane.WARNING_MESSAGE
            );
            return false;
        }
        softRestart();
        return true;
    }

    private void upsertOutttsKey(String key, String value) {
        try {
            if (value == null) value = "";
            Path file = Path.of(System.getProperty("user.dir"), "_outtts.txt");
            List<String> lines = Files.exists(file)
                    ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                    : new ArrayList<>();
            int markerIndex = -1;
            int keyIndex = -1;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.contains("↑Settings↓Logs below")) {
                    markerIndex = i;
                    break;
                }
                if (line.startsWith(key + "=")) {
                    keyIndex = i;
                }
            }
            String newLine = key + "=" + value;
            if (keyIndex >= 0) {
                lines.set(keyIndex, newLine);
            } else if (markerIndex >= 0) {
                lines.add(markerIndex, newLine);
            } else {
                lines.add(newLine);
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
            try { Config.reload(); } catch (Exception ignore) {}
        } catch (Exception ex) {
            Config.logDebug("[Talk] failed to update _outtts key " + key + ": " + ex.getMessage());
        }
    }

    private String getTalkSourceLang() {
        String sourceLang = getTalkLanguage();
        if (sourceLang == null) {
            return "auto";
        }
        return sourceLang.trim().toLowerCase(Locale.ROOT);
    }

    private String translateTalkTextNow(String text, String sourceLang, String targetLang) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (sourceLang == null || sourceLang.isBlank() || "auto".equalsIgnoreCase(sourceLang)) {
            return null;
        }
        if (targetLang == null || targetLang.isBlank() || sourceLang.equalsIgnoreCase(targetLang)) {
            return null;
        }
        synchronized (talkTranslatorLock) {
            LocalTranslator translator = getOrCreateTalkTranslator();
            if (translator == null || !translator.isLoaded()) {
                return null;
            }
            String translated = translator.translate(text, sourceLang, targetLang);
            if (translated == null) {
                return null;
            }
            translated = translated.trim();
            if (translated.isEmpty() || translated.equals(text.trim())) {
                return null;
            }
            return translated;
        }
    }

    private record TalkTextResult(String outputText, String historyText, String translatedText) {}

    private TalkTextResult buildTalkTextResult(String originalText) {
        String original = (originalText == null) ? "" : originalText.trim();
        if (original.isEmpty()) {
            return new TalkTextResult("", "", null);
        }
        String normalized = applyTalkPostProcess(original);
        String translated = null;
        String targetLang = getTalkTranslateTarget();
        String sourceLang = getTalkSourceLang();
        if (!"OFF".equals(targetLang) && !sourceLang.isBlank() && !"auto".equals(sourceLang)) {
            translated = translateTalkTextNow(normalized, sourceLang, targetLang.toLowerCase(Locale.ROOT));
        }
        String output = (translated == null || translated.isBlank()) ? normalized : translated;
        return new TalkTextResult(output, buildTranslatedCaption(normalized, translated), translated);
    }

    private PostProcessor getOrCreateTalkPostProcessor() {
        synchronized (talkPostProcessorLock) {
            if (talkPostProcessor != null && talkPostProcessor.isLoaded()) {
                return talkPostProcessor;
            }
            if (talkPostProcessorInitAttempted) {
                return null;
            }
            talkPostProcessorInitAttempted = true;
            File exeDir = getExeDir();
            File modelDir = new File(exeDir, "models" + File.separator + "mt5_onnx_int8");
            PostProcessor processor = new PostProcessor(modelDir);
            if (!processor.load(exeDir)) {
                return null;
            }
            talkPostProcessor = processor;
            return talkPostProcessor;
        }
    }

    private String applyTalkPostProcess(String originalText) {
        String original = (originalText == null) ? "" : originalText.trim();
        if (original.isEmpty()) {
            lastLatPostProcessMs.set(-1);
            return original;
        }
        if (!TALK_POST_PROCESS_ENABLED) {
            lastLatPostProcessMs.set(-1);
            return original;
        }
        String promptLanguage = getTalkOutputLanguage();
        try {
            PostProcessor processor = getOrCreateTalkPostProcessor();
            if (processor == null || !processor.isLoaded()) {
                lastLatPostProcessMs.set(-1);
                return original;
            }
            PostProcessor.ProcessResult result = processor.process(original, promptLanguage);
            if (result == null) {
                lastLatPostProcessMs.set(-1);
                return original;
            }
            lastLatPostProcessMs.set(result.elapsedMs());
            String processed = result.text();
            return (processed == null || processed.isBlank()) ? original : processed.trim();
        } catch (Throwable t) {
            lastLatPostProcessMs.set(-1);
            Config.logError("[Talk][PostProcessor] failed", t);
            return original;
        }
    }

    private void unloadTalkPostProcessor() {
        synchronized (talkPostProcessorLock) {
            try {
                if (talkPostProcessor != null) {
                    talkPostProcessor.unload();
                }
            } catch (Throwable ignore) {
            } finally {
                talkPostProcessor = null;
                talkPostProcessorInitAttempted = false;
            }
        }
    }

    public void speakTalkTextForUi(String originalText) {
        String original = (originalText == null) ? "" : originalText.trim();
        if (original.isEmpty()) return;
        TalkTextResult talkText = buildTalkTextResult(original);
        String out = (talkText.outputText() == null || talkText.outputText().isBlank()) ? original : talkText.outputText();
        String historyText = (talkText.historyText() == null || talkText.historyText().isBlank()) ? original : talkText.historyText();
        if (ttsConfirmMode) {
            enqueuePendingConfirm(original, out, historyText, Action.NOTHING, 0L, "radio/ui", null);
            return;
        }
        commitTalkOutput(original, out, historyText, Action.NOTHING, 0L, "radio/ui", null);
    }

    public String buildTranslatedCaption(String originalText, String translatedText) {
        if (translatedText == null || translatedText.isBlank()) {
            return originalText;
        }
        String original = (originalText == null) ? "" : originalText.trim();
        String translated = translatedText.trim();
        if (original.isEmpty()) {
            return translated;
        }
        return translated + " (" + original + ")";
    }

    public boolean isTtsConfirmModeEnabled() {
        return ttsConfirmMode;
    }

    public boolean hasPendingConfirm() {
        synchronized (confirmLock) {
            return !pendingConfirmQueue.isEmpty();
        }
    }

    public int getPendingConfirmCount() {
        synchronized (confirmLock) {
            return pendingConfirmQueue.size();
        }
    }

    private PendingConfirmItem peekPendingConfirmItem() {
        synchronized (confirmLock) {
            return pendingConfirmQueue.peekFirst();
        }
    }

    private java.util.List<PendingConfirmItem> snapshotPendingConfirmItems() {
        synchronized (confirmLock) {
            return new ArrayList<>(pendingConfirmQueue);
        }
    }

    public String getPendingConfirmPreviewText() {
        PendingConfirmItem item = peekPendingConfirmItem();
        if (item == null) return "";
        return (item.historyText == null || item.historyText.isBlank()) ? item.outputText : item.historyText;
    }

    public int getPendingConfirmRemainingSeconds() {
        PendingConfirmItem item = peekPendingConfirmItem();
        if (item == null) return 0;
        long remainMs = Math.max(0L, item.dueAtMs - System.currentTimeMillis());
        return (int) Math.ceil(remainMs / 1000.0);
    }

    public void approvePendingConfirm() {
        flushPendingConfirm(true, "manual");
    }

    public void approvePendingConfirmAtSlot(int slot) {
        flushPendingConfirmAtSlot(slot, true, "manual-slot");
    }

    public void cancelPendingConfirm() {
        flushPendingConfirm(false, "manual");
    }

    public java.util.List<String> getPendingConfirmPreviewList(int maxItems) {
        ArrayList<String> out = new ArrayList<>();
        int limit = Math.max(1, maxItems);
        for (PendingConfirmItem item : snapshotPendingConfirmItems()) {
            String preview = (item.historyText == null || item.historyText.isBlank()) ? item.outputText : item.historyText;
            if (preview != null && !preview.isBlank()) {
                out.add(preview);
            }
            if (out.size() >= limit) break;
        }
        return out;
    }

    private int getConfirmDelaySeconds() {
        try {
            return Math.max(1, prefs.getInt("tts.confirm_sec", CONFIRM_DEFAULT_SEC));
        } catch (Exception ignore) {
            return CONFIRM_DEFAULT_SEC;
        }
    }

    private void enqueuePendingConfirm(String sourceText,
                                       String outputText,
                                       String historyText,
                                       Action action,
                                       long acceptedAtMs,
                                       String originTag,
                                       TtsProsodyProfile ttsProsodyProfile) {
        long now = System.currentTimeMillis();
        long dueAtMs = now + (getConfirmDelaySeconds() * 1000L);
        PendingConfirmItem next = new PendingConfirmItem(
                pendingConfirmSeq.incrementAndGet(),
                sourceText,
                outputText,
                historyText,
                action,
                acceptedAtMs,
                dueAtMs,
                originTag,
                ttsProsodyProfile
        );
        PendingConfirmItem dropped = null;
        boolean wasEmpty;
        synchronized (confirmLock) {
            wasEmpty = pendingConfirmQueue.isEmpty();
            while (pendingConfirmQueue.size() >= CONFIRM_QUEUE_MAX) {
                if (pendingConfirmQueue.size() <= 1) {
                    dropped = pendingConfirmQueue.pollLast();
                    break;
                }
                Iterator<PendingConfirmItem> it = pendingConfirmQueue.iterator();
                if (it.hasNext()) it.next(); // keep current countdown head
                if (it.hasNext()) {
                    dropped = it.next();
                    it.remove();
                } else {
                    dropped = pendingConfirmQueue.pollLast();
                }
            }
            pendingConfirmQueue.addLast(next);
        }
        if (dropped != null) {
            Config.log("[Confirm] drop queued pending: " + dropped.outputText + " -> enqueue " + next.outputText);
        } else {
            Config.log("[Confirm] enqueue pending: " + next.outputText + " (queue=" + getPendingConfirmCount() + ")");
        }
        SwingUtilities.invokeLater(() -> {
            if (wasEmpty || confirmCountdownTimer == null || !confirmCountdownTimer.isRunning()) {
                restartConfirmCountdown();
            }
            updateConfirmUi();
        });
    }

    private void restartConfirmCountdown() {
        if (confirmCountdownTimer != null) {
            confirmCountdownTimer.stop();
        }
        confirmCountdownTimer = new Timer(250, e -> {
            PendingConfirmItem item = peekPendingConfirmItem();
            if (item == null) {
                ((Timer) e.getSource()).stop();
                updateConfirmUi();
                return;
            }
            if (System.currentTimeMillis() >= item.dueAtMs) {
                ((Timer) e.getSource()).stop();
                flushPendingConfirm(true, "timeout");
                return;
            }
            updateConfirmUi();
        });
        confirmCountdownTimer.setRepeats(true);
        confirmCountdownTimer.start();
    }

    private void flushPendingConfirm(boolean doSpeak, String reason) {
        flushPendingConfirmAtSlot(1, doSpeak, reason);
    }

    private void clearPendingConfirmQueue(String reason) {
        if (confirmCountdownTimer != null) {
            confirmCountdownTimer.stop();
        }
        int cleared = 0;
        synchronized (confirmLock) {
            cleared = pendingConfirmQueue.size();
            pendingConfirmQueue.clear();
        }
        Config.log("[Confirm] clear queue (" + reason + "): count=" + cleared);
        SwingUtilities.invokeLater(this::updateConfirmUi);
    }

    private void flushPendingConfirmAtSlot(int slot, boolean doSpeak, String reason) {
        if (confirmCountdownTimer != null) {
            confirmCountdownTimer.stop();
        }
        PendingConfirmItem item;
        synchronized (confirmLock) {
            if (pendingConfirmQueue.isEmpty()) {
                item = null;
            } else {
                int index = Math.max(1, slot) - 1;
                if (index >= pendingConfirmQueue.size()) {
                    item = null;
                } else
                if (index <= 0) {
                    item = pendingConfirmQueue.pollFirst();
                } else {
                    item = null;
                    int i = 0;
                    Iterator<PendingConfirmItem> it = pendingConfirmQueue.iterator();
                    while (it.hasNext()) {
                        PendingConfirmItem next = it.next();
                        if (i == index) {
                            item = next;
                            it.remove();
                            break;
                        }
                        i++;
                    }
                }
            }
        }
        if (item != null) {
            if (doSpeak) {
                Config.log("[Confirm] flush speak (" + reason + "): " + item.outputText);
                commitTalkOutput(
                        item.sourceText,
                        item.outputText,
                        item.historyText,
                        item.action,
                        item.acceptedAtMs,
                        item.originTag + "/confirm",
                        item.ttsProsodyProfile
                );
            } else {
                Config.log("[Confirm] flush cancel (" + reason + "): " + item.outputText);
            }
        } else {
            Config.log("[Confirm] flush skipped (" + reason + "): no item for slot=" + slot);
        }
        SwingUtilities.invokeLater(() -> {
            restartConfirmCountdown();
            updateConfirmUi();
        });
    }

    private void setConfirmMode(boolean enabled) {
        ttsConfirmMode = enabled;
        try {
            prefs.putBoolean("tts.confirm_mode", enabled);
            try { prefs.sync(); } catch (Exception ignore) {}
        } catch (Exception ignore) {}
        if (!enabled) {
            clearPendingConfirmQueue("mode_off");
        }
        updateConfirmModeToggleUi();
        updateConfirmUi();
        Config.log("[Confirm] mode=" + (enabled ? "PENDING" : "INSTANT"));
    }

    private void updateConfirmModeToggleUi() {
        if (confirmModeToggle == null) return;
        confirmModeToggle.setSelected(ttsConfirmMode);
        confirmModeToggle.setText(ttsConfirmMode ? uiOr("ui.main.confirm.pending.short", "P") : uiOr("ui.main.confirm.instant.short", "I"));
        confirmModeToggle.setToolTipText(ttsConfirmMode
                ? uiOr("ui.main.confirm.pending.tip", "Pending confirm mode")
                : uiOr("ui.main.confirm.instant.tip", "Immediate mode"));
        confirmModeToggle.setOpaque(true);
        confirmModeToggle.setForeground(Color.WHITE);
        if (ttsConfirmMode) {
            confirmModeToggle.setBackground(new Color(120, 88, 22));
            confirmModeToggle.setBorder(BorderFactory.createLineBorder(new Color(206, 154, 54)));
        } else {
            confirmModeToggle.setBackground(new Color(56, 66, 78));
            confirmModeToggle.setBorder(BorderFactory.createLineBorder(new Color(110, 130, 150)));
        }
    }

    private void updateConfirmUi() {
        PendingConfirmItem item = peekPendingConfirmItem();
        int queueCount = getPendingConfirmCount();
        if (confirmCountdownLabel != null) {
            String timerText = (item == null)
                    ? ""
                    : uiOr("ui.confirm.countdown.prefix", "T-") + Math.max(0, (int) Math.ceil((item.dueAtMs - System.currentTimeMillis()) / 1000.0)) + "s";
            confirmCountdownLabel.setText(timerText);
        }
        if (confirmTextLabel != null) {
            String preview = (item == null) ? "" : getPendingConfirmPreviewText();
            if (queueCount > 1) {
                preview = preview + "  (+" + (queueCount - 1) + ")";
            }
            confirmTextLabel.setText(preview);
            String tooltip = String.join("\n", getPendingConfirmPreviewList(CONFIRM_QUEUE_MAX));
            confirmTextLabel.setToolTipText(tooltip.isBlank() ? preview : tooltip);
        }
        if (confirmPanel != null) {
            confirmPanel.setVisible(ttsConfirmMode && item != null);
            confirmPanel.revalidate();
            confirmPanel.repaint();
        }
        if (radioPage == RADIO_CONFIRM_PAGE && !shouldShowConfirmRadioPage()) {
            radioPage = 0;
        }
        updateConfirmModeToggleUi();
        updateRadioOverlay();
        notifyHistoryConfirmStateChanged();
        if (window != null) {
            window.revalidate();
            window.repaint();
        }
    }
    private void migrateTtsReflectEmotionDefaultIfNeeded() {
        if (prefs == null) return;
        boolean touched = prefs.getBoolean("tts.reflect_emotion.user_touched", false);
        String raw = prefs.get("tts.reflect_emotion", "__missing__");
        if (!touched && ("__missing__".equals(raw) || "false".equalsIgnoreCase(raw))) {
            prefs.putBoolean("tts.reflect_emotion", true);
            Config.logDebug("[TTS_PROSODY] migrate default -> ON (untouched install)");
        }
    }

    private void notifyHistoryConfirmStateChanged() {
        if (historyFrame == null) return;
        SwingUtilities.invokeLater(() -> {
            if (historyFrame != null) {
                historyFrame.refreshConfirmControls();
            }
        });
    }

    private void commitTalkOutput(String sourceText,
                                  String out,
                                  String historyText,
                                  Action action,
                                  long acceptedAtMs,
                                  String logTag,
                                  TtsProsodyProfile ttsProsodyProfile) {
        final String safeSource = (sourceText == null) ? "" : sourceText.trim();
        final String safeOut = (out == null || out.isBlank()) ? safeSource : out;
        final String safeHistory = (historyText == null || historyText.isBlank()) ? safeOut : historyText;

        SwingUtilities.invokeLater(() -> {
            if (action == Action.TYPE_STRING) {
                try { new RobotTyper().typeString(safeOut, 11); } catch (Exception ignore) {}
            } else if (action == Action.COPY_TO_CLIPBOARD_AND_PASTE) {
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                Transferable prev = null;
                try { prev = clipboard.getContents(null); } catch (Exception ignore) {}
                final Transferable prevF = prev;
                clipboard.setContents(new StringSelection(safeOut), null);
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

        if (action != Action.NOTHING_NO_SPEAK) {
            SwingUtilities.invokeLater(() -> addHistory(safeHistory, safeSource));
            CompletableFuture.runAsync(() -> {
                long ttsStart = System.currentTimeMillis();
                latTtsStartMs.set(ttsStart);

                if (acceptedAtMs > 0L) {
                    long f2tts = ttsStart - acceptedAtMs;
                    Config.logDebug("★LAT(ms) confirm final->tts=" + f2tts);
                }
                speak(safeOut, ttsProsodyProfile);
                Config.appendOutTts(safeOut);
                Config.logDebug("speak(" + logTag + "): " + safeOut);
            });
        } else {
            Config.logDebug("speak skipped (" + logTag + "): " + safeOut);
        }
    }

    private static String normalizeTalkShortCaptionKey(String text) {
        if (text == null) return "";
        String s = text.trim().toLowerCase(Locale.ROOT);
        s = s.replaceAll("[\\s\\p{Punct}、。！？「」『』（）()\\[\\]{}【】…・~〜ー]+", "");
        return s;
    }

    private boolean shouldSuppressTalkPartialPreview(String text) {
        String normalized = normalizeTalkShortCaptionKey(text);
        if (normalized.isEmpty()) return false;
        int cp = normalized.codePointCount(0, normalized.length());
        if (cp <= 0 || cp > TALK_SHORT_REPEAT_MAX_CP) return false;

        long now = System.currentTimeMillis();
        synchronized (recentTalkShortCaptionMs) {
            recentTalkShortCaptionMs.entrySet().removeIf(e -> now - e.getValue() > TALK_SHORT_REPEAT_SUPPRESS_MS);
            Long prev = recentTalkShortCaptionMs.get(normalized);
            recentTalkShortCaptionMs.put(normalized, now);
            if (prev != null && now - prev <= TALK_SHORT_REPEAT_SUPPRESS_MS) {
                Config.logDebug("[Talk][Filter] suppress short repeated preview: " + text);
                return true;
            }
        }
        return false;
    }

    private void updateTalkPartialPreview(String text) {
        long seq = talkPartialPreviewSeq.incrementAndGet();
        if (historyFrame == null) {
            return;
        }
        if (text == null || text.isBlank()) {
            historyFrame.setPartialPreview("");
            return;
        }
        final String original = text.trim();
        if (shouldSuppressTalkPartialPreview(original)) {
            historyFrame.setPartialPreview("");
            return;
        }
        historyFrame.setPartialPreview(original);

        String targetLang = getTalkTranslateTarget();
        String sourceLang = getTalkSourceLang();
        if ("OFF".equals(targetLang) || sourceLang.isBlank() || "auto".equals(sourceLang) || sourceLang.equalsIgnoreCase(targetLang)) {
            return;
        }
        if (!TALK_PARTIAL_TRANSLATION_ENABLED) {
            return;
        }
        if (isTalkTranslatorUnavailableForSession()) {
            return;
        }
        if (!talkPartialTranslateBusy.compareAndSet(false, true)) {
            Config.logDebug("[Talk][Translator] skip partial because previous async translation is still running");
            return;
        }
        Config.logDebug("[Talk][Translator] partial async start: src=" + sourceLang + " tgt=" + targetLang + " text=" + original);
        talkTranslateExecutor.execute(() -> {
            try {
                String translated = translateTalkTextNow(original, sourceLang, targetLang.toLowerCase(Locale.ROOT));
                Config.logDebug("[Talk][Translator] partial async done: " + ((translated == null) ? "<null>" : translated));
                if (translated != null && !translated.isBlank() && talkPartialPreviewSeq.get() == seq && historyFrame != null) {
                    String caption = buildTranslatedCaption(original, translated);
                    if (!shouldSuppressTalkPartialPreview(caption)) {
                        historyFrame.setPartialPreview(caption);
                    }
                }
            } catch (Throwable t) {
                Config.logError("[Talk][Translator] partial async translate failed", t);
            } finally {
                talkPartialTranslateBusy.set(false);
            }
        });
    }
    private LocalTranslator getOrCreateHearingTranslator() {
        synchronized (hearingTranslatorLock) {
            if (hearingTranslator != null && hearingTranslator.isLoaded()) {
                hearingTranslatorUnavailableLogged = false;
                return hearingTranslator;
            }
            if (hearingTranslatorInitAttempted) {
                return null;
            }
            hearingTranslatorInitAttempted = true;
            File exeDir = getExeDir();
            File modelDir = null;
            LocalTranslator translator = new LocalTranslator(modelDir, 2);
            if (!translator.load(exeDir)) {
                return null;
            }
            hearingTranslator = translator;
            hearingTranslatorUnavailableLogged = false;
            return hearingTranslator;
        }
    }

    private boolean isHearingTranslatorUnavailableForSession() {
        synchronized (hearingTranslatorLock) {
            boolean unavailable = hearingTranslatorInitAttempted
                    && (hearingTranslator == null || !hearingTranslator.isLoaded());
            if (unavailable && !hearingTranslatorUnavailableLogged) {
                Config.log("[Hearing][Translator] disabled for this session because translator init failed");
                hearingTranslatorUnavailableLogged = true;
            }
            return unavailable;
        }
    }

    public String getHearingTranslateTarget() {
        String v = "OFF";
        try {
            if (prefs != null) v = prefs.get("hearing.translate.target", "");
        } catch (Exception ignore) {}
        if (v == null || v.isBlank()) {
            boolean legacy = false;
            try { if (prefs != null) legacy = prefs.getBoolean("hearing.translate_to_en", false); } catch (Exception ignore) {}
            v = legacy ? "EN" : "OFF";
        }
        v = LanguageOptions.normalizeTranslationTarget(v);
        return v;
    }

    public String getHearingTranslateQueueMode() {
        String mode = HEARING_TRANSLATE_MODE_REALTIME;
        try {
            if (prefs != null) mode = prefs.get("hearing.translate.queue_mode", HEARING_TRANSLATE_MODE_REALTIME);
        } catch (Exception ignore) {}
        if (!HEARING_TRANSLATE_MODE_LATEST.equals(mode)
                && !HEARING_TRANSLATE_MODE_PREFER_NEW.equals(mode)
                && !HEARING_TRANSLATE_MODE_REALTIME.equals(mode)) {
            mode = HEARING_TRANSLATE_MODE_REALTIME;
        }
        return mode;
    }

    private String getHearingSourceLang() {
        String sourceLang = "auto";
        try {
            if (prefs != null) sourceLang = prefs.get("hearing.lang", "auto");
        } catch (Exception ignore) {}
        if (sourceLang == null) {
            return "auto";
        }
        return sourceLang.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isHearingAutoLanguage() {
        String sourceLang = getHearingSourceLang();
        return sourceLang.isBlank()
                || "auto".equalsIgnoreCase(sourceLang)
                || LanguageOptions.HEARING_AUTO_STABLE.equalsIgnoreCase(sourceLang);
    }

    public boolean isHearingAutoStableLanguage() {
        return LanguageOptions.HEARING_AUTO_STABLE.equalsIgnoreCase(getHearingSourceLang());
    }

    private String normalizeHearingWhisperRuntimeLang(String lang) {
        if (lang == null) return "auto";
        String normalized = lang.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()
                || "auto".equals(normalized)
                || LanguageOptions.HEARING_AUTO_STABLE.equals(normalized)) {
            return "auto";
        }
        return normalized;
    }

    private String getHearingWhisperRuntimeLang() {
        if (isHearingAutoStableLanguage()) {
            String session = normalizeHearingWhisperRuntimeLang(hearingWhisperAutoSessionLang);
            if (!"auto".equals(session)) return session;
        }
        return normalizeHearingWhisperRuntimeLang(getHearingSourceLang());
    }

    private String getHearingMoonshineAutoBaseLang() {
        String lang = hearingMoonshineAutoSessionLang;
        if (lang == null || lang.isBlank()) {
            return "en";
        }
        return lang.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeHearingMoonshineSessionLang(String lang) {
        if (lang == null) return "";
        String normalized = lang.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || "auto".equals(normalized)) return "";
        return normalized;
    }

    private String resolveHearingMoonshineModelPathForLang(String lang) {
        String normalized = normalizeHearingMoonshineSessionLang(lang);
        if (normalized.isBlank()) {
            normalized = "en";
        }
        try {
            LinkedHashMap<String, File> modelMap = scanMoonshineModelMap();
            for (Map.Entry<String, File> e : modelMap.entrySet()) {
                String key = (e.getKey() == null) ? "" : e.getKey().trim().toLowerCase(Locale.ROOT);
                if (key.equals(normalized) || key.startsWith(normalized)) {
                    return e.getValue().getAbsolutePath();
                }
            }
        } catch (Exception ignore) {}
        return "";
    }

    private LocalMoonshineSTT loadHearingMoonshineModel(String path, String langForLog) {
        if (path == null || path.isBlank()) return null;
        LocalMoonshineSTT hm = new LocalMoonshineSTT();
        hm.setNumThreads(Math.max(2, Runtime.getRuntime().availableProcessors() / 4));
        int[] archs = {1, 3, 0, 2, 4, 5};
        for (int arch : archs) {
            try {
                if (hm.load(path, arch)) {
                    Config.log("[Moonshine][Hearing] loaded OK arch=" + arch + " lang=" + langForLog);
                    return hm;
                }
            } catch (Throwable t) {
                Config.log("[Moonshine][Hearing] load failed arch=" + arch + " : " + t.getMessage());
            }
        }
        try { hm.close(); } catch (Throwable ignore) {}
        Config.log("[Moonshine][Hearing] load failed, fallback to Whisper");
        return null;
    }

    public void requestHearingAutoMoonshineSessionLang(String lang) {
        String normalized = normalizeHearingMoonshineSessionLang(lang);
        if (normalized.isBlank()) return;
        if (!isHearingEngineMoonshine() || !isHearingAutoLanguage()) return;
        String current = getHearingMoonshineAutoBaseLang();
        if (normalized.equalsIgnoreCase(current)) return;
        long now = System.currentTimeMillis();
        if ((now - hearingMoonshineLastReloadMs) < HEARING_MOONSHINE_AUTO_SWITCH_COOLDOWN_MS) {
            Config.logDebug("[Moonshine][Hearing] auto-switch skipped by cooldown: current="
                    + current + " next=" + normalized
                    + " elapsed=" + (now - hearingMoonshineLastReloadMs));
            return;
        }
        hearingMoonshineAutoSessionLang = normalized;
        long seq = hearingMoonshineReloadSeq.incrementAndGet();
        hearingMoonshineReloadExecutor.execute(() -> reloadHearingMoonshineForAutoSessionLang(normalized, seq));
    }

    public void requestHearingAutoWhisperSessionLang(String lang) {
        String normalized = normalizeHearingWhisperRuntimeLang(lang);
        if ("auto".equals(normalized)) return;
        if (isHearingEngineMoonshine() || !isHearingAutoStableLanguage()) return;
        String current = normalizeHearingWhisperRuntimeLang(hearingWhisperAutoSessionLang);
        if (normalized.equals(current)) return;
        long now = System.currentTimeMillis();
        if ((now - hearingWhisperLastShiftMs) < HEARING_MOONSHINE_AUTO_SWITCH_COOLDOWN_MS) {
            Config.logDebug("[Whisper][Hearing] auto-shift skipped by cooldown: current="
                    + current + " next=" + normalized
                    + " elapsed=" + (now - hearingWhisperLastShiftMs));
            return;
        }
        hearingWhisperAutoSessionLang = normalized;
        hearingWhisperLastShiftMs = now;
        try {
            LocalWhisperCPP cur = wHearing;
            if (cur != null) cur.setLanguage(normalized);
            Config.log("[Whisper][Hearing] auto-session language shifted -> " + normalized);
        } catch (Exception ex) {
            Config.logError("[Whisper][Hearing] auto-session language shift failed", ex);
        }
    }

    public void resetHearingAutoSessionState(String reason) {
        hearingMoonshineReloadSeq.incrementAndGet();
        hearingMoonshineAutoSessionLang = "en";
        hearingMoonshineLastReloadMs = 0L;
        hearingWhisperAutoSessionLang = "auto";
        hearingWhisperLastShiftMs = 0L;

        synchronized (hearingWhisperLock) {
            wHearing = null;
            hw = null;
        }
        hearingSem.acquireUninterruptibly();
        try {
            synchronized (hearingMoonshineLock) {
                if (hearingMoonshine != null) {
                    try { hearingMoonshine.close(); } catch (Throwable ignore) {}
                    hearingMoonshine = null;
                }
            }
        } finally {
            hearingSem.release();
        }
        Config.log("[Hearing] auto session state reset (" + reason + ")");
    }

    private void reloadHearingMoonshineForAutoSessionLang(String lang, long seq) {
        if (!isHearingEngineMoonshine() || !isHearingAutoLanguage()) return;
        String path = resolveHearingMoonshineModelPathForLang(lang);
        if (path.isBlank()) {
            Config.log("[Moonshine][Hearing] auto-switch skipped: no model for lang=" + lang);
            return;
        }
        hearingSem.acquireUninterruptibly();
        try {
            if (seq != hearingMoonshineReloadSeq.get()) return;
            synchronized (hearingMoonshineLock) {
                if (hearingMoonshine != null) {
                    try { hearingMoonshine.close(); } catch (Throwable ignore) {}
                    hearingMoonshine = null;
                }
            }
            if (seq != hearingMoonshineReloadSeq.get()) return;
            LocalMoonshineSTT hm = loadHearingMoonshineModel(path, "auto-session:" + lang);
            if (hm == null) return;
            synchronized (hearingMoonshineLock) {
                if (seq != hearingMoonshineReloadSeq.get()) {
                    try { hm.close(); } catch (Throwable ignore) {}
                    return;
                }
                hearingMoonshine = hm;
                hearingMoonshineLastReloadMs = System.currentTimeMillis();
                Config.log("[Moonshine][Hearing] auto-session model switched -> " + lang);
            }
        } finally {
            hearingSem.release();
        }
    }

    private String getHearingMoonshineModelPath() {
        String path = "";
        try {
            if (prefs != null) path = prefs.get("hearing.moonshine.model_path", "");
        } catch (Exception ignore) {}
        String hearingLang = getHearingSourceLang();
        boolean autoLikeLang = hearingLang.isBlank()
                || "auto".equalsIgnoreCase(hearingLang)
                || LanguageOptions.HEARING_AUTO_STABLE.equalsIgnoreCase(hearingLang);
        if (path != null && !path.isBlank() && !autoLikeLang) {
            return path;
        }
        String modelLang = hearingLang;
        if (autoLikeLang) {
            modelLang = getHearingMoonshineAutoBaseLang();
        }
        String resolved = resolveHearingMoonshineModelPathForLang(modelLang);
        if (!resolved.isBlank() && !autoLikeLang) {
            try {
                if (prefs != null) prefs.put("hearing.moonshine.model_path", resolved);
            } catch (Exception ignore) {}
        } else if (resolved.isBlank()) {
            Config.log("[Moonshine][Hearing] no model path resolved for hearing.lang="
                    + hearingLang + " modelLang=" + modelLang);
        }
        return resolved;
    }

    public String detectHearingSourceLangByScript(String text) {
        String detected = inferHearingSourceLangByScript(text);
        return (detected == null) ? "" : detected;
    }

    private String resolveHearingSourceLangForTranslation(String text, String targetLang) {
        String configured = getHearingSourceLang();
        if (!configured.isBlank() && !"auto".equals(configured)) {
            return configured;
        }
        String inferred = inferHearingSourceLangFromText(text, targetLang);
        Config.logDebug("[Hearing][Translator] auto source inferred: "
                + inferred + " for target=" + targetLang + " text=" + text);
        return inferred;
    }

    private String inferHearingSourceLangFromText(String text, String targetLang) {
        String detected = inferHearingSourceLangByScript(text);
        if (!detected.isBlank()) {
            return detected;
        }
        return fallbackHearingSourceLang(targetLang);
    }

    private String inferWhisperAutoStableCjkBiasLang(int latin, int kana, int cjk, int hangul, int total) {
        if (isHearingEngineMoonshine() || !isHearingAutoStableLanguage()) {
            return "";
        }
        String currentRuntime = getHearingWhisperRuntimeLang();
        if (!"en".equals(currentRuntime) || total <= 0) {
            return "";
        }
        int jaScore = kana + cjk;
        if (hangul >= 3
                && hangul >= (latin * 2)
                && (hangul * 100) >= (total * 60)) {
            Config.logDebug("[Hearing][Lang] whisper auto-stable cjk-bias -> ko"
                    + " hangul=" + hangul + " total=" + total + " latin=" + latin);
            return "ko";
        }
        if (kana >= 3
                && jaScore >= 6
                && jaScore >= (latin * 2)
                && (jaScore * 100) >= (total * 60)) {
            Config.logDebug("[Hearing][Lang] whisper auto-stable cjk-bias -> ja"
                    + " kana=" + kana + " cjk=" + cjk + " total=" + total + " latin=" + latin);
            return "ja";
        }
        if (cjk >= 6
                && kana == 0
                && cjk >= (latin * 2)
                && (cjk * 100) >= (total * 70)) {
            Config.logDebug("[Hearing][Lang] whisper auto-stable cjk-bias -> zh"
                    + " cjk=" + cjk + " total=" + total + " latin=" + latin);
            return "zh";
        }
        return "";
    }

    private String inferHearingSourceLangByScript(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int latin = 0;
        int hiragana = 0;
        int katakana = 0;
        int cjk = 0;
        int hangul = 0;
        int cyrillic = 0;
        int arabic = 0;
        int hebrew = 0;
        int thai = 0;
        int devanagari = 0;

        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
            if (block == Character.UnicodeBlock.HIRAGANA) hiragana++;
            else if (block == Character.UnicodeBlock.KATAKANA || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS) katakana++;
            else if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) cjk++;
            else if (block == Character.UnicodeBlock.HANGUL_SYLLABLES
                    || block == Character.UnicodeBlock.HANGUL_JAMO
                    || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) hangul++;
            else if (block == Character.UnicodeBlock.CYRILLIC
                    || block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY
                    || block == Character.UnicodeBlock.CYRILLIC_EXTENDED_A
                    || block == Character.UnicodeBlock.CYRILLIC_EXTENDED_B) cyrillic++;
            else if (block == Character.UnicodeBlock.ARABIC
                    || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A
                    || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B) arabic++;
            else if (block == Character.UnicodeBlock.HEBREW) hebrew++;
            else if (block == Character.UnicodeBlock.THAI) thai++;
            else if (block == Character.UnicodeBlock.DEVANAGARI) devanagari++;
            else if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z')
                    || block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT
                    || block == Character.UnicodeBlock.LATIN_EXTENDED_A
                    || block == Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL
                    || block == Character.UnicodeBlock.LATIN_EXTENDED_B) latin++;
        }

        int kana = hiragana + katakana;
        int total = latin + kana + cjk + hangul + cyrillic + arabic + hebrew + thai + devanagari;
        if (total <= 0) return "";

        String whisperAutoStableBiasLang = inferWhisperAutoStableCjkBiasLang(latin, kana, cjk, hangul, total);
        if (!whisperAutoStableBiasLang.isBlank()) {
            return whisperAutoStableBiasLang;
        }

        // 英語音声に少量のかな/漢字ノイズが混ざるケースが多いので、
        // mixed script では latin 優勢を先に拾うっす。
        int nonLatin = total - latin;
        if (latin >= 6 && latin >= (nonLatin * 2)) return "en";
        if (latin >= 10 && (latin * 100) >= (total * 55)) return "en";

        if (kana >= 4 && (kana * 100) >= (total * 30)) return "ja";
        if (kana >= 2 && (kana + cjk) >= 6 && ((kana + cjk) * 100) >= (total * 45)) return "ja";

        if (hangul >= 2 && (hangul * 100) >= (total * 30)) return "ko";
        if (cjk >= 4 && kana == 0 && (cjk * 100) >= (total * 35)) return "zh";
        if (cyrillic >= 2 && (cyrillic * 100) >= (total * 30)) return "ru";
        if (arabic >= 2 && (arabic * 100) >= (total * 30)) return "ar";
        if (hebrew >= 2 && (hebrew * 100) >= (total * 30)) return "he";
        if (thai >= 2 && (thai * 100) >= (total * 30)) return "th";
        if (devanagari >= 2 && (devanagari * 100) >= (total * 30)) return "hi";

        // 短い mixed text（例: はいOkay.）だけで en に倒れるのを避けつつ、
        // 英語主体の弱い断片は拾えるように 70% 支配で見るっす。
        if (latin >= 6 && (latin * 100) >= (total * 70)) return "en";
        return "";
    }

    private String fallbackHearingSourceLang(String targetLang) {
        String normalizedTarget = (targetLang == null) ? "" : targetLang.trim().toLowerCase(Locale.ROOT);
        if (!"en".equals(normalizedTarget)) {
            return "en";
        }
        try {
            if (prefs != null) {
                String appLang = prefs.get("language", "ja");
                if (LanguageOptions.isTranslationLangSupported(appLang)) {
                    return appLang.trim().toLowerCase(Locale.ROOT);
                }
            }
        } catch (Exception ignore) {}
        return "ja";
    }

    private String translateHearingTextNow(String text, String sourceLang, String targetLang) {
        if (text == null || text.isBlank()) {
            return null;
        }
        LocalTranslator translator;
        synchronized (hearingTranslatorLock) {
            translator = getOrCreateHearingTranslator();
            if (translator == null || !translator.isLoaded()) {
                return null;
            }
        }
        String translated = translator.translate(text, sourceLang, targetLang);
        if (translated == null) {
            return null;
        }
        translated = translated.trim();
        if (translated.isEmpty() || translated.equals(text.trim())) {
            return null;
        }
        return translated;
    }

    private void recoverHearingTranslatorIfStuck() {
        long startedAt = hearingTranslateStartedMs.get();
        if (!hearingTranslateBusy.get() || startedAt <= 0L) return;
        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed < HEARING_TRANSLATE_STUCK_MS) return;

        Config.log("[Hearing][Translator] stuck detected (" + elapsed + "ms), resetting translator. active=" + hearingTranslateActiveText);
        hearingTranslateQueue.set(null);
        hearingTranslateBusy.set(false);
        hearingTranslateStartedMs.set(0L);
        hearingTranslateActiveText = "";
        synchronized (hearingTranslatorLock) {
            try {
                if (hearingTranslator != null) hearingTranslator.unload();
            } catch (Throwable ignore) {}
            hearingTranslator = null;
            hearingTranslatorInitAttempted = false;
            hearingTranslatorUnavailableLogged = false;
        }
    }

    private void dispatchHearingTranslate(HearingTranslateRequest request, String mode) {
        hearingTranslateExecutor.execute(() -> {
            try {
                hearingTranslateStartedMs.set(System.currentTimeMillis());
                hearingTranslateActiveText = request.text;
                String translated = translateHearingTextNow(
                        request.text,
                        request.sourceLang,
                        request.targetLang.toLowerCase(Locale.ROOT)
                );
                boolean stale = HEARING_TRANSLATE_MODE_PREFER_NEW.equals(mode)
                        && hearingTranslateSeq.get() > request.seq;
                Config.log("[Hearing][Translator] async done: "
                        + ((translated == null) ? "<null>" : translated)
                        + (stale ? " (stale)" : ""));
                if (!stale && translated != null && !translated.isBlank()) {
                    request.onTranslated.accept(translated);
                }
                if (!stale && (translated == null || translated.isBlank())) {
                    Config.log("[Hearing][Translator] async no update");
                }
            } catch (Throwable t) {
                Config.logError("[Hearing][Translator] async translate failed", t);
            } finally {
                String nextMode = getHearingTranslateQueueMode();
                HearingTranslateRequest queued = hearingTranslateQueue.getAndSet(null);
                if (queued != null && !HEARING_TRANSLATE_MODE_REALTIME.equals(nextMode)) {
                    Config.log("[Hearing][Translator] drain queue: " + queued.text);
                    dispatchHearingTranslate(queued, nextMode);
                    return;
                }
                hearingTranslateBusy.set(false);
                hearingTranslateStartedMs.set(0L);
                hearingTranslateActiveText = "";

                HearingTranslateRequest lateQueued = hearingTranslateQueue.getAndSet(null);
                if (lateQueued != null
                        && !HEARING_TRANSLATE_MODE_REALTIME.equals(getHearingTranslateQueueMode())
                        && hearingTranslateBusy.compareAndSet(false, true)) {
                    Config.log("[Hearing][Translator] drain late queue: " + lateQueued.text);
                    dispatchHearingTranslate(lateQueued, getHearingTranslateQueueMode());
                }
            }
        });
    }

    public void translateHearingTextAsync(String text, java.util.function.Consumer<String> onTranslated) {
        translateHearingTextAsync(text, null, onTranslated);
    }

    public void translateHearingTextAsync(String text,
                                          String sourceLangHint,
                                          java.util.function.Consumer<String> onTranslated) {
        if (text == null || text.isBlank() || onTranslated == null) {
            return;
        }
        String targetLang = getHearingTranslateTarget();
        if ("OFF".equals(targetLang)) {
            return;
        }
        String sourceLang = (sourceLangHint == null) ? "" : sourceLangHint.trim().toLowerCase(Locale.ROOT);
        if (sourceLang.isBlank()) {
            sourceLang = resolveHearingSourceLangForTranslation(text, targetLang);
        }
        if (sourceLang == null || sourceLang.isBlank()) {
            Config.log("[Hearing][Translator] skip because source language could not be resolved");
            return;
        }
        if (isHearingTranslatorUnavailableForSession()) {
            return;
        }
        recoverHearingTranslatorIfStuck();
        String mode = getHearingTranslateQueueMode();
        HearingTranslateRequest request = new HearingTranslateRequest(
                text,
                sourceLang,
                targetLang,
                onTranslated,
                hearingTranslateSeq.incrementAndGet()
        );
        if (!hearingTranslateBusy.compareAndSet(false, true)) {
            if (HEARING_TRANSLATE_MODE_REALTIME.equals(mode)) {
                Config.log("[Hearing][Translator] skip because previous async translation is still running");
                return;
            }
            hearingTranslateQueue.set(request);
            Config.log("[Hearing][Translator] queued while busy (" + mode + "): " + text);
            return;
        }
        Config.log("[Hearing][Translator] async start: mode=" + mode
                + " src=" + sourceLang + " tgt=" + targetLang + " text=" + text);
        dispatchHearingTranslate(request, mode);
    }

    public boolean isHearingTranslateEnabled() {
        return !"OFF".equals(getHearingTranslateTarget());
    }
    public boolean isHearingTranslateBusy() {
        return hearingTranslateBusy.get();
    }
    public String getHearingTranslateActiveText() {
        return hearingTranslateActiveText;
    }

    public boolean isTtsSpeaking() {
        return isTtsSpeaking;
    }
    public boolean isHearingBlockedByTts() {
        long now = System.currentTimeMillis();
        if (isTtsSpeaking) {
            return (now - ttsStartedMs) < HEARING_TTS_ACTIVE_BLOCK_MS;
        }
        return (now - ttsEndedMs) < HEARING_TTS_COOLDOWN_MS;
    }
    public boolean looksLikeTtsEcho(String hearingText) {
        if (hearingText == null || hearingText.isBlank()) return false;
        String last = lastSpeakStr;
        if (last == null || last.isBlank()) return false;
        if ((System.currentTimeMillis() - lastSpeakMs) > 10_000L) return false;

        String h = normForNearDup(hearingText);
        String t = normForNearDup(last);
        if (h.isEmpty() || t.isEmpty()) return false;
        return t.contains(h) || h.contains(t) || similarityRatio(h, t) > 0.75;
    }

    public String buildHearingTranslatedCaption(String originalText, String translatedText) {
        if (translatedText == null || translatedText.isBlank()) {
            return originalText;
        }
        String original = (originalText == null) ? "" : originalText.trim();
        String translated = translatedText.trim();
        if (original.isEmpty()) {
            return translated;
        }
        return translated + " (" + original + ")";
    }


    // 翻訳モデル初期化
    private void initTranslator() {
        if (localTranslator != null && localTranslator.isLoaded()) {
            return;
        }
        File exeDir = getExeDir();
        File modelDir = null;
        LocalTranslator translator = new LocalTranslator(modelDir, 2);
        if (translator.load(exeDir)) {
            localTranslator = translator;
            talkTranslatorInitAttempted = true;
            talkTranslatorUnavailableLogged = false;
        } else {
            localTranslator = null;
            talkTranslatorInitAttempted = true;
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
                    try { this.w.setLanguage(getTalkLanguage()); } catch (Exception ignore) {}
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

        // ★v1.5.0: onPartial → UIタイトル更新 + lastPartialResult更新
        moonshine.setOnPartial(text -> {
            if (text == null || text.isBlank()) return;

            long nowMs = System.currentTimeMillis();
            moonshineNoOutputCount = 0;   // 出力ありでリセット
            moonshineLastOutputMs = nowMs;
            lastMoonshineOutputUtteranceSeq = currentUtteranceSeq;

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
            if (isSpeaking && latVadStartMs.get() != 0) {
                latFirstPartMs.compareAndSet(0, nowMs);
            }
            // ★v1.5.0: Whisperから移植 — UI詰まり防止（100文字制限）
            String clamped = trimmed;
            if (clamped.length() > 100) {
                clamped = clamped.substring(0, 100) + "…";
            }
            markFirstPartialNow(nowMs);
            String bestPartial = choosePreferredUtteranceText(MobMateWhisp.getLastPartial(), clamped);
            MobMateWhisp.setLastPartial(bestPartial);
            lastPartialUpdateMs = nowMs;
            setTranscribing(true);  // ★ADD: partial到達時にtranscribing開始（全フレームではなく）
            if (!bestPartial.equals(clamped)) {
                Config.logDebug("★Partial merged: new=[" + clamped + "] keep=[" + bestPartial + "]");
            }
            Config.logDebug("★Partial: " + bestPartial);
            final String forUi = bestPartial;
            // ★ADD: HistoryFrameにpartialをリアルタイム表示
            updateTalkPartialPreview(bestPartial);
            SwingUtilities.invokeLater(() -> {
                if (window != null) {
                    window.setTitle("[TRANS]:" + forUi);
                    window.setIconImage(imageTranscribing);
                }
            });
        });

        // ★v1.5.0: onCompleted → handleFinalText
        moonshine.setOnCompleted(text -> {
            if (text == null || text.isBlank()) return;

            long nowMs = System.currentTimeMillis();
            moonshineNoOutputCount = 0;   // 出力ありでリセット
            moonshineLastOutputMs = nowMs;
            lastMoonshineOutputUtteranceSeq = currentUtteranceSeq;

            String trimmed = text.trim();
            trimmed = removeCjkSpaces(trimmed);
            if (LocalWhisperCPP.isIgnoredStatic(trimmed)) {
                Config.logDebug("★Ignored text: " + trimmed);
                return;
            }
            setTranscribing(false);
            // ★ADD: COMPLETE確定時にpartialプレビューをクリア
            updateTalkPartialPreview("");

            if (isSpeaking) {
                // ★発話中の COMPLETE は「候補保存だけ」にする
                //   rescue の stale clock と trans 状態は partial 側に任せる
                String best = cacheMoonshineCompleteCandidate(currentUtteranceSeq, trimmed);

                // ★重要:
                // lastPartial は更新してよいが、lastPartialUpdateMs は触らない
                // setTranscribing(true) もしない
                String cur = MobMateWhisp.getLastPartial();
                if (cur == null || !best.equals(cur.trim())) {
                    MobMateWhisp.setLastPartial(best);
                    updateTalkPartialPreview(best);
                }

                if (containsLaughTokenByConfig(best)) {
                    laughPartialCount = 0;
                    lastLaughPartialMs = 0;
                }

                Config.logDebug("★Moon COMPLETE cached only (speaking): " + best);
                return;
            }

            // isSpeaking=false（VADがFinal処理済み or 無音確定後）
            setTranscribing(false);
            updateTalkPartialPreview("");

            long uttSeq = currentUtteranceSeq;
            if (shouldSuppressMoonshineComplete(trimmed, uttSeq)) {
                MobMateWhisp.setLastPartial("");
                clearMoonshineCompleteCandidate(uttSeq);
                return;
            }

            String cached = consumeMoonshineCompleteCandidate(uttSeq);
            String candidate = combineCompleteSegments(cached, trimmed);
            String lastP = MobMateWhisp.getLastPartial();
            boolean partialExtendsComplete = lastP != null && !lastP.isBlank()
                    && lastP.startsWith(candidate)
                    && lastP.length() > candidate.length();

            if (partialExtendsComplete) {
                Config.logDebug("★Instant FINAL (partial extends complete): " + lastP);
                handleFinalTextWithUtteranceSeq(uttSeq, lastP, getActionFromPrefs(), true);
            } else {
                handleFinalTextWithUtteranceSeq(uttSeq, candidate, getActionFromPrefs(), false);
            }
            MobMateWhisp.setLastPartial("");
            clearMoonshineCompleteCandidate(uttSeq);
        });

        moonshine.start();
        moonshineLastOutputMs = System.currentTimeMillis();
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
    private String normForNearDup(String s) {
        if (s == null) return "";
        s = removeCjkSpaces(s);
        s = s.replaceAll("[\\p{Punct}、。！？「」（）\\[\\]{}]", "");
        s = s.replaceAll("\\s+", "");
        return s.trim().toLowerCase(Locale.ROOT);
    }
    private boolean areUtteranceVariants(String current, String candidate) {
        String curNorm = normForNearDup(current);
        String candNorm = normForNearDup(candidate);
        if (curNorm.isEmpty() || candNorm.isEmpty()) return false;
        if (curNorm.equals(candNorm)) return true;
        if (candNorm.startsWith(curNorm) || curNorm.startsWith(candNorm)) return true;
        if (candNorm.endsWith(curNorm) || curNorm.endsWith(candNorm)) return true;
        return similarityRatio(curNorm, candNorm) >= 0.88;
    }
    private boolean shouldInsertSegmentSpace(String left, String right) {
        if (left == null || right == null) return false;
        if (left.isEmpty() || right.isEmpty()) return false;
        char lc = left.charAt(left.length() - 1);
        char rc = right.charAt(0);
        Character.UnicodeScript ls = Character.UnicodeScript.of(lc);
        Character.UnicodeScript rs = Character.UnicodeScript.of(rc);
        boolean leftCjk = ls == Character.UnicodeScript.HAN
                || ls == Character.UnicodeScript.HIRAGANA
                || ls == Character.UnicodeScript.KATAKANA
                || ls == Character.UnicodeScript.HANGUL;
        boolean rightCjk = rs == Character.UnicodeScript.HAN
                || rs == Character.UnicodeScript.HIRAGANA
                || rs == Character.UnicodeScript.KATAKANA
                || rs == Character.UnicodeScript.HANGUL;
        return !leftCjk && !rightCjk;
    }
    private boolean endsWithSentencePunctuation(String text) {
        if (text == null || text.isEmpty()) return false;
        char c = text.charAt(text.length() - 1);
        return c == '。' || c == '！' || c == '!' || c == '？' || c == '?' || c == '.' || c == '…';
    }
    private boolean isShortTokenTrailingPunctuation(int cp) {
        return cp == '。' || cp == '！' || cp == '!' || cp == '？' || cp == '?'
                || cp == '.' || cp == '…' || cp == '、' || cp == '，' || cp == ',';
    }
    private String trimShortTokenTrailingPunctuation(String text) {
        String s = (text == null) ? "" : removeCjkSpaces(text).trim();
        while (!s.isEmpty()) {
            int cp = s.codePointBefore(s.length());
            if (!isShortTokenTrailingPunctuation(cp)) break;
            s = s.substring(0, s.offsetByCodePoints(s.length(), -1)).trim();
        }
        return s;
    }
    private boolean isKanaPhoneticCodePoint(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) {
            return true;
        }
        return cp == 'ー' || cp == '・' || cp == '･';
    }
    private boolean isShortPhoneticToken(String text) {
        String s = trimShortTokenTrailingPunctuation(text);
        if (s.isEmpty()) return false;
        int cpLen = s.codePointCount(0, s.length());
        if (cpLen < 2 || cpLen > 4) return false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (!isKanaPhoneticCodePoint(cp)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }
    private boolean isPhoneticOnlyText(String text) {
        String s = trimShortTokenTrailingPunctuation(text);
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (!isKanaPhoneticCodePoint(cp)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }
    private boolean shouldKeepCurrentShortPhoneticToken(String current, String candidate) {
        String cur = trimShortTokenTrailingPunctuation(current);
        String cand = trimShortTokenTrailingPunctuation(candidate);
        if (!isShortPhoneticToken(cur)) return false;
        if (cand.isEmpty() || isShortPhoneticToken(cand)) return false;
        if (isPhoneticOnlyText(cand)
                && cand.codePointCount(0, cand.length()) >= cur.codePointCount(0, cur.length()) + 2) {
            return false;
        }
        if (areUtteranceVariants(cur, cand)) return false;
        int candCp = cand.codePointCount(0, cand.length());
        return candCp <= 6;
    }
    private boolean containsClausePunctuation(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '、' || c == '，' || c == ',' || c == '；' || c == ';' || c == '：' || c == ':') {
                return true;
            }
        }
        return false;
    }
    private boolean looksSuspiciousShortComplete(String text) {
        if (text == null) return false;
        String s = removeCjkSpaces(text).trim();
        if (s.isEmpty()) return false;
        int cp = s.codePointCount(0, s.length());
        boolean hasDigit = false;
        boolean hasReplacement = s.contains("??") || s.indexOf('\uFFFD') >= 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                hasDigit = true;
                break;
            }
        }
        char first = s.charAt(0);
        boolean oddStart = Character.isDigit(first)
                || first == '「'
                || first == '」'
                || first == '?'
                || first == '？';
        return hasReplacement || ((hasDigit || oddStart) && cp <= 12);
    }
    private boolean shouldPreferLastPartialOverComplete(String partial, String complete) {
        String p = (partial == null) ? "" : removeCjkSpaces(partial).trim();
        String c = (complete == null) ? "" : removeCjkSpaces(complete).trim();
        if (p.isEmpty() || c.isEmpty()) return false;
        if (shouldKeepCurrentShortPhoneticToken(p, c)) return true;
        if (looksSuspiciousShortComplete(p)) return false;
        String pn = normForNearDup(p);
        String cn = normForNearDup(c);
        if (pn.isEmpty() || cn.isEmpty()) return false;
        int pCp = p.codePointCount(0, p.length());
        int cCp = c.codePointCount(0, c.length());
        if (pCp + 1 < cCp) return false;
        if ((pCp - cCp) > 4) return false;
        if (pn.equals(cn)) return true;
        if (pn.endsWith(cn) || cn.endsWith(pn)) return pCp >= cCp;
        double sim = similarityRatio(pn, cn);
        if (sim >= 0.88 && pCp >= cCp) return true;
        return sim >= 0.83 && pCp > cCp;
    }
    private boolean shouldAppendCompleteSegment(String current, String candidate) {
        String cur = (current == null) ? "" : removeCjkSpaces(current).trim();
        String cand = (candidate == null) ? "" : removeCjkSpaces(candidate).trim();
        if (cur.isEmpty() || cand.isEmpty()) return true;
        if (areUtteranceVariants(cur, cand)) return true;

        int curCp = cur.codePointCount(0, cur.length());
        int candCp = cand.codePointCount(0, cand.length());
        if (candCp <= 4 && !endsWithSentencePunctuation(cand)) return false;
        if (curCp >= 14) return false;
        if (containsClausePunctuation(cand) && candCp >= 6) return true;
        if (endsWithSentencePunctuation(cur) && endsWithSentencePunctuation(cand) && curCp <= 10 && candCp >= 5) return true;
        if (endsWithSentencePunctuation(cur) && candCp >= 6) return true;
        return curCp <= 8 && candCp >= 8;
    }
    private String appendUtteranceSegment(String current, String candidate) {
        String cur = (current == null) ? "" : removeCjkSpaces(current).trim();
        String cand = (candidate == null) ? "" : removeCjkSpaces(candidate).trim();
        if (cur.isEmpty()) return cand;
        if (cand.isEmpty()) return cur;
        if (areUtteranceVariants(cur, cand)) {
            return choosePreferredUtteranceText(cur, cand);
        }
        String sep = shouldInsertSegmentSpace(cur, cand) ? " " : "";
        return cur + sep + cand;
    }
    private String combineCompleteSegments(String current, String candidate) {
        String cur = (current == null) ? "" : removeCjkSpaces(current).trim();
        String cand = (candidate == null) ? "" : removeCjkSpaces(candidate).trim();
        if (cur.isEmpty()) return cand;
        if (cand.isEmpty()) return cur;
        if (looksSuspiciousShortComplete(cur) && !looksSuspiciousShortComplete(cand)) {
            return cand;
        }
        if (!looksSuspiciousShortComplete(cur) && looksSuspiciousShortComplete(cand)) {
            return cur;
        }
        if (areUtteranceVariants(cur, cand)) {
            return choosePreferredUtteranceText(cur, cand);
        }
        if (!shouldAppendCompleteSegment(cur, cand)) {
            // Distinct complete sentences in the same utterance sequence should stay together.
            // Replacing here made "直した。送った。" collapse to only the latter sentence too often.
            if (endsWithSentencePunctuation(cur)
                    && endsWithSentencePunctuation(cand)
                    && !areUtteranceVariants(cur, cand)) {
                return appendUtteranceSegment(cur, cand);
            }
            return cur;
        }
        return appendUtteranceSegment(cur, cand);
    }
    private String choosePreferredUtteranceText(String current, String candidate) {
        String cur = (current == null) ? "" : removeCjkSpaces(current).trim();
        String cand = (candidate == null) ? "" : removeCjkSpaces(candidate).trim();
        if (cur.isEmpty()) return cand;
        if (cand.isEmpty()) return cur;
        if (cur.equals(cand)) return cand;
        if (shouldKeepCurrentShortPhoneticToken(cur, cand)) {
            return cur;
        }

        String curNorm = normForNearDup(cur);
        String candNorm = normForNearDup(cand);
        if (curNorm.isEmpty()) return cand;
        if (candNorm.isEmpty()) return cur;
        if (curNorm.equals(candNorm)) {
            return (cand.length() >= cur.length()) ? cand : cur;
        }
        if (candNorm.startsWith(curNorm)) return cand;
        if (curNorm.startsWith(candNorm)) {
            return ((curNorm.length() - candNorm.length()) <= 2) ? cur : cand;
        }
        if (curNorm.endsWith(candNorm)) {
            return (candNorm.length() <= 4) ? cur : cand;
        }
        if (candNorm.endsWith(curNorm)) {
            return ((candNorm.length() - curNorm.length()) <= 2) ? cur : cand;
        }

        double sim = similarityRatio(curNorm, candNorm);
        if (sim >= 0.88 && Math.abs(candNorm.length() - curNorm.length()) <= 1) {
            if (endsWithSentencePunctuation(cur) && !endsWithSentencePunctuation(cand)) return cur;
            return cur;
        }
        if (sim >= 0.80) {
            if (candNorm.length() > curNorm.length() + 1) return cand;
            return cur;
        }
        return cand;
    }
    private String cacheMoonshineCompleteCandidate(long utteranceSeq, String text) {
        if (utteranceSeq <= 0 || text == null || text.isBlank()) return "";
        String candidate = removeCjkSpaces(text).trim();
        String lastP = MobMateWhisp.getLastPartial();
        if (shouldPreferLastPartialOverComplete(lastP, candidate)) {
            candidate = removeCjkSpaces(lastP).trim();
        }
        String best = combineCompleteSegments(pendingMoonshineCompleteText, candidate);
        pendingMoonshineCompleteUtteranceSeq = utteranceSeq;
        pendingMoonshineCompleteText = best;
        return best;
    }
    private String consumeMoonshineCompleteCandidate(long utteranceSeq) {
        if (utteranceSeq <= 0) return "";
        if (pendingMoonshineCompleteUtteranceSeq != utteranceSeq) return "";
        String text = pendingMoonshineCompleteText;
        pendingMoonshineCompleteUtteranceSeq = -1L;
        pendingMoonshineCompleteText = "";
        return (text == null) ? "" : text.trim();
    }
    private void clearMoonshineCompleteCandidate(long utteranceSeq) {
        if (utteranceSeq > 0 && pendingMoonshineCompleteUtteranceSeq != utteranceSeq) return;
        pendingMoonshineCompleteUtteranceSeq = -1L;
        pendingMoonshineCompleteText = "";
        clearMoonshineUtteranceAcoustics(utteranceSeq);
    }
    private boolean hasMoonshineOutputForUtterance(long utteranceSeq) {
        if (utteranceSeq <= 0) return false;
        if (lastMoonshineOutputUtteranceSeq == utteranceSeq) return true;
        return pendingMoonshineCompleteUtteranceSeq == utteranceSeq
                && pendingMoonshineCompleteText != null
                && !pendingMoonshineCompleteText.isBlank();
    }
    private LocalWhisperCPP getOrCreateMoonshineFallbackWhisper() {
        LocalWhisperCPP cur = moonshineFallbackWhisper;
        if (cur != null) return cur;
        synchronized (moonshineFallbackWhisperLock) {
            cur = moonshineFallbackWhisper;
            if (cur != null) return cur;
            if (model_dir == null || model_dir.isBlank() || model == null || model.isBlank()) {
                Config.logDebug("★Moon fallback Whisper unavailable: model path missing");
                return null;
            }
            try {
                cur = new LocalWhisperCPP(new File(model_dir, model), "");
                moonshineFallbackWhisper = cur;
                Config.logDebug("★Moon fallback Whisper loaded: " + new File(model_dir, model).getAbsolutePath());
            } catch (Throwable t) {
                Config.logError("★Moon fallback Whisper load failed", t);
                return null;
            }
            return cur;
        }
    }
    private String runWhisperFallbackForMoonshine(byte[] pcm16le, Action action, boolean commitFinal, long utteranceSeq) {
        if (pcm16le == null || pcm16le.length == 0) return "";
        LocalWhisperCPP fallback = getOrCreateMoonshineFallbackWhisper();
        if (fallback == null) return "";
        try {
            String raw = fallback.transcribeRaw(pcm16le, action, this, true);
            String out = (raw == null) ? "" : raw.trim();
            if (commitFinal && !out.isBlank()) {
                Config.logDebug("★Moon no-output fallback -> handleFinalText: " + out);
                handleFinalTextWithUtteranceSeq(utteranceSeq, out, action, true);
                MobMateWhisp.setLastPartial("");
            } else if (!out.isBlank()) {
                Config.logDebug("★Moon no-output fallback raw: " + out);
            }
            return out;
        } catch (Throwable t) {
            Config.logError("★Moon fallback Whisper transcribe failed", t);
            return "";
        }
    }
    private void maybeFallbackMoonshineAcceptedUtterance(byte[] pcm16le, Action action, long utteranceSeq) {
        if (!isEngineMoonshine() || utteranceSeq <= 0 || pcm16le == null || pcm16le.length == 0) return;
        if (!cliWavTestMode && !prefs.getBoolean("moonshine.no_output_whisper_fallback", false)) {
            return;
        }
        executorService.submit(() -> {
            try {
                Thread.sleep(MOONSHINE_NO_OUTPUT_FALLBACK_WAIT_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            if (hasMoonshineOutputForUtterance(utteranceSeq)) return;
            Config.logDebug("★Moon no-output fallback armed: utt=" + utteranceSeq + " bytes=" + pcm16le.length);
            runWhisperFallbackForMoonshine(pcm16le, action, true, utteranceSeq);
        });
    }
    private int levenshtein(String a, String b) {
        int n = a.length(), m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;

        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];

        for (int j = 0; j <= m; j++) prev[j] = j;

        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost
                );
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[m];
    }
    private double similarityRatio(String a, String b) {
        String x = normForNearDup(a);
        String y = normForNearDup(b);
        if (x.isEmpty() || y.isEmpty()) return 0.0;

        int maxLen = Math.max(x.length(), y.length());
        if (maxLen == 0) return 1.0;

        int dist = levenshtein(x, y);
        return 1.0 - (dist / (double) maxLen);
    }

    private Preferences getPrefs() {
        if (prefs == null) {
            prefs = Preferences.userRoot().node("MobMateWhispTalk");
            audioPrefilterMode = normalizeAudioPrefilterMode(prefs.get("audio.prefilter.mode", "normal"));
            hearingAudioPrefilterMode = loadHearingAudioPrefilterMode(prefs);
        }
        return prefs;
    }

    private static String loadHearingAudioPrefilterMode(Preferences p) {
        if (p == null) return null;
        String raw = p.get("hearing.audio.prefilter.mode", "");
        if (raw == null || raw.trim().isEmpty()) return null;
        return normalizeAudioPrefilterMode(raw);
    }

    private static String normalizeAudioPrefilterMode(String mode) {
        if (mode == null) return "normal";
        return switch (mode.trim().toLowerCase(Locale.ROOT)) {
            case "off", "disabled", "none" -> "off";
            case "strong", "aggressive" -> "strong";
            default -> "normal";
        };
    }

    public static String getAudioPrefilterMode() {
        return normalizeAudioPrefilterMode(audioPrefilterMode);
    }

    public static void setAudioPrefilterMode(String mode) {
        String normalized = normalizeAudioPrefilterMode(mode);
        audioPrefilterMode = normalized;
        if (prefs != null) {
            prefs.put("audio.prefilter.mode", normalized);
        }
    }
    public static String getHearingAudioPrefilterMode() {
        String mode = hearingAudioPrefilterMode;
        if (mode == null || mode.trim().isEmpty()) {
            return getAudioPrefilterMode();
        }
        return normalizeAudioPrefilterMode(mode);
    }

    public static void setHearingAudioPrefilterMode(String mode) {
        String normalized = normalizeAudioPrefilterMode(mode);
        hearingAudioPrefilterMode = normalized;
        if (prefs != null) {
            prefs.put("hearing.audio.prefilter.mode", normalized);
        }
    }
    private static boolean isStrongAudioPrefilterMode(String mode) {
        return "strong".equalsIgnoreCase(normalizeAudioPrefilterMode(mode));
    }
    private static final double STRONG_FINAL_COLLAPSE_RMS_RATIO = 0.33;
    private static final double STRONG_FINAL_COLLAPSE_PEAK_RATIO = 0.42;
    private static final double STRONG_FINAL_COLLAPSE_HARD_RMS_RATIO = 0.24;
    private static final double STRONG_FINAL_COLLAPSE_MIN_RAW_RMS = 260.0;
    private static final int STRONG_FINAL_COLLAPSE_MIN_RAW_PEAK = 2200;

    private static boolean shouldFallbackStrongFinal(byte[] rawChunk, byte[] filteredChunk, int sampleRateHz) {
        if (rawChunk == null || filteredChunk == null || rawChunk.length < 4 || filteredChunk.length < 4) {
            return false;
        }
        AudioPrefilter.VoiceMetrics rawMetrics =
                AudioPrefilter.analyzeVoiceLike(rawChunk, rawChunk.length, sampleRateHz);
        if (rawMetrics.rms < STRONG_FINAL_COLLAPSE_MIN_RAW_RMS
                || rawMetrics.peak < STRONG_FINAL_COLLAPSE_MIN_RAW_PEAK) {
            return false;
        }
        AudioPrefilter.VoiceMetrics filteredMetrics =
                AudioPrefilter.analyzeVoiceLike(filteredChunk, filteredChunk.length, sampleRateHz);
        double rmsRatio = filteredMetrics.rms / Math.max(1.0, rawMetrics.rms);
        double peakRatio = (double) filteredMetrics.peak / Math.max(1.0, rawMetrics.peak);
        return rmsRatio < STRONG_FINAL_COLLAPSE_HARD_RMS_RATIO
                || (rmsRatio < STRONG_FINAL_COLLAPSE_RMS_RATIO
                && peakRatio < STRONG_FINAL_COLLAPSE_PEAK_RATIO);
    }

    public boolean isEngineMoonshine() {
        return "moonshine".equalsIgnoreCase(prefs.get("recog.engine", "whisper"));
    }
    public boolean isHearingEngineMoonshine() {
        return "moonshine".equalsIgnoreCase(prefs.get("hearing.engine", "whisper"));
    }
    public String getHearingEngine() {
        return isHearingEngineMoonshine() ? "moonshine" : "whisper";
    }
    public String getHearingSourceLangForUi() {
        return getHearingSourceLang();
    }
    public String getHearingAudioPrefilterModeForUi() {
        return getHearingAudioPrefilterMode();
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

    private static final AtomicBoolean demoAnnounceStarted = new AtomicBoolean(false);
    private static boolean hasMoonshineModelFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] files = dir.listFiles((d, name) ->
                name.endsWith(".onnx") || name.endsWith(".bin"));
        return files != null && files.length > 0;
    }
    public File getMoonBaseDir() {
        return new File(getExeDir(), "moonshine_model");
    }
    public LinkedHashMap<String, File> scanMoonshineModelMap() {
        LinkedHashMap<String, File> out = new LinkedHashMap<>();

        File moonBaseDir = getMoonBaseDir();
        File[] langDirs = moonBaseDir.isDirectory()
                ? moonBaseDir.listFiles(File::isDirectory)
                : null;

        if (langDirs == null || langDirs.length == 0) {
            return out;
        }

        Arrays.sort(langDirs, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        for (File langDir : langDirs) {
            File modelDir = findDeepestModelDir(langDir);
            if (hasMoonshineModelFiles(modelDir)) {
                out.put(langDir.getName(), modelDir);
            }
        }
        return out;
    }
    public String findMoonshineModelKey(String savedPath, Map<String, File> modelMap) {
        if (savedPath == null || savedPath.isBlank() || modelMap == null || modelMap.isEmpty()) {
            return null;
        }

        String normSaved = new File(savedPath).getAbsolutePath();
        for (Map.Entry<String, File> e : modelMap.entrySet()) {
            String modelPath = e.getValue().getAbsolutePath();
            if (normSaved.equals(modelPath) || normSaved.startsWith(modelPath + File.separator)) {
                return e.getKey();
            }
        }
        return null;
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
            final PopupMenu popup = createTrayUtilityPopup();
            this.trayIcon.setPopupMenu(popup);
            Config.log("[TRAY] add mouse listener...");
            this.trayIcon.addMouseListener(new MouseAdapter() {
                private void refreshTrayPopup(MouseEvent e) {
                    if (!e.isPopupTrigger()) return;
                    try {
                        trayIcon.setPopupMenu(createTrayUtilityPopup());
                    } catch (Throwable t) {
                        Config.logError("[TRAY] popup refresh failed", t);
                    }
                }
                @Override
                public void mousePressed(MouseEvent e) {
                    refreshTrayPopup(e);
                }
                @Override
                public void mouseReleased(MouseEvent e) {
                    refreshTrayPopup(e);
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

    protected JPopupMenu createDeltaQuickMenu() {
        final JPopupMenu popup = new JPopupMenu();

        JMenu presetMenu = new JMenu(presetPlaceholderText());
        refreshPresetQuickMenu(presetMenu);
        presetMenu.addMenuListener(new javax.swing.event.MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
                refreshPresetQuickMenu(presetMenu);
            }

            @Override
            public void menuDeselected(javax.swing.event.MenuEvent e) {}

            @Override
            public void menuCanceled(javax.swing.event.MenuEvent e) {}
        });
        popup.add(presetMenu);

        popup.addSeparator();

        JMenu debugMenu = new JMenu(uiOr("ui.main.delta.debug", "Debug"));
        boolean readyToZip = Config.getBool("log.debug", false);

        if (!readyToZip) {
            JMenuItem enableDebugItem = new JMenuItem(UiText.t("menu.debug.enableLog"));
            enableDebugItem.addActionListener(e -> {
                try {
                    Config.appendOutTts("log.debug=true");
                    Config.appendOutTts("log.vad.detailed=true");
                    JOptionPane.showMessageDialog(
                            window,
                            uiOr("ui.main.delta.debug.enable.msg",
                                    "Debug logging enabled temporarily.\n"
                                            + "Restart the app and reproduce the issue.\n"
                                            + "(Automatically turns off after ~500 messages.)"),
                            "MobMate",
                            JOptionPane.INFORMATION_MESSAGE
                    );
                    restartSelf(true);
                } catch (Exception ex) {
                    Config.log("Failed to Debug Log Output ON.");
                }
            });
            debugMenu.add(enableDebugItem);
        } else {
            JMenuItem zipLogsItem = new JMenuItem(UiText.t("menu.debug.createZip"));
            zipLogsItem.addActionListener(e -> {
                try {
                    File zip = DebugLogZipper.createZip();
                    JOptionPane.showMessageDialog(
                            window,
                            uiOr("ui.main.delta.debug.zip.msg",
                                    "Debug logs have been collected.\n\n"
                                            + "Please attach this zip file and send it to support.\n\n")
                                    + zip.getAbsolutePath(),
                            "MobMate",
                            JOptionPane.INFORMATION_MESSAGE
                    );
                } catch (Exception ex) {
                    Config.log("Failed to Debug Log Output Zipping.");
                }
            });
            debugMenu.add(zipLogsItem);
        }
        popup.add(debugMenu);

        popup.addSeparator();

        JMenuItem softRestartItem = new JMenuItem(uiOr("ui.main.delta.softRestart", "Soft Restart"));
        softRestartItem.addActionListener(e -> softRestart());
        popup.add(softRestartItem);

        popup.addSeparator();

        JMenuItem exitItem = new JMenuItem(UiText.t("menu.exit"));
        exitItem.addActionListener(e -> {
            try { VoicegerManager.stopIfRunning(MobMateWhisp.prefs); } catch (Throwable ignore) {}
            Config.mirrorAllToCloud();
            System.exit(0);
        });
        popup.add(exitItem);

        return popup;
    }
    protected PopupMenu createTrayUtilityPopup() {
        Config.log("[TRAY] utility popup: begin");

        final PopupMenu popup = new PopupMenu();

        MenuItem openWindowItem = new MenuItem("Open Window");
        openWindowItem.addActionListener(e ->
                SwingUtilities.invokeLater(() -> bringToFront(window)));
        popup.add(openWindowItem);

        MenuItem historyItem = new MenuItem("History");
        historyItem.addActionListener(e ->
                SwingUtilities.invokeLater(this::showHistory));
        popup.add(historyItem);

        MenuItem settingsItem = new MenuItem("Settings");
        settingsItem.addActionListener(e ->
                SwingUtilities.invokeLater(this::openSettingsCenter));
        popup.add(settingsItem);

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

        boolean readyToZip = Config.getBool("log.debug", false);
        Menu debugMenu = new Menu("MobMateWhispTalk v" + Version.APP_VERSION);

        if (!readyToZip) {
            MenuItem enableDebugItem = new MenuItem(trayText("menu.debug.enableLog"));
            enableDebugItem.addActionListener(e -> {
                try {
                    Config.appendOutTts("log.debug=true");
                    Config.appendOutTts("log.vad.detailed=true");
                    JOptionPane.showMessageDialog(
                            null,
                            "Debug logging enabled temporarily.\n"
                                    + "Restart the app and reproduce the issue.\n"
                                    + "(Automatically turns off after ~500 messages.)",
                            "MobMate",
                            JOptionPane.INFORMATION_MESSAGE
                    );
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
                    JOptionPane.showMessageDialog(
                            null,
                            "Debug logs have been collected.\n\n"
                                    + "Please attach this zip file and send it to support.\n\n"
                                    + zip.getAbsolutePath(),
                            "MobMate",
                            JOptionPane.INFORMATION_MESSAGE
                    );
                } catch (Exception ex) {
                    Config.log("Failed to Debug Log Output Zipping..");
                }
            });
            debugMenu.add(zipLogsItem);
        }
        popup.add(debugMenu);

        popup.addSeparator();

        MenuItem softRestartItem = new MenuItem("Soft Restart");
        softRestartItem.addActionListener(e -> softRestart());
        popup.add(softRestartItem);

        popup.addSeparator();

        MenuItem exitItem = new MenuItem(trayText("menu.exit"));
        exitItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try { VoicegerManager.stopIfRunning(MobMateWhisp.prefs); } catch (Throwable ignore) {}
                Config.mirrorAllToCloud();
                System.exit(0);
            }
        });
        popup.add(exitItem);

        Font awtFont = new Font(
                UIManager.getFont("Menu.font").getFamily(),
                Font.PLAIN,
                prefs.getInt("ui.font.size", DEFAULT_UI_FONT_SIZE)
        );
        applyAwtFontRecursive(popup, awtFont);

        startDemoAnnounceTimer();
        Config.log("[TRAY] utility popup: end");
        return popup;
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
    private boolean shouldShowConfirmRadioPage() {
        return ttsConfirmMode && hasPendingConfirm();
    }
    private java.util.List<Integer> getActiveRadioPages() {
        ArrayList<Integer> pages = new ArrayList<>();
        if (shouldShowConfirmRadioPage()) {
            pages.add(RADIO_CONFIRM_PAGE);
        }
        for (int i = 0; i < RADIO_PAGE_MAX; i++) {
            pages.add(i);
        }
        return pages;
    }
    private int getInitialRadioPage() {
        return shouldShowConfirmRadioPage() ? RADIO_CONFIRM_PAGE : 0;
    }
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
                    speakTalkTextForUi(s);
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
        if (radioPage == RADIO_CONFIRM_PAGE && shouldShowConfirmRadioPage()) {
            if (digit >= 1 && digit <= CONFIRM_QUEUE_MAX) {
                approvePendingConfirmAtSlot(digit);
            } else if (digit == 5) {
                cancelPendingConfirm();
            }
            return;
        }
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

        if (page == RADIO_CONFIRM_PAGE && shouldShowConfirmRadioPage()) {
            java.util.List<String> previews = getPendingConfirmPreviewList(CONFIRM_QUEUE_MAX);
            int remainSec = getPendingConfirmRemainingSeconds();
            lines.add(uiOr("ui.radio.confirm.title", "Pending TTS") + " (" + getPendingConfirmCount() + ")");
            lines.add(uiOr("ui.radio.confirm.countdown", "Auto speak in") + " " + Math.max(0, remainSec) + "s");
            lines.add("--------------------------------");
            int slot = 1;
            for (String preview : previews) {
                if (preview == null || preview.isBlank()) continue;
                lines.add(slot + ": " + preview);
                slot++;
                if (slot > CONFIRM_QUEUE_MAX) break;
            }
            lines.add("5: " + uiOr("ui.radio.confirm.cancel", "Cancel"));
            lines.add("--------------------------------");
            StringBuilder confirmSb = new StringBuilder();
            for (String ln : lines) confirmSb.append(ln).append("\n");
            return confirmSb.toString();
        }

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
            radioPage = getInitialRadioPage();

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
        java.util.List<Integer> pages = getActiveRadioPages();
        int idx = pages.indexOf(radioPage);
        if (idx < 0) idx = 0;
        radioPage = pages.get((idx + 1) % pages.size());
        Config.log("Radio page -> " + radioPage + " / " + pages);
        updateRadioOverlay();
    }


    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        // ===== [ADD] Radio hold begin / digits while held =====
        if (isRadioHoldKeyEvent(e)) {
            reloadRadioCmdCacheIfUpdated(); // ★ここで更新分だけ読む
            radioHeld = true;
            radioConsumed = false;
            radioPage = getInitialRadioPage();
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
        return prefs.getInt("ui.font.size", DEFAULT_UI_FONT_SIZE);
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

    public List<String> getVirtualAudioOutputCandidates() {
        List<String> names = new ArrayList<>();
        for (String name : getOutputMixerNames()) {
            if (looksLikeVirtualAudioDeviceName(name)) {
                names.add(name);
            }
        }
        return names;
    }

    public String getPreferredVirtualAudioOutputCandidate() {
        List<String> candidates = getVirtualAudioOutputCandidates();
        if (candidates.isEmpty()) {
            return "";
        }

        List<String> preferredHints = Arrays.asList(
                "cable input",
                "vb-audio",
                "virtual audio cable",
                "sonar chat",
                "sonar stream",
                "sonar media",
                "sonar"
        );

        for (String hint : preferredHints) {
            for (String candidate : candidates) {
                if (candidate.toLowerCase(Locale.ROOT).contains(hint)) {
                    return candidate;
                }
            }
        }

        return candidates.get(0);
    }

    public List<String> getVirtualAudioInputCandidates() {
        List<String> names = new ArrayList<>();
        for (String name : getInputsMixerNames()) {
            if (looksLikeVirtualAudioDeviceName(name)) {
                names.add(name);
            }
        }
        return names;
    }

    public String getPreferredVirtualAudioInputCandidate(String outputDeviceName) {
        List<String> candidates = getVirtualAudioInputCandidates();
        if (candidates.isEmpty()) {
            return "";
        }

        String normalizedOutput = outputDeviceName == null ? "" : outputDeviceName.toLowerCase(Locale.ROOT);
        if (!normalizedOutput.isBlank()) {
            String expected = normalizedOutput.replace("input", "output");
            for (String candidate : candidates) {
                String lower = candidate.toLowerCase(Locale.ROOT);
                if (lower.equals(expected) || lower.contains(expected) || expected.contains(lower)) {
                    return candidate;
                }
            }
        }

        List<String> preferredHints = Arrays.asList(
                "cable output",
                "microphone",
                "vb-audio",
                "virtual audio cable",
                "sonar"
        );

        for (String hint : preferredHints) {
            for (String candidate : candidates) {
                if (candidate.toLowerCase(Locale.ROOT).contains(hint)) {
                    return candidate;
                }
            }
        }

        return candidates.get(0);
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
        setStartupStatus(true, "INIT");
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
                        setStartupStatus(false, "READY");
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
                    setStartupStatus(false, "READY");
                });
            } catch (Exception e) {
                Config.log("Calibration failed: " + e.getMessage());
                SwingUtilities.invokeLater(() -> {
                    window.setTitle("[CALIBRATING] failed - " + e.getMessage());
                    button.setEnabled(true);
                    button.setText(UiText.t("ui.main.start"));
                    isCalibrationComplete = true;
                    setStartupStatus(false, "READY");
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
    private volatile long pendingMoonshineCompleteUtteranceSeq = -1L;
    private volatile String pendingMoonshineCompleteText = "";
    volatile boolean isSpeaking = false;
    private volatile boolean cliWavTestMode = false;
    long speechStartTime = 0;
    long lastSpeechEndTime = 0;
    // ===== Latency meter (VAD->P1 / VAD->Final / Final->TTS start) =====
    private final AtomicLong latFirstPartialAtMs = new AtomicLong(0);
    private final AtomicLong latFinalAtMs = new AtomicLong(0);
    private final AtomicBoolean latFinalToTtsDone = new AtomicBoolean(false);
    private final AtomicInteger lastLatVadToP1Ms = new AtomicInteger(-1);
    private final AtomicInteger lastLatVadToFinalMs = new AtomicInteger(-1);
    private final AtomicInteger lastLatFinalToTtsMs = new AtomicInteger(-1);
    private final AtomicInteger lastLatPostProcessMs = new AtomicInteger(-1);
    // Disabled for now because mt5-small currently returns span-masking tokens such as <extra_id_0>
    // too often for this realtime talk path.
    private static final boolean TALK_POST_PROCESS_ENABLED = false;

    // speak() が「今回のFinalの読み上げ」かを判定するための印っす
    private final AtomicReference<String> pendingLatencyTtsText = new AtomicReference<>(null);
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

        javax.swing.Timer watchdog = new javax.swing.Timer(800, ev -> {
            if (!isStartingRecording.get() || isRecording()) {
                ((javax.swing.Timer) ev.getSource()).stop();
                return;
            }
            if (ignorePreloadPending || LocalWhisperCPP.isIgnoreExpansionBusy()) {
                return;
            }
            Config.logDebug("[Start] waiting for actual recording start...");
        });
        watchdog.setInitialDelay(2500);
        watchdog.setRepeats(true);
        watchdog.start();

        final String audioDevice = prefs.get("audio.device", "");
        final String previsouAudipDevice = prefs.get("audio.device.previous", "");

        try {
            this.audioService.execute(() -> {
                talkRealtimePrefilterState.reset();
                talkVadPrefilterState.reset();
                preloadIgnoreForTalkStartIfNeeded();
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
                                speakerProfile.updateSettings(
                                        prefs.getInt("speaker.enroll_samples", 5),
                                        prefs.getFloat("speaker.threshold_initial", 0.60f),
                                        prefs.getFloat("speaker.threshold_target", prefs.getFloat("speaker.threshold_initial", 0.60f))
                                );
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
                            ByteArrayOutputStream rawSpeechBuffer = new ByteArrayOutputStream();
                            ByteArrayOutputStream rawPreRoll = new ByteArrayOutputStream();
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
                            // ★Moonshine: 文中でCOMPLETE発火するため、息継ぎによる誤断を防ぐ
                            if (isEngineMoonshine()) {
                                SILENCE_FRAMES_FOR_FINAL = Math.max(6, SILENCE_FRAMES_FOR_FINAL);
                            }
                            Config.logDebug("★調整後 SILENCE_FRAMES_FOR_FINAL: " + SILENCE_FRAMES_FOR_FINAL);

                            boolean firstPartialSent = false;
                            // 16kHz / 16bit / mono = 1秒あたり 32000 bytes
                            final int PREROLL_BYTES = (int) (audioFormat.getSampleRate() * audioFormat.getFrameSize() * 1.5);
                            final int MIN_AUDIO_DATA_LENGTH = 16000;
                            long lastFinalMs = 0;
                            final long FINAL_COOLDOWN_MS = 200;
                            final long FORCE_FINAL_MS = isEngineMoonshine() ? 5000L : 2500L;
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
                                String audioPrefilterModeNow = getAudioPrefilterMode();
                                String realtimeAudioPrefilterMode = isStrongAudioPrefilterMode(audioPrefilterModeNow)
                                        ? "normal"
                                        : audioPrefilterModeNow;
                                byte[] pcmForAsr;
                                try {
                                    pcmForAsr = AudioPrefilter.processForAsr(
                                            pcm, n, (int) audioFormat.getSampleRate(),
                                            AudioPrefilter.Mode.TALK, realtimeAudioPrefilterMode, talkRealtimePrefilterState);
                                } catch (Throwable t) {
                                    Config.logError("[AudioPrefilter][Talk] realtime profile failed -> fallback raw", t);
                                    talkRealtimePrefilterState.reset();
                                    pcmForAsr = new byte[n];
                                    System.arraycopy(pcm, 0, pcmForAsr, 0, n);
                                }
                                byte[] pcmForVad = pcmForAsr;
                                if (isStrongAudioPrefilterMode(audioPrefilterModeNow)) {
                                    try {
                                        pcmForVad = AudioPrefilter.processForAsr(
                                                pcm, n, (int) audioFormat.getSampleRate(),
                                                AudioPrefilter.Mode.TALK, AudioPrefilter.Profile.NORMAL, talkVadPrefilterState);
                                    } catch (Throwable t) {
                                        Config.logError("[AudioPrefilter][Talk] strong VAD path failed -> fallback selected profile", t);
                                        talkVadPrefilterState.reset();
                                        pcmForVad = pcmForAsr;
                                    }
                                } else {
                                    talkVadPrefilterState.reset();
                                }
                                preRoll.write(pcmForAsr, 0, n);
                                if (preRoll.size() > PREROLL_BYTES) {
                                    byte[] pr = preRoll.toByteArray();
                                    preRoll.reset();
                                    preRoll.write(pr, pr.length - PREROLL_BYTES, PREROLL_BYTES);
                                }
                                rawPreRoll.write(pcm, 0, n);
                                if (rawPreRoll.size() > PREROLL_BYTES) {
                                    byte[] pr = rawPreRoll.toByteArray();
                                    rawPreRoll.reset();
                                    rawPreRoll.write(pr, pr.length - PREROLL_BYTES, PREROLL_BYTES);
                                }
                                // ★v1.5.0: Moonshineモードのときだけ音声を流す
//                                if (isEngineMoonshine() && moonshine != null && moonshine.isLoaded()) {
//                                    final float[] fpcm = pcm16leToFloat(pcm, n);
//                                    moonshine.addAudio(fpcm, 16000);
//                                    // ★REMOVE: setTranscribing(true)はonPartialで行う
//                                }

                                int peak = vad.getPeak(pcmForVad, n);
                                int avg  = vad.getAvg(pcmForVad, n);
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
                                        ? ((ImprovedVAD) vad).isSpeech(pcmForVad, n, buffer.size())
                                        : vad.isSpeech(pcmForVad, n);

                                // ★ADD: TTS再生中はVADをマスク（ループバック防止）
                                if (isTtsSpeaking && speech && System.currentTimeMillis() < ttsVadSuppressUntilMs) {
                                    int suppressPeakFloor = 520;
                                    int suppressAvgFloor = 120;
                                    if (vad instanceof ImprovedVAD) {
                                        AdaptiveNoiseProfile profile = ((ImprovedVAD) vad).getNoiseProfile();
                                        suppressPeakFloor = Math.max(suppressPeakFloor, Math.max(260, (int) (profile.getPeakThreshold() * 0.42)));
                                        suppressAvgFloor = Math.max(suppressAvgFloor, Math.max(110, (int) (profile.getAvgThreshold() * 0.38)));
                                    }
                                    boolean likelyUserBargeIn = isSpeaking
                                            || buffer.size() >= 3200
                                            || peak >= suppressPeakFloor
                                            || avg >= suppressAvgFloor;
                                    if (likelyUserBargeIn) {
                                        Config.logDebug("★VAD suppress bypass (possible user barge-in): peak=" + peak
                                                + " avg=" + avg
                                                + " buf=" + buffer.size());
                                    } else {
                                        Config.logDebug("★VAD suppressed (TTS playing)");
                                        speech = false;
                                    }
                                }
                                // ===== 話者照合はfinalChunkレベルで実施（フレーム単位は精度不足）=====

                                // ===== [ADD] strong signal but speech=false guard =====
                                if (vad instanceof ImprovedVAD) {
                                    AdaptiveNoiseProfile np = ((ImprovedVAD) vad).getNoiseProfile();
                                    int peakTh = np.getPeakThreshold();
                                    int avgTh  = np.getAvgThreshold();

                                    // 笑い声みたいな短いburstを拾う
                                    int strongPeakMin = np.isLowGainMic ? 260 : 1200;
                                    boolean strongInput =
                                            peak >= Math.max((int)(peakTh * 0.90), strongPeakMin) &&
                                                    avg  >= Math.max(120, (int)(avgTh * 0.35));

                                    boolean reactionInput =
                                            !np.isLowGainMic &&
                                                    peak >= Math.max(900, (int)(peakTh * 0.65)) &&
                                                    avg  >= Math.max(160, (int)(avgTh * 0.55));

                                    if (!speech && strongInput) {
                                        strongNoSpeechFrames++;
                                    } else {
                                        strongNoSpeechFrames = 0;
                                    }

                                    if (strongNoSpeechFrames == 1) {
                                        Config.logDebug(String.format(
                                                "★VAD laugh-burst? peak=%d avg=%d peakTh=%d avgTh=%d lowGain=%s bgm=%s",
                                                peak, avg, np.getPeakThreshold(), np.getAvgThreshold(),
                                                np.isLowGainMic, ((ImprovedVAD) vad).looksLikeBGM(peak, avg)
                                        ));
                                    }

                                    if (!speech
                                            && !isSpeaking
                                            && strongInput
                                            && isEngineMoonshine()
                                            && moonshine != null
                                            && moonshine.isLoaded()
                                            && preSpeechMoonFeedFrames < PRE_SPEECH_MOON_FEED_MAX) {

                                        final float[] fpcm = pcm16leToFloat(pcmForVad, n);
                                        moonshine.addAudio(fpcm, 16000);
                                        preSpeechMoonFeedFrames++;

                                        Config.logDebug("★Moon prefeed: frame=" + preSpeechMoonFeedFrames
                                                + " peak=" + peak + " avg=" + avg);
                                    }

                                    if (strongNoSpeechFrames >= 2) {
                                        speech = true;
                                        Config.logDebug("★VAD override: strongNoSpeechFrames>=2 -> speech=true");
                                        strongNoSpeechFrames = 0;
                                    } else if (!speech && !isSpeaking && reactionInput) {
                                        speech = true;
                                        Config.logDebug("★VAD reaction rescue: peak=" + peak + " avg=" + avg
                                                + " peakTh=" + peakTh + " avgTh=" + avgTh);
                                    }

                                    if (speech || !strongInput) {
                                        preSpeechMoonFeedFrames = 0;
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
                                // ★強制Final直後のfinal-only COMPLETE回収待ち
                                if (moonResetDeferredUntilMs != 0L) {
                                    long moonWaitMs = moonResetDeferredUntilMs - System.currentTimeMillis();
                                    if (speech && !isSpeaking && moonWaitMs > 0L) {
                                        try {
                                            Thread.sleep(Math.min(moonWaitMs, 25L));
                                        } catch (InterruptedException ie) {
                                            Thread.currentThread().interrupt();
                                        }
                                        continue;
                                    }
                                    if (moonWaitMs <= 0L && (!speech || !isSpeaking)) {
                                        tryDrainAndResetMoonshine();
                                    }
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
                                        resetLatencyForNewUtterance(now);
                                        latVadStartMs.set(now);
                                        latFirstPartMs.set(0);
                                        latFinalMs.set(0);
                                        latTtsStartMs.set(0);
                                        laughAlreadyHandled = false;
                                        pendingLaughAppend = false;
                                        pendingLaughText = null;
                                        laughDet.reset();
                                        buffer.reset();
                                        rawSpeechBuffer.reset();
                                        buffer.write(preRoll.toByteArray());
                                        rawSpeechBuffer.write(rawPreRoll.toByteArray());
                                        if (vad instanceof ImprovedVAD) {
                                            ((ImprovedVAD) vad).reset();
                                        }
                                        silenceFrames = 0;
                                        firstPartialSent = false;
                                        forceFinalizePending = false;
                                        currentUtteranceSeq = ++utteranceSeqGen;
                                        resetActiveTalkProsodyTail(currentUtteranceSeq);
                                        MobMateWhisp.setLastPartial("");
                                        clearMoonshineCompleteCandidate(-1L);
                                        lastPartialUpdateMs = 0L; // partial更新時刻もリセット
                                        preSpeechMoonFeedFrames = 0;
                                        Config.logDebug("★発話開始");
                                        // ★FIX: preRollをMoonshineに送る（発声冒頭の欠落防止）
                                        // streamはFinal時にresetStream済みなのでここではresetは不要
                                        if (isEngineMoonshine() && moonshine != null && moonshine.isLoaded()) {
                                            byte[] pr = preRoll.toByteArray();
                                            if (pr.length > 0) {
                                                final float[] fpr = pcm16leToFloat(pr, pr.length);
                                                moonshine.addAudio(fpr, 16000);
                                            }
                                        }
                                        SwingUtilities.invokeLater(() -> window.setTitle(UiText.t("ui.title.rec")));
                                    }
                                    // ★クリッピング検知: buffer書き込み前にチェック
//                                    if (isEngineMoonshine() && peak >= 32700
//                                            && moonshine != null && moonshine.isLoaded()) {
//                                        moonshine.resetStreamForClipping();
//                                        Config.logDebug("★Clipping: skip frame & stream reset (peak=" + peak + ")");
//                                        continue; // bufferに書かずスキップ
//                                    }
                                    buffer.write(pcmForAsr, 0, n);
                                    rawSpeechBuffer.write(pcm, 0, n);
                                    appendActiveTalkProsodyTail(currentUtteranceSeq, pcm, n, now);
                                    partialBuf.write(pcmForAsr, 0, n);
                                    lastSpeechEndTime = now;

                                    // Moonshineへの音声投入は発話中のみ（無音期間の汚染防止）
                                    if (isEngineMoonshine() && moonshine != null && moonshine.isLoaded()) {
                                        final float[] fpcm = pcm16leToFloat(pcmForAsr, n);
                                        moonshine.addAudio(fpcm, 16000);
                                    }

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
                                    // ★FIX: Moonshineにも無音フレームを送り続けてpollThreadを維持
                                    // （speech→falseで addAudio が止まると hasNewAudio=false → poll停止
                                    //   → 短い発話のCOMPLETEが拾えずにresetStreamで消滅する問題の防止）
                                    if (isEngineMoonshine() && moonshine != null && moonshine.isLoaded()) {
                                        final float[] fpcm = pcm16leToFloat(pcmForAsr, n);
                                        moonshine.addAudio(fpcm, 16000);
                                    }
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

                                        handleFinalTextWithUtteranceSeq(currentUtteranceSeq, p, action, true);
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

                                    // ★FIX: バッファ不足でpartialも無い場合、pollThread待ちのために最低200ms猶予を与える
                                    // （長い無音後の最初の発話でFinalが早期爆発してrescueできない問題の防止）
                                    if (!meetsMinBytes && !hasPartialText) {
                                        long speechDurationMs = nowMs - speechStartTime;
                                        if (speechDurationMs < 200) {
                                            continue; // pollThreadが最初のpartialを返すまで待つ
                                        }
                                    }

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
                                        final byte[] finalRawChunk = rawSpeechBuffer.toByteArray();

                                        // ★FIX: silence-trimmed chunk for speaker gate + transcribe (helps short laugh after long silence)
                                        byte[] procChunk;
                                        try {
                                            // absThr: 260〜420くらいが無難。まず300。
                                            // padMs: 前後少し残す（発声の立ち上がり欠け防止）まず120ms。
                                            procChunk = trimPcmSilence16le(finalRawChunk, audioFormat, 300, 120);
                                        } catch (Exception ignore) {
                                            // 万一でも落とさない
                                            //noinspection AssignmentToMethodParameter
                                            procChunk = finalRawChunk;
                                        }
                                        final byte[] trimmedRawChunk = procChunk;
                                        try {
                                            procChunk = AudioPrefilter.processForAsr(
                                                    procChunk,
                                                    procChunk.length,
                                                    (int) audioFormat.getSampleRate(),
                                                    AudioPrefilter.Mode.TALK,
                                                    audioPrefilterModeNow,
                                                    new AudioPrefilter.State()
                                            );
                                            if (isStrongAudioPrefilterMode(audioPrefilterModeNow)
                                                    && shouldFallbackStrongFinal(
                                                    trimmedRawChunk,
                                                    procChunk,
                                                    (int) audioFormat.getSampleRate())) {
                                                AudioPrefilter.VoiceMetrics rawMetrics =
                                                        AudioPrefilter.analyzeVoiceLike(
                                                                trimmedRawChunk,
                                                                trimmedRawChunk.length,
                                                                (int) audioFormat.getSampleRate());
                                                AudioPrefilter.VoiceMetrics filteredMetrics =
                                                        AudioPrefilter.analyzeVoiceLike(
                                                                procChunk,
                                                                procChunk.length,
                                                                (int) audioFormat.getSampleRate());
                                                Config.log("[AudioPrefilter][Talk] strong collapsed final -> fallback normal"
                                                        + " rawRms=" + String.format(java.util.Locale.ROOT, "%.1f", rawMetrics.rms)
                                                        + " filteredRms=" + String.format(java.util.Locale.ROOT, "%.1f", filteredMetrics.rms)
                                                        + " rawPeak=" + rawMetrics.peak
                                                        + " filteredPeak=" + filteredMetrics.peak);
                                                procChunk = AudioPrefilter.processForAsr(
                                                        trimmedRawChunk,
                                                        trimmedRawChunk.length,
                                                        (int) audioFormat.getSampleRate(),
                                                        AudioPrefilter.Mode.TALK,
                                                        AudioPrefilter.Profile.NORMAL,
                                                        new AudioPrefilter.State()
                                                );
                                            }
                                        } catch (Exception ex) {
                                            Config.logDebug("[AudioPrefilter][Talk] final profile failed -> keep trimmed raw: " + ex);
                                        }
                                        try {
                                            procChunk = AudioPrefilter.normalizeFinalChunkForAsr(procChunk, procChunk.length);
                                        } catch (Exception ignore) {
                                            // normalize は補助輪なので、失敗しても元chunkで続行するっす。
                                        }
                                        final byte[] finalProcChunk = procChunk;
                                        final byte[] finalSpeakerChunk = trimmedRawChunk;

                                        // ---- 状態リセット ----
                                        buffer.reset();
                                        rawSpeechBuffer.reset();
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
                                        // ★Moonshineは少しdrain待ちしてからresetする
                                        deferMoonshineResetAfterFinal();

                                        executorService.submit(() -> {
                                            try {
                                                if (useRescueText) {
                                                    Config.logDebug("★Final rescue from partial: " + rescueText);
                                                    handleFinalTextWithUtteranceSeq(currentUtteranceSeq, rescueText, action, true);
                                                } else {
                                                    final long uttSeq = currentUtteranceSeq;

                                                    // 話者ゲート（speaker.enabled時のみ）
                                                    boolean speakerOk = true;
                                                    if (prefs.getBoolean("speaker.enabled", false)
                                                            && speakerProfile.isReady()) {
                                                        speakerOk = speakerProfile.isMatchingSpeaker(finalSpeakerChunk);
                                                        if (!speakerOk) {
                                                            AudioPrefilter.VoiceMetrics rawSpeakerMetrics =
                                                                    AudioPrefilter.analyzeVoiceLike(
                                                                            finalSpeakerChunk,
                                                                            finalSpeakerChunk.length,
                                                                            (int) audioFormat.getSampleRate());
                                                            AudioPrefilter.VoiceMetrics procSpeakerMetrics =
                                                                    AudioPrefilter.analyzeVoiceLike(
                                                                            finalProcChunk,
                                                                            finalProcChunk.length,
                                                                            (int) audioFormat.getSampleRate());
                                                            Config.logDebug("★Speaker REJECTED bytes=" + finalSpeakerChunk.length
                                                                    + " rawRms=" + String.format(java.util.Locale.ROOT, "%.1f", rawSpeakerMetrics.rms)
                                                                    + " rawPeak=" + rawSpeakerMetrics.peak
                                                                    + " procRms=" + String.format(java.util.Locale.ROOT, "%.1f", procSpeakerMetrics.rms)
                                                                    + " procPeak=" + procSpeakerMetrics.peak);
                                                        }
                                                        updateSpeakerStatus();
                                                    }
                                                    // ★v1.5.0: Moonshine用にgate結果をキャッシュ（onCompletedが参照）
                                                    if (isEngineMoonshine()) {
                                                        moonshineLastGateOk = speakerOk;
                                                    }
                                                    // ★v1.5.0: Moonshineモードはspeaker gate外でmic_debugを書く
                                                    // （COMPLETEはonCompletedで既にspeakされており、gateはWAVの記録に無関係）
                                                    if (isEngineMoonshine() && micDump && finalProcChunk.length > 0) {
                                                        try {
                                                            writeMicDebugPair(finalRawChunk, finalProcChunk, audioFormat, "moonshine-utterance");
                                                        } catch (Exception ex) {
                                                            Config.logDebug("★MIC: per-utterance dump failed: " + ex);
                                                        }
                                                    }

                                                    if (speakerOk) {
                                                        if (isEngineMoonshine()) {
                                                            rememberMoonshineUtteranceAcoustics(uttSeq, finalProcChunk);
                                                            rememberTalkTtsProsodyProfile(uttSeq, trimmedRawChunk);
                                                            String cachedComplete = consumeMoonshineCompleteCandidate(uttSeq);
                                                            if (cachedComplete != null && !cachedComplete.isBlank()) {
                                                                String candidate = removeCjkSpaces(cachedComplete).trim();
                                                                String lastP = MobMateWhisp.getLastPartial();
                                                                if (shouldPreferLastPartialOverComplete(lastP, candidate)) {
                                                                    candidate = removeCjkSpaces(lastP).trim();
                                                                }
                                                                if (!candidate.isBlank()) {
                                                                    Config.logDebug("★Moon final commit from cached COMPLETE: " + candidate);
                                                                    handleFinalTextWithUtteranceSeq(uttSeq, candidate, action, false);
                                                                    MobMateWhisp.setLastPartial("");
                                                                    clearMoonshineUtteranceAcoustics(uttSeq);
                                                                    return;
                                                                }
                                                            }
                                                        }

                                                        Config.logDebug("★Final send");
                                                        transcribe(finalProcChunk, action, true, uttSeq);

                                                        // Moonshine連続無出力ガード
                                                        if (isEngineMoonshine()) {
                                                            moonshineNoOutputCount++;

                                                            long moonSilentMs = System.currentTimeMillis() - moonshineLastOutputMs;

                                                            if (moonshineNoOutputCount >= MOONSHINE_NO_OUTPUT_RELOAD
                                                                    && moonSilentMs >= MOONSHINE_NO_OUTPUT_RELOAD_GRACE_MS
                                                                    && moonshine != null && moonshine.isLoaded()) {
                                                                Config.logDebug("★Moonshine: " + moonshineNoOutputCount
                                                                        + "回連続無出力 / lastOutput=" + moonSilentMs + "ms -> forceReload");
                                                                moonshine.forceReload();
                                                                moonshineNoOutputCount = 0;
                                                                moonshineLastOutputMs = System.currentTimeMillis();
                                                            }
                                                            maybeFallbackMoonshineAcceptedUtterance(finalProcChunk, action, uttSeq);
                                                        }

                                                        // ★話者プロファイル微調整＆定期保存
                                                        if (prefs.getBoolean("speaker.enabled", false)
                                                                && speakerProfile.isReady()) {
                                                            boolean shouldSave = speakerProfile.refineSample(finalSpeakerChunk);
                                                            if (shouldSave) {
                                                                speakerProfile.saveToFile(new File("speaker_profile.dat"));
                                                            }
                                                        }

                                                        // ★Mic debug wav (既存処理そのまま)
                                                        if (!isEngineMoonshine() && micDump && finalRawChunk.length > 0) {
                                                            try {
                                                                writeMicDebugPair(finalRawChunk, finalProcChunk, audioFormat, "per-utterance");
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
                                                SwingUtilities.invokeLater(() -> {
                                                    window.setTitle(UiText.t("ui.title.idle"));
                                                    MobMateWhisp.this.window.setIconImage(imageInactive);
                                                });
                                                // ★FIX: BGMリセット時もMoonshineのstreamをリセット
                                                if (isEngineMoonshine() && moonshine != null && moonshine.isLoaded()) {
                                                    moonshine.resetStreamForClipping();
                                                }
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
                                        preSpeechMoonFeedFrames = 0;
                                        vad.reset();
                                        MobMateWhisp.setLastPartial("");
                                        // ★FIX: dropped時もMoonshineのstreamをリセット（蓄積汚染防止）
                                        if (isEngineMoonshine() && moonshine != null && moonshine.isLoaded()) {
                                            moonshine.resetStreamForClipping();
                                        }
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
    private void deferMoonshineResetAfterFinal() {
        if (!isEngineMoonshine() || moonshine == null || !moonshine.isLoaded()) return;

        // COMPLETE/flush を少し待ってから reset する。
        // 即時 reset だと短い発話の final-only COMPLETE を取りこぼしやすい。
        moonResetDeferredUntilMs = System.currentTimeMillis() + MOON_FINAL_DRAIN_MS;
    }
    private void tryDrainAndResetMoonshine() {
        if (!isEngineMoonshine() || moonshine == null || !moonshine.isLoaded()) {
            moonResetDeferredUntilMs = 0L;
            return;
        }
        moonResetDeferredUntilMs = 0L;
        try {
            moonshine.resetStream();
            Config.logDebug("★Moon drain done -> stream reset");
        } catch (Exception ex) {
            Config.logDebug("★Moon drain reset failed: " + ex);
        }
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
            updateTalkPartialPreview(show);
            SwingUtilities.invokeLater(() -> {
                window.setTitle("[TRANS]:" + show);
                MobMateWhisp.this.window.setIconImage(imageTranscribing);
            });
        } else {
            updateTalkPartialPreview("");
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
    private volatile String lastSpeakStr = "";
    private volatile long lastSpeakMs = 0;
    private final Object talkFinalDedupeLock = new Object();
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

        TalkTextResult talkText = buildTalkTextResult(s);
        final String out = talkText.outputText();
        final String historyText = talkText.historyText();
        String dedupeText = (out == null || out.isBlank()) ? s : out;

        // ★同一文の連続を捨てすぎない(内部二重発火だけ潰す)
        // ★翻訳ON時は翻訳後テキストで重複を見るっす
        long now = System.currentTimeMillis();
        synchronized (talkFinalDedupeLock) {
            if (!lastSpeakStr.isEmpty() && (now - lastSpeakMs) <= 1200) {
                String prev = lastSpeakStr;
                String prevNorm = normForNearDup(prev);
                String curNorm  = normForNearDup(dedupeText);

                if (prevNorm.equals(curNorm)) {
                    Config.logDebug("★Final skip (exact dup <1.2s): " + dedupeText);
                    lastSpeakMs = now;
                    lastSpeakStr = dedupeText;
                    return;
                }

                if (prevNorm.startsWith(curNorm)) {
                    Config.logDebug("★Final skip (shorter prefix <1.2s): prev=[" + prev + "] new=[" + dedupeText + "]");
                    lastSpeakMs = now;
                    lastSpeakStr = dedupeText;
                    return;
                }

                double sim = similarityRatio(prevNorm, curNorm);
                if (curNorm.startsWith(prevNorm)) {
                    int grow = curNorm.length() - prevNorm.length();
                    if (grow <= 4 || sim >= 0.88) {
                        Config.logDebug(String.format(
                                "★Final skip (longer near-dup %.2f <1.2s): prev=[%s] new=[%s]",
                                sim, prev, dedupeText));
                        lastSpeakMs = now;
                        lastSpeakStr = dedupeText;
                        return;
                    }
                    Config.logDebug("★Final update (longer prefix <1.2s): prev=[" + prev + "] new=[" + dedupeText + "]");
                } else if (sim >= 0.80) {
                    Config.logDebug(String.format(
                            "★Final skip (near-dup %.2f <1.2s): prev=[%s] new=[%s]",
                            sim, prev, dedupeText));
                    lastSpeakMs = now;
                    lastSpeakStr = dedupeText;
                    return;
                }
            }
            lastSpeakMs = now;
            lastSpeakStr = dedupeText;
        }
        latFinalMs.compareAndSet(0, now);

        boolean willSpeak = (action != Action.NOTHING_NO_SPEAK);
        markFinalAcceptedNow(now, willSpeak, out);

        long uttSeq = TL_FINAL_UTTERANCE_SEQ.get();
        TtsProsodyProfile ttsProsodyProfile = consumeTalkTtsProsodyProfile(uttSeq, s);

        if (ttsConfirmMode && action != Action.NOTHING_NO_SPEAK) {
            enqueuePendingConfirm(s, out, historyText, action, now, "final", ttsProsodyProfile);
            return;
        }

        long vad = latVadStartMs.get();
        long p1  = latFirstPartMs.get();
        long fin = latFinalMs.get();
        if (vad > 0) {
            long vad2p = (p1 > 0)  ? (p1 - vad)  : -1;
            long vad2f = (fin > 0) ? (fin - vad) : -1;
            Config.logDebug("★LAT(ms) vad->p1=" + vad2p + " vad->final=" + vad2f);
        }
        commitTalkOutput(s, out, historyText, action, now, "final", ttsProsodyProfile);
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
    private void handleFinalTextWithUtteranceSeq(long utteranceSeq, String finalStr, Action action, boolean flgRescue) {
        if (utteranceSeq > 0) {
            TL_FINAL_UTTERANCE_SEQ.set(utteranceSeq);
        } else {
            TL_FINAL_UTTERANCE_SEQ.remove();
        }
        try {
            handleFinalText(finalStr, action, flgRescue);
        } finally {
            TL_FINAL_UTTERANCE_SEQ.remove();
        }
    }
    // ★ partial救済（transcribeが二度と呼ばれないケース対策）
    private static final ThreadLocal<Long> TL_FINAL_UTTERANCE_SEQ =
            ThreadLocal.withInitial(() -> -1L);
    private volatile long lastPartialUpdateMs = 0L;
    private volatile long lastRescueSpeakMs = 0L;
    private volatile long lastMoonshineCompleteUtteranceSeq = -1L;
    private volatile String lastMoonshineCompleteNorm = "";
    private volatile long lastMoonshineCompleteAtMs = 0L;
    private static final int TTS_PROSODY_ACTIVE_TAIL_MAX_BYTES = 16000 * 2 * 8;
    private static final long TTS_PROSODY_ACTIVE_TAIL_MAX_AGE_MS = 3000L;
    private final Object talkTtsProsodyTailLock = new Object();
    private volatile long talkTtsProsodyTailUttSeq = -1L;
    private volatile byte[] talkTtsProsodyActiveTail = new byte[0];
    private volatile long talkTtsProsodyTailUpdatedAtMs = 0L;
    private final java.util.concurrent.ConcurrentHashMap<Long, Boolean> moonshineUtteranceMostlySilent =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Long, String> moonshineUtterancePartialSnapshot =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Long, TtsProsodyProfile> talkTtsProsodyByUtterance =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long lastTalkTtsProsodyRecentSeq = -1L;
    private volatile long lastTalkTtsProsodyRecentAtMs = 0L;
    private volatile TtsProsodyProfile lastTalkTtsProsodyRecentProfile = null;
    private static final long TTS_PROSODY_RECENT_FALLBACK_MS = 2500L;
    private static final long PARTIAL_RESCUE_STALE_MS = 90;      // partial更新が止まった判
    private static final long PARTIAL_RESCUE_MIN_SILENCE_MS = 40; // 無音が続いてる判定
    private static final long PARTIAL_RESCUE_COOLDOWN_MS = 400;    // 連発防止
    void maybeRescueSpeakFromPartial(Action action, long nowMs, long utteranceSeq) {
        // ★ADD: Final処理中はrescueしない（セーフティネット）
        if (isProcessingFinal.get()) return;

        String pendingComplete = (pendingMoonshineCompleteUtteranceSeq == utteranceSeq)
                ? pendingMoonshineCompleteText : "";
        String p = (pendingComplete != null && !pendingComplete.isBlank())
                ? pendingComplete
                : MobMateWhisp.getLastPartial();
        if (p == null) return;
        String s = p.trim();
        if (s.isEmpty()) return;

        // ★追加: 発話が切り替わったら古いpartialは捨てる
        if (utteranceSeq != currentUtteranceSeq) {
            Config.logDebug("★Partial rescue blocked (utterance changed): old=" + currentUtteranceSeq + " new=" + utteranceSeq);
            MobMateWhisp.setLastPartial(""); // 古いpartialをクリア
            clearMoonshineCompleteCandidate(utteranceSeq);
            return;
        }

        Config.logDebug("★Partial rescue -> maybeRescueSpeakFromPartial: " + s);

        // ★超即時 short-partial → lowGainMicのみ（normal gainでは暴発の原因になる）
        if (vad.getNoiseProfile().isLowGainMic
                && shouldInstantSpeakShortPartialForLowGain(s)
                && (nowMs - lastPartialUpdateMs) <= 80
                && (nowMs - lastRescueSpeakMs) >= 500) {
            lastRescueSpeakMs = nowMs;
            rememberTalkTtsProsodyProfile(utteranceSeq, copyActiveTalkProsodyTail(utteranceSeq, nowMs));
            MobMateWhisp.setLastPartial("");
            clearMoonshineCompleteCandidate(utteranceSeq);
            Config.logDebug("★Instant short partial speak (lowGain): " + s);
            if (utteranceSeq > 0) {
                lastRescueUtteranceSeq = utteranceSeq;
                lastRescueAtMs = nowMs;
                lastRescueNorm = normForRescueDup(s);
            }
            handleFinalTextWithUtteranceSeq(utteranceSeq, s, action, true);
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
        rememberTalkTtsProsodyProfile(utteranceSeq, copyActiveTalkProsodyTail(utteranceSeq, nowMs));
        MobMateWhisp.setLastPartial("");
        clearMoonshineCompleteCandidate(utteranceSeq);

        Config.logDebug("★Partial rescue -> handleFinalText: " + s);

        if (utteranceSeq > 0) {
            lastRescueUtteranceSeq = utteranceSeq;
            lastRescueAtMs = nowMs;
            lastRescueNorm = normForRescueDup(s);
        }
        handleFinalTextWithUtteranceSeq(utteranceSeq, s, action, true);
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
        handleFinalTextWithUtteranceSeq(currentUtteranceSeq, s, action, true);
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
    private boolean shouldSuppressMoonshineComplete(String finalStr, long uttSeq) {
        if (isDuplicateFinalOfRescue(finalStr, uttSeq)) {
            Config.logDebug("★Moon COMPLETE suppressed (dup of partial rescue): " + finalStr);
            return true;
        }
        String norm = normForNearDup(finalStr);
        if (norm.isEmpty()) return false;
        long now = System.currentTimeMillis();
        if (uttSeq > 0 && uttSeq == lastMoonshineCompleteUtteranceSeq
                && norm.equals(lastMoonshineCompleteNorm)
                && (now - lastMoonshineCompleteAtMs) <= 10000) {
            Config.logDebug("★Moon COMPLETE suppressed (same utterance duplicate): " + finalStr);
            return true;
        }
        if (shouldSuppressWeakMoonshineHallucination(finalStr, uttSeq)) {
            Config.logDebug("★Moon COMPLETE suppressed (weak hallucination): " + finalStr);
            return true;
        }
        lastMoonshineCompleteUtteranceSeq = uttSeq;
        lastMoonshineCompleteNorm = norm;
        lastMoonshineCompleteAtMs = now;
        return false;
    }
    private void rememberMoonshineUtteranceAcoustics(long utteranceSeq, byte[] pcm16le) {
        if (utteranceSeq <= 0 || pcm16le == null || pcm16le.length == 0) return;
        moonshineUtteranceMostlySilent.put(utteranceSeq, isMostlySilentPcm16le(pcm16le));
        String lastP = removeCjkSpaces(MobMateWhisp.getLastPartial()).trim();
        if (lastP.isEmpty()) {
            moonshineUtterancePartialSnapshot.remove(utteranceSeq);
        } else {
            moonshineUtterancePartialSnapshot.put(utteranceSeq, lastP);
        }
    }
    private void resetActiveTalkProsodyTail(long utteranceSeq) {
        synchronized (talkTtsProsodyTailLock) {
            talkTtsProsodyTailUttSeq = utteranceSeq;
            talkTtsProsodyActiveTail = new byte[0];
            talkTtsProsodyTailUpdatedAtMs = 0L;
        }
    }
    private void appendActiveTalkProsodyTail(long utteranceSeq, byte[] pcm16le, int len, long nowMs) {
        if (utteranceSeq <= 0 || pcm16le == null || len <= 0) return;
        synchronized (talkTtsProsodyTailLock) {
            if (talkTtsProsodyTailUttSeq != utteranceSeq) {
                talkTtsProsodyTailUttSeq = utteranceSeq;
                talkTtsProsodyActiveTail = new byte[0];
            }
            int cappedNewLen = Math.min(TTS_PROSODY_ACTIVE_TAIL_MAX_BYTES, talkTtsProsodyActiveTail.length + len);
            byte[] merged = new byte[cappedNewLen];
            int keepPrev = Math.max(0, cappedNewLen - len);
            if (keepPrev > 0 && talkTtsProsodyActiveTail.length > 0) {
                System.arraycopy(
                        talkTtsProsodyActiveTail,
                        talkTtsProsodyActiveTail.length - keepPrev,
                        merged,
                        0,
                        keepPrev
                );
            }
            int copyLen = Math.min(len, cappedNewLen);
            System.arraycopy(pcm16le, len - copyLen, merged, cappedNewLen - copyLen, copyLen);
            talkTtsProsodyActiveTail = merged;
            talkTtsProsodyTailUpdatedAtMs = nowMs;
        }
    }
    private byte[] copyActiveTalkProsodyTail(long utteranceSeq, long nowMs) {
        synchronized (talkTtsProsodyTailLock) {
            if (utteranceSeq <= 0 || talkTtsProsodyTailUttSeq != utteranceSeq) return null;
            if (talkTtsProsodyActiveTail == null || talkTtsProsodyActiveTail.length == 0) return null;
            if ((nowMs - talkTtsProsodyTailUpdatedAtMs) > TTS_PROSODY_ACTIVE_TAIL_MAX_AGE_MS) return null;
            return Arrays.copyOf(talkTtsProsodyActiveTail, talkTtsProsodyActiveTail.length);
        }
    }
    private void rememberTalkTtsProsodyProfile(long utteranceSeq, byte[] pcm16le) {
        if (utteranceSeq <= 0 || pcm16le == null || pcm16le.length == 0) return;
        byte[] analysisPcm = prepareTalkProsodyAnalysisPcm(pcm16le);
        TtsProsodyProfile profile = TtsProsodyAnalyzer.analyzePcm(analysisPcm, 16000);
        if (profile != null && profile.isUsable()) {
            talkTtsProsodyByUtterance.put(utteranceSeq, profile);
            lastTalkTtsProsodyRecentSeq = utteranceSeq;
            lastTalkTtsProsodyRecentAtMs = System.currentTimeMillis();
            lastTalkTtsProsodyRecentProfile = profile;
            Config.logDebug("[TTS_PROSODY] cached uttSeq=" + utteranceSeq
                    + " mood=" + profile.mood()
                    + " conf=" + profile.confidence()
                    + " rise=" + String.format(Locale.ROOT, "%.2f", profile.contourRise())
                    + " melody=" + String.format(Locale.ROOT, "%.2f", profile.melodyDepth())
                    + " dark=" + String.format(Locale.ROOT, "%.2f", profile.darkScore())
                    + " energy=" + String.format(Locale.ROOT, "%.2f", profile.energyScore()));
        } else {
            talkTtsProsodyByUtterance.remove(utteranceSeq);
        }
    }
    private byte[] prepareTalkProsodyAnalysisPcm(byte[] pcm16le) {
        if (pcm16le == null || pcm16le.length < 3200) return pcm16le;
        int length = pcm16le.length & ~1;
        int minKeep = 16000 * 2;
        if (length <= minKeep * 2) return Arrays.copyOf(pcm16le, length);

        int leadDrop = (int) (length * 0.16f);
        int tailDrop = (int) (length * 0.20f);
        leadDrop &= ~1;
        tailDrop &= ~1;
        int start = Math.max(0, leadDrop);
        int end = Math.max(start + minKeep, length - tailDrop);
        end = Math.min(length, end & ~1);
        if ((end - start) < minKeep) {
            start = Math.max(0, (length - minKeep) / 2);
            start &= ~1;
            end = Math.min(length, start + minKeep);
        }
        if (start <= 0 && end >= length) return Arrays.copyOf(pcm16le, length);
        return Arrays.copyOfRange(pcm16le, start, end);
    }
    private TtsProsodyProfile consumeTalkTtsProsodyProfile(long utteranceSeq, String text) {
        TtsProsodyProfile textProfile = TtsProsodyAnalyzer.analyzeText(text);
        TtsProsodyProfile pcmProfile = null;
        if (utteranceSeq > 0) {
            pcmProfile = talkTtsProsodyByUtterance.remove(utteranceSeq);
        }
        if (pcmProfile == null) {
            pcmProfile = consumeRecentTalkTtsProsodyFallback(utteranceSeq);
        }
        if (pcmProfile == null) return textProfile;
        if (!textProfile.isConfident()) return pcmProfile;
        return textProfile.blendWith(pcmProfile);
    }
    private TtsProsodyProfile consumeRecentTalkTtsProsodyFallback(long utteranceSeq) {
        TtsProsodyProfile recent = lastTalkTtsProsodyRecentProfile;
        long ageMs = System.currentTimeMillis() - lastTalkTtsProsodyRecentAtMs;
        if (recent == null || ageMs > TTS_PROSODY_RECENT_FALLBACK_MS) return null;
        if (utteranceSeq > 0 && lastTalkTtsProsodyRecentSeq > 0 && utteranceSeq != lastTalkTtsProsodyRecentSeq) {
            return null;
        }
        Config.logDebug("[TTS_PROSODY] fallback recent seq=" + lastTalkTtsProsodyRecentSeq + " ageMs=" + ageMs);
        lastTalkTtsProsodyRecentProfile = null;
        return recent;
    }
    private void clearMoonshineUtteranceAcoustics(long utteranceSeq) {
        if (utteranceSeq > 0) {
            moonshineUtteranceMostlySilent.remove(utteranceSeq);
            moonshineUtterancePartialSnapshot.remove(utteranceSeq);
            talkTtsProsodyByUtterance.remove(utteranceSeq);
            synchronized (talkTtsProsodyTailLock) {
                if (talkTtsProsodyTailUttSeq == utteranceSeq) {
                    talkTtsProsodyTailUttSeq = -1L;
                    talkTtsProsodyActiveTail = new byte[0];
                    talkTtsProsodyTailUpdatedAtMs = 0L;
                }
            }
        } else {
            moonshineUtteranceMostlySilent.clear();
            moonshineUtterancePartialSnapshot.clear();
            talkTtsProsodyByUtterance.clear();
            synchronized (talkTtsProsodyTailLock) {
                talkTtsProsodyTailUttSeq = -1L;
                talkTtsProsodyActiveTail = new byte[0];
                talkTtsProsodyTailUpdatedAtMs = 0L;
            }
        }
    }

    public void prewarmPiperPlusForTalkTargetSelection(Component parent, String selectedTarget) {
        String engine = prefs.get("tts.engine", "auto").toLowerCase(Locale.ROOT);
        if (!"piper_plus".equals(engine)) return;

        String target = LanguageOptions.normalizeTranslationTarget(selectedTarget);
        if ("OFF".equals(target)) {
            Config.logDebug("[Piper+][PREWARM] skip: talk target is OFF");
            return;
        }

        String modelId = prefs.get("piper.plus.model_id", "").trim();
        PiperPlusCatalog.Entry entry = PiperPlusCatalog.resolveForLanguage(modelId, target);
        if (entry == null) {
            Config.log("[Piper+][PREWARM] no entry resolved for target=" + target + " modelId=" + modelId);
            return;
        }

        boolean installed = PiperPlusModelManager.isInstalled(entry);
        Config.log("[Piper+][PREWARM] talk target=" + target
                + " modelId=" + modelId
                + " resolved=" + entry.id()
                + " installKey=" + entry.installKey()
                + " installed=" + installed);

        if (isKnownUnstableBuiltInPiperPlusEntry(entry)) {
            notifyKnownPiperPlusCompatibilityIssue(parent, entry);
            return;
        }

        if (!installed && ensurePiperPlusModelReady(entry, parent)) {
            Config.log("[Piper+][PREWARM] model downloaded on target selection: " + entry.id());
            installed = PiperPlusModelManager.isInstalled(entry);
        }
        if (!installed) return;
        schedulePiperPlusPersistentPrewarm(entry, target, "target-select");
    }

    private void bootstrapPiperPlusPersistentPrewarmIfNeeded() {
        String engine = prefs.get("tts.engine", "auto").toLowerCase(Locale.ROOT);
        if (!"piper_plus".equals(engine)) return;

        String outputLanguage = getTalkOutputLanguage();
        if (outputLanguage == null || outputLanguage.isBlank()) {
            Config.logDebug("[Piper+][PREWARM] startup skip: output language unavailable");
            return;
        }

        String modelId = prefs.get("piper.plus.model_id", "").trim();
        PiperPlusCatalog.Entry entry = PiperPlusCatalog.resolveForLanguage(modelId, outputLanguage);
        if (entry == null) {
            Config.logDebug("[Piper+][PREWARM] startup skip: no entry for output=" + outputLanguage
                    + " modelId=" + modelId);
            return;
        }
        if (isKnownUnstableBuiltInPiperPlusEntry(entry)) {
            Config.logDebug("[Piper+][PREWARM] startup skip: unstable builtin entry=" + entry.id());
            return;
        }
        if (!PiperPlusModelManager.isInstalled(entry)) {
            Config.logDebug("[Piper+][PREWARM] startup skip: model not installed entry=" + entry.id());
            return;
        }
        schedulePiperPlusPersistentPrewarm(entry, outputLanguage, "startup-bootstrap");
    }

    private void schedulePiperPlusPersistentPrewarm(PiperPlusCatalog.Entry entry, String target, String reason) {
        if (entry == null || !prefs.getBoolean("piper.plus.persistent", true)) return;
        Path exePath = PiperPlusModelManager.findBundledExe();
        if (exePath == null) {
            Config.logDebug("[Piper+][PREWARM] skip: bundled runtime missing");
            return;
        }
        String cooldownKey = entry.installKey() + "|" + entry.cliTextLanguage() + "|" + target;
        long now = System.currentTimeMillis();
        long until = piperPlusSessionPrewarmUntil.getOrDefault(cooldownKey, 0L);
        if (until > now) {
            Config.logDebug("[Piper+][PREWARM] skip: cooldown key=" + cooldownKey
                    + " remain_ms=" + (until - now));
            return;
        }
        piperPlusSessionPrewarmUntil.put(cooldownKey, now + PIPER_PLUS_SESSION_PREWARM_COOLDOWN_MS);
        List<PiperSynthesisOptions> warmOptions = buildCanonicalPiperPersistentPrewarmOptions(entry);
        if (warmOptions.isEmpty()) return;
        piperPlusPrewarmExecutor.submit(() -> runPiperPlusPersistentPrewarm(exePath, entry, target, reason, warmOptions));
    }

    private List<PiperSynthesisOptions> buildCanonicalPiperPersistentPrewarmOptions(PiperPlusCatalog.Entry entry) {
        if (entry == null) return Collections.emptyList();
        int sampleRate = PiperPlusModelManager.getSampleRate(entry);
        LinkedHashMap<String, PiperSynthesisOptions> unique = new LinkedHashMap<>();
        addCanonicalPiperPersistentPrewarmOption(unique, sampleRate, 0.80f, 0.16f);
        addCanonicalPiperPersistentPrewarmOption(unique, sampleRate, 0.68f, 0.16f);
        addCanonicalPiperPersistentPrewarmOption(unique, sampleRate, 0.80f, 0.08f);
        addCanonicalPiperPersistentPrewarmOption(unique, sampleRate, 0.80f, 0.32f);
        addCanonicalPiperPersistentPrewarmOption(unique, sampleRate, 0.68f, 0.08f);
        addCanonicalPiperPersistentPrewarmOption(unique, sampleRate, 0.68f, 0.32f);
        addCanonicalPiperPersistentPrewarmOption(unique, sampleRate, 1.00f, 0.16f);
        return new ArrayList<>(unique.values());
    }

    private void addCanonicalPiperPersistentPrewarmOption(Map<String, PiperSynthesisOptions> unique,
                                                          int sampleRate,
                                                          float lengthScale,
                                                          float sentenceSilence) {
        PiperSynthesisOptions seeded = new PiperSynthesisOptions(
                sampleRate,
                lengthScale,
                sentenceSilence,
                0.65f,
                0.85f,
                String.format(Locale.ROOT, "%.3f", lengthScale),
                String.format(Locale.ROOT, "%.3f", sentenceSilence),
                "0.650",
                "0.850"
        );
        PiperSynthesisOptions persistent = toPersistentSessionOptions(seeded);
        String key = persistent.lengthScaleArg() + "/" + persistent.sentenceSilenceArg();
        unique.putIfAbsent(key, persistent);
    }

    private void runPiperPlusPersistentPrewarm(Path exePath,
                                               PiperPlusCatalog.Entry entry,
                                               String target,
                                               String reason,
                                               List<PiperSynthesisOptions> warmOptions) {
        String warmupText = selectPiperPlusWarmupText(target);
        for (PiperSynthesisOptions option : warmOptions) {
            try {
                PiperPlusPersistentSessionManager.PrewarmResult result = piperPlusSessionManager.prewarm(
                        exePath,
                        entry,
                        new PiperPlusPersistentSessionManager.SynthesisOptions(
                                option.sampleRate(),
                                option.lengthScaleArg(),
                                option.sentenceSilenceArg(),
                                option.noiseScaleArg(),
                                option.noiseWArg()),
                        warmupText);
                Config.logDebug("[Piper+][PREWARM] reason=" + reason
                        + " target=" + target
                        + " key=" + result.sessionKey()
                        + " alreadyWarm=" + result.alreadyWarm()
                        + " boot_ms=" + result.bootMs()
                        + " warm_ms=" + result.warmupMs()
                        + " pool=" + result.activeSessions());
            } catch (Exception ex) {
                Config.logDebug("[Piper+][PREWARM] failed reason=" + reason
                        + " target=" + target
                        + " len=" + option.lengthScaleArg()
                        + " sil=" + option.sentenceSilenceArg()
                        + " err=" + ex.getMessage());
            }
        }
    }

    private void schedulePiperPlusEmotionFamilyWarmup(Path exePath,
                                                      PiperPlusCatalog.Entry entry,
                                                      String target,
                                                      PiperSynthesisOptions options,
                                                      TtsProsodyProfile profile,
                                                      String reason) {
        if (exePath == null || entry == null || options == null || profile == null || !profile.isUsable()) return;
        PiperSynthesisOptions persistent = toPersistentSessionOptions(options);
        String familyKey = entry.installKey() + "|" + entry.cliTextLanguage()
                + "|family|" + persistent.lengthScaleArg();
        long now = System.currentTimeMillis();
        long until = piperPlusSessionPrewarmUntil.getOrDefault(familyKey, 0L);
        if (until > now) return;
        piperPlusSessionPrewarmUntil.put(familyKey, now + PIPER_PLUS_SESSION_PREWARM_COOLDOWN_MS);
        int sampleRate = persistent.sampleRate();
        LinkedHashMap<String, PiperSynthesisOptions> family = new LinkedHashMap<>();
        addCanonicalPiperPersistentPrewarmOption(family, sampleRate, persistent.lengthScale(), 0.08f);
        addCanonicalPiperPersistentPrewarmOption(family, sampleRate, persistent.lengthScale(), 0.16f);
        addCanonicalPiperPersistentPrewarmOption(family, sampleRate, persistent.lengthScale(), 0.32f);
        if (family.isEmpty()) return;
        piperPlusPrewarmExecutor.submit(() -> runPiperPlusPersistentPrewarm(
                exePath,
                entry,
                target,
                reason,
                new ArrayList<>(family.values())));
    }

    private String selectPiperPlusWarmupText(String target) {
        String lang = LanguageOptions.normalizeTranslationTarget(target);
        return switch (lang.toLowerCase(Locale.ROOT)) {
            case "ja" -> "はい。";
            case "zh", "zh-cn", "zh-tw" -> "好的。";
            case "es" -> "si.";
            case "fr" -> "oui.";
            case "pt", "pt-br" -> "sim.";
            default -> "ok.";
        };
    }

    private boolean isKnownUnstableBuiltInPiperPlusEntry(PiperPlusCatalog.Entry entry) {
        return entry != null && "css10-ja-6lang-zh".equalsIgnoreCase(entry.id());
    }

    private void notifyKnownPiperPlusCompatibilityIssue(Component parent, PiperPlusCatalog.Entry entry) {
        if (entry == null) return;
        String key = "compat:" + entry.id().toLowerCase(Locale.ROOT);
        if (!piperPlusCompatibilityNotified.add(key)) return;

        String message = "The bundled Piper+ Chinese route is currently unstable with this CSS10 6lang voice.\n\n"
                + "MobMate will fall back to Windows TTS for Chinese output.\n"
                + "If you want Piper+ Chinese later, you can add a separate local Chinese model.\n\n"
                + "Open the manual guide or the models folder now?";
        Runnable show = () -> {
            Object[] options = {"Open Chinese model guide", "Open models folder", "Close"};
            int choice = JOptionPane.showOptionDialog(
                    parent != null ? parent : window,
                    message,
                    "MobMate",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.INFORMATION_MESSAGE,
                    null,
                    options,
                    options[0]
            );
            if (choice == 0) {
                openPiperPlusGuide(parent);
            } else if (choice == 1) {
                openPiperPlusModelsFolder(parent);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            SwingUtilities.invokeLater(show);
        }
        Config.log("[Piper+][Compat] builtin Chinese route marked unstable for entry=" + entry.id());
    }

    private void openPiperPlusGuide(Component parent) {
        String remoteGuideUrl = "https://github.com/zufall-upon/MobMate#piper-model-guide";
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(new URI(remoteGuideUrl));
                    return;
                }
            }
        } catch (Exception ex) {
            Config.log("[Piper+][Compat] failed to open remote guide: " + ex.getMessage());
        }

        Path[] candidates = new Path[] {
                Path.of(System.getProperty("user.dir"), "piper_plus", "ZH_MODEL_GUIDE.html"),
                Path.of(System.getProperty("user.dir"), "app", "piper_plus", "ZH_MODEL_GUIDE.html"),
                Path.of(System.getProperty("user.dir"), "..", "app", "piper_plus", "ZH_MODEL_GUIDE.html").normalize()
        };
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) continue;
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop desktop = Desktop.getDesktop();
                    if (desktop.isSupported(Desktop.Action.BROWSE)) {
                        desktop.browse(candidate.toUri());
                        return;
                    }
                    desktop.open(candidate.toFile());
                    return;
                }
            } catch (Exception ex) {
                Config.log("[Piper+][Compat] failed to open Chinese guide: " + ex.getMessage());
                break;
            }
        }
        JOptionPane.showMessageDialog(
                parent != null ? parent : window,
                "Open this guide manually:\n" + remoteGuideUrl,
                "MobMate",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    private void openPiperPlusModelsFolder(Component parent) {
        Path dir = PiperPlusModelManager.getRootDir();
        try {
            Files.createDirectories(dir);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir.toFile());
                return;
            }
        } catch (Exception ex) {
            Config.log("[Piper+][Compat] failed to open models folder: " + ex.getMessage());
        }
        JOptionPane.showMessageDialog(
                parent != null ? parent : window,
                "Open this folder manually:\n" + dir.toAbsolutePath(),
                "MobMate",
                JOptionPane.INFORMATION_MESSAGE
        );
    }
    private boolean shouldSuppressWeakMoonshineHallucination(String finalStr, long uttSeq) {
        if (!isLikelyJapaneseHallucinationContext(finalStr)) return false;
        String s = removeCjkSpaces(finalStr).trim();
        if (!looksLikeShortDanglingJapaneseFragment(s)) return false;
        boolean mostlySilent = uttSeq > 0 && Boolean.TRUE.equals(moonshineUtteranceMostlySilent.get(uttSeq));
        if (mostlySilent) return true;
        return isWeakMoonshinePartialContext(s, uttSeq);
    }
    private boolean isLikelyJapaneseHallucinationContext(String text) {
        if (text == null || text.isBlank()) return false;
        String talkLang = getTalkLanguage();
        if (talkLang != null && talkLang.toLowerCase(Locale.ROOT).startsWith("ja")) {
            return true;
        }
        return containsJapaneseScript(text);
    }
    private boolean looksLikeShortDanglingJapaneseFragment(String text) {
        if (text == null) return false;
        String s = removeCjkSpaces(text).trim();
        if (s.isEmpty()) return false;
        if (s.indexOf(' ') >= 0 || s.indexOf('　') >= 0) return false;
        if (endsWithSentencePunctuation(s) || s.endsWith("？") || s.endsWith("?")) return false;
        int cp = s.codePointCount(0, s.length());
        if (cp < 4 || cp > 6) return false;
        if (!isAllJapaneseScriptText(s)) return false;
        char last = s.charAt(s.length() - 1);
        return last == 'は' || last == 'が' || last == 'を' || last == 'に' || last == 'へ' || last == 'と';
    }
    private boolean isWeakMoonshinePartialContext(String finalStr, long uttSeq) {
        String lastP = uttSeq > 0 ? moonshineUtterancePartialSnapshot.getOrDefault(uttSeq, "") : "";
        if (lastP.isEmpty()) return false;
        if (areUtteranceVariants(lastP, finalStr)) return false;
        int partialCp = lastP.codePointCount(0, lastP.length());
        int finalCp = finalStr.codePointCount(0, finalStr.length());
        return partialCp <= 2 && finalCp >= 4;
    }
    private boolean containsJapaneseScript(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(text.charAt(i));
            if (script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }
    private boolean isAllJapaneseScriptText(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x3040 && c <= 0x309F)
                    || (c >= 0x30A0 && c <= 0x30FF)
                    || (c >= 0x4E00 && c <= 0x9FFF)) {
                continue;
            }
            return false;
        }
        return true;
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
                    markFirstPartialNow(System.currentTimeMillis());
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

    private void setStartupStatus(boolean busy, String text) {
        this.startupBusy = busy;
        if (text != null && !text.isBlank()) {
            this.startupStatusText = text.trim().toUpperCase(Locale.ROOT);
        }
        updateIcon();
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

            boolean loadingIgnore = ignorePreloadPending || LocalWhisperCPP.isIgnoreExpansionBusy();

            // ★ここが本命：Start押下でdisableしたままにならないよう復帰させる
            //   キャリブ中・開始準備中・ignore preload中は無効化
            boolean enableMainButton = isCalibrationComplete && !isStartingRecording.get() && !loadingIgnore;
            if (MobMateWhisp.this.button != null) {
                MobMateWhisp.this.button.setEnabled(enableMainButton);
            }

            if (MobMateWhisp.this.window != null) {
                if (rec) {
                    MobMateWhisp.this.button.setText(UiText.t("ui.main.stop"));
                    LocalWhisperCPP.markInitialPromptDirty();

                } else if (isStartingRecording.get() || loadingIgnore) {
                    MobMateWhisp.this.button.setText("Starting...");
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
                    if (startupBusy) {
                        String bootText = (startupStatusText == null || startupStatusText.isBlank()) ? "BOOT" : startupStatusText;
                        statusLabel.setText("● " + bootText);
                        statusLabel.setForeground(new Color(220, 53, 69)); // 赤
                    } else if (ignorePreloadPending || LocalWhisperCPP.isIgnoreExpansionBusy()) {
                        int pct = LocalWhisperCPP.getIgnoreExpansionProgressPct();
                        statusLabel.setText(pct >= 0 ? ("● NGLOAD " + pct + "%") : "● NGLOAD");
                        statusLabel.setForeground(new Color(255, 105, 180)); // ピンク
                    } else if (tr) {
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

    private static void writeMicDebugPair(byte[] rawPcm,
                                          byte[] filteredPcm,
                                          AudioFormat format,
                                          String tag) throws Exception {
        int idx = MIC_DUMP_SEQ.getAndIncrement() % MIC_DUMP_ROTATE;
        File rawOut = new File("mic_debug_" + idx + ".wav");
        writePcm16leToWav(rawPcm, format, rawOut);
        Config.logDebug("★MIC: wrote " + rawOut.getAbsolutePath()
                + " bytes=" + rawPcm.length + " (" + tag + "-raw)");

        if (filteredPcm != null && filteredPcm.length > 0) {
            File filteredOut = new File("mic_debug_" + idx + "_f.wav");
            writePcm16leToWav(filteredPcm, format, filteredOut);
            Config.logDebug("★MIC: wrote " + filteredOut.getAbsolutePath()
                    + " bytes=" + filteredPcm.length + " (" + tag + "-filtered)");
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
        talkRealtimePrefilterState.reset();
        talkVadPrefilterState.reset();

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
    public List<HistoryEntry> getHistory() {
        return this.history;
    }
    public void addHistory(String s) {
        addHistory(s, s);
    }
    public void addHistory(String displayText, String rawText) {
        if (displayText == null) return;
        displayText = displayText.trim();
        if (displayText.isEmpty()) return;
        this.history.add(new HistoryEntry(displayText, rawText));
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
            audioPrefilterMode = normalizeAudioPrefilterMode(p.get("audio.prefilter.mode", "normal"));
            hearingAudioPrefilterMode = loadHearingAudioPrefilterMode(p);
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
            applyUIFont(p.getInt("ui.font.size", DEFAULT_UI_FONT_SIZE));
            if (!new File("_radiocmd.txt").exists()) {
                copyPreset("libs/preset/_radiocmd_" + suffix + ".txt", "_radiocmd.txt");
            }
            loadRadioCmdFileToPrefs(p, new File("_radiocmd.txt"));

            try {
                boolean debug = false;
                String url = null;
                boolean forceOpenWindow = false;
                String wavTestPath = null;
                String speakerCheckWavPath = null;
                for (int i = 0; i < args.length; i++) {
                    final String arg = args[i];
                    if (!arg.startsWith("-D")) {
                        if (arg.startsWith("http")) {
                            url = arg;
                        } else if (arg.equals("--window")) {
                            forceOpenWindow = true;
                        } else if (arg.equals("--debug")) {
                            debug = true;
                        } else if (arg.equals("--wav-test") && (i + 1) < args.length) {
                            wavTestPath = args[++i];
                        } else if (arg.equals("--speaker-check-wav") && (i + 1) < args.length) {
                            speakerCheckWavPath = args[++i];
                        }
                    }
                }
                final MobMateWhisp r = new MobMateWhisp(url);
                r.debug = debug;
                if (speakerCheckWavPath != null && !speakerCheckWavPath.isBlank()) {
                    final String wavPathFinal = speakerCheckWavPath;
                    Thread speakerCheckThread = new Thread(() -> {
                        int exitCode = 0;
                        try {
                            String result = r.runCliSpeakerCheck(new File(wavPathFinal));
                            Config.log("[SPEAKER-CHECK-CLI] RESULT=" + result);
                            System.out.println("[SPEAKER-CHECK-CLI] RESULT=" + result);
                        } catch (Throwable t) {
                            exitCode = 1;
                            Config.logError("[SPEAKER-CHECK-CLI] failed", t);
                            t.printStackTrace();
                        } finally {
                            try { if (hearingFrame != null) hearingFrame.shutdownForExit(); } catch (Throwable ignore) {}
                            try { shutdownMoonshine(); } catch (Throwable ignore) {}
                            try { SteamHelper.shutdown(); } catch (Throwable ignore) {}
                            System.exit(exitCode);
                        }
                    }, "speaker-check-cli");
                    speakerCheckThread.setDaemon(false);
                    speakerCheckThread.start();
                    return;
                }
                if (wavTestPath != null && !wavTestPath.isBlank()) {
                    final String wavPathFinal = wavTestPath;
                    Thread wavTestThread = new Thread(() -> {
                        int exitCode = 0;
                        try {
                            String result = r.runCliWavTest(new File(wavPathFinal));
                            Config.log("[WAV-TEST-CLI] FINAL=" + result);
                            System.out.println("[WAV-TEST-CLI] FINAL=" + result);
                        } catch (Throwable t) {
                            exitCode = 1;
                            Config.logError("[WAV-TEST-CLI] failed", t);
                            t.printStackTrace();
                        } finally {
                            try { if (hearingFrame != null) hearingFrame.shutdownForExit(); } catch (Throwable ignore) {}
                            try { shutdownMoonshine(); } catch (Throwable ignore) {}
                            try { SteamHelper.shutdown(); } catch (Throwable ignore) {}
                            System.exit(exitCode);
                        }
                    }, "wav-test-cli");
                    wavTestThread.setDaemon(false);
                    wavTestThread.start();
                    return;
                }
                r.autoStartVoiceVox();
                r.bootstrapPiperPlusPersistentPrewarmIfNeeded();
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
                    if (!shutdownHookOnce.compareAndSet(false, true)) return;
                    try { piperPlusSessionManager.close(); } catch (Throwable ignore) {}
                    try { shutdownMoonshine(); } catch (Throwable ignore) {}
                    try { SteamHelper.shutdown(); } catch (Throwable ignore) {}
                    try {
                        executorService.shutdown();
                        executorService.awaitTermination(1200, TimeUnit.MILLISECONDS);
                    } catch (Throwable ignore) {
                    } finally {
                        try { executorService.shutdownNow(); } catch (Throwable ignore) {}
                    }
                    try {
                        audioService.shutdown();
                        audioService.awaitTermination(1200, TimeUnit.MILLISECONDS);
                    } catch (Throwable ignore) {
                    } finally {
                        try { audioService.shutdownNow(); } catch (Throwable ignore) {}
                    }
                    try {
                        piperPlusPrewarmExecutor.shutdown();
                        piperPlusPrewarmExecutor.awaitTermination(1200, TimeUnit.MILLISECONDS);
                    } catch (Throwable ignore) {
                    } finally {
                        try { piperPlusPrewarmExecutor.shutdownNow(); } catch (Throwable ignore) {}
                    }
                }, "core-shutdown"));



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
    private void showStartupWizardIfNeeded() {
        if (window == null) return;
        if (prefs.getBoolean("wizard.completed", false)) return;
        if (prefs.getBoolean("wizard.never", false)) return;

        SwingUtilities.invokeLater(() -> {
            FirstLaunchWizard wizard = null;
            try {
                wizard = new FirstLaunchWizard(window, this);
                wizard.setLocationRelativeTo(window);
                wizard.setVisible(true);
            } catch (Throwable t) {
                Config.logError("[Wizard] startup open failed", t);
            } finally {
                try {
                    if (wizard != null) wizard.stopAllWizardTests();
                } catch (Throwable ignore) {}
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
        mainPanel.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 4)); // 左右余白をさらに圧縮

        MobMateWhisp.this.window.setTitle(cpugpumode);

        // ===== カスタムタイトルバー（ボタン類） =====
        JPanel titleBar = new JPanel();
        titleBar.setLayout(new BoxLayout(titleBar, BoxLayout.X_AXIS));
        titleBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(3, 2, 3, 1)
        ));

        // 左側：レベルメーター（広く取る）
        gainMeter = new GainMeter();
        gainMeter.setFont(titleBar.getFont());
        int titleFontSize = Math.round(titleBar.getFont().getSize2D());
        int meterWidth = clampInt(170 + Math.max(0, titleFontSize - 16) * 14, 170, 320);
        int meterHeight = clampInt(titleFontSize + 6, 18, 34);
        gainMeter.setCompactLabels(false);
        gainMeter.setPreferredSize(new Dimension(meterWidth, meterHeight));
        gainMeter.setMinimumSize(new Dimension(meterWidth, meterHeight));
        gainMeter.setMaximumSize(new Dimension(meterWidth, meterHeight));
        titleBar.add(gainMeter);
        titleBar.add(Box.createHorizontalStrut(4));

        // 中央：ボタン類
        titleBar.add(this.button);
        pinMainButtonWidth(this.button);

        final JButton historyButton = new JButton(UiText.t("ui.main.history"));
        titleBar.add(Box.createHorizontalStrut(4));
        titleBar.add(historyButton);

        final JButton hearingButton = new JButton(uiOr("ui.main.hearing", "Hearing"));
        hearingButton.setToolTipText(uiOr("ui.main.hearing.tip", "Open Hearing window"));
        titleBar.add(Box.createHorizontalStrut(4));
        titleBar.add(hearingButton);

        final JButton settingsCenterButton = new JButton(uiOr("ui.main.settingsCenter", "Settings"));
        settingsCenterButton.setToolTipText(uiOr("ui.main.settingsCenter.tip", "Settings Center"));
        titleBar.add(Box.createHorizontalStrut(4));
        titleBar.add(settingsCenterButton);

        confirmModeToggle = new JToggleButton();
        ttsConfirmMode = prefs.getBoolean("tts.confirm_mode", false);
        updateConfirmModeToggleUi();
        titleBar.add(Box.createHorizontalStrut(4));
        titleBar.add(confirmModeToggle);

        final JButton prefButton = new JButton(UiText.t("ui.main.prefs"));
        titleBar.add(Box.createHorizontalStrut(4));
        titleBar.add(prefButton);

        compactTitleButton(this.button,
                UiText.t("ui.main.start"),
                UiText.t("ui.main.stop"),
                UiText.t("ui.calibrating"),
                "Starting...");
        compactTitleButton(historyButton, UiText.t("ui.main.history"));
        compactTitleButton(hearingButton, uiOr("ui.main.hearing", "Hearing"));
        compactTitleButton(settingsCenterButton, uiOr("ui.main.settingsCenter", "Settings"));
        compactTitleButton(confirmModeToggle,
                uiOr("ui.main.confirm.instant.short", "I"),
                uiOr("ui.main.confirm.pending.short", "P"));
        compactTitleButton(prefButton, UiText.t("ui.main.prefs"));

        // 右側：×ボタン
        titleBar.add(Box.createHorizontalStrut(3));
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

        confirmPanel = new JPanel(new BorderLayout(6, 0));
        confirmPanel.setOpaque(true);
        confirmPanel.setBackground(new Color(52, 38, 18));
        confirmPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, new Color(180, 120, 45)),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)
        ));
        confirmCountdownLabel = new JLabel("");
        confirmCountdownLabel.setForeground(new Color(255, 200, 120));
        confirmCountdownLabel.setPreferredSize(new Dimension(56, 20));
        confirmTextLabel = new JLabel("");
        confirmTextLabel.setForeground(Color.WHITE);
        JButton confirmOkButton = new JButton(uiOr("ui.confirm.approve", "OK"));
        JButton confirmCancelButton = new JButton(uiOr("ui.confirm.cancel", "Cancel"));
        compactTitleButton(confirmOkButton, uiOr("ui.confirm.approve", "OK"));
        compactTitleButton(confirmCancelButton, uiOr("ui.confirm.cancel", "Cancel"));
        confirmOkButton.setOpaque(true);
        confirmOkButton.setForeground(Color.WHITE);
        confirmOkButton.setBackground(new Color(126, 92, 26));
        confirmOkButton.setBorder(BorderFactory.createLineBorder(new Color(214, 168, 70)));
        confirmCancelButton.setOpaque(true);
        confirmCancelButton.setForeground(new Color(230, 224, 214));
        confirmCancelButton.setBackground(new Color(74, 64, 52));
        confirmCancelButton.setBorder(BorderFactory.createLineBorder(new Color(122, 106, 88)));
        confirmOkButton.addActionListener(e -> approvePendingConfirm());
        confirmCancelButton.addActionListener(e -> cancelPendingConfirm());
        JPanel confirmLeftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        confirmLeftPanel.setOpaque(false);
        confirmLeftPanel.add(confirmCancelButton);
        confirmLeftPanel.add(confirmCountdownLabel);
        confirmLeftPanel.add(confirmOkButton);
        confirmPanel.add(confirmLeftPanel, BorderLayout.WEST);
        confirmPanel.add(confirmTextLabel, BorderLayout.CENTER);
        confirmPanel.setVisible(false);

        // ===== メインレイアウト =====
        JPanel contentPane = new JPanel();
        contentPane.setLayout(new BoxLayout(contentPane, BoxLayout.Y_AXIS));
        contentPane.add(Box.createVerticalStrut(6));
        contentPane.add(titleBar, BorderLayout.CENTER);
        contentPane.add(confirmPanel, BorderLayout.CENTER);
        contentPane.add(Box.createVerticalStrut(4));
        contentPane.add(statusBar, BorderLayout.SOUTH);

        this.window.setContentPane(contentPane);
        this.label.setText(UiText.t("ui.main.transcribing"));

        this.window.pack();
        Dimension packedSize = this.window.getSize();
        this.window.setMinimumSize(
                new Dimension(Math.max(510, packedSize.width), packedSize.height)
        );
        this.label.setText(UiText.t("ui.main.stop"));
        this.window.setResizable(false);

        Rectangle fallback = new Rectangle(15, 15, this.window.getWidth(), this.window.getHeight());
        restoreFixedSizeWindowBounds(this.window, "ui.main", fallback);
        UiFontApplier.ensureWindowFitsPreferredSize(this.window);
        installLocationSaver(this.window, "ui.main");

        window.setVisible(true);

        // ★前回開いていた場合だけ、起動時にHistoryも自動で開く
        SwingUtilities.invokeLater(() -> {
            boolean restoreHistory = (pendingHistoryRestore != null)
                    ? pendingHistoryRestore.booleanValue()
                    : prefs.getBoolean("ui.history.open_on_startup", true);
            if (restoreHistory) {
                showHistory();
            }
            pendingHistoryRestore = null;
            suppressHistoryCloseCallbacks = false;
            if (prefs.getBoolean("ui.hearing.visible", false)) {
                try {
                    showHearingWindow();
                } catch (Throwable t) {
                    Config.logError("[Hearing] startup restore failed", t);
                }
            }

            // 主役はメイン画面のまま
            if (window != null) {
                window.toFront();
                window.requestFocus();
            }
        });

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

        prefButton.setToolTipText(uiOr("ui.main.prefs.tip", "Quick menu"));

        this.button.addActionListener(e -> toggleRecordingFromUI());

        historyButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showHistory();
            }
        });

        hearingButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showHearingWindow();
            }
        });

        settingsCenterButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openSettingsCenter();
            }
        });

        confirmModeToggle.addActionListener(e -> setConfirmMode(confirmModeToggle.isSelected()));

        prefButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JPopupMenu popup = createDeltaQuickMenu();
                popup.show(prefButton, 0, prefButton.getHeight());
            }
        });

        this.window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowActivated(WindowEvent e) {
                // Settings / History / Hearing が開いている時は、
                // 親ウィンドウを前面に引っ張らない（ちらつき防止）
                if (settingsCenterFrame != null && settingsCenterFrame.isShowing()) return;
                if (historyFrame != null && historyFrame.isShowing()) return;
                if (hearingFrame != null && hearingFrame.isShowing()) return;

                // 通常時だけ明示前面化したいなら残す
                // bringToFront(window);
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
        updateConfirmUi();

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
            byte[] pcm = loadWavAs16kMonoPcm(wavFile);

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
    private byte[] loadWavAs16kMonoPcm(File wavFile) throws Exception {
        javax.sound.sampled.AudioInputStream in =
                javax.sound.sampled.AudioSystem.getAudioInputStream(wavFile);
        javax.sound.sampled.AudioFormat targetFmt =
                new javax.sound.sampled.AudioFormat(16000f, 16, 1, true, false);
        javax.sound.sampled.AudioInputStream converted =
                javax.sound.sampled.AudioSystem.getAudioInputStream(targetFmt, in);
        try {
            return converted.readAllBytes();
        } finally {
            try { converted.close(); } catch (Exception ignore) {}
            try { in.close(); } catch (Exception ignore) {}
        }
    }
    private void awaitEdtIdle() {
        if (SwingUtilities.isEventDispatchThread()) return;
        try {
            SwingUtilities.invokeAndWait(() -> {});
        } catch (Exception ignore) {}
    }
    private HistoryEntry getLatestHistoryEntry() {
        if (history.isEmpty()) return null;
        return history.get(history.size() - 1);
    }
    private String runCliWavTest(File wavFile) throws Exception {
        cliWavTestMode = true;
        try {
            Config.log("[WAV-TEST-CLI] file=" + wavFile.getAbsolutePath()
                    + " engine=" + (isEngineMoonshine() ? "moonshine" : "whisper"));
            byte[] pcm = loadWavAs16kMonoPcm(wavFile);

            awaitEdtIdle();
            SwingUtilities.invokeAndWait(() -> history.clear());

            if (isEngineMoonshine()) {
                return runMoonshineCliWavTest(pcm);
            }

            String raw = transcribe(pcm, Action.NOTHING, true);
            awaitEdtIdle();
            HistoryEntry latest = getLatestHistoryEntry();
            if (latest != null && latest.rawText() != null && !latest.rawText().isBlank()) {
                return latest.rawText().trim();
            }
            return (raw == null) ? "" : raw.trim();
        } finally {
            cliWavTestMode = false;
        }
    }
    private String runCliSpeakerCheck(File wavFile) throws Exception {
        Config.log("[SPEAKER-CHECK-CLI] file=" + wavFile.getAbsolutePath());
        File spkFile = new File("speaker_profile.dat");
        if (!speakerProfile.isReady() && spkFile.exists()) {
            boolean loaded = speakerProfile.loadFromFile(spkFile);
            if (loaded) {
                speakerProfile.updateSettings(
                        prefs.getInt("speaker.enroll_samples", 5),
                        prefs.getFloat("speaker.threshold_initial", 0.60f),
                        prefs.getFloat("speaker.threshold_target",
                                prefs.getFloat("speaker.threshold_initial", 0.60f))
                );
            }
            Config.log("[SPEAKER-CHECK-CLI] profileLoaded=" + loaded + " file=" + spkFile.getAbsolutePath());
        }
        if (!speakerProfile.isReady()) {
            return "PROFILE_NOT_READY";
        }

        byte[] pcm = loadWavAs16kMonoPcm(wavFile);
        AudioFormat fmt = new AudioFormat(16000f, 16, 1, true, false);
        byte[] trimmed = trimPcmSilence16le(pcm, fmt, 300, 120);
        boolean match = speakerProfile.isMatchingSpeaker(trimmed);
        return match ? "PASS" : "REJECT";
    }
    private String runMoonshineCliWavTest(byte[] pcm) throws Exception {
        if (moonshine == null || !moonshine.isLoaded()) {
            throw new IllegalStateException("Moonshine not loaded");
        }

        lastSpeakStr = "";
        lastSpeakMs = 0L;
        lastRescueUtteranceSeq = -1L;
        lastRescueAtMs = 0L;
        lastRescueNorm = "";
        MobMateWhisp.setLastPartial("");
        clearMoonshineCompleteCandidate(-1L);

        long uttSeq = ++utteranceSeqGen;
        currentUtteranceSeq = uttSeq;
        isSpeaking = true;
        speechStartTime = System.currentTimeMillis();
        lastSpeechEndTime = 0L;
        lastPartialUpdateMs = 0L;

        moonshine.resetStream();

        final int chunkBytes = 640; // 20ms at 16kHz/16bit/mono
        Config.log("[WAV-TEST-CLI] Moonshine feed start bytes=" + pcm.length + " chunkBytes=" + chunkBytes);
        for (int off = 0; off < pcm.length; off += chunkBytes) {
            int len = Math.min(chunkBytes, pcm.length - off);
            byte[] chunk = Arrays.copyOfRange(pcm, off, off + len);
            float[] fpcm = pcm16leToFloat(chunk, len);
            moonshine.addAudio(fpcm, 16000);
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        moonshine.addAudio(new float[1600 * 6], 16000); // 600ms tail silence
        try {
            Thread.sleep(250);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        isSpeaking = false;
        lastSpeechEndTime = System.currentTimeMillis() - PARTIAL_RESCUE_MIN_SILENCE_MS;
        moonshine.resetStream();

        try {
            Thread.sleep(700);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        awaitEdtIdle();

        HistoryEntry latest = getLatestHistoryEntry();
        if (latest != null && latest.rawText() != null && !latest.rawText().isBlank()) {
            return latest.rawText().trim();
        }
        if (!hasMoonshineOutputForUtterance(uttSeq)) {
            String fallback = runWhisperFallbackForMoonshine(pcm, Action.NOTHING, false, uttSeq);
            if (fallback != null && !fallback.isBlank()) {
                return fallback.trim();
            }
            awaitEdtIdle();
            latest = getLatestHistoryEntry();
            if (latest != null && latest.rawText() != null && !latest.rawText().isBlank()) {
                return latest.rawText().trim();
            }
        }
        String partial = MobMateWhisp.getLastPartial();
        return (partial == null) ? "" : partial.trim();
    }
    private Action getActionFromPrefs() {
        if (cliWavTestMode) {
            return Action.NOTHING;
        }
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

        // 状態表示
        statusLabel = new JLabel("● BOOT");
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
        statusLabel.setForeground(new Color(220, 53, 69));
        statusLabel.setToolTipText("Startup / initialization status");

        // ★LAT表示
        latencyLabel = new JLabel(buildLatencyText());
        latencyLabel.setFont(latencyLabel.getFont().deriveFont(12f));
        latencyLabel.setToolTipText(buildLatencyTooltip());

        // 話者照合
        speakerStatusLabel = new JLabel("○SPK");
        speakerStatusLabel.setFont(speakerStatusLabel.getFont().deriveFont(11f));
        speakerStatusLabel.setToolTipText("Speaker Verification Status");

        // VV/VG
        vvStatusLabel = new JLabel("○VV");
        vvStatusLabel.setFont(vvStatusLabel.getFont().deriveFont(11f));
        vvStatusLabel.setToolTipText("VoiceVox Status");

        vgStatusLabel = new JLabel("○VG");
        vgStatusLabel.setFont(vgStatusLabel.getFont().deriveFont(11f));
        vgStatusLabel.setToolTipText("Voiceger Status");

        // 録音時間
        durationLabel = new JLabel("0:00");
        durationLabel.setFont(durationLabel.getFont().deriveFont(11f));
        durationLabel.setToolTipText("Recording Time");

        // hotkey/model/mem/version
        JLabel hotkeyLabel = new JLabel(getHotkeyString());
        hotkeyLabel.setFont(hotkeyLabel.getFont().deriveFont(11f));
        hotkeyLabel.setToolTipText("Hotkey");

        modelLabel = new JLabel(buildCurrentModelStatusText());
        modelLabel.setFont(modelLabel.getFont().deriveFont(11f));
        modelLabel.setToolTipText(buildCurrentModelTooltip());

        memLabel = new JLabel("Mem: -");
        memLabel.setFont(memLabel.getFont().deriveFont(11f));
        memLabel.setToolTipText("Memory Usage");

        versionLabel = new JLabel("v" + Version.APP_VERSION);
        versionLabel.setFont(versionLabel.getFont().deriveFont(11f));

        // separators
        JSeparator sep1 = new JSeparator(SwingConstants.VERTICAL); sep1.setPreferredSize(new Dimension(1, 12));
        JSeparator sep2 = new JSeparator(SwingConstants.VERTICAL); sep2.setPreferredSize(new Dimension(1, 12));
        JSeparator sep3 = new JSeparator(SwingConstants.VERTICAL); sep3.setPreferredSize(new Dimension(1, 12));
        JSeparator sep4 = new JSeparator(SwingConstants.VERTICAL); sep4.setPreferredSize(new Dimension(1, 12));
        JSeparator sep5 = new JSeparator(SwingConstants.VERTICAL); sep5.setPreferredSize(new Dimension(1, 12));
        JSeparator sep6 = new JSeparator(SwingConstants.VERTICAL); sep6.setPreferredSize(new Dimension(1, 12));
        JSeparator sep7 = new JSeparator(SwingConstants.VERTICAL); sep7.setPreferredSize(new Dimension(1, 12));
        JSeparator sep8 = new JSeparator(SwingConstants.VERTICAL); sep8.setPreferredSize(new Dimension(1, 12));
        JSeparator sepLat = new JSeparator(SwingConstants.VERTICAL); sepLat.setPreferredSize(new Dimension(1, 12));

        // order
        statusBar.add(durationLabel);
        statusBar.add(sep7);
        statusBar.add(latencyLabel);
        statusBar.add(sepLat);
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

        statusBar.add(hotkeyLabel);
        statusBar.add(sep3);
        statusBar.add(modelLabel);
        statusBar.add(sep4);
        statusBar.add(memLabel);
        statusBar.add(sep8);
        statusBar.add(versionLabel);

        return statusBar;
    }

    private String buildCurrentModelStatusText() {
        if (isEngineMoonshine()) {
            String savedPath = prefs.get("moonshine.model_path", "");
            String key = findMoonshineModelKey(savedPath, scanMoonshineModelMap());

            if (key != null && !key.isBlank()) {
                return "Model: moonshine-" + key;
            }
            if (savedPath != null && !savedPath.isBlank()) {
                return "Model: moonshine-" + new File(savedPath).getName();
            }
            return "Model: moonshine";
        }

        return "Model: " + (model != null ? model.replace(".bin", "") : "-");
    }

    private String buildCurrentModelTooltip() {
        return isEngineMoonshine() ? "Current Moonshine model" : "Current Whisper model";
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
    private void resetLatencyForNewUtterance(long vadStartMs) {
        latVadStartMs.set(vadStartMs);
        latFirstPartialAtMs.set(0);
        latFinalAtMs.set(0);
        latFinalToTtsDone.set(false);
        pendingLatencyTtsText.set(null);

        // 今回の発話の測定としてリセット（UIが「計測中」になるっす）
        lastLatVadToP1Ms.set(-1);
        lastLatVadToFinalMs.set(-1);
        lastLatFinalToTtsMs.set(-1);
        lastLatPostProcessMs.set(-1);
    }

    private void markFirstPartialNow(long nowMs) {
        long vad = latVadStartMs.get();
        if (vad <= 0) return;

        if (latFirstPartialAtMs.compareAndSet(0, nowMs)) {
            long dt = nowMs - vad;
            if (dt < 0) dt = 0;
            if (dt > Integer.MAX_VALUE) dt = Integer.MAX_VALUE;
            lastLatVadToP1Ms.set((int) dt);
        }
    }

    private void markFinalAcceptedNow(long nowMs, boolean willSpeak, String finalText) {
        long vad = latVadStartMs.get();
        if (vad > 0) {
            long dt = nowMs - vad;
            if (dt < 0) dt = 0;
            if (dt > Integer.MAX_VALUE) dt = Integer.MAX_VALUE;
            lastLatVadToFinalMs.set((int) dt);
        }

        latFinalAtMs.set(nowMs);
        latFinalToTtsDone.set(false);

        if (willSpeak && finalText != null) {
            pendingLatencyTtsText.set(finalText.trim());
        } else {
            pendingLatencyTtsText.set(null);
            lastLatFinalToTtsMs.set(-1);
            latFinalToTtsDone.set(true);
        }
    }

    private void markTtsStartNowIfThisFinal(String rawText, long nowMs) {
        if (rawText == null) return;

        String pending = pendingLatencyTtsText.get();
        String raw = rawText.trim();
        if (pending == null || !pending.equals(raw)) return;

        // ここで「このspeakはFinal由来」確定っす
        if (!pendingLatencyTtsText.compareAndSet(pending, null)) return;

        long fin = latFinalAtMs.get();
        if (fin <= 0) return;
        if (!latFinalToTtsDone.compareAndSet(false, true)) return;

        long dt = nowMs - fin;
        if (dt < 0) dt = 0;
        if (dt > Integer.MAX_VALUE) dt = Integer.MAX_VALUE;
        lastLatFinalToTtsMs.set((int) dt);

        Config.logDebug("[LAT] vad->p1=" + lastLatVadToP1Ms.get()
                + "ms vad->final=" + lastLatVadToFinalMs.get()
                + "ms post=" + lastLatPostProcessMs.get()
                + "ms final->tts=" + dt + "ms");
    }

    private static String fmtMs(int v) {
        return (v < 0) ? "--" : String.valueOf(v);
    }

    private boolean isJaUi() {
        try {
            String ui = (prefs != null) ? prefs.get("ui.language", "en") : "en";
            return ui != null && ui.toLowerCase(java.util.Locale.ROOT).startsWith("ja");
        } catch (Exception e) {
            return false;
        }
    }

    private String buildLatencyText() {
        int p1  = lastLatVadToP1Ms.get();
        int fin = lastLatVadToFinalMs.get();
        int tts = lastLatFinalToTtsMs.get();
        int post = lastLatPostProcessMs.get();

        // “ぱっと見”用：基本は VAD->Final。TTSが取れてるなら足す。
        long total = -1;
        if (fin >= 0 && tts >= 0) total = (long) fin + (long) tts;
        else if (fin >= 0)        total = fin;
        else if (p1 >= 0)         total = p1; // フォールバック（計測中でも何か見えるように）

        if (total > Integer.MAX_VALUE) total = Integer.MAX_VALUE;

        String label = isJaUi() ? "Proc" : "Proc";
        String postSuffix = (post >= 0) ? " (PP " + post + "ms)" : "";
        return label + ": " + fmtMs((int) total) + "ms" + postSuffix;
    }

    private String buildLatencyTooltip() {
        int p1  = lastLatVadToP1Ms.get();
        int fin = lastLatVadToFinalMs.get();
        int tts = lastLatFinalToTtsMs.get();
        int post = lastLatPostProcessMs.get();

        return "Breakdown: VAD->1stPartial " + fmtMs(p1) + "ms / VAD->Final " + fmtMs(fin)
                + "ms / PostProcess " + fmtMs(post) + "ms / Final->TTS start " + fmtMs(tts) + "ms";
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

            if (modelLabel != null) {
                modelLabel.setText(buildCurrentModelStatusText());
                modelLabel.setToolTipText(buildCurrentModelTooltip());
            }

            // ★LAT表示を更新
            if (latencyLabel != null) {
                latencyLabel.setText(buildLatencyText());
                latencyLabel.setToolTipText(buildLatencyTooltip());
            }
            if (statusLabel != null) {
                boolean rec = isRecording();
                boolean tr = isTranscribing();
                boolean loadingIgnore = ignorePreloadPending || LocalWhisperCPP.isIgnoreExpansionBusy();
                if (startupBusy) {
                    String bootText = (startupStatusText == null || startupStatusText.isBlank()) ? "BOOT" : startupStatusText;
                    statusLabel.setText("● " + bootText);
                    statusLabel.setForeground(new Color(220, 53, 69));
                } else if (loadingIgnore) {
                    int pct = LocalWhisperCPP.getIgnoreExpansionProgressPct();
                    statusLabel.setText(pct >= 0 ? ("● NGLOAD " + pct + "%") : "● NGLOAD");
                    statusLabel.setForeground(new Color(255, 105, 180));
                    statusLabel.setToolTipText("Loading / translating ignore words");
                } else if (tr) {
                    statusLabel.setText("◉ TRANS");
                    statusLabel.setForeground(new Color(255, 152, 0));
                } else if (rec) {
                    statusLabel.setText("● [REC]");
                    statusLabel.setForeground(new Color(220, 53, 69));
                } else {
                    statusLabel.setText("▪ Ready");
                    statusLabel.setForeground(UIManager.getColor("Label.foreground"));
                }
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
        Config.log("[Hearing] showHearingWindow begin frameExists=" + (hearingFrame != null));
        try {
            if (hearingFrame == null) {
                Config.log("[Hearing] creating HearingFrame...");
                hearingFrame = new HearingFrame(prefs, this.imageInactive, this);
                Config.log("[Hearing] HearingFrame created");
            } else {
                Config.log("[Hearing] refreshing output devices...");
                hearingFrame.refreshOutputDevices();
            }
            Config.log("[Hearing] showWindow...");
            hearingFrame.showWindow();
            Config.log("[Hearing] showHearingWindow success");
        } catch (Throwable t) {
            logDetailedThrowable("[Hearing] showHearingWindow failed", t);
            if (hearingFrame != null && !hearingFrame.isDisplayable()) {
                hearingFrame = null;
            }
            throw t;
        }
    }
    private void logDetailedThrowable(String prefix, Throwable t) {
        Config.log("[ERROR] " + prefix + ": " + t);
        if (t == null) return;
        int count = 0;
        for (StackTraceElement ste : t.getStackTrace()) {
            Config.log("[ERROR]   at " + ste);
            count++;
            if (count >= 14) break;
        }
        Throwable cause = t.getCause();
        int depth = 0;
        while (cause != null && depth < 3) {
            Config.log("[ERROR] caused by: " + cause);
            int causeCount = 0;
            for (StackTraceElement ste : cause.getStackTrace()) {
                Config.log("[ERROR]   at " + ste);
                causeCount++;
                if (causeCount >= 10) break;
            }
            cause = cause.getCause();
            depth++;
        }
    }
    public void setHearingLanguage(String lang) {
        if (lang == null || lang.trim().isEmpty()) lang = "auto";
        String normalizedLang = lang.trim().toLowerCase(Locale.ROOT);
        try {
            if (prefs != null) prefs.put("hearing.lang", normalizedLang);
        } catch (Exception ignore) {}
        hearingMoonshineReloadSeq.incrementAndGet();
        hearingMoonshineAutoSessionLang = "auto".equalsIgnoreCase(normalizedLang)
                || LanguageOptions.HEARING_AUTO_STABLE.equalsIgnoreCase(normalizedLang)
                ? "en"
                : normalizedLang;
        hearingWhisperAutoSessionLang = "auto";
        hearingWhisperLastShiftMs = 0L;

        LocalWhisperCPP.markIgnoreDirty();
        // 既にHearing用Whisperが居れば即反映
        try {
            LocalWhisperCPP cur = wHearing;
            if (cur != null) cur.setLanguage(getHearingWhisperRuntimeLang());
        } catch (Exception ignore) {}

        try {
            LinkedHashMap<String, File> modelMap = scanMoonshineModelMap();
            File newModelDir = null;
            if (!"auto".equalsIgnoreCase(normalizedLang)) {
                for (Map.Entry<String, File> e : modelMap.entrySet()) {
                    String key = (e.getKey() == null) ? "" : e.getKey().trim();
                    if (key.equalsIgnoreCase(normalizedLang)
                            || key.toLowerCase(Locale.ROOT).startsWith(normalizedLang.toLowerCase(Locale.ROOT))) {
                        newModelDir = e.getValue();
                        break;
                    }
                }
            }
            if (newModelDir != null && prefs != null) {
                prefs.put("hearing.moonshine.model_path", newModelDir.getAbsolutePath());
                Config.log("[Hearing] moonshine model switched for lang=" + normalizedLang + " -> " + newModelDir.getAbsolutePath());
            } else if (prefs != null && ("auto".equalsIgnoreCase(normalizedLang)
                    || LanguageOptions.HEARING_AUTO_STABLE.equalsIgnoreCase(normalizedLang))) {
                prefs.put("hearing.moonshine.model_path", "");
                Config.log("[Hearing] moonshine model cleared for auto language");
            } else {
                Config.log("[Hearing] moonshine model path unchanged for lang=" + normalizedLang);
            }
        } catch (Exception ex) {
            Config.logError("[Hearing] failed to refresh moonshine model for language switch", ex);
        }

        hearingSem.acquireUninterruptibly();
        try {
            synchronized (hearingMoonshineLock) {
                if (hearingMoonshine != null) {
                    try { hearingMoonshine.close(); } catch (Throwable ignore) {}
                    hearingMoonshine = null;
                }
            }
        } finally {
            hearingSem.release();
        }
    }
    public void setHearingEngine(String engine) {
        String normalized = "moonshine".equalsIgnoreCase(engine) ? "moonshine" : "whisper";
        try {
            if (prefs != null) prefs.put("hearing.engine", normalized);
            try { prefs.sync(); } catch (Exception ignore) {}
        } catch (Exception ignore) {}
        hearingMoonshineReloadSeq.incrementAndGet();
        hearingMoonshineAutoSessionLang = "en";
        hearingWhisperAutoSessionLang = "auto";
        hearingWhisperLastShiftMs = 0L;

        synchronized (hearingWhisperLock) {
            wHearing = null;
            hw = null;
        }
        hearingSem.acquireUninterruptibly();
        try {
            synchronized (hearingMoonshineLock) {
                if (hearingMoonshine != null) {
                    try { hearingMoonshine.close(); } catch (Throwable ignore) {}
                    hearingMoonshine = null;
                }
            }
        } finally {
            hearingSem.release();
        }
        Config.log("[Hearing] engine switched to " + normalized);
    }
    public void setHearingAudioPrefilterModeForUi(String mode) {
        setHearingAudioPrefilterMode(mode);
    }
    public void setHearingTranslateToEn(boolean v) {
        setHearingTranslateTarget(v ? "EN" : "OFF");
    }
    public void setHearingTranslateTarget(String target) {
        target = LanguageOptions.normalizeTranslationTarget(target);
        try {
            if (prefs != null) {
                prefs.put("hearing.translate.target", target);
                prefs.putBoolean("hearing.translate_to_en", !"OFF".equals(target));
            }
            try { prefs.sync(); } catch (Exception ignore) {}
        } catch (Exception ignore) {}

        syncVoicegerTtsLangWithTalkOutput();
        if ("OFF".equals(target)) {
            synchronized (hearingTranslatorLock) {
                try {
                    if (hearingTranslator != null) hearingTranslator.unload();
                } catch (Throwable ignore) {}
                hearingTranslator = null;
                hearingTranslatorInitAttempted = false;
                hearingTranslatorUnavailableLogged = false;
            }
        }

        // HearingのWhisper本体翻訳は使わず、独立translatorに寄せる
        try {
            LocalWhisperCPP cur = wHearing;
            if (cur != null) cur.setHearingTranslateToEn(false);
        } catch (Exception ignore) {}
    }
    private final java.util.concurrent.Semaphore hearingSem = new java.util.concurrent.Semaphore(1);
    LocalWhisperCPP hw;
    public String transcribeHearingRaw(byte[] pcm16k16mono) {
        if (!hearingSem.tryAcquire()) return ""; // 混雑時は捨てる
        try {
            if (isHearingEngineMoonshine()) {
                LocalMoonshineSTT hm = getOrCreateHearingMoonshine();
                if (hm != null) {
                    float[] pcmFloat = pcm16leToFloat(pcm16k16mono, pcm16k16mono.length);
                    String moonText = removeCjkSpaces(hm.transcribeOneShot(pcmFloat)).trim();
                    if (!moonText.isBlank()) {
                        return moonText;
                    }
                    long fallbackCount = hearingMoonshineBlankFallbackCount.incrementAndGet();
                    Config.log("[Moonshine][Hearing] blank result -> fallback to Whisper (count=" + fallbackCount + ")");
                }
                Config.log("[Moonshine][Hearing] unavailable, fallback to Whisper");
            }
            if (hw == null) {
                hw = getOrCreateHearingWhisper();
            }
            if (hw == null) return "";
            String hearingText = removeCjkSpaces(hw.transcribeRawHearing(pcm16k16mono, Action.NOTHING_NO_SPEAK, this)).trim();
            return hearingText;
        } catch (Exception ex) {
            Config.logError("[Hearing] transcribe failed", ex);
            return "";
        } finally {
            hearingSem.release();
        }
    }
    private LocalMoonshineSTT getOrCreateHearingMoonshine() {
        LocalMoonshineSTT cur = hearingMoonshine;
        if (cur != null && cur.isLoaded()) return cur;
        synchronized (hearingMoonshineLock) {
            if (hearingMoonshine != null && hearingMoonshine.isLoaded()) return hearingMoonshine;

            String path = getHearingMoonshineModelPath();
            if (path == null || path.isBlank()) return null;
            String langForLog = isHearingAutoLanguage()
                    ? ("auto-session:" + getHearingMoonshineAutoBaseLang())
                    : getHearingSourceLang();
            LocalMoonshineSTT hm = loadHearingMoonshineModel(path, langForLog);
            if (hm == null) return null;
            hearingMoonshine = hm;
            return hearingMoonshine;
        }
    }

    // ★FIX: 長い無音後の発話頭を落とさないよう、前方トリムはかなり保守的にする。
    // short laugh after long silence gets ENERGY_REJECT because RMS is diluted by silence/preRoll.
    // Trim leading/trailing near-silence from 16-bit PCM (LE) before speaker gate/transcribe.
    static byte[] trimPcmSilence16le(byte[] pcm16le, AudioFormat fmt, int absThr, int padMs) {
        if (pcm16le == null || pcm16le.length < 4) return pcm16le;
        if (fmt == null) return pcm16le;

        // only handle mono 16-bit
        int bytesPerSample = 2;
        float srF = fmt.getSampleRate();
        int sampleRate = (srF > 0) ? (int) srF : 16000;

        int tailPadSamples = Math.max(0, (sampleRate * padMs) / 1000);
        int leadPadSamples = Math.max(tailPadSamples, (sampleRate * 500) / 1000);
        int totalSamples = pcm16le.length / bytesPerSample;

        int first = -1;
        int last = -1;

        for (int i = 0; i < totalSamples; i++) {
            int off = i * 2;
            short s = (short) (((pcm16le[off + 1] & 0xFF) << 8) | (pcm16le[off] & 0xFF));
            if (Math.abs((int) s) >= absThr) { first = i; break; }
        }
        if (first < 0) return pcm16le; // all silence-ish

        for (int i = totalSamples - 1; i >= 0; i--) {
            int off = i * 2;
            short s = (short) (((pcm16le[off + 1] & 0xFF) << 8) | (pcm16le[off] & 0xFF));
            if (Math.abs((int) s) >= absThr) { last = i; break; }
        }
        if (last < 0 || last <= first) return pcm16le;

        int start = Math.max(0, first - leadPadSamples);
        int end = Math.min(totalSamples - 1, last + tailPadSamples);

        int bStart = start * 2;
        int bEndExcl = (end + 1) * 2;
        if (bEndExcl <= bStart + 4) return pcm16le;

        // Safety: don't shrink too aggressively (keep at least ~120ms)
        int minBytes = (sampleRate * 120 / 1000) * 2;
        if ((bEndExcl - bStart) < minBytes && pcm16le.length >= minBytes) {
            return pcm16le;
        }
        if (bStart > 0 || bEndExcl < pcm16le.length) {
            int leadTrimMs = (first * 1000) / sampleRate;
            int keptLeadMs = ((first - start) * 1000) / sampleRate;
            int keptTailMs = ((end - last) * 1000) / sampleRate;
            Config.logDebug("★PCM trim: leadTrimMs=" + leadTrimMs
                    + " keptLeadMs=" + keptLeadMs
                    + " keptTailMs=" + keptTailMs
                    + " bytes=" + pcm16le.length + " -> " + (bEndExcl - bStart));
        }
        return java.util.Arrays.copyOfRange(pcm16le, bStart, bEndExcl);
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

        if (ignorePreloadPending || LocalWhisperCPP.isIgnoreExpansionBusy()) {
            gainMeter.setValue(0, -60.0, autoGainEnabledNow, autoGainMultiplier, userGain, false);
            return;
        }

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
    private void restoreFixedSizeWindowBounds(Window w, String prefix, Rectangle fallback) {
        if (w == null) return;
        if (fallback == null) {
            w.setLocationRelativeTo(null);
            return;
        }

        int x = prefs.getInt(k(prefix, "x"), Integer.MIN_VALUE);
        int y = prefs.getInt(k(prefix, "y"), Integer.MIN_VALUE);

        Rectangle target = new Rectangle(fallback);
        if (x != Integer.MIN_VALUE && y != Integer.MIN_VALUE) {
            Rectangle savedPos = new Rectangle(x, y, target.width, target.height);
            if (isOnAnyScreen(savedPos)) {
                w.setBounds(savedPos);
                return;
            }
        }

        w.setBounds(target);
        if (!isOnAnyScreen(w.getBounds())) {
            w.setLocationRelativeTo(null);
        }
    }
    private void normalizeUtilityWindowBoundsOnce(Window w, String prefix, Rectangle fallback) {
        if (w == null || fallback == null) return;
        String fixedKey = k(prefix, "autofit_reset_done");
        if (prefs.getBoolean(fixedKey, false)) return;

        Rectangle b = w.getBounds();
        int width = Math.min(b.width, fallback.width);
        int height = Math.min(b.height, fallback.height);
        if (width != b.width || height != b.height) {
            w.setBounds(b.x, b.y, width, height);
            prefs.putInt(k(prefix, "x"), b.x);
            prefs.putInt(k(prefix, "y"), b.y);
            prefs.putInt(k(prefix, "w"), width);
            prefs.putInt(k(prefix, "h"), height);
        }
        prefs.putBoolean(fixedKey, true);
        try { prefs.sync(); } catch (Exception ignore) {}
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
    private void installLocationSaver(Window w, String prefix) {
        w.addComponentListener(new ComponentAdapter() {
            private long lastSaveMs = 0;

            private void save() {
                long now = System.currentTimeMillis();
                if (now - lastSaveMs < 400) return;
                lastSaveMs = now;

                Point p = w.getLocation();
                prefs.putInt(k(prefix, "x"), p.x);
                prefs.putInt(k(prefix, "y"), p.y);
                try { prefs.sync(); } catch (Exception ignore) {}
            }

            @Override public void componentMoved(ComponentEvent e) { save(); }
        });
    }

    private void pinMainButtonWidth(JButton btn) {
        if (btn == null) return;
        FontMetrics fm = btn.getFontMetrics(btn.getFont());
        Insets insets = new Insets(0, 6, 0, 6);
        btn.setMargin(insets);
        int widest = 0;
        String[] labels = {
                UiText.t("ui.main.start"),
                UiText.t("ui.main.stop"),
                "[CALIBRATING]",
                "Starting..."
        };
        for (String label : labels) {
            if (label == null) continue;
            widest = Math.max(widest, fm.stringWidth(label));
        }
        int fontExtra = Math.max(0, Math.round(btn.getFont().getSize2D()) - 16) * 2;
        int hPad = 6 + Math.max(0, insets.left) + Math.max(0, insets.right) + fontExtra;
        int vPad = Math.max(6, Math.max(0, insets.top) + Math.max(0, insets.bottom));
        int width = Math.max(72, widest + hPad);
        int height = Math.max(btn.getPreferredSize().height, fm.getHeight() + vPad);
        Dimension fixed = new Dimension(width, height);
        btn.setPreferredSize(fixed);
        btn.setMinimumSize(fixed);
        btn.setMaximumSize(fixed);
    }
    private void compactTitleButton(AbstractButton btn, String... labels) {
        if (btn == null) return;
        Insets insets = new Insets(0, 6, 0, 6);
        btn.setMargin(insets);
        FontMetrics fm = btn.getFontMetrics(btn.getFont());
        int widest = 0;
        for (String label : labels) {
            if (label == null || label.isBlank()) continue;
            widest = Math.max(widest, fm.stringWidth(label));
        }
        if (widest <= 0) widest = fm.stringWidth(btn.getText());
        int fontExtra = Math.max(0, Math.round(btn.getFont().getSize2D()) - 16) * 2;
        int width = Math.max(42, widest + insets.left + insets.right + 6 + fontExtra);
        int height = Math.max(22, fm.getHeight() + 6);
        Dimension size = new Dimension(width, height);
        btn.setPreferredSize(size);
        btn.setMinimumSize(size);
        btn.setMaximumSize(size);
    }
    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
    private int calcPresetComboWidth(JComboBox<String> combo) {
        if (combo == null) return 112;
        FontMetrics fm = combo.getFontMetrics(combo.getFont());
        int widest = fm.stringWidth(presetPlaceholderText());
        for (int i = 0; i < combo.getItemCount(); i++) {
            Object item = combo.getItemAt(i);
            widest = Math.max(widest, fm.stringWidth(Objects.toString(item, "")));
        }
        int fontFloor = Math.round(combo.getFont().getSize2D() * 6.0f);
        return clampInt(Math.max(widest + 42, fontFloor), 96, 180);
    }
    private void applyCompactPresetSizing(JComboBox<String> combo, JButton addBtn, JButton delBtn, JPanel group) {
        if (combo == null || addBtn == null || delBtn == null || group == null) return;
        int comboW = calcPresetComboWidth(combo);
        int btnH = clampInt(Math.round(combo.getFont().getSize2D() + 10f), 18, 26);
        int iconW = clampInt(Math.round(combo.getFont().getSize2D() + 2f), 16, 22);
        Dimension iconSize = new Dimension(iconW, btnH - 2);
        addBtn.setPreferredSize(iconSize);
        addBtn.setMinimumSize(iconSize);
        addBtn.setMaximumSize(iconSize);
        delBtn.setPreferredSize(iconSize);
        delBtn.setMinimumSize(iconSize);
        delBtn.setMaximumSize(iconSize);

        Dimension comboSize = new Dimension(comboW, btnH);
        combo.setPreferredSize(comboSize);
        combo.setMinimumSize(new Dimension(Math.min(96, comboW), btnH));
        combo.setMaximumSize(comboSize);

        int groupW = comboW + iconSize.width * 2 + 18;
        int groupH = btnH + 2;
        Dimension groupSize = new Dimension(groupW, groupH);
        group.setPreferredSize(groupSize);
        group.setMinimumSize(new Dimension(groupW, groupH));
        group.setMaximumSize(groupSize);
    }


    private static String uiOr(String key, String fallback) {
        try {
            String v = UiText.t(key);
            if (v == null || v.isBlank() || key.equals(v)) return fallback;
            return v;
        } catch (Throwable t) {
            return fallback;
        }
    }
    private static String presetPlaceholderText() {
        return uiOr("ui.main.preset.placeholder", "Preset");
    }
    private void setHistoryStartupPref(boolean open) {
        prefs.putBoolean("ui.history.open_on_startup", open);
        try { prefs.sync(); } catch (Exception ignore) {}
    }
    public void openSettingsCenter() {
        SwingUtilities.invokeLater(() -> {
            try {
                if (settingsCenterFrame != null) {
                    if (settingsCenterFrame.isDisplayable()) {
                        settingsCenterFrame.toFront();
                        settingsCenterFrame.requestFocus();
                        return;
                    }
                    settingsCenterFrame = null;
                }

                final MobMateSettingsFrame dlg = new MobMateSettingsFrame(window, this);

                dlg.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosed(WindowEvent e) {
                        if (settingsCenterFrame == dlg) {
                            settingsCenterFrame = null;
                        }
                    }
                });

                Rectangle fb;
                if (window != null) {
                    fb = new Rectangle(window.getX() + 20, window.getY() + 70, 1080, 760);
                } else {
                    fb = new Rectangle(40, 40, 1080, 760);
                }

                restoreBounds(dlg, "ui.settings", fb);
                normalizeUtilityWindowBoundsOnce(dlg, "ui.settings", fb);
                installBoundsSaver(dlg, "ui.settings");

                settingsCenterFrame = dlg;
                dlg.setVisible(true);
            } catch (Throwable ex) {
                settingsCenterFrame = null;
                Config.logError("[SettingsCenter] open failed", ex);

                JOptionPane.showMessageDialog(
                        window,
                        "Failed to open Settings Center.\n"
                                + ex.getClass().getSimpleName()
                                + (ex.getMessage() != null ? ": " + ex.getMessage() : ""),
                        "MobMate",
                        JOptionPane.ERROR_MESSAGE
                );
            }
        });
    }
    public List<String> getWindowsVoicesForSettings() {
        return getWindowsVoices();
    }
    public List<VoiceVoxSpeaker> getVoiceVoxSpeakersForSettings() {
        return getVoiceVoxSpeakers();
    }
    public void requestSoftRestartForSettings() {
        softRestart();
    }
    public void showHistory() {
        if (historyFrame != null && historyFrame.isShowing()) {
            setHistoryStartupPref(true);
            historyFrame.refreshTalkControls();
            historyFrame.refreshConfirmControls();
            historyFrame.toFront();
            historyFrame.requestFocus();
            return;
        }

        historyFrame = new HistoryFrame(this);
        historyFrame.refreshTalkControls();
        historyFrame.refreshConfirmControls();
        historyFrame.setMinimumSize(new Dimension(510, 200));
        historyFrame.setSize(510, 400);
        SwingUtilities.updateComponentTreeUI(historyFrame);

        Rectangle fb;
        if (window != null) {
            fb = new Rectangle(window.getX() + 15, window.getY() + 80, 510, 400);
        } else {
            fb = new Rectangle(15, 80, 510, 400);
        }
        restoreBounds(historyFrame, "ui.history", fb);
        normalizeUtilityWindowBoundsOnce(historyFrame, "ui.history", fb);
        installBoundsSaver(historyFrame, "ui.history");

        historyFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (suppressHistoryCloseCallbacks) return;
                setHistoryStartupPref(false);
            }

            @Override
            public void windowClosed(WindowEvent e) {
                if (suppressHistoryCloseCallbacks) return;
                setHistoryStartupPref(false);
                historyFrame = null;
            }
        });

        historyFrame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentHidden(ComponentEvent e) {
                if (suppressHistoryCloseCallbacks) return;
                setHistoryStartupPref(false);
            }

            @Override
            public void componentShown(ComponentEvent e) {
                setHistoryStartupPref(true);
            }
        });

        historyFrame.refresh();
        historyFrame.refreshConfirmControls();
        historyFrame.setVisible(true);
        setHistoryStartupPref(true);

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
        speak(text, null);
    }
    public void speak(String text, TtsProsodyProfile ttsProsodyProfile) {
        if (text == null || text.trim().isEmpty()) return;
        markTtsStartNowIfThisFinal(text, System.currentTimeMillis());
        text = normalizeLaugh(text);
        text = Config.applyDictionary(text);
        TtsProsodyProfile effectiveProsodyProfile =
                prefs.getBoolean("tts.reflect_emotion", true) ? ttsProsodyProfile : null;

        String engine = prefs.get("tts.engine", "auto").toLowerCase(Locale.ROOT);

        // --- forced route ---
        if ("voiceger_tts".equals(engine)) {
            speakVoiceger(text);      // 今の実装をそのまま：/tts優先→VCフォールバック
            return;
        }
        if ("voiceger_vc".equals(engine) || "voiceger".equals(engine)) {
            speakVoicegerVcOnly(text); // VCのみ
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

                speakVoiceVox(text, String.valueOf(styleId), api, effectiveProsodyProfile);
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
        if ("piper_plus".equals(engine)) {
            speakPiperPlus(text, effectiveProsodyProfile);
            return;
        }
        if ("windows".equals(engine)) {
            speakWindows(text, effectiveProsodyProfile);
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

            speakVoiceVox(text, String.valueOf(styleId), api, effectiveProsodyProfile);
        } else if (isXttsAvailable()) {
            try { speakXtts(text); } catch (Exception ignore) {}
        } else {
            speakWindows(text, effectiveProsodyProfile);
        }
    }

    private void speakPiperPlus(String text, TtsProsodyProfile ttsProsodyProfile) {
        String modelId = prefs.get("piper.plus.model_id", "").trim();
        PiperPlusCatalog.Entry entry = PiperPlusCatalog.resolveForLanguage(modelId, getTalkOutputLanguage());
        if (entry == null) {
            Config.log("[Piper+] no model selected, fallback to Windows TTS");
            speakWindows(text, ttsProsodyProfile);
            return;
        }
        boolean installed = PiperPlusModelManager.isInstalled(entry);
        Config.log("[Piper+] resolve output=" + getTalkOutputLanguage()
                + " modelId=" + modelId
                + " resolved=" + entry.id()
                + " installKey=" + entry.installKey()
                + " installed=" + installed
                + " cliLang=" + entry.cliTextLanguage());
        if (isKnownUnstableBuiltInPiperPlusEntry(entry)) {
            notifyKnownPiperPlusCompatibilityIssue(window, entry);
            Config.log("[Piper+][Compat] fallback to Windows TTS for unstable entry=" + entry.id());
            speakWindows(text, ttsProsodyProfile);
            return;
        }
        if (!installed) {
            if (ensurePiperPlusModelReady(entry, window)) {
                Config.log("[Piper+] model downloaded on demand: " + entry.id());
            } else {
                Config.log("[Piper+] model not installed: " + entry.id() + ", fallback to Windows TTS");
                speakWindows(text, ttsProsodyProfile);
                return;
            }
        }
        if (!PiperPlusModelManager.isInstalled(entry)) {
            Config.log("[Piper+] model not installed: " + modelId + ", fallback to Windows TTS");
            speakWindows(text, ttsProsodyProfile);
            return;
        }
        Path exePath = PiperPlusModelManager.findBundledExe();
        if (exePath == null) {
            Config.log("[Piper+] runtime not bundled, fallback to Windows TTS");
            speakWindows(text, ttsProsodyProfile);
            return;
        }
        try {
            PiperSynthesisOptions options = buildPiperSynthesisOptions(entry, text, ttsProsodyProfile);
            schedulePiperPlusEmotionFamilyWarmup(
                    exePath,
                    entry,
                    getTalkOutputLanguage(),
                    options,
                    ttsProsodyProfile,
                    "runtime-family");
            PiperPlusPersistentSessionManager.SynthesisResult session = synthPiperPlusViaPersistentSession(exePath, entry, text, options);
            if (session != null && session.wavPath() != null && session.wavBytes() >= 256) {
                Config.logDebug("[Piper+][SESSION] key=" + session.sessionKey()
                        + " reused=" + session.reused()
                        + " boot_ms=" + session.bootMs()
                        + " synth_wait_ms=" + session.synthWaitMs()
                        + " wav_bytes=" + session.wavBytes());
                playViaPowerShellPathAsync(maybePostProcessPiperWavPath(session.wavPath(), ttsProsodyProfile), true);
                return;
            }
            Path tempWav = synthPiperPlusToTempWavFile(exePath, entry, text, options);
            if (tempWav != null && Files.isRegularFile(tempWav)) {
                try {
                    long wavBytes = Files.size(tempWav);
                    Config.logDebug("[Piper+] route=temp_wav_path language=" + entry.cliTextLanguage()
                            + " wav_bytes=" + wavBytes
                            + " lenScale=" + String.format(Locale.ROOT, "%.3f", options.lengthScale())
                            + " sentSil=" + String.format(Locale.ROOT, "%.3f", options.sentenceSilence())
                            + " noise=" + String.format(Locale.ROOT, "%.3f", options.noiseScale())
                            + " noiseW=" + String.format(Locale.ROOT, "%.3f", options.noiseW()));
                } catch (Exception ignore) {}
                playViaPowerShellPathAsync(maybePostProcessPiperWavPath(tempWav, ttsProsodyProfile), true);
                return;
            }
            PiperRawSynthesis raw = synthPiperPlusRaw(exePath, entry, text, options);
            if (raw != null && raw.pcmBytes() != null && raw.pcmBytes().length >= 128) {
                Config.logDebug("[Piper+] route=stdout_raw sr=" + raw.sampleRate()
                        + " bytes=" + raw.pcmBytes().length
                        + " spawn_ms=" + raw.spawnMs()
                        + " raw_read_ms=" + raw.rawReadMs()
                        + " language=" + entry.cliTextLanguage()
                        + " lenScale=" + String.format(Locale.ROOT, "%.3f", raw.lengthScale())
                        + " sentSil=" + String.format(Locale.ROOT, "%.3f", raw.sentenceSilence())
                        + " noise=" + String.format(Locale.ROOT, "%.3f", raw.noiseScale())
                        + " noiseW=" + String.format(Locale.ROOT, "%.3f", raw.noiseW()));
                byte[] postPcm = maybePostProcessPiperPcm16Mono(raw.pcmBytes(), raw.sampleRate(), ttsProsodyProfile);
                playViaPowerShellPcm16MonoAsync(postPcm, raw.sampleRate(), raw.spawnMs(), raw.rawReadMs());
                return;
            }
            byte[] wavBytes = synthPiperPlusToWavBytes(exePath, entry, text, ttsProsodyProfile);
            if (wavBytes != null && wavBytes.length >= 256) {
                playViaPowerShellBytesAsync(maybePostProcessPiperWavBytes(wavBytes, ttsProsodyProfile));
                return;
            }
        } catch (Exception ex) {
            Config.log("[Piper+] synth failed: " + ex.getMessage());
        }
        speakWindows(text, ttsProsodyProfile);
    }

    private boolean ensurePiperPlusModelReady(PiperPlusCatalog.Entry entry, Component parent) {
        if (entry == null) return false;
        if (PiperPlusModelManager.isInstalled(entry)) return true;
        if (!entry.isDownloadable()) return false;

        long now = System.currentTimeMillis();
        Long declinedUntil = piperPlusDownloadDeclinedUntil.get(entry.id());
        if (declinedUntil != null && declinedUntil > now) {
            return false;
        }

        AtomicReference<Integer> answerRef = new AtomicReference<>(JOptionPane.NO_OPTION);
        Runnable ask = () -> {
            String target = LanguageOptions.displayTranslationTarget(entry.language());
            String message = "Piper+ model is not downloaded yet.\n\n"
                    + entry.displayName() + "\n"
                    + "Target language: " + target + "\n\n"
                    + "Download it now?";
            int answer = JOptionPane.showConfirmDialog(
                    parent != null ? parent : window,
                    message,
                    "MobMate",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
            );
            answerRef.set(answer);
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                ask.run();
            } else {
                SwingUtilities.invokeAndWait(ask);
            }
        } catch (Exception ex) {
            Config.log("[Piper+] download prompt failed: " + ex.getMessage());
            return false;
        }

        if (answerRef.get() != JOptionPane.YES_OPTION) {
            piperPlusDownloadDeclinedUntil.put(entry.id(), now + PIPER_PLUS_DOWNLOAD_DECLINE_COOLDOWN_MS);
            return false;
        }

        try {
            Config.log("[Piper+] downloading on demand: " + entry.id());
            PiperPlusModelManager.ensureDownloaded(entry);
            return PiperPlusModelManager.isInstalled(entry);
        } catch (Exception ex) {
            Config.log("[Piper+] on-demand download failed: " + ex.getMessage());
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    parent != null ? parent : window,
                    "Failed to download Piper+ model:\n" + ex.getMessage(),
                    "MobMate",
                    JOptionPane.WARNING_MESSAGE
            ));
            return false;
        }
    }

    private PiperPlusPersistentSessionManager.SynthesisResult synthPiperPlusViaPersistentSession(Path exePath,
                                                                                                 PiperPlusCatalog.Entry entry,
                                                                                                 String text,
                                                                                                 PiperSynthesisOptions options) {
        if (!prefs.getBoolean("piper.plus.persistent", true)) return null;
        PiperSynthesisOptions persistent = toPersistentSessionOptions(options);
        try {
            return piperPlusSessionManager.synth(
                    exePath,
                    entry,
                    text,
                    new PiperPlusPersistentSessionManager.SynthesisOptions(
                            persistent.sampleRate(),
                            persistent.lengthScaleArg(),
                            persistent.sentenceSilenceArg(),
                            persistent.noiseScaleArg(),
                            persistent.noiseWArg()));
        } catch (Exception ex) {
            Config.logDebug("[Piper+][SESSION] fallback to one-shot spawn: " + ex.getMessage());
            return null;
        }
    }

    private byte[] synthPiperPlusToWavBytes(Path exePath,
                                            PiperPlusCatalog.Entry entry,
                                            String text,
                                            TtsProsodyProfile profile) throws Exception {
        try {
            return synthPiperPlusToWavBytesRaw(exePath, entry, text, profile);
        } catch (Exception rawEx) {
            Config.logDebug("[Piper+] raw stdout path failed -> temp wav fallback: " + rawEx.getMessage());
            Path tempWav = synthPiperPlusToTempWavFile(exePath, entry, text, buildPiperSynthesisOptions(entry, text, profile));
            if (tempWav == null || !Files.isRegularFile(tempWav)) {
                throw new IOException("PiperPlus did not create temp wav");
            }
            try {
                return Files.readAllBytes(tempWav);
            } finally {
                try { Files.deleteIfExists(tempWav); } catch (Exception ignore) {}
            }
        }
    }

    private record PiperSynthesisOptions(int sampleRate,
                                         float lengthScale,
                                         float sentenceSilence,
                                         float noiseScale,
                                         float noiseW,
                                         String lengthScaleArg,
                                         String sentenceSilenceArg,
                                         String noiseScaleArg,
                                         String noiseWArg) {}

    private record PiperRawSynthesis(byte[] pcmBytes,
                                     int sampleRate,
                                     long spawnMs,
                                     long rawReadMs,
                                     float lengthScale,
                                     float sentenceSilence,
                                     float noiseScale,
                                     float noiseW) {}

    private PiperSynthesisOptions buildPiperSynthesisOptions(PiperPlusCatalog.Entry entry,
                                                             String text,
                                                             TtsProsodyProfile profile) {
        int sampleRate = PiperPlusModelManager.getSampleRate(entry);
        float lengthScale = piperLengthScale(text, profile);
        float sentenceSilence = piperSentenceSilence(text, profile);
        float noiseScale = piperNoiseScale(profile);
        float noiseW = piperNoiseW(profile);
        PiperSynthesisOptions options = new PiperSynthesisOptions(
                sampleRate,
                lengthScale,
                sentenceSilence,
                noiseScale,
                noiseW,
                String.format(Locale.ROOT, "%.3f", lengthScale),
                String.format(Locale.ROOT, "%.3f", sentenceSilence),
                String.format(Locale.ROOT, "%.3f", noiseScale),
                String.format(Locale.ROOT, "%.3f", noiseW)
        );
        logPiperProsodyOptions(text, profile, options);
        return options;
    }

    private void logPiperProsodyOptions(String text,
                                        TtsProsodyProfile profile,
                                        PiperSynthesisOptions options) {
        float contourPref = getPiperContourReflectionStrength();
        float tonePref = getPiperToneEmphasisStrength();
        String contourMode = prefs.get("tts.reflect.contour_strength", "normal");
        String toneMode = prefs.get("tts.reflect.tone_emphasis", "normal");
        if (profile == null || !profile.isUsable()) {
            Config.logDebug("[TTS_PROSODY][PIPER+] no-conf text=" + shortenForProsodyLog(text, 48)
                    + " contourMode=" + contourMode
                    + " toneMode=" + toneMode
                    + " contourPref=" + String.format(Locale.ROOT, "%.2f", contourPref)
                    + " tonePref=" + String.format(Locale.ROOT, "%.2f", tonePref)
                    + " pitchDirect=false");
            return;
        }
        float sourceTimingSec = estimateSourceTimingDurationSec(profile);
        int units = estimateSpeechUnits(text);
        float msPerUnit = (sourceTimingSec > 0.0f && units > 0)
                ? (sourceTimingSec * 1000.0f) / units
                : 0.0f;
        float derivedSpeed = inferPiperSpeedScale(text, profile);
        Config.logDebug("[TTS_PROSODY][PIPER+] mood=" + profile.mood()
                + " speed=" + String.format(Locale.ROOT, "%.3f", profile.speedScale())
                + " pitch=" + String.format(Locale.ROOT, "%.3f", profile.pitchScale())
                + " intonation=" + String.format(Locale.ROOT, "%.3f", profile.intonationScale())
                + " volume=" + String.format(Locale.ROOT, "%.3f", profile.volumeScale())
                + " conf=" + String.format(Locale.ROOT, "%.2f", profile.confidence())
                + " rise=" + String.format(Locale.ROOT, "%.2f", profile.contourRise())
                + " melody=" + String.format(Locale.ROOT, "%.2f", profile.melodyDepth())
                + " dark=" + String.format(Locale.ROOT, "%.2f", profile.darkScore())
                + " energy=" + String.format(Locale.ROOT, "%.2f", profile.energyScore())
                + " sourceSec=" + String.format(Locale.ROOT, "%.3f", sourceTimingSec)
                + " msPerUnit=" + String.format(Locale.ROOT, "%.1f", msPerUnit)
                + " derivedSpeed=" + String.format(Locale.ROOT, "%.3f", derivedSpeed)
                + " lengthScale=" + options.lengthScaleArg()
                + " sentenceSilence=" + options.sentenceSilenceArg()
                + " noiseScale=" + options.noiseScaleArg()
                + " noiseW=" + options.noiseWArg()
                + " contourMode=" + contourMode
                + " toneMode=" + toneMode
                + " contourPref=" + String.format(Locale.ROOT, "%.2f", contourPref)
                + " tonePref=" + String.format(Locale.ROOT, "%.2f", tonePref)
                + " pitchDirect=false"
                + " text=" + shortenForProsodyLog(text, 48));
    }

    private PiperSynthesisOptions toPersistentSessionOptions(PiperSynthesisOptions options) {
        float lengthScale = roundToStep(options.lengthScale(), 0.20f, 0.68f, 1.45f);
        float sentenceSilence = canonicalPersistentSentenceSilence(options.sentenceSilence());
        // Keep persistent-session noise knobs nearly fixed so warm sessions are reused more often.
        // Bright/dark character is still handled mainly by the post-process tone shaping path.
        float noiseScale = 0.65f;
        float noiseW = 0.85f;
        return new PiperSynthesisOptions(
                options.sampleRate(),
                lengthScale,
                sentenceSilence,
                noiseScale,
                noiseW,
                String.format(Locale.ROOT, "%.3f", lengthScale),
                String.format(Locale.ROOT, "%.3f", sentenceSilence),
                String.format(Locale.ROOT, "%.3f", noiseScale),
                String.format(Locale.ROOT, "%.3f", noiseW)
        );
    }

    private float canonicalPersistentSentenceSilence(float value) {
        float clamped = clampVoiceVoxProsody(value, 0.06f, 0.44f);
        if (clamped <= 0.12f) return 0.08f;
        if (clamped <= 0.22f) return 0.16f;
        return 0.32f;
    }

    private float roundToStep(float value, float step, float min, float max) {
        if (step <= 0f) return clampVoiceVoxProsody(value, min, max);
        float rounded = Math.round(value / step) * step;
        return clampVoiceVoxProsody(rounded, min, max);
    }

    private PiperRawSynthesis synthPiperPlusRaw(Path exePath,
                                                PiperPlusCatalog.Entry entry,
                                                String text,
                                                PiperSynthesisOptions options) throws Exception {

        ArrayList<String> cmd = new ArrayList<>();
        cmd.add(exePath.toAbsolutePath().toString());
        cmd.add("--model");
        cmd.add(PiperPlusModelManager.getModelFile(entry).toAbsolutePath().toString());
        cmd.add("--config");
        cmd.add(PiperPlusModelManager.getConfigFile(entry).toAbsolutePath().toString());
        cmd.add("--output_raw");
        cmd.add("--language");
        cmd.add(entry.cliTextLanguage());
        cmd.add("--speaker");
        cmd.add("0");
        cmd.add("--length-scale");
        cmd.add(options.lengthScaleArg());
        cmd.add("--sentence-silence");
        cmd.add(options.sentenceSilenceArg());
        cmd.add("--noise-scale");
        cmd.add(options.noiseScaleArg());
        cmd.add("--noise-w");
        cmd.add(options.noiseWArg());
        cmd.add("--text");
        cmd.add(text);
        cmd.add("--quiet");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(exePath.getParent().toFile());
        pb.redirectErrorStream(false);
        long spawnStartNs = System.nanoTime();
        Process process = pb.start();
        long spawnMs = (System.nanoTime() - spawnStartNs) / 1_000_000L;

        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> {
            try (InputStream err = process.getErrorStream()) {
                return new String(err.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                return "";
            }
        });

        byte[] pcmBytes;
        long readStartNs = System.nanoTime();
        try (InputStream in = process.getInputStream()) {
            pcmBytes = in.readAllBytes();
        }
        long rawReadMs = (System.nanoTime() - readStartNs) / 1_000_000L;

        int exit = process.waitFor();
        String stderr = "";
        try {
            stderr = stderrFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception ignore) {}
        if (exit != 0) {
            throw new IOException("PiperPlus raw exit=" + exit + " err=" + stderr);
        }
        if (pcmBytes == null || pcmBytes.length < 128) {
            throw new IOException("PiperPlus raw stdout empty");
        }
        return new PiperRawSynthesis(
                pcmBytes,
                options.sampleRate(),
                spawnMs,
                rawReadMs,
                options.lengthScale(),
                options.sentenceSilence(),
                options.noiseScale(),
                options.noiseW());
    }

    private byte[] synthPiperPlusToWavBytesRaw(Path exePath,
                                               PiperPlusCatalog.Entry entry,
                                               String text,
                                               TtsProsodyProfile profile) throws Exception {
        PiperRawSynthesis raw = synthPiperPlusRaw(exePath, entry, text, buildPiperSynthesisOptions(entry, text, profile));
        return wrapPcm16MonoAsWav(raw.pcmBytes(), raw.sampleRate());
    }

    private Path synthPiperPlusToTempWavFile(Path exePath,
                                             PiperPlusCatalog.Entry entry,
                                             String text,
                                             PiperSynthesisOptions options) throws Exception {
        Path modelPath = PiperPlusModelManager.getModelFile(entry);
        Path configPath = PiperPlusModelManager.getConfigFile(entry);
        Path tempOut = Files.createTempFile("piper_plus_", ".wav");
        try {
            ArrayList<String> cmd = new ArrayList<>();
            cmd.add(exePath.toAbsolutePath().toString());
            cmd.add("--model");
            cmd.add(modelPath.toAbsolutePath().toString());
            cmd.add("--config");
            cmd.add(configPath.toAbsolutePath().toString());
            cmd.add("--output_file");
            cmd.add(tempOut.toAbsolutePath().toString());
            cmd.add("--language");
            cmd.add(entry.cliTextLanguage());
            cmd.add("--speaker");
            cmd.add("0");
            cmd.add("--length-scale");
            cmd.add(options.lengthScaleArg());
            cmd.add("--sentence-silence");
            cmd.add(options.sentenceSilenceArg());
            cmd.add("--noise-scale");
            cmd.add(options.noiseScaleArg());
            cmd.add("--noise-w");
            cmd.add(options.noiseWArg());
            cmd.add("--text");
            cmd.add(text);
            cmd.add("--quiet");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(exePath.getParent().toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("PiperPlus exit=" + exit + " out=" + output);
            }
            if (!Files.isRegularFile(tempOut)) {
                throw new IOException("PiperPlus did not create wav");
            }
            return tempOut;
        } finally {
            if (!Files.isRegularFile(tempOut)) {
                try { Files.deleteIfExists(tempOut); } catch (Exception ignore) {}
            }
        }
    }

    private float piperLengthScale(String text, TtsProsodyProfile profile) {
        if (profile == null) return 1.0f;
        float speed = Math.max(0.64f, Math.min(1.40f, inferPiperSpeedScale(text, profile)));
        float lengthScale = 1.0f / speed;
        float timingSec = estimateSourceTimingDurationSec(profile);
        int units = estimateSpeechUnits(text);
        if (timingSec > 0.18f && units >= 3) {
            float msPerUnit = (timingSec * 1000.0f) / Math.max(1, units);
            float pacingAssist = clampVoiceVoxProsody((msPerUnit - 112.0f) / 150.0f, -0.12f, 0.18f);
            lengthScale += pacingAssist;
        }
        return Math.max(0.68f, Math.min(1.45f, lengthScale));
    }

    private float piperSentenceSilence(String text, TtsProsodyProfile profile) {
        if (profile == null) return 0.20f;
        float speed = Math.max(0.68f, Math.min(1.40f, inferPiperSpeedScale(text, profile)));
        float dark = Math.max(0.0f, Math.min(1.0f, profile.darkScore()));
        float energy = Math.max(0.0f, Math.min(1.0f, profile.energyScore()));
        float pause = 0.19f + (dark * 0.12f) - (energy * 0.06f) + ((1.0f / speed) - 1.0f) * 0.12f;
        if (profile.hasTimingContour()) {
            pause += 0.02f;
        }
        float sourceTimingSec = estimateSourceTimingDurationSec(profile);
        int units = estimateSpeechUnits(text);
        if (sourceTimingSec > 0.18f && units >= 4) {
            float msPerUnit = (sourceTimingSec * 1000.0f) / Math.max(1, units);
            pause += clampVoiceVoxProsody((msPerUnit - 112.0f) / 320.0f, -0.04f, 0.07f);
        }
        return Math.max(0.06f, Math.min(0.44f, pause));
    }

    private float inferPiperSpeedScale(String text, TtsProsodyProfile profile) {
        if (profile == null) return 1.0f;
        float inferred = inferWindowsNarratorSpeedScale(text, profile);
        float base = 1.0f + ((inferred - 1.0f) * 0.55f);
        float contourAssist = clampVoiceVoxProsody(profile.contourRise() * 0.05f, -0.04f, 0.04f);
        float energyAssist = clampVoiceVoxProsody((profile.energyScore() - profile.darkScore()) * 0.10f, -0.06f, 0.06f);
        float melodyAssist = clampVoiceVoxProsody((profile.melodyDepth() - 0.45f) * 0.05f, -0.03f, 0.03f);
        float timingAssist = 0.0f;
        float sourceTimingSec = estimateSourceTimingDurationSec(profile);
        int units = estimateSpeechUnits(text);
        if (sourceTimingSec > 0.18f && units >= 3) {
            float msPerUnit = (sourceTimingSec * 1000.0f) / Math.max(1, units);
            timingAssist = clampVoiceVoxProsody((108.0f - msPerUnit) / 150.0f, -0.16f, 0.16f);
        }
        return clampVoiceVoxProsody(base + contourAssist + energyAssist + melodyAssist + timingAssist, 0.64f, 1.40f);
    }

    private Path maybePostProcessPiperWavPath(Path wavPath, TtsProsodyProfile profile) {
        if (wavPath == null || !Files.isRegularFile(wavPath) || profile == null || !profile.isUsable()) return wavPath;
        try {
            byte[] wavBytes = Files.readAllBytes(wavPath);
            byte[] processed = maybePostProcessPiperWavBytes(wavBytes, profile);
            if (processed != wavBytes) {
                Files.write(wavPath, processed, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            }
        } catch (Exception ex) {
            Config.logDebug("[Piper+][POST] wav path skipped: " + ex.getMessage());
        }
        return wavPath;
    }

    private byte[] maybePostProcessPiperWavBytes(byte[] wavBytes, TtsProsodyProfile profile) {
        if (wavBytes == null || wavBytes.length < 64 || profile == null || !profile.isUsable()) return wavBytes;
        WavPcmView wav = tryParsePcm16MonoWav(wavBytes);
        if (wav == null) return wavBytes;
        byte[] processedPcm = maybePostProcessPiperPcm16Mono(wav.pcmBytes(), wav.sampleRate(), profile);
        if (processedPcm == wav.pcmBytes()) return wavBytes;
        byte[] out = Arrays.copyOf(wavBytes, wavBytes.length);
        System.arraycopy(processedPcm, 0, out, wav.dataOffset(), processedPcm.length);
        return out;
    }

    private byte[] maybePostProcessPiperPcm16Mono(byte[] pcmBytes, int sampleRate, TtsProsodyProfile profile) {
        if (pcmBytes == null || pcmBytes.length < 8 || sampleRate < 8000 || profile == null || !profile.isUsable()) {
            return pcmBytes;
        }
        float contourPref = getPiperContourReflectionStrength();
        float tonePref = getPiperToneEmphasisStrength();
        float tone = derivePiperToneColor(profile);
        if (Math.abs(tone) < 0.08f) return pcmBytes;

        byte[] out = Arrays.copyOf(pcmBytes, pcmBytes.length);
        float cutoffHz = 1500.0f + (Math.abs(tone) * 1100.0f);
        float dt = 1.0f / Math.max(8000, sampleRate);
        float rc = 1.0f / (2.0f * (float) Math.PI * cutoffHz);
        float alpha = clampVoiceVoxProsody(dt / (rc + dt), 0.03f, 0.40f);
        float low = 0.0f;
        float prevInput = 0.0f;
        float brightMix = (tone >= 0.0f)
                ? (1.20f + (tone * 1.40f))
                : (0.10f + ((1.0f + tone) * 0.18f));
        float darkMix = (tone >= 0.0f)
                ? (0.78f - (tone * 0.20f))
                : (1.15f + ((-tone) * 0.55f));
        float melodicLift = Math.max(0.0f, profile.melodyDepth() - 0.45f) * 0.18f * contourPref;
        float presence = Math.max(0.0f, tone) * (0.48f + (0.18f * tonePref) + melodicLift);
        float gain = 1.0f + (tone * (0.07f + (0.03f * tonePref)));

        for (int i = 0; i + 1 < out.length; i += 2) {
            short sample = readLePcm16Sample(out, i);
            float x = sample / 32768.0f;
            low += alpha * (x - low);
            float high = x - low;
            float y = (low * darkMix) + (high * brightMix);
            if (presence > 0.0f) {
                float pre = x - (prevInput * (0.68f + (presence * 0.22f)));
                y += pre * presence;
            }
            y *= gain;
            y = (float) Math.tanh(y * (1.05f + (Math.abs(tone) * 0.18f)));
            short shaped = (short) Math.round(clampVoiceVoxProsody(y, -1.0f, 1.0f) * 32767.0f);
            writeLePcm16Sample(out, i, shaped);
            prevInput = x;
        }

        Config.logDebug("[Piper+][POST] tone=" + String.format(Locale.ROOT, "%.3f", tone)
                + " pitch=" + String.format(Locale.ROOT, "%.3f", profile.pitchScale())
                + " dark=" + String.format(Locale.ROOT, "%.2f", profile.darkScore())
                + " energy=" + String.format(Locale.ROOT, "%.2f", profile.energyScore())
                + " melody=" + String.format(Locale.ROOT, "%.2f", profile.melodyDepth())
                + " sr=" + sampleRate);
        return out;
    }

    private float derivePiperToneColor(TtsProsodyProfile profile) {
        if (profile == null) return 0.0f;
        float tonePref = getPiperToneEmphasisStrength();
        float pitch = clampVoiceVoxProsody(profile.pitchScale(), -0.20f, 0.20f);
        float dark = clampVoiceVoxProsody(profile.darkScore(), 0.0f, 1.0f);
        float energy = clampVoiceVoxProsody(profile.energyScore(), 0.0f, 1.0f);
        float melody = clampVoiceVoxProsody(profile.melodyDepth(), 0.0f, 1.0f);
        float moodBias = switch (profile.mood()) {
            case "excited" -> 0.24f;
            case "sing_song_lite" -> 0.14f;
            case "question" -> 0.06f;
            case "calm" -> -0.18f;
            case "whisper" -> -0.28f;
            default -> 0.0f;
        };
        float tone = (pitch * 7.5f)
                + ((energy - 0.52f) * 0.75f)
                - ((dark - 0.18f) * 1.20f)
                + ((melody - 0.50f) * 0.18f)
                + moodBias;
        tone *= tonePref;
        return clampVoiceVoxProsody(tone, -1.0f, 1.0f);
    }

    private WavPcmView tryParsePcm16MonoWav(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length < 44) return null;
        if (!matchesWavTag(wavBytes, 0, "RIFF") || !matchesWavTag(wavBytes, 8, "WAVE")) return null;

        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        int dataOffset = -1;
        int dataLength = 0;
        int offset = 12;
        while (offset + 8 <= wavBytes.length) {
            int chunkSize = readLeIntFromBytes(wavBytes, offset + 4);
            int chunkDataOffset = offset + 8;
            if (chunkSize < 0 || chunkDataOffset > wavBytes.length) break;
            int safeChunkLength = Math.min(chunkSize, wavBytes.length - chunkDataOffset);
            if (matchesWavTag(wavBytes, offset, "fmt ") && safeChunkLength >= 16) {
                channels = readLeShortFromBytes(wavBytes, chunkDataOffset + 2) & 0xFFFF;
                sampleRate = readLeIntFromBytes(wavBytes, chunkDataOffset + 4);
                bitsPerSample = readLeShortFromBytes(wavBytes, chunkDataOffset + 14) & 0xFFFF;
            } else if (matchesWavTag(wavBytes, offset, "data") && safeChunkLength >= 4) {
                dataOffset = chunkDataOffset;
                dataLength = safeChunkLength;
                break;
            }
            int padded = (chunkSize + 1) & ~1;
            offset = chunkDataOffset + padded;
        }
        if (sampleRate < 8000 || channels != 1 || bitsPerSample != 16 || dataOffset < 0 || dataLength < 4) return null;
        byte[] pcm = Arrays.copyOfRange(wavBytes, dataOffset, dataOffset + dataLength);
        return new WavPcmView(sampleRate, dataOffset, dataLength, pcm);
    }

    private boolean matchesWavTag(byte[] bytes, int offset, String tag) {
        if (bytes == null || tag == null || offset < 0 || offset + tag.length() > bytes.length) return false;
        for (int i = 0; i < tag.length(); i++) {
            if ((byte) tag.charAt(i) != bytes[offset + i]) return false;
        }
        return true;
    }

    private int readLeIntFromBytes(byte[] bytes, int offset) {
        if (bytes == null || offset < 0 || offset + 3 >= bytes.length) return -1;
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private short readLeShortFromBytes(byte[] bytes, int offset) {
        if (bytes == null || offset < 0 || offset + 1 >= bytes.length) return 0;
        return (short) ((bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8));
    }

    private short readLePcm16Sample(byte[] bytes, int offset) {
        return readLeShortFromBytes(bytes, offset);
    }

    private void writeLePcm16Sample(byte[] bytes, int offset, short value) {
        if (bytes == null || offset < 0 || offset + 1 >= bytes.length) return;
        bytes[offset] = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    private record WavPcmView(int sampleRate, int dataOffset, int dataLength, byte[] pcmBytes) {}

    private byte[] wrapPcm16MonoAsWav(byte[] pcm16le, int sampleRate) throws IOException {
        if (pcm16le == null || pcm16le.length == 0) return new byte[0];
        int safeSampleRate = Math.max(8000, sampleRate);
        int channels = 1;
        int bitsPerSample = 16;
        int blockAlign = channels * (bitsPerSample / 8);
        int byteRate = safeSampleRate * blockAlign;
        int dataSize = pcm16le.length;
        int riffSize = 36 + dataSize;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataSize);
        try (DataOutputStream dos = new DataOutputStream(out)) {
            dos.writeBytes("RIFF");
            writeLeInt(dos, riffSize);
            dos.writeBytes("WAVE");
            dos.writeBytes("fmt ");
            writeLeInt(dos, 16);
            writeLeShort(dos, (short) 1);
            writeLeShort(dos, (short) channels);
            writeLeInt(dos, safeSampleRate);
            writeLeInt(dos, byteRate);
            writeLeShort(dos, (short) blockAlign);
            writeLeShort(dos, (short) bitsPerSample);
            dos.writeBytes("data");
            writeLeInt(dos, dataSize);
            dos.write(pcm16le);
        }
        return out.toByteArray();
    }

    private void writeLeInt(DataOutputStream dos, int value) throws IOException {
        dos.writeByte(value & 0xFF);
        dos.writeByte((value >>> 8) & 0xFF);
        dos.writeByte((value >>> 16) & 0xFF);
        dos.writeByte((value >>> 24) & 0xFF);
    }

    private void writeLeShort(DataOutputStream dos, short value) throws IOException {
        dos.writeByte(value & 0xFF);
        dos.writeByte((value >>> 8) & 0xFF);
    }

    private float piperNoiseScale(TtsProsodyProfile profile) {
        if (profile == null) return 0.667f;
        float tonePref = getPiperToneEmphasisStrength();
        float melodic = Math.max(0.0f, Math.min(1.0f, profile.melodyDepth()));
        float conf = Math.max(0.0f, Math.min(1.0f, profile.confidence()));
        float energy = Math.max(0.0f, Math.min(1.0f, profile.energyScore()));
        float tone = derivePiperToneColor(profile);
        float noise = 0.58f + (melodic * 0.08f)
                + (Math.max(0.0f, tone) * 0.07f * tonePref)
                - (Math.max(0.0f, -tone) * 0.05f * tonePref)
                + ((energy - 0.45f) * 0.04f * tonePref)
                - (conf * 0.02f);
        return Math.max(0.42f, Math.min(0.92f, noise));
    }

    private float piperNoiseW(TtsProsodyProfile profile) {
        if (profile == null) return 0.8f;
        float contourPref = getPiperContourReflectionStrength();
        float timing = profile.hasTimingContour() ? 0.86f : 0.80f;
        float dark = Math.max(0.0f, Math.min(1.0f, profile.darkScore()));
        float melody = Math.max(0.0f, Math.min(1.0f, profile.melodyDepth()));
        float contour = Math.abs(clampVoiceVoxProsody(profile.contourRise(), -1.0f, 1.0f));
        float tone = derivePiperToneColor(profile);
        float width = timing
                + ((melody - 0.45f) * 0.08f * contourPref)
                + (contour * 0.04f * contourPref)
                + (Math.max(0.0f, -tone) * 0.06f)
                - (Math.max(0.0f, tone) * 0.04f)
                + (dark * 0.03f);
        return Math.max(0.68f, Math.min(0.96f, width));
    }

    public void requestVoicegerRestartForSettings() {
        try {
            // ★古い成功キャッシュ/失敗直後の揺れを持ち越さない
            vgHealthOkUntilMs = 0L;
        } catch (Throwable ignore) {}

        restartVoicegerApiAsync();
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
        return synthWindowsToWavBytesViaAgent(text, null, null, null);
    }
    private byte[] synthWindowsToWavBytesViaAgent(String text, Integer rate, Integer volume) throws Exception {
        if (text == null || text.isBlank()) return null;

        synchronized (psLock) {
            ensurePsServerLocked();

            String voice = resolveWindowsAutoVoiceName();
            String id = Long.toHexString(System.nanoTime());
            String b64Text = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));

            psWriter.write("SYNTHB64 " + voice + " " + id + "\n");
            if (rate != null) {
                psWriter.write("TRATE " + id + " " + rate + "\n");
            }
            if (volume != null) {
                psWriter.write("TVOLUME " + id + " " + volume + "\n");
            }

            final int CHUNK = 12000;
            for (int i = 0; i < b64Text.length(); i += CHUNK) {
                int end = Math.min(b64Text.length(), i + CHUNK);
                psWriter.write("TDATA " + id + " " + b64Text.substring(i, end) + "\n");
            }
            psWriter.write("TEND " + id + "\n");
            psWriter.flush();

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
    private byte[] synthWindowsToWavBytesViaAgent(String text, String ssml, Integer rate, Integer volume) throws Exception {
        if ((text == null || text.isBlank()) && (ssml == null || ssml.isBlank())) return null;

        synchronized (psLock) {
            ensurePsServerLocked();

            String voice = resolveWindowsAutoVoiceName();

            String id = Long.toHexString(System.nanoTime());
            String payload = (ssml != null && !ssml.isBlank()) ? ssml : text;
            byte[] txtBytes = payload.getBytes(StandardCharsets.UTF_8);
            String b64Text = Base64.getEncoder().encodeToString(txtBytes);

            String synthCmd = (ssml != null && !ssml.isBlank()) ? "SYNTHSSMLB64" : "SYNTHB64";
            psWriter.write(synthCmd + " " + voice + " " + id + "\n");
            if (rate != null) {
                psWriter.write("TRATE " + id + " " + rate + "\n");
            }
            if (volume != null) {
                psWriter.write("TVOLUME " + id + " " + volume + "\n");
            }

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


    private void speakVoiceVox(String text, String speakerId, String base, TtsProsodyProfile ttsProsodyProfile) {
        try {
            byte[] wavBytes = synthVoiceVoxToWavBytes(text, speakerId, base, ttsProsodyProfile);
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
    private byte[] synthVoiceVoxToWavBytes(String text, String speakerId, String base, TtsProsodyProfile ttsProsodyProfile) throws Exception {
        String queryUrl = base + "/audio_query?text=" +
                URLEncoder.encode(text, StandardCharsets.UTF_8) +
                "&speaker=" + speakerId;
        HttpURLConnection q = (HttpURLConnection) new URL(queryUrl).openConnection();
        q.setRequestMethod("POST");
        q.setDoOutput(true);
        q.getOutputStream().write(new byte[0]);
        String queryJson = new String(q.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        queryJson = applyVoiceVoxEmotionReflectionIfEnabled(text, queryJson, ttsProsodyProfile);
        String synthUrl = base + "/synthesis?speaker=" + speakerId;
        HttpURLConnection s = (HttpURLConnection) new URL(synthUrl).openConnection();
        s.setRequestMethod("POST");
        s.setDoOutput(true);
        s.setRequestProperty("Content-Type", "application/json");
        s.getOutputStream().write(queryJson.getBytes(StandardCharsets.UTF_8));
        try (InputStream in = s.getInputStream()) {
            return in.readAllBytes();
        }
    }
    private String applyVoiceVoxEmotionReflectionIfEnabled(String text, String queryJson, TtsProsodyProfile ttsProsodyProfile) {
        if (!prefs.getBoolean("tts.reflect_emotion", true)) return queryJson;
        if (queryJson == null || queryJson.isBlank()) return queryJson;
        try {
            TtsProsodyProfile textProfile = TtsProsodyAnalyzer.analyzeText(text);
            TtsProsodyProfile profile = softenVoiceVoxBorderlineUpbeatProfile(
                    resolveVoiceVoxProsodyProfile(textProfile, ttsProsodyProfile));
            if (profile != null && profile.hasPitchContour() && !"contour_transfer".equals(profile.mood())) {
                profile = profile.asContourDriven();
            }
            if (profile == null || !profile.isUsable()) {
                Config.logDebug("[TTS_PROSODY][VV] no-conf text=" + shortenForProsodyLog(text, 48));
                return queryJson;
            }

            JSONObject query = new JSONObject(queryJson);
            boolean contourDriven = profile.hasPitchContour();
            float contourPref = getVoiceVoxContourReflectionStrength();
            float tonePref = getVoiceVoxToneEmphasisStrength();
            float weight = contourDriven ? Math.max(0.88f, prosodyWeight(profile.confidence())) : prosodyWeight(profile.confidence());
            float contour = profile.contourRise() * weight;
            float melodyDepth = profile.melodyDepth() * weight;
            float darkDepth = profile.darkScore() * weight;
            float energyDepth = profile.energyScore() * weight;
            float contourDarkAssist = (contour < -0.16f)
                    ? clampVoiceVoxProsody(((-contour) - 0.16f) * 0.55f, 0.0f, 0.35f)
                    : 0.0f;
            float rawDarkTone = Math.max(darkDepth, contourDarkAssist);
            float rawBrightTone = Math.max(0.0f, Math.max(contour, energyDepth - 0.18f));
            float toneBalance = rawDarkTone + rawBrightTone + 0.0001f;
            float darkWeightLocal = rawDarkTone / toneBalance;
            float strongToneBoost = clampVoiceVoxProsody(tonePref / 1.10f, 0.85f, 1.60f);
            float darkBlendGate = clamp01(
                    (((rawDarkTone - 0.12f) / 0.42f) * 0.62f)
                    + (((-Math.min(contour, 0.0f)) - 0.08f) / 0.55f * 0.38f)
            );
            float brightSuppression = clamp01(darkBlendGate * (0.30f + (0.18f * strongToneBoost)));
            float brightTone = rawBrightTone * (1.0f - brightSuppression);
            float darkTone = clamp01(rawDarkTone * (1.0f + ((darkWeightLocal * 0.22f) + (darkBlendGate * 0.16f)) * strongToneBoost));

            float pitchTarget = contourDriven
                    ? ((brightTone * 0.0032f) - (darkTone * 0.032f)) * tonePref
                    : profile.pitchScale()
                        + (Math.max(0.0f, contour) * 0.015f * tonePref)
                        - (darkTone * 0.032f * tonePref);
            float intonationTarget = contourDriven
                    ? 1.0f + ((melodyDepth * 0.026f) + (brightTone * 0.006f) - (darkTone * 0.112f)) * tonePref
                    : profile.intonationScale()
                        + (melodyDepth * 0.09f * tonePref)
                        + (Math.max(0.0f, contour) * 0.03f * tonePref)
                        - (darkTone * 0.062f * tonePref);
            float speedTarget = profile.speedScale();
            float volumeTarget = profile.volumeScale();

            query.put("pitchScale", clampVoiceVoxProsody(scaleAroundNeutral(0.0f, pitchTarget, weight), -0.20f, 0.20f));
            query.put("intonationScale", clampVoiceVoxProsody(scaleAroundNeutral(1.0f, intonationTarget, weight), 0.75f, 1.45f));
            query.put("speedScale", clampVoiceVoxProsody(scaleAroundNeutral(1.0f, speedTarget, weight), 0.72f, 1.25f));
            query.put("volumeScale", clampVoiceVoxProsody(scaleAroundNeutral(1.0f, volumeTarget, weight), 0.78f, 1.25f));
            String melodyInfo = applyVoiceVoxMoraMelodyIfNeeded(query, profile, weight, contourPref);
            String timingInfo = applyVoiceVoxTimingTransferIfNeeded(query, profile, weight, contourPref);
            Config.logDebug("[TTS_PROSODY][VV] mood=" + profile.mood()
                    + " pitch=" + query.getDouble("pitchScale")
                    + " intonation=" + query.getDouble("intonationScale")
                    + " speed=" + query.getDouble("speedScale")
                    + " volume=" + query.getDouble("volumeScale")
                    + " weight=" + weight
                    + " conf=" + profile.confidence()
                    + " rise=" + profile.contourRise()
                    + " melodyDepth=" + profile.melodyDepth()
                    + " dark=" + profile.darkScore()
                    + " energy=" + profile.energyScore()
                    + " rawDarkTone=" + rawDarkTone
                    + " rawBrightTone=" + rawBrightTone
                    + " darkTone=" + darkTone
                    + " brightTone=" + brightTone
                    + " darkGate=" + darkBlendGate
                    + " darkWeight=" + darkWeightLocal
                    + " contourPref=" + contourPref
                    + " tonePref=" + tonePref
                    + melodyInfo
                    + timingInfo
                    + " text=" + shortenForProsodyLog(text, 48));
            return query.toString();
        } catch (Exception ex) {
            Config.logDebug("[TTS_PROSODY][VV] skipped: " + ex.getMessage());
            return queryJson;
        }
    }
    private TtsProsodyProfile resolveVoiceVoxProsodyProfile(TtsProsodyProfile textProfile, TtsProsodyProfile pcmProfile) {
        if (pcmProfile == null || !pcmProfile.isUsable()) return textProfile;
        if (textProfile == null || !textProfile.isUsable()) return pcmProfile;
        if (pcmProfile.hasPitchContour()) {
            return pcmProfile.asContourDriven();
        }

        boolean pcmDark = pcmProfile.isDarkLike();
        boolean pcmMelodic = pcmProfile.isMelodicLike();
        boolean pcmContinuous = pcmProfile.hasContinuousHint();

        if ((pcmDark || pcmMelodic || pcmContinuous)
                && pcmProfile.confidence() + 0.04f >= textProfile.confidence()) {
            return pcmProfile.blendWith(textProfile);
        }
        if (!textProfile.isConfident()) {
            return pcmProfile;
        }
        return textProfile.blendWith(pcmProfile);
    }
    private TtsProsodyProfile softenVoiceVoxBorderlineUpbeatProfile(TtsProsodyProfile profile) {
        if (profile == null) return null;
        if (profile.hasPitchContour()) return profile;
        if (!isUpbeatTtsMood(profile.mood())) return profile;

        float conf = profile.confidence();
        if (conf < 0.60f) {
            return new TtsProsodyProfile(
                    "neutral",
                    0.0f,
                    1.0f + ((profile.intonationScale() - 1.0f) * 0.35f),
                    1.0f + ((profile.speedScale() - 1.0f) * 0.35f),
                    1.0f + ((profile.volumeScale() - 1.0f) * 0.35f),
                    Math.max(0.28f, conf * 0.85f),
                    profile.contourRise(),
                    profile.melodyDepth(),
                    profile.darkScore(),
                    profile.energyScore()
            );
        }
        if (conf < 0.78f) {
            return new TtsProsodyProfile(
                    profile.mood(),
                    Math.min(profile.pitchScale(), 0.012f),
                    1.0f + ((profile.intonationScale() - 1.0f) * 0.65f),
                    1.0f + ((profile.speedScale() - 1.0f) * 0.65f),
                    1.0f + ((profile.volumeScale() - 1.0f) * 0.65f),
                    profile.confidence(),
                    profile.contourRise(),
                    profile.melodyDepth(),
                    profile.darkScore(),
                    profile.energyScore()
            );
        }
        return profile;
    }
    private boolean isDarkTtsMood(String mood) {
        return "whisper".equals(mood) || "calm".equals(mood);
    }
    private boolean isUpbeatTtsMood(String mood) {
        return "sing_song_lite".equals(mood) || "excited".equals(mood) || "question".equals(mood);
    }
    private String applyVoiceVoxMoraMelodyIfNeeded(JSONObject query, TtsProsodyProfile profile, float weight, float contourPref) {
        JSONArray accentPhrases = query.optJSONArray("accent_phrases");
        if (accentPhrases == null || accentPhrases.size() == 0) return " melody=off";

        ArrayList<JSONObject> voicedMoras = new ArrayList<>();
        ArrayList<JSONObject> voicedPhraseObjects = new ArrayList<>();
        ArrayList<ArrayList<JSONObject>> voicedPhraseMoras = new ArrayList<>();
        ArrayList<ArrayList<Integer>> voicedPhraseIndices = new ArrayList<>();
        for (int i = 0; i < accentPhrases.size(); i++) {
            JSONObject phrase = accentPhrases.optJSONObject(i);
            if (phrase == null) continue;
            JSONArray moras = phrase.optJSONArray("moras");
            if (moras == null) continue;
            ArrayList<JSONObject> phraseVoicedMoras = new ArrayList<>();
            ArrayList<Integer> phraseVoicedIndices = new ArrayList<>();
            for (int j = 0; j < moras.size(); j++) {
                JSONObject mora = moras.optJSONObject(j);
                if (mora == null) continue;
                double pitch = mora.optDouble("pitch", 0.0d);
                if (pitch > 0.0d) {
                    voicedMoras.add(mora);
                    phraseVoicedMoras.add(mora);
                    phraseVoicedIndices.add(j);
                }
            }
            if (!phraseVoicedMoras.isEmpty()) {
                voicedPhraseObjects.add(phrase);
                voicedPhraseMoras.add(phraseVoicedMoras);
                voicedPhraseIndices.add(phraseVoicedIndices);
            }
        }
        int voicedCount = voicedMoras.size();
        if (voicedCount < 3) return " melody=off voiced=" + voicedCount;
        if (profile.hasPitchContour()) {
            float[] contour = normalizeContourForVoiceVox(profile.pitchContour());
            float[] activity = profile.activityContour();
            float[] voicing = profile.voicingContour();
            List<ProsodySegment> segments = buildProsodySegments(contour, activity, voicing);
            if (isHummingLikeContour(profile, segments, voicedCount)) {
                String noteInfo = applyVoiceVoxNoteTransfer(voicedMoras, segments, contour, weight, contourPref);
                if (noteInfo != null) {
                    return noteInfo;
                }
            }
            float maxAbs = 0.0f;
            float sumSq = 0.0f;
            for (float v : contour) {
                maxAbs = Math.max(maxAbs, Math.abs(v));
                sumSq += v * v;
            }
            float rms = (float) Math.sqrt(sumSq / Math.max(1, contour.length));
            float contourStrength = clamp01((maxAbs * 0.58f) + (rms * 0.70f) + (Math.abs(profile.contourRise()) * 0.18f));
            float amplitude = clampVoiceVoxProsody(
                    (0.12f + (0.22f * Math.max(0.20f, contourStrength) * (0.40f + (0.60f * weight)))) * contourPref,
                    0.10f, 0.40f);
            double totalDur = estimateTotalVoicedMoraDurationSec(voicedMoras);
            double elapsed = 0.0d;
            double firstDelta = 0.0d;
            double lastDelta = 0.0d;
            int voicedIndex = 0;
            for (int phraseIdx = 0; phraseIdx < voicedPhraseMoras.size(); phraseIdx++) {
                ArrayList<JSONObject> phraseMoras = voicedPhraseMoras.get(phraseIdx);
                for (int i = 0; i < phraseMoras.size(); i++) {
                    JSONObject mora = phraseMoras.get(i);
                    double dur = estimateVoiceVoxMoraDurationSec(mora);
                    double startPos = (totalDur <= 0.0d)
                            ? (voicedIndex / (double) Math.max(1, voicedCount))
                            : (elapsed / totalDur);
                    double endPos = (totalDur <= 0.0d)
                            ? ((voicedIndex + 1.0d) / Math.max(1, voicedCount))
                            : ((elapsed + dur) / totalDur);
                    float contourDelta = samplePitchContourAverage(contour, startPos, endPos);
                    double onsetRamp = Math.min(1.0d, (voicedIndex + 0.35d) / 1.8d);
                    contourDelta *= (float) onsetRamp;
                    if (voicedIndex == 0 && contourDelta > 0.0f) {
                        contourDelta *= 0.58f;
                    } else if (voicedIndex == 1 && contourDelta > 0.0f) {
                        contourDelta *= 0.82f;
                    }
                    double basePitch = mora.optDouble("pitch", 0.0d);
                    if (basePitch <= 0.0d) continue;
                    double applied = amplitude * contourDelta;
                    if (voicedIndex == 0) firstDelta = applied;
                    lastDelta = applied;
                    mora.put("pitch", basePitch + applied);
                    elapsed += dur;
                    voicedIndex++;
                }
            }
            return " melody=contour-transfer voiced=" + voicedCount
                    + " amp=" + String.format(Locale.ROOT, "%.3f", amplitude)
                    + " contourPts=" + contour.length
                    + " contourMax=" + String.format(Locale.ROOT, "%.3f", maxAbs)
                    + " contourRms=" + String.format(Locale.ROOT, "%.3f", rms)
                    + " headDelta=" + String.format(Locale.ROOT, "%.3f", firstDelta)
                    + " tailDelta=" + String.format(Locale.ROOT, "%.3f", lastDelta)
                    + " accents=preserve";
        }

        float amplitude;
        String mode;
        float melodyDepth = profile.melodyDepth() * weight;
        float contour = profile.contourRise() * weight;
        float darkDepth = profile.darkScore() * weight;
        if (profile.isMelodicLike() || melodyDepth >= 0.28f) {
            if (profile.confidence() < 0.60f) {
                amplitude = clampVoiceVoxProsody(0.016f + (0.026f * Math.max(weight * 0.7f, melodyDepth * 0.6f)), 0.016f, 0.045f);
                mode = "ripple-lite";
            } else {
                amplitude = clampVoiceVoxProsody(0.03f + (0.05f * Math.max(weight, melodyDepth)), 0.03f, 0.10f);
                mode = "arch+ripple";
            }
        } else if ("excited".equals(profile.mood())) {
            amplitude = clampVoiceVoxProsody(0.018f + (0.032f * Math.max(weight * 0.7f, profile.energyScore() * weight)), 0.018f, 0.065f);
            mode = "lift";
        } else if ("question".equals(profile.mood()) || contour >= 0.18f) {
            amplitude = clampVoiceVoxProsody(0.015f + (0.030f * Math.max(weight * 0.6f, contour)), 0.015f, 0.055f);
            mode = "tail-rise";
        } else if (profile.isDarkLike() || contour <= -0.16f || darkDepth >= 0.18f) {
            amplitude = clampVoiceVoxProsody(0.015f + (0.035f * Math.max(darkDepth, -contour)), 0.015f, 0.06f);
            mode = "tail-fall";
        } else {
            return " melody=off voiced=" + voicedCount;
        }

        for (int i = 0; i < voicedCount; i++) {
            JSONObject mora = voicedMoras.get(i);
            double basePitch = mora.optDouble("pitch", 0.0d);
            if (basePitch <= 0.0d) continue;

            double pos = (voicedCount <= 1) ? 0.0d : (i / (double) (voicedCount - 1));
            double delta;
            switch (mode) {
                case "arch+ripple":
                    double arch = Math.sin(Math.PI * pos);
                    double ripple = Math.sin(2.0d * Math.PI * pos);
                    delta = amplitude * ((arch * 0.75d) + (ripple * 0.25d));
                    break;
                case "ripple-lite":
                    delta = amplitude * Math.sin(2.0d * Math.PI * pos) * 0.55d;
                    break;
                case "lift":
                    delta = amplitude * Math.sin(Math.PI * pos);
                    break;
                case "tail-fall":
                    delta = -amplitude * Math.pow(pos, 1.55d);
                    break;
                case "tail-rise":
                    delta = amplitude * pos * pos;
                    break;
                default:
                    delta = 0.0d;
                    break;
            }
            mora.put("pitch", basePitch + delta);
        }
        return " melody=" + mode + " voiced=" + voicedCount + " amp=" + String.format(Locale.ROOT, "%.3f", amplitude);
    }

    private String applyVoiceVoxNoteTransfer(List<JSONObject> voicedMoras, List<ProsodySegment> segments, float[] contour, float weight, float contourPref) {
        if (voicedMoras == null || voicedMoras.size() < 3 || segments == null || segments.isEmpty()) return null;
        ArrayList<ProsodySegment> noteSegments = new ArrayList<>();
        int restCount = 0;
        float maxPitch = 0.0f;
        for (ProsodySegment segment : segments) {
            if (segment.rest()) {
                restCount++;
                continue;
            }
            noteSegments.add(segment);
            maxPitch = Math.max(maxPitch, Math.abs(segment.meanPitch()));
        }
        if (noteSegments.size() < 2) return null;

        int[] alloc = allocateSegmentCounts(noteSegments, voicedMoras.size());
        float amplitude = clampVoiceVoxProsody(
                (0.16f + (0.24f * Math.max(0.30f, maxPitch) * (0.45f + (0.55f * weight)))) * contourPref,
                0.14f, 0.48f);
        double firstDelta = 0.0d;
        double lastDelta = 0.0d;
        int moraIndex = 0;
        int assigned = 0;
        float avgFramesPerMora = estimateAverageFramesPerMora(noteSegments, alloc);
        float totalNoteFrames = 0.0f;
        for (ProsodySegment segment : noteSegments) totalNoteFrames += segment.frameCount();
        float noteFrameCursor = 0.0f;
        for (int i = 0; i < noteSegments.size(); i++) {
            ProsodySegment segment = noteSegments.get(i);
            int count = alloc[i];
            float segPitch = segment.meanPitch();
            if (i == 0 && segPitch > 0.0f) segPitch *= 0.88f;
            if (i == 1 && segPitch > 0.0f) segPitch *= 0.94f;
            double delta = amplitude * segPitch;
            if (assigned == 0) firstDelta = delta;
            for (int j = 0; j < count && moraIndex < voicedMoras.size(); j++, moraIndex++, assigned++) {
                JSONObject mora = voicedMoras.get(moraIndex);
                double basePitch = mora.optDouble("pitch", 0.0d);
                if (basePitch <= 0.0d) continue;
                double applied;
                if (contour != null && contour.length >= 3 && totalNoteFrames > 0.0f) {
                    double segStartPos = noteFrameCursor / totalNoteFrames;
                    double segEndPos = (noteFrameCursor + segment.frameCount()) / totalNoteFrames;
                    double subStartPos = segStartPos + ((segEndPos - segStartPos) * j / Math.max(1.0d, count));
                    double subEndPos = segStartPos + ((segEndPos - segStartPos) * (j + 1.0d) / Math.max(1.0d, count));
                    float localPitch = samplePitchContourAverage(contour, subStartPos, subEndPos);
                    applied = amplitude * localPitch;
                    if (i == 0 && applied > 0.0d && count > 0) {
                        double within = (j + 0.5d) / Math.max(1.0d, count);
                        double onsetRamp = 0.22d + (0.78d * Math.pow(within, 1.35d));
                        applied *= onsetRamp;
                    }
                    if (i == 0 && j == 0 && applied > 0.0d) {
                        applied *= 0.55d;
                    }
                } else {
                    applied = delta;
                    if (i == 0 && delta > 0.0d && count > 0) {
                        double within = (j + 0.5d) / Math.max(1.0d, count);
                        double onsetRamp = 0.45d + (0.55d * within);
                        applied *= onsetRamp;
                    }
                }
                mora.put("pitch", basePitch + applied);
                lastDelta = applied;
            }
            noteFrameCursor += segment.frameCount();
        }
        return " melody=note-transfer voiced=" + voicedMoras.size()
                + " notes=" + noteSegments.size()
                + " rests=" + restCount
                + " amp=" + String.format(Locale.ROOT, "%.3f", amplitude)
                + " noteAlloc=" + Arrays.toString(alloc)
                + " avgFpm=" + String.format(Locale.ROOT, "%.2f", avgFramesPerMora)
                + " headDelta=" + String.format(Locale.ROOT, "%.3f", firstDelta)
                + " tailDelta=" + String.format(Locale.ROOT, "%.3f", lastDelta)
                + " accents=preserve";
    }

    private String applyVoiceVoxTimingTransferIfNeeded(JSONObject query, TtsProsodyProfile profile, float weight, float contourPref) {
        if (profile == null || !profile.hasTimingContour()) return " timing=off";
        JSONArray accentPhrases = query.optJSONArray("accent_phrases");
        if (accentPhrases == null || accentPhrases.size() == 0) return " timing=off";

        ArrayList<TimingUnit> units = new ArrayList<>();
        for (int i = 0; i < accentPhrases.size(); i++) {
            JSONObject phrase = accentPhrases.optJSONObject(i);
            if (phrase == null) continue;
            JSONArray moras = phrase.optJSONArray("moras");
            if (moras != null) {
                for (int j = 0; j < moras.size(); j++) {
                    JSONObject mora = moras.optJSONObject(j);
                    if (mora == null) continue;
                    boolean voiced = mora.optDouble("pitch", 0.0d) > 0.0d;
                    units.add(new TimingUnit(mora, false, voiced));
                }
            }
            JSONObject pauseMora = phrase.optJSONObject("pause_mora");
            if (pauseMora != null) {
                units.add(new TimingUnit(pauseMora, true, false));
            }
        }
        if (units.size() < 2) return " timing=off units=" + units.size();

        double totalDur = 0.0d;
        for (TimingUnit unit : units) {
            totalDur += estimateVoiceVoxMoraDurationSec(unit.mora());
        }
        if (totalDur <= 0.05d) return " timing=off units=" + units.size();

        float[] activity = profile.activityContour();
        float[] voicing = profile.voicingContour();
        if ((activity == null || activity.length < 3) && (voicing == null || voicing.length < 3)) {
            return " timing=off units=" + units.size();
        }
        List<ProsodySegment> segments = buildProsodySegments(
                profile.hasPitchContour() ? normalizeContourForVoiceVox(profile.pitchContour()) : new float[0],
                activity,
                voicing);
        boolean hummingLike = isHummingLikeContour(profile, segments, units.size());
        boolean cadenceAware = hummingLike || shouldUseCadenceAwareTiming(profile, segments, units.size());
        int[] noteAlloc = null;
        int noteAllocIndex = 0;
        int pauseAllocIndex = 0;
        ArrayList<ProsodySegment> noteSegments = new ArrayList<>();
        ArrayList<ProsodySegment> restSegments = new ArrayList<>();
        float avgNoteFramesPerMora = 1.0f;
        float avgRestFrames = 1.0f;
        if (cadenceAware) {
            for (ProsodySegment segment : segments) {
                if (segment.rest()) restSegments.add(segment);
                else noteSegments.add(segment);
            }
            int voicedUnitCount = 0;
            for (TimingUnit unit : units) if (!unit.pause()) voicedUnitCount++;
            if (!noteSegments.isEmpty() && voicedUnitCount > 0) {
                noteAlloc = allocateSegmentCounts(noteSegments, voicedUnitCount);
                avgNoteFramesPerMora = estimateAverageFramesPerMora(noteSegments, noteAlloc);
            }
            avgRestFrames = estimateAverageSegmentFrames(restSegments);
        }

        float pref = contourPref;
        double elapsed = 0.0d;
        int changedMoras = 0;
        int changedPauses = 0;
        float meanVoicedScale = 0.0f;
        float meanPauseScale = 0.0f;
        int voicedScaleCount = 0;
        int pauseScaleCount = 0;
        float sourceTimingSec = estimateSourceTimingDurationSec(profile);
        float speedAdjust = 1.0f;
        double speechDur = Math.max(0.05d, totalDur
                - query.optDouble("prePhonemeLength", 0.0d)
                - query.optDouble("postPhonemeLength", 0.0d));
        if (cadenceAware && sourceTimingSec > 0.15f && speechDur > 0.08d) {
            float sourceToTarget = sourceTimingSec / (float) speechDur;
            float shortUtteranceBoost = 1.0f + (0.18f * clamp01((8.0f - units.size()) / 4.0f));
            speedAdjust = clampVoiceVoxProsody(1.0f + (((1.0f / Math.max(0.45f, sourceToTarget)) - 1.0f) * 0.72f * pref * shortUtteranceBoost), 0.76f, 1.32f);
            query.put("speedScale", clampVoiceVoxProsody((float) query.optDouble("speedScale", 1.0d) * speedAdjust, 0.70f, 1.45f));
        }

        for (TimingUnit unit : units) {
            JSONObject mora = unit.mora();
            double baseDur = estimateVoiceVoxMoraDurationSec(mora);
            double startPos = elapsed / totalDur;
            double endPos = (elapsed + baseDur) / totalDur;
            float activityAvg = sampleGenericContourAverage(activity, startPos, endPos, 0.0f);
            float voicingAvg = sampleGenericContourAverage(voicing, startPos, endPos, unit.voiced() ? 1.0f : 0.0f);
            float silenceAvg = clamp01(1.0f - Math.max(activityAvg, voicingAvg));
            float scale;

            if (unit.pause()) {
                if (cadenceAware && pauseAllocIndex < restSegments.size()) {
                    ProsodySegment rest = restSegments.get(pauseAllocIndex++);
                    float restRatio = clampVoiceVoxProsody(rest.frameCount() / estimateAverageSegmentFrames(restSegments), 0.75f, 1.90f);
                    scale = clampVoiceVoxProsody(0.82f + ((restRatio - 1.0f) * 0.92f * pref) + (silenceAvg * 0.34f), 0.66f, 2.10f);
                } else {
                    scale = clampVoiceVoxProsody(
                            0.92f + (silenceAvg * 0.40f * pref) - (activityAvg * 0.10f),
                            0.78f, 1.55f);
                }
                if (applyVoiceVoxTimingScale(mora, scale)) changedPauses++;
                meanPauseScale += scale;
                pauseScaleCount++;
            } else if (unit.voiced()) {
                if (cadenceAware && noteAlloc != null && noteAllocIndex < noteAlloc.length) {
                    int segmentIdx = findSegmentIndexForAllocatedMora(noteAlloc, noteAllocIndex);
                    ProsodySegment note = noteSegments.get(Math.min(segmentIdx, noteSegments.size() - 1));
                    int allocCount = Math.max(1, noteAlloc[segmentIdx]);
                    float framesPerMora = note.frameCount() / (float) allocCount;
                    float noteRatio = clampVoiceVoxProsody(framesPerMora / Math.max(1.0f, avgNoteFramesPerMora), 0.62f, 2.45f);
                    float durationBoost = 0.80f
                            + ((noteRatio - 1.0f) * 1.02f * pref)
                            + (Math.max(voicingAvg, activityAvg * 0.30f) * 0.16f)
                            + (silenceAvg * 0.05f);
                    scale = clampVoiceVoxProsody(durationBoost, 0.58f, 2.40f);
                    noteAllocIndex++;
                } else {
                    float expressiveness = Math.max(voicingAvg, (activityAvg * 0.35f));
                    float durationBoost = 0.90f + (expressiveness * 0.30f * pref) + (silenceAvg * 0.08f);
                    scale = clampVoiceVoxProsody(durationBoost, 0.82f, 1.60f);
                }
                if (applyVoiceVoxTimingScale(mora, scale)) changedMoras++;
                meanVoicedScale += scale;
                voicedScaleCount++;
            } else {
                scale = clampVoiceVoxProsody(0.95f + (silenceAvg * 0.18f), 0.82f, 1.25f);
                if (applyVoiceVoxTimingScale(mora, scale)) changedMoras++;
                meanVoicedScale += scale;
                voicedScaleCount++;
            }
            elapsed += baseDur;
        }
        return " timing=dur-transfer"
                + " moras=" + changedMoras
                + " pauses=" + changedPauses
                + (hummingLike ? " mode=note-aware" : (cadenceAware ? " mode=cadence-aware" : " mode=direct"))
                + (cadenceAware ? " noteAlloc=" + Arrays.toString(noteAlloc == null ? new int[0] : noteAlloc) : "")
                + (cadenceAware ? " avgNoteFpm=" + String.format(Locale.ROOT, "%.2f", avgNoteFramesPerMora) : "")
                + (cadenceAware ? " avgRestFrames=" + String.format(Locale.ROOT, "%.2f", avgRestFrames) : "")
                + (cadenceAware ? " sourceSec=" + String.format(Locale.ROOT, "%.2f", sourceTimingSec) : "")
                + (cadenceAware ? " speechSec=" + String.format(Locale.ROOT, "%.2f", speechDur) : "")
                + (cadenceAware ? " speedAdj=" + String.format(Locale.ROOT, "%.3f", speedAdjust) : "")
                + " voicedScale=" + String.format(Locale.ROOT, "%.3f", voicedScaleCount == 0 ? 1.0f : (meanVoicedScale / voicedScaleCount))
                + " pauseScale=" + String.format(Locale.ROOT, "%.3f", pauseScaleCount == 0 ? 1.0f : (meanPauseScale / pauseScaleCount));
    }

    private float getVoiceVoxContourReflectionStrength() {
        String mode = prefs.get("tts.reflect.contour_strength", "normal");
        if ("mild".equalsIgnoreCase(mode)) return 0.85f;
        if ("strong".equalsIgnoreCase(mode)) return 1.35f;
        return 1.10f;
    }

    private float getVoiceVoxToneEmphasisStrength() {
        String mode = prefs.get("tts.reflect.tone_emphasis", "normal");
        if ("mild".equalsIgnoreCase(mode)) return 0.85f;
        if ("strong".equalsIgnoreCase(mode)) return 1.55f;
        return 1.10f;
    }
    private float getPiperContourReflectionStrength() {
        String mode = prefs.get("tts.reflect.contour_strength", "normal");
        if ("mild".equalsIgnoreCase(mode)) return 0.85f;
        if ("strong".equalsIgnoreCase(mode)) return 1.30f;
        return 1.00f;
    }

    private float getPiperToneEmphasisStrength() {
        String mode = prefs.get("tts.reflect.tone_emphasis", "normal");
        if ("mild".equalsIgnoreCase(mode)) return 0.85f;
        if ("strong".equalsIgnoreCase(mode)) return 1.35f;
        return 1.00f;
    }
    private static float prosodyWeight(float confidence) {
        float c = Math.max(0.25f, Math.min(1.0f, confidence));
        float norm = (c - 0.25f) / 0.75f;
        return 0.20f + (0.80f * (float) Math.sqrt(Math.max(0.0f, norm)));
    }
    private static float scaleAroundNeutral(float neutral, float target, float weight) {
        return neutral + ((target - neutral) * weight);
    }
    private static float clampVoiceVoxProsody(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
    private static double estimateVoiceVoxMoraDurationSec(JSONObject mora) {
        if (mora == null) return 0.12d;
        double consonant = mora.optDouble("consonant_length", 0.0d);
        double vowel = mora.optDouble("vowel_length", 0.0d);
        double total = consonant + vowel;
        return (total > 0.02d) ? total : 0.12d;
    }
    private static double estimateTotalVoicedMoraDurationSec(List<JSONObject> voicedMoras) {
        double total = 0.0d;
        if (voicedMoras == null) return total;
        for (JSONObject mora : voicedMoras) total += estimateVoiceVoxMoraDurationSec(mora);
        return total;
    }
    private static boolean applyVoiceVoxTimingScale(JSONObject mora, float scale) {
        if (mora == null) return false;
        boolean changed = false;
        double consonant = mora.optDouble("consonant_length", 0.0d);
        double vowel = mora.optDouble("vowel_length", 0.0d);
        if (consonant > 0.0d) {
            double consonantScale = 1.0d + ((scale - 1.0d) * 0.45d);
            mora.put("consonant_length", clampVoiceVoxDuration(consonant * consonantScale, 0.018d, 0.320d));
            changed = true;
        }
        if (vowel > 0.0d) {
            mora.put("vowel_length", clampVoiceVoxDuration(vowel * scale, 0.040d, 0.750d));
            changed = true;
        }
        return changed;
    }
    private static double clampVoiceVoxDuration(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
    private static float samplePitchContourAt(float[] contour, double pos) {
        if (contour == null || contour.length == 0) return 0.0f;
        if (contour.length == 1) return contour[0];
        double clamped = Math.max(0.0d, Math.min(1.0d, pos));
        double scaled = clamped * (contour.length - 1);
        int lo = (int) Math.floor(scaled);
        int hi = Math.min(contour.length - 1, lo + 1);
        double t = scaled - lo;
        return (float) (contour[lo] + ((contour[hi] - contour[lo]) * t));
    }
    private static float samplePitchContourAverage(float[] contour, double startPos, double endPos) {
        if (contour == null || contour.length == 0) return 0.0f;
        if (contour.length == 1) return contour[0];
        double lo = Math.max(0.0d, Math.min(1.0d, startPos));
        double hi = Math.max(lo, Math.min(1.0d, endPos));
        if (Math.abs(hi - lo) < 0.0001d) {
            return samplePitchContourAt(contour, (lo + hi) * 0.5d);
        }
        double sum = 0.0d;
        int n = 5;
        for (int i = 0; i < n; i++) {
            double t = (i + 0.5d) / n;
            double pos = lo + ((hi - lo) * t);
            sum += samplePitchContourAt(contour, pos);
        }
        return (float) (sum / n);
    }
    private static float sampleGenericContourAverage(float[] contour, double startPos, double endPos, float defaultValue) {
        if (contour == null || contour.length == 0) return defaultValue;
        if (contour.length == 1) return contour[0];
        double lo = Math.max(0.0d, Math.min(1.0d, startPos));
        double hi = Math.max(lo, Math.min(1.0d, endPos));
        if (Math.abs(hi - lo) < 0.0001d) {
            return samplePitchContourAt(contour, (lo + hi) * 0.5d);
        }
        double sum = 0.0d;
        int n = 5;
        for (int i = 0; i < n; i++) {
            double t = (i + 0.5d) / n;
            double pos = lo + ((hi - lo) * t);
            sum += samplePitchContourAt(contour, pos);
        }
        return (float) (sum / n);
    }
    private static float sampleGenericContourAt(float[] contour, double pos, float defaultValue) {
        if (contour == null || contour.length == 0) return defaultValue;
        return samplePitchContourAt(contour, pos);
    }
    private static List<ProsodySegment> buildProsodySegments(float[] contour, float[] activity, float[] voicing) {
        int len = Math.max(contour == null ? 0 : contour.length, Math.max(activity == null ? 0 : activity.length, voicing == null ? 0 : voicing.length));
        if (len < 3) return List.of();
        ArrayList<ProsodySegment> segments = new ArrayList<>();
        int start = 0;
        float sumPitch = 0.0f;
        float sumActivity = 0.0f;
        float sumVoicing = 0.0f;
        int pitchCount = 0;
        boolean currentRest = false;
        float lastPitch = 0.0f;
        boolean initialized = false;
        for (int i = 0; i < len; i++) {
            double pos = len <= 1 ? 0.0d : (i / (double) (len - 1));
            float a = sampleGenericContourAt(activity, pos, 0.0f);
            float v = sampleGenericContourAt(voicing, pos, 0.0f);
            float p = sampleGenericContourAt(contour, pos, 0.0f);
            boolean rest = (v <= 0.28f && a <= 0.22f);
            if (!initialized) {
                currentRest = rest;
                start = i;
                initialized = true;
            } else {
                boolean split = rest != currentRest;
                if (!split && !rest && pitchCount >= 3 && Math.abs(p - lastPitch) >= 0.42f && (i - start) >= 3) {
                    split = true;
                }
                if (split) {
                    addProsodySegment(segments, start, i - 1, currentRest, sumPitch, pitchCount, sumActivity, sumVoicing, i - start);
                    start = i;
                    sumPitch = 0.0f;
                    sumActivity = 0.0f;
                    sumVoicing = 0.0f;
                    pitchCount = 0;
                    currentRest = rest;
                }
            }
            sumActivity += a;
            sumVoicing += v;
            if (!rest) {
                sumPitch += p;
                pitchCount++;
                lastPitch = p;
            }
        }
        addProsodySegment(segments, start, len - 1, currentRest, sumPitch, pitchCount, sumActivity, sumVoicing, len - start);
        return mergeShortProsodySegments(segments);
    }
    private static void addProsodySegment(List<ProsodySegment> segments, int start, int end, boolean rest,
                                          float sumPitch, int pitchCount, float sumActivity, float sumVoicing, int frames) {
        int frameCount = Math.max(1, end - start + 1);
        float meanPitch = (rest || pitchCount == 0) ? 0.0f : (sumPitch / Math.max(1, pitchCount));
        float meanActivity = sumActivity / Math.max(1, frameCount);
        float meanVoicing = sumVoicing / Math.max(1, frameCount);
        segments.add(new ProsodySegment(rest, start, end, frameCount, meanPitch, meanActivity, meanVoicing));
    }
    private static List<ProsodySegment> mergeShortProsodySegments(List<ProsodySegment> raw) {
        if (raw == null || raw.size() <= 1) return raw == null ? List.of() : raw;
        ArrayList<ProsodySegment> merged = new ArrayList<>();
        for (ProsodySegment seg : raw) {
            if (!merged.isEmpty() && seg.frameCount() <= (seg.rest() ? 1 : 2)) {
                ProsodySegment prev = merged.remove(merged.size() - 1);
                merged.add(prev.merge(seg));
            } else {
                merged.add(seg);
            }
        }
        return merged;
    }
    private static boolean isHummingLikeContour(TtsProsodyProfile profile, List<ProsodySegment> segments, int voicedUnits) {
        if (profile == null || segments == null || segments.isEmpty()) return false;
        if (!profile.hasPitchContour()) return false;
        int notes = 0;
        int rests = 0;
        float totalNoteFrames = 0.0f;
        float maxPitch = 0.0f;
        for (ProsodySegment seg : segments) {
            if (seg.rest()) {
                rests++;
            } else {
                notes++;
                totalNoteFrames += seg.frameCount();
                maxPitch = Math.max(maxPitch, Math.abs(seg.meanPitch()));
            }
        }
        float avgNoteFrames = notes == 0 ? 0.0f : totalNoteFrames / notes;
        return notes >= 2
                && voicedUnits >= 3
                && avgNoteFrames >= 3.0f
                && maxPitch >= 0.28f
                && (rests >= 1 || profile.melodyDepth() >= 0.62f);
    }
    private static boolean shouldUseCadenceAwareTiming(TtsProsodyProfile profile, List<ProsodySegment> segments, int totalUnits) {
        if (profile == null || segments == null || segments.isEmpty()) return false;
        if (!profile.hasTimingContour()) return false;
        int notes = 0;
        int rests = 0;
        float voicedFrames = 0.0f;
        float maxPitch = 0.0f;
        for (ProsodySegment seg : segments) {
            if (seg.rest()) {
                rests++;
            } else {
                notes++;
                voicedFrames += seg.frameCount();
                maxPitch = Math.max(maxPitch, Math.abs(seg.meanPitch()));
            }
        }
        float avgNoteFrames = notes == 0 ? 0.0f : voicedFrames / notes;
        boolean rhythmicRest = rests >= 1 && avgNoteFrames >= 2.0f;
        boolean melodicEnough = notes >= 2 && maxPitch >= 0.18f && profile.melodyDepth() >= 0.28f;
        boolean contourDriven = Math.abs(profile.contourRise()) >= 0.22f && profile.confidence() >= 0.28f;
        return totalUnits >= 4
                && notes >= 2
                && avgNoteFrames >= 2.0f
                && (melodicEnough || (rhythmicRest && contourDriven));
    }
    private static int[] allocateSegmentCounts(List<ProsodySegment> segments, int totalUnits) {
        int[] counts = new int[segments.size()];
        if (segments.isEmpty() || totalUnits <= 0) return counts;
        float totalFrames = 0.0f;
        for (ProsodySegment seg : segments) totalFrames += seg.frameCount();
        int assigned = 0;
        double[] fractions = new double[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            double raw = (segments.get(i).frameCount() / Math.max(1.0f, totalFrames)) * totalUnits;
            counts[i] = Math.max(1, (int) Math.floor(raw));
            fractions[i] = raw - Math.floor(raw);
            assigned += counts[i];
        }
        while (assigned > totalUnits) {
            int idx = indexOfMax(counts);
            if (idx < 0 || counts[idx] <= 1) break;
            counts[idx]--;
            assigned--;
        }
        while (assigned < totalUnits) {
            int idx = indexOfMax(fractions);
            if (idx < 0) idx = counts.length - 1;
            counts[idx]++;
            fractions[idx] = -1.0d;
            assigned++;
        }
        return counts;
    }
    private static int findSegmentIndexForAllocatedMora(int[] alloc, int moraIndex) {
        int sum = 0;
        for (int i = 0; i < alloc.length; i++) {
            sum += alloc[i];
            if (moraIndex < sum) return i;
        }
        return Math.max(0, alloc.length - 1);
    }
    private static float estimateAverageSegmentFrames(List<ProsodySegment> segments) {
        if (segments == null || segments.isEmpty()) return 1.0f;
        float sum = 0.0f;
        for (ProsodySegment segment : segments) sum += segment.frameCount();
        return Math.max(1.0f, sum / segments.size());
    }
    private static float estimateAverageFramesPerMora(List<ProsodySegment> segments, int[] alloc) {
        if (segments == null || segments.isEmpty() || alloc == null || alloc.length == 0) return 1.0f;
        float frames = 0.0f;
        int moras = 0;
        int count = Math.min(segments.size(), alloc.length);
        for (int i = 0; i < count; i++) {
            frames += segments.get(i).frameCount();
            moras += Math.max(1, alloc[i]);
        }
        return frames <= 0.0f || moras <= 0 ? 1.0f : Math.max(1.0f, frames / moras);
    }
    private static float estimateSourceTimingDurationSec(TtsProsodyProfile profile) {
        if (profile == null || profile.contourStepMs() <= 0) return 0.0f;
        int len = 0;
        if (profile.activityContour() != null) len = Math.max(len, profile.activityContour().length);
        if (profile.voicingContour() != null) len = Math.max(len, profile.voicingContour().length);
        if (len <= 0) return 0.0f;
        float fullSec = (len * profile.contourStepMs()) / 1000.0f;
        float voicedSec = 0.0f;
        if (profile.voicingContour() != null && profile.voicingContour().length > 0) {
            for (float v : profile.voicingContour()) {
                voicedSec += clampVoiceVoxProsody(v, 0.0f, 1.0f);
            }
            voicedSec = (voicedSec * profile.contourStepMs()) / 1000.0f;
        }
        float activeSec = 0.0f;
        if (profile.activityContour() != null && profile.activityContour().length > 0) {
            for (float v : profile.activityContour()) {
                activeSec += clampVoiceVoxProsody(v, 0.0f, 1.0f);
            }
            activeSec = (activeSec * profile.contourStepMs()) / 1000.0f;
        }
        float effectiveSec = Math.max(voicedSec, (activeSec > 0.0f) ? (voicedSec + ((activeSec - voicedSec) * 0.35f)) : voicedSec);
        if (effectiveSec >= 0.18f) {
            return Math.min(fullSec, effectiveSec);
        }
        return fullSec;
    }
    private static int indexOfMax(int[] values) {
        int idx = -1;
        int best = Integer.MIN_VALUE;
        for (int i = 0; i < values.length; i++) {
            if (values[i] > best) {
                best = values[i];
                idx = i;
            }
        }
        return idx;
    }
    private static int indexOfMax(double[] values) {
        int idx = -1;
        double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < values.length; i++) {
            if (values[i] > best) {
                best = values[i];
                idx = i;
            }
        }
        return idx;
    }
    private static float[] normalizeContourForVoiceVox(float[] contour) {
        if (contour == null || contour.length == 0) return new float[0];
        float mean = 0.0f;
        for (float v : contour) mean += v;
        mean /= contour.length;
        float[] centered = new float[contour.length];
        for (int i = 0; i < contour.length; i++) {
            centered[i] = contour[i] - mean;
        }
        if (centered.length >= 3) {
            float[] smoothed = centered.clone();
            for (int i = 1; i < centered.length - 1; i++) {
                smoothed[i] = (centered[i - 1] * 0.20f) + (centered[i] * 0.60f) + (centered[i + 1] * 0.20f);
            }
            centered = smoothed;
        }
        float[] absValues = new float[centered.length];
        for (int i = 0; i < centered.length; i++) {
            absValues[i] = Math.abs(centered[i]);
        }
        Arrays.sort(absValues);
        float normBase = absValues[Math.max(0, (int) Math.floor((absValues.length - 1) * 0.90d))];
        normBase = Math.max(0.85f, normBase);
        for (int i = 0; i < centered.length; i++) {
            centered[i] = clampVoiceVoxProsody(centered[i] / normBase, -1.0f, 1.0f);
        }
        return centered;
    }
    private static String shortenForProsodyLog(String text, int max) {
        if (text == null) return "";
        String s = text.replace("\r", " ").replace("\n", " ").trim();
        return (s.length() <= max) ? s : s.substring(0, max) + "...";
    }
    private record TimingUnit(JSONObject mora, boolean pause, boolean voiced) {}
    private record ProsodySegment(boolean rest, int startFrame, int endFrame, int frameCount, float meanPitch, float meanActivity, float meanVoicing) {
        private ProsodySegment merge(ProsodySegment other) {
            int totalFrames = Math.max(1, this.frameCount + other.frameCount);
            float mergedPitch;
            if (this.rest && other.rest) {
                mergedPitch = 0.0f;
            } else if (this.rest) {
                mergedPitch = other.meanPitch;
            } else if (other.rest) {
                mergedPitch = this.meanPitch;
            } else {
                mergedPitch = ((this.meanPitch * this.frameCount) + (other.meanPitch * other.frameCount)) / totalFrames;
            }
            return new ProsodySegment(
                    this.rest && other.rest,
                    this.startFrame,
                    other.endFrame,
                    totalFrames,
                    mergedPitch,
                    ((this.meanActivity * this.frameCount) + (other.meanActivity * other.frameCount)) / totalFrames,
                    ((this.meanVoicing * this.frameCount) + (other.meanVoicing * other.frameCount)) / totalFrames
            );
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
        speakWindows(text, null);
    }
    public void speakWindows(String text, TtsProsodyProfile ttsProsodyProfile) {
        if (text == null || text.isBlank()) return;
        try {
            WindowsNarratorProsodySettings settings = buildWindowsNarratorProsodySettings(text, ttsProsodyProfile);
            byte[] wavBytes;
            if (settings.ssml() != null && !settings.ssml().isBlank()) {
                try {
                    wavBytes = synthWindowsToWavBytesViaAgent(text, settings.ssml(), settings.rate(), settings.volume());
                } catch (Exception ssmlEx) {
                    Config.logDebug("[WIN WARN] ssml synth failed -> fallback plain: " + ssmlEx.getMessage());
                    wavBytes = synthWindowsToWavBytesViaAgent(text, settings.rate(), settings.volume());
                }
            } else {
                wavBytes = synthWindowsToWavBytesViaAgent(text, settings.rate(), settings.volume());
            }
            if (!looksLikeWav(wavBytes)) {
                Config.logDebug("[WIN] synth bytes not wav");
                return;
            }
            playViaPowerShellBytesAsync(wavBytes);
        } catch (Exception ex) {
            Config.logDebug("[WIN ERROR] " + ex.getMessage());
        }
    }
    private WindowsNarratorProsodySettings buildWindowsNarratorProsodySettings(String text, TtsProsodyProfile profile) {
        if (text == null || text.isBlank()) {
            return new WindowsNarratorProsodySettings(0, 100, null, "mode=plain-empty");
        }
        if (profile == null || !prefs.getBoolean("tts.reflect_emotion", true) || !profile.isUsable()) {
            String logLine = "[TTS_PROSODY][WIN] mode=tempo-only-off rate=0 volume=100 text="
                    + shortenForProsodyLog(text, 80);
            Config.log(logLine);
            return new WindowsNarratorProsodySettings(0, 100, null, logLine);
        }

        float contourStrength = getVoiceVoxContourReflectionStrength();
        float toneStrength = getVoiceVoxToneEmphasisStrength();
        String contourMode = prefs.get("tts.reflect.contour_strength", "normal");
        String toneMode = prefs.get("tts.reflect.tone_emphasis", "normal");
        boolean strongMode = "strong".equalsIgnoreCase(contourMode) || "strong".equalsIgnoreCase(toneMode);
        boolean mildMode = !strongMode && ("mild".equalsIgnoreCase(contourMode) || "mild".equalsIgnoreCase(toneMode));
        float weightFloor = strongMode ? 0.65f : (mildMode ? 0.62f : 0.55f);
        float weight = windowsProsodyWeight(profile.confidence(), weightFloor);
        int rateCap = strongMode ? 45 : (mildMode ? 18 : 24);
        float pitchCap = strongMode ? 6.5f : (mildMode ? 2.5f : 3.5f);
        float inferredSpeedScale = inferWindowsNarratorSpeedScale(text, profile);
        float speedSignal = (inferredSpeedScale - 1.0f);
        float tempoSignal = speedSignal * 140.0f;
        // Windows narration: keep cadence assists one-way so fast speech is never inverted to slow.
        float positiveAssist = Math.max(0.0f, profile.energyScore() - 0.35f) * (8.0f * contourStrength);
        float negativeAssist = Math.max(0.0f, profile.darkScore() - 0.35f) * (6.0f * toneStrength);
        if (speedSignal >= 0.0f) {
            tempoSignal += positiveAssist;
        } else {
            tempoSignal -= negativeAssist;
        }
        int ratePercent = Math.max(-rateCap, Math.min(rateCap,
                Math.round(clampVoiceVoxProsody(tempoSignal * weight, -rateCap, rateCap))));
        if (speedSignal >= 0.0f && ratePercent < 0) {
            ratePercent = 0;
        } else if (speedSignal <= 0.0f && ratePercent > 0) {
            ratePercent = 0;
        }
        int legacyRate = Math.max(-6, Math.min(6, Math.round(ratePercent / 4.5f)));

        float dark = profile.darkScore();
        float energy = profile.energyScore();
        float toneDelta = energy - dark;
        float contourPitch = clampVoiceVoxProsody(profile.contourRise(), -0.65f, 0.65f);
        float tonePitch = clampVoiceVoxProsody(toneDelta, -0.55f, 0.55f);
        float pitchSemitone = 0.0f;
        if (Math.abs(contourPitch) >= 0.10f || Math.abs(tonePitch) >= 0.14f || Math.abs(profile.pitchScale()) >= 0.012f) {
            pitchSemitone = clampVoiceVoxProsody(
                    (contourPitch * 2.2f * contourStrength)
                            + (tonePitch * 1.3f * toneStrength)
                            + (profile.pitchScale() * 18.0f),
                    -pitchCap, pitchCap);
        }

        int volume = 100;
        float weightedTone = toneDelta * toneStrength * weight;
        if (weightedTone >= 0.28f) {
            volume = 100;
        } else if (weightedTone <= -0.26f || profile.isDarkLike()) {
            // Keep dark tone audible on Windows Narrator; avoid dropping to "soft".
            volume = 96;
        } else if (weightedTone <= -0.12f) {
            volume = 98;
        }

        String ssmlLang = resolveWindowsNarratorSsmlLang();
        String ssml = buildWindowsNarratorSsml(text, ratePercent, pitchSemitone, volume, ssmlLang);

        String logLine = "[TTS_PROSODY][WIN] mood=" + profile.mood()
                + " conf=" + String.format(Locale.ROOT, "%.2f", profile.confidence())
                + " mode=single-ssml"
                + " rate=" + legacyRate
                + " ratePct=" + ratePercent + "%"
                + " pitchSt=" + String.format(Locale.ROOT, "%.2f", pitchSemitone)
                + " volume=" + volume
                + " lang=" + ssmlLang
                + " wFloor=" + String.format(Locale.ROOT, "%.2f", weightFloor)
                + " w=" + String.format(Locale.ROOT, "%.2f", weight)
                + " rateCap=" + rateCap
                + " pitchCap=" + String.format(Locale.ROOT, "%.2f", pitchCap)
                + " speedSig=" + String.format(Locale.ROOT, "%.3f", speedSignal)
                + " inferSpeed=" + String.format(Locale.ROOT, "%.3f", inferredSpeedScale)
                + " posAs=" + String.format(Locale.ROOT, "%.3f", positiveAssist)
                + " negAs=" + String.format(Locale.ROOT, "%.3f", negativeAssist)
                + " speed=" + String.format(Locale.ROOT, "%.3f", profile.speedScale())
                + " rise=" + String.format(Locale.ROOT, "%.3f", profile.contourRise())
                + " melody=" + String.format(Locale.ROOT, "%.3f", profile.melodyDepth())
                + " dark=" + String.format(Locale.ROOT, "%.3f", profile.darkScore())
                + " energy=" + String.format(Locale.ROOT, "%.3f", profile.energyScore())
                + " text=" + shortenForProsodyLog(text, 80);
        Config.log(logLine);
        return new WindowsNarratorProsodySettings(legacyRate, volume, ssml, logLine);
    }
    private float inferWindowsNarratorSpeedScale(String text, TtsProsodyProfile profile) {
        if (profile == null) return 1.0f;
        float base = profile.speedScale();
        if (Math.abs(base - 1.0f) >= 0.025f) {
            return base;
        }
        if (!profile.hasTimingContour() || profile.contourStepMs() <= 0) {
            return base;
        }
        int unitCount = estimateSpeechUnits(text);
        if (unitCount <= 0) {
            return base;
        }
        float[] voicing = profile.voicingContour();
        float[] activity = profile.activityContour();
        int frameCount = Math.max(
                (activity == null) ? 0 : activity.length,
                (voicing == null) ? 0 : voicing.length
        );
        if (frameCount < 6 || unitCount < 5) {
            return base;
        }
        int activeFrames = 0;
        int voicedFrames = 0;
        for (int i = 0; i < frameCount; i++) {
            float a = (activity != null && i < activity.length) ? activity[i] : 0.0f;
            float v = (voicing != null && i < voicing.length) ? voicing[i] : 0.0f;
            if (a >= 0.30f || v >= 0.35f) {
                activeFrames++;
            }
            if (v >= 0.35f) {
                voicedFrames++;
            }
        }
        float activeRatio = activeFrames / (float) frameCount;
        float voicedRatio = voicedFrames / (float) frameCount;
        if (activeRatio < 0.35f && voicedRatio < 0.30f) {
            return base;
        }
        float utterMs = frameCount * profile.contourStepMs();
        float msPerUnit = utterMs / Math.max(1.0f, unitCount);
        float timingSignal = clampVoiceVoxProsody((115.0f - msPerUnit) / 120.0f, -0.12f, 0.12f);
        float contourAssist = clampVoiceVoxProsody(profile.contourRise() * 0.03f, -0.02f, 0.02f);
        float melodyAssist = clampVoiceVoxProsody((profile.melodyDepth() - 0.40f) * 0.03f, -0.02f, 0.02f);
        return clampVoiceVoxProsody(1.0f + timingSignal + contourAssist + melodyAssist, 0.92f, 1.08f);
    }
    private int estimateSpeechUnits(String text) {
        if (text == null || text.isBlank()) return 0;
        int units = 0;
        boolean inLatinWord = false;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                inLatinWord = false;
                continue;
            }
            if (isSentencePunctuationCp(cp)) {
                inLatinWord = false;
                continue;
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HAN) {
                units++;
                inLatinWord = false;
                continue;
            }
            if (Character.isLetterOrDigit(cp)) {
                if (!inLatinWord) {
                    units++;
                    inLatinWord = true;
                }
                continue;
            }
            inLatinWord = false;
        }
        return units;
    }
    private boolean isSentencePunctuationCp(int cp) {
        return switch (cp) {
            case '.', ',', '!', '?', ';', ':', '。', '、', '！', '？', '，', '．', '・', '…' -> true;
            default -> false;
        };
    }
    private static float windowsProsodyWeight(float confidence, float floor) {
        float c = Math.max(0.0f, Math.min(1.0f, confidence));
        float f = Math.max(0.25f, Math.min(0.8f, floor));
        return f + ((1.0f - f) * (float) Math.pow(c, 0.7d));
    }
    private String buildWindowsNarratorSsml(String text, int ratePercent, float pitchSemitone, int volume, String lang) {
        String escaped = escapeXmlForSsml(text);
        String rateAttr = formatSsmlPercent(ratePercent);
        String pitchAttr = formatSsmlPitchPercent(pitchSemitone);
        // "soft" is too attenuated for live talkback; keep floor at "medium".
        String volumeAttr = (volume >= 100) ? "default" : "medium";
        String xmlLang = (lang == null || lang.isBlank()) ? "ja-JP" : lang;
        return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"" + xmlLang + "\">"
                + "<prosody rate=\"" + rateAttr + "\" pitch=\"" + pitchAttr + "\" volume=\"" + volumeAttr + "\">"
                + escaped
                + "</prosody></speak>";
    }
    private String resolveWindowsNarratorSsmlLang() {
        String voiceName = resolveWindowsAutoVoiceName();
        if (voiceName != null && !voiceName.isBlank() && !"auto".equalsIgnoreCase(voiceName)) {
            String key = voiceName.trim().toLowerCase(Locale.ROOT);
            for (WindowsVoiceInfo info : getWindowsVoiceInfos()) {
                if (info == null || info.name.isBlank()) continue;
                if (info.name.trim().toLowerCase(Locale.ROOT).equals(key)) {
                    String culture = normalizeCultureForSsml(info.culture);
                    if (!culture.isBlank()) return culture;
                    break;
                }
            }
        }
        String out = (getTalkOutputLanguage() == null) ? "" : getTalkOutputLanguage().trim().toLowerCase(Locale.ROOT);
        return switch (out) {
            case "ja" -> "ja-JP";
            case "en" -> "en-US";
            case "ko" -> "ko-KR";
            case "zh" -> "zh-CN";
            case "yue" -> "zh-HK";
            case "fr" -> "fr-FR";
            case "de" -> "de-DE";
            case "es" -> "es-ES";
            case "it" -> "it-IT";
            case "pt" -> "pt-PT";
            case "ru" -> "ru-RU";
            default -> "ja-JP";
        };
    }
    private static String normalizeCultureForSsml(String culture) {
        if (culture == null) return "";
        String trimmed = culture.trim();
        if (trimmed.isBlank()) return "";
        String normalized = trimmed.replace('_', '-');
        String[] parts = normalized.split("-");
        if (parts.length == 1) {
            String lang = parts[0].toLowerCase(Locale.ROOT);
            return switch (lang) {
                case "ja" -> "ja-JP";
                case "en" -> "en-US";
                case "ko" -> "ko-KR";
                case "zh" -> "zh-CN";
                case "fr" -> "fr-FR";
                case "de" -> "de-DE";
                case "es" -> "es-ES";
                case "it" -> "it-IT";
                case "pt" -> "pt-PT";
                case "ru" -> "ru-RU";
                default -> lang;
            };
        }
        String lang = parts[0].toLowerCase(Locale.ROOT);
        String region = parts[1].toUpperCase(Locale.ROOT);
        return lang + "-" + region;
    }
    private static String escapeXmlForSsml(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
    private static String formatSsmlPercent(int percent) {
        int clamped = Math.max(-30, Math.min(30, percent));
        return (clamped >= 0 ? "+" : "") + clamped + "%";
    }
    private static String formatSsmlPitchPercent(float semitone) {
        float clamped = clampVoiceVoxProsody(semitone, -6.5f, 6.5f);
        double ratio = Math.pow(2.0d, clamped / 12.0d);
        int percent = (int) Math.round((ratio - 1.0d) * 100.0d);
        int bounded = Math.max(-45, Math.min(45, percent));
        return (bounded >= 0 ? "+" : "") + bounded + "%";
    }
    private static final class WindowsNarratorProsodySettings {
        private final Integer rate;
        private final Integer volume;
        private final String ssml;
        private final String logLine;
        private WindowsNarratorProsodySettings(Integer rate, Integer volume, String ssml, String logLine) {
            this.rate = rate;
            this.volume = volume;
            this.ssml = ssml;
            this.logLine = logLine;
        }
        private Integer rate() { return rate; }
        private Integer volume() { return volume; }
        private String ssml() { return ssml; }
        @SuppressWarnings("unused")
        private String logLine() { return logLine; }
    }
    private String resolveWindowsAutoVoiceName() {
        String configured = "auto";
        try {
            if (prefs != null) configured = prefs.get("tts.windows.voice", "auto");
        } catch (Exception ignore) {}

        if (!"auto".equalsIgnoreCase(configured == null ? "auto" : configured.trim())) {
            return configured;
        }

        String translateTarget = getTalkTranslateTarget();
        if ("OFF".equals(translateTarget)) {
            return "auto";
        }

        WindowsVoiceInfo match = findBestWindowsVoiceForLanguage(getTalkOutputLanguage());
        if (match != null && !match.name.isBlank()) {
            Config.logDebug("[WIN] auto voice matched output lang=" + getTalkOutputLanguage() + " -> " + match.name + " (" + match.culture + ")");
            return match.name;
        }
        Config.logDebug("[WIN] auto voice fallback: no installed voice for output lang=" + getTalkOutputLanguage());
        return "auto";
    }
    private WindowsVoiceInfo findBestWindowsVoiceForLanguage(String lang) {
        String normalized = (lang == null) ? "" : lang.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || "off".equals(normalized) || "auto".equals(normalized)) {
            return null;
        }

        List<String> candidates = new ArrayList<>();
        switch (normalized) {
            case "yue" -> {
                candidates.add("zh-hk");
                candidates.add("zh-tw");
                candidates.add("zh");
            }
            case "zh" -> {
                candidates.add("zh-cn");
                candidates.add("zh-tw");
                candidates.add("zh-hk");
                candidates.add("zh");
            }
            default -> {
                candidates.add(normalized);
                int dash = normalized.indexOf('-');
                if (dash > 0) {
                    candidates.add(normalized.substring(0, dash));
                }
            }
        }

        List<WindowsVoiceInfo> infos = getWindowsVoiceInfos();
        for (String candidate : candidates) {
            for (WindowsVoiceInfo info : infos) {
                if (candidate.equals(info.culture)) {
                    return info;
                }
            }
        }
        for (String candidate : candidates) {
            String prefix = candidate + "-";
            for (WindowsVoiceInfo info : infos) {
                if (info.culture.startsWith(prefix)) {
                    return info;
                }
            }
        }

        String nameHint = switch (normalized) {
            case "ja" -> "japanese";
            case "en" -> "english";
            case "ko" -> "korean";
            case "zh", "yue" -> "chinese";
            case "fr" -> "french";
            case "de" -> "german";
            case "es" -> "spanish";
            case "it" -> "italian";
            case "pt" -> "portuguese";
            case "ru" -> "russian";
            default -> "";
        };
        if (!nameHint.isBlank()) {
            for (WindowsVoiceInfo info : infos) {
                if (info.name.toLowerCase(Locale.ROOT).contains(nameHint)) {
                    return info;
                }
            }
        }
        return null;
    }
    private List<WindowsVoiceInfo> getWindowsVoiceInfos() {
        long now = System.currentTimeMillis();
        List<WindowsVoiceInfo> cached = windowsVoiceInfoCache;
        if (!cached.isEmpty() && (now - windowsVoiceInfoCacheLoadedAtMs) < WINDOWS_VOICE_CACHE_TTL_MS) {
            return cached;
        }

        synchronized (windowsVoiceCacheLock) {
            cached = windowsVoiceInfoCache;
            if (!cached.isEmpty() && (now - windowsVoiceInfoCacheLoadedAtMs) < WINDOWS_VOICE_CACHE_TTL_MS) {
                return cached;
            }

            List<WindowsVoiceInfo> voices = new ArrayList<>();
            try {
                Process p = new ProcessBuilder(
                        "powershell",
                        "-Command",
                        "Add-Type -AssemblyName System.Speech; " +
                                "[System.Speech.Synthesis.SpeechSynthesizer]::new().GetInstalledVoices() | " +
                                "ForEach-Object { '{0}`t{1}' -f $_.VoiceInfo.Name, $_.VoiceInfo.Culture.Name }"
                ).start();

                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line == null || line.isBlank()) continue;
                        String[] parts = line.split("\\t", 2);
                        String name = parts[0].trim();
                        String culture = (parts.length >= 2) ? parts[1].trim() : "";
                        if (!name.isBlank()) {
                            voices.add(new WindowsVoiceInfo(name, culture));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            Map<String, WindowsVoiceInfo> merged = new LinkedHashMap<>();
            for (WindowsVoiceInfo info : voices) {
                if (info == null || info.name.isBlank()) continue;
                merged.putIfAbsent(info.name.toLowerCase(Locale.ROOT), info);
            }
            for (WindowsVoiceInfo info : loadWindowsVoiceInfosFromRegistry()) {
                if (info == null || info.name.isBlank()) continue;
                merged.putIfAbsent(info.name.toLowerCase(Locale.ROOT), info);
            }
            voices = new ArrayList<>(merged.values());
            voices.sort(Comparator.comparing(v -> v.name, String.CASE_INSENSITIVE_ORDER));
            windowsVoiceInfoCache = Collections.unmodifiableList(voices);
            windowsVoiceInfoCacheLoadedAtMs = System.currentTimeMillis();
            return windowsVoiceInfoCache;
        }
    }
    private List<WindowsVoiceInfo> loadWindowsVoiceInfosFromRegistry() {
        LinkedHashMap<String, WindowsVoiceInfo> map = new LinkedHashMap<>();
        loadWindowsVoiceInfosFromRegistryPath("HKLM\\SOFTWARE\\Microsoft\\Speech\\Voices\\Tokens", map);
        loadWindowsVoiceInfosFromRegistryPath("HKLM\\SOFTWARE\\Microsoft\\Speech_OneCore\\Voices\\Tokens", map);
        return new ArrayList<>(map.values());
    }
    private void loadWindowsVoiceInfosFromRegistryPath(String root, Map<String, WindowsVoiceInfo> dest) {
        try {
            Process p = new ProcessBuilder("reg", "query", root, "/s").start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                String currentName = null;
                String currentCulture = "";
                while ((line = r.readLine()) != null) {
                    if (line == null || line.isBlank()) {
                        if (currentName != null && !currentName.isBlank()) {
                            dest.putIfAbsent(currentName.toLowerCase(Locale.ROOT), new WindowsVoiceInfo(currentName, currentCulture));
                        }
                        currentName = null;
                        currentCulture = "";
                        continue;
                    }
                    String trimmed = line.trim();
                    if (trimmed.startsWith("HKEY_")) {
                        if (currentName != null && !currentName.isBlank()) {
                            dest.putIfAbsent(currentName.toLowerCase(Locale.ROOT), new WindowsVoiceInfo(currentName, currentCulture));
                        }
                        currentName = null;
                        currentCulture = cultureFromRegistryTokenPath(trimmed);
                        continue;
                    }
                    if (trimmed.startsWith("Name")) {
                        String[] parts = trimmed.split("\\s{2,}");
                        if (parts.length >= 3) {
                            currentName = parts[2].trim();
                        }
                        continue;
                    }
                    if (trimmed.startsWith("Language")) {
                        String[] parts = trimmed.split("\\s{2,}");
                        if (parts.length >= 3) {
                            String parsed = cultureFromRegistryLanguage(parts[2].trim());
                            if (!parsed.isBlank()) currentCulture = parsed;
                        }
                    }
                }
                if (currentName != null && !currentName.isBlank()) {
                    dest.putIfAbsent(currentName.toLowerCase(Locale.ROOT), new WindowsVoiceInfo(currentName, currentCulture));
                }
            }
        } catch (Exception ignore) {
        }
    }
    private String cultureFromRegistryTokenPath(String path) {
        if (path == null) return "";
        Matcher desktop = Pattern.compile("TTS_MS_([A-Za-z]{2})-([A-Za-z]{2})_").matcher(path);
        if (desktop.find()) {
            return (desktop.group(1) + "-" + desktop.group(2)).toLowerCase(Locale.ROOT);
        }
        Matcher oneCore = Pattern.compile("MSTTS_V\\d+_([a-z]{2})([A-Z]{2})_").matcher(path);
        if (oneCore.find()) {
            return (oneCore.group(1) + "-" + oneCore.group(2)).toLowerCase(Locale.ROOT);
        }
        return "";
    }
    private String cultureFromRegistryLanguage(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "409", "0409" -> "en-us";
            case "411", "0411" -> "ja-jp";
            case "412", "0412" -> "ko-kr";
            case "804", "0804" -> "zh-cn";
            case "404", "0404" -> "zh-tw";
            case "c04", "0c04" -> "zh-hk";
            case "40c", "040c" -> "fr-fr";
            case "407", "0407" -> "de-de";
            case "40a", "040a" -> "es-es";
            case "410", "0410" -> "it-it";
            case "416", "0416" -> "pt-br";
            case "816", "0816" -> "pt-pt";
            case "419", "0419" -> "ru-ru";
            default -> "";
        };
    }
    private List<String> getWindowsVoices() {
        List<String> voices = new ArrayList<>();
        for (WindowsVoiceInfo info : getWindowsVoiceInfos()) {
            if (!info.name.isBlank()) voices.add(info.name);
        }
        if (!voices.isEmpty()) {
            return voices;
        }
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
        if (wavFile == null) return;
        playViaPowerShellPathAsync(wavFile.toPath(), false);
    }
    private void playViaPowerShellPathAsync(Path wavPath, boolean deleteAfterPlay) {
        if (wavPath == null || !Files.isRegularFile(wavPath)) return;
        try {
            updateSpeakerMeterFromWav(Files.readAllBytes(wavPath));
        } catch (Exception e) {
            Config.logDebug("Failed to play wav file: " + e.getMessage());
            return;
        }

        ttsStartedMs = System.currentTimeMillis();
        isTtsSpeaking = true;
        ttsVadSuppressUntilMs = System.currentTimeMillis() + TTS_VAD_SUPPRESS_MS;
        playExecutor.submit(() -> {
            synchronized (psLock) {
                try {
                    ensurePsServerLocked();

                    String outputDeviceName = prefs.get("audio.output.device", "").trim();
                    int devIndex = findOutputDeviceIndex(outputDeviceName);
                    String outputDeviceB64 = encodeOutputDeviceNameForAgent(outputDeviceName);
                    String escapedPath = wavPath.toAbsolutePath().toString().replace("\"", "\\\"");
                    StringBuilder cmd = new StringBuilder("PLAY \"")
                            .append(escapedPath)
                            .append("\" ")
                            .append(devIndex);
                    if (!outputDeviceB64.isEmpty()) {
                        cmd.append(' ').append(outputDeviceB64);
                    }
                    psWriter.write(cmd + "\n");
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
                    if (deleteAfterPlay) {
                        try { Files.deleteIfExists(wavPath); } catch (Exception ignore) {}
                    }
                    isTtsSpeaking = false;
                    ttsEndedMs = System.currentTimeMillis();
                }
            }
        });
    }
    private String encodeOutputDeviceNameForAgent(String deviceName) {
        String name = (deviceName == null) ? "" : deviceName.trim();
        if (name.isEmpty()) return "";
        return Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8));
    }
    public void playViaPowerShellBytesAsync(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length < 256) return;

        // ★スピーカー出力レベルを計算してメーターに反映
        updateSpeakerMeterFromWav(wavBytes);

        ttsStartedMs = System.currentTimeMillis();
        isTtsSpeaking = true;  // ★ADD: 再生開始前にフラグON
        ttsVadSuppressUntilMs = System.currentTimeMillis() + TTS_VAD_SUPPRESS_MS;
        playExecutor.submit(() -> {
            synchronized (psLock) {
                try {
                    ensurePsServerLocked();

                    String outputDeviceName = prefs.get("audio.output.device", "").trim();
                    int devIndex = findOutputDeviceIndex(outputDeviceName);
                    String outputDeviceB64 = encodeOutputDeviceNameForAgent(outputDeviceName);

                    String id = Long.toHexString(System.nanoTime());
                    String b64 = Base64.getEncoder().encodeToString(wavBytes);

                    StringBuilder cmd = new StringBuilder("PLAYB64 ")
                            .append(devIndex)
                            .append(' ')
                            .append(id);
                    if (!outputDeviceB64.isEmpty()) {
                        cmd.append(' ').append(outputDeviceB64);
                    }
                    psWriter.write(cmd + "\n");

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
                    ttsEndedMs = System.currentTimeMillis();
                }
            }
        });
    }
    public void playViaPowerShellPcm16MonoAsync(byte[] pcmBytes, int sampleRate, long synthSpawnMs, long rawReadMs) {
        if (pcmBytes == null || pcmBytes.length < 128) return;

        byte[] meterWav;
        try {
            meterWav = wrapPcm16MonoAsWav(pcmBytes, sampleRate);
        } catch (IOException ioEx) {
            Config.logDebug("[Piper+][PLAY_RAW] wav wrap failed: " + ioEx.getMessage());
            return;
        }
        updateSpeakerMeterFromWav(meterWav);

        ttsStartedMs = System.currentTimeMillis();
        isTtsSpeaking = true;
        ttsVadSuppressUntilMs = System.currentTimeMillis() + TTS_VAD_SUPPRESS_MS;
        playExecutor.submit(() -> {
            synchronized (psLock) {
                long ensureStartNs = System.nanoTime();
                long sendStartNs = 0L;
                try {
                    ensurePsServerLocked();
                    long ensureMs = (System.nanoTime() - ensureStartNs) / 1_000_000L;

                    String outputDeviceName = prefs.get("audio.output.device", "").trim();
                    int devIndex = findOutputDeviceIndex(outputDeviceName);
                    String outputDeviceB64 = encodeOutputDeviceNameForAgent(outputDeviceName);

                    String id = Long.toHexString(System.nanoTime());
                    String b64 = Base64.getEncoder().encodeToString(pcmBytes);

                    StringBuilder cmd = new StringBuilder("PLAYPCM16B64 ")
                            .append(Math.max(8000, sampleRate))
                            .append(' ')
                            .append(devIndex)
                            .append(' ')
                            .append(id);
                    if (!outputDeviceB64.isEmpty()) {
                        cmd.append(' ').append(outputDeviceB64);
                    }
                    psWriter.write(cmd + "\n");

                    sendStartNs = System.nanoTime();
                    final int CHUNK = 12000;
                    for (int i = 0; i < b64.length(); i += CHUNK) {
                        int end = Math.min(b64.length(), i + CHUNK);
                        psWriter.write("DATA " + id + " " + b64.substring(i, end) + "\n");
                    }
                    psWriter.write("ENDPCM " + id + "\n");
                    psWriter.flush();

                    String resp = psReader.readLine();
                    long doneNs = System.nanoTime();
                    if (!"DONE".equals(resp)) {
                        psHealthCounter = 0;
                        throw new IOException("tts_agent bad response: " + resp);
                    }
                    long sendMs = (doneNs - sendStartNs) / 1_000_000L;
                    long preSendMs = synthSpawnMs + rawReadMs + sendMs;
                    Config.logDebug("[Piper+][LAT] spawn_ms=" + synthSpawnMs
                            + " raw_pcm_read_ms=" + rawReadMs
                            + " base64_send_ms=" + sendMs
                            + " pre_send_ms~=" + preSendMs
                            + " pcm_bytes=" + pcmBytes.length
                            + " sr=" + sampleRate);
                    psHealthCounter++;
                    if (psHealthCounter >= PS_HEALTH_CHECK_INTERVAL) psHealthCounter = 0;
                } catch (Exception e) {
                    Config.log("TTS raw agent error: " + e.getMessage());
                    if (e instanceof IOException) {
                        Config.log("TTS raw pipe dead → restarting agent");
                        stopPsServerLocked();
                    }
                    playViaPowerShellBytesAsync(meterWav);
                } finally {
                    isTtsSpeaking = false;
                    ttsEndedMs = System.currentTimeMillis();
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
                return i;
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
            if (looksLikeVirtualAudioDeviceName(info.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeVirtualAudioDeviceName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }

        String lower = name.toLowerCase(Locale.ROOT);
        String[] hints = {
                "virtual audio",
                "virtual cable",
                "virtual audio cable",
                "vb-audio",
                "vb cable",
                "cable input",
                "cable output",
                "voicemeeter",
                "sonar"
        };
        for (String hint : hints) {
            if (lower.contains(hint)) {
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

    private Path getPresetDirPath() {
        return getExeDir().toPath().resolve(PRESET_DIR_NAME);
    }

    private static String normalizePresetDisplayName(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ");
    }

    private static String sanitizePresetFileBase(String name) {
        String normalized = normalizePresetDisplayName(name);
        normalized = normalized.replaceAll("[\\\\/:*?\"<>|]", "_");
        normalized = normalized.replaceAll("[. ]+$", "");
        if (normalized.isBlank()) normalized = "preset";
        return normalized;
    }
    private static String presetNameFromPath(Path path) {
        if (path == null) return "";
        String fileName = Objects.toString(path.getFileName(), "");
        if (fileName.toLowerCase(Locale.ROOT).endsWith(PRESET_EXT)) {
            fileName = fileName.substring(0, fileName.length() - PRESET_EXT.length());
        }
        return normalizePresetDisplayName(fileName);
    }
    private static boolean isPresetManagedKey(String key) {
        return PRESET_KEYS.containsKey(key) || PRESET_CONFIG_KEYS.containsKey(key);
    }

    private List<TalkPreset> loadTalkPresets() {
        List<TalkPreset> presets = new ArrayList<>();
        try {
            Path dir = getPresetDirPath();
            if (!Files.isDirectory(dir)) return presets;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + PRESET_EXT)) {
                for (Path path : stream) {
                    TalkPreset preset = loadTalkPreset(path);
                    if (preset != null && !preset.name.isBlank()) presets.add(preset);
                }
            }
        } catch (Exception e) {
            Config.logDebug("[Preset] load list failed: " + e.getMessage());
        }
        presets.sort(Comparator.comparing(p -> p.name, String.CASE_INSENSITIVE_ORDER));
        return presets;
    }

    private TalkPreset loadTalkPreset(Path path) {
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            for (String raw : lines) {
                if (raw == null) continue;
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1);
                if (isPresetManagedKey(key)) {
                    values.put(key, value);
                }
            }
            String displayName = presetNameFromPath(path);
            return new TalkPreset(displayName, path, values);
        } catch (Exception e) {
            Config.logDebug("[Preset] load failed: " + path + " / " + e.getMessage());
            return null;
        }
    }

    private String readPresetSourceValue(String key, String fallback) {
        return Objects.toString(prefs.get(key, fallback), fallback);
    }
    private String readPresetConfigValue(String key, String fallback) {
        return Objects.toString(Config.loadSetting(key, fallback), fallback);
    }
    private void writePresetConfigValue(String key, String value, boolean quote) throws IOException {
        if (value == null) value = "";
        Path f = Path.of(System.getProperty("user.dir"), "_outtts.txt");

        List<String> lines = Files.exists(f)
                ? Files.readAllLines(f, StandardCharsets.UTF_8)
                : new ArrayList<>();

        int markerIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(OUTTTS_MARKER)) {
                markerIndex = i;
                break;
            }
        }

        List<String> settings = (markerIndex >= 0)
                ? new ArrayList<>(lines.subList(0, markerIndex))
                : new ArrayList<>(lines);

        String formattedValue = quote
                ? ("\"" + value.replace("\"", "") + "\"")
                : value;
        String newLine = key + "=" + formattedValue;

        boolean replaced = false;
        for (int i = 0; i < settings.size(); i++) {
            String raw = settings.get(i);
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) continue;
            int eq = t.indexOf('=');
            String existingKey = t.substring(0, eq).trim();
            if (existingKey.equals(key)) {
                settings.set(i, newLine);
                replaced = true;
                break;
            }
        }
        if (!replaced) settings.add(newLine);

        List<String> out = new ArrayList<>(settings);
        if (markerIndex >= 0) out.addAll(lines.subList(markerIndex, lines.size()));
        Files.write(f, out, StandardCharsets.UTF_8);
    }

    private Path saveCurrentSettingsAsPreset(String displayName) throws IOException {
        String normalizedName = normalizePresetDisplayName(displayName);
        if (normalizedName.isBlank()) throw new IOException("preset name empty");
        Path dir = getPresetDirPath();
        Files.createDirectories(dir);
        Path path = dir.resolve(sanitizePresetFileBase(normalizedName) + PRESET_EXT);
        Map<String, String> overrides = new LinkedHashMap<>();
        if (settingsCenterFrame != null && settingsCenterFrame.isDisplayable()) {
            try {
                overrides.putAll(settingsCenterFrame.collectPresetSnapshotOverrides());
            } catch (Exception ex) {
                Config.logDebug("[Preset] settings snapshot override failed: " + ex.getMessage());
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("# MobMate preset");
        for (Map.Entry<String, String> entry : PRESET_KEYS.entrySet()) {
            String value = overrides.containsKey(entry.getKey())
                    ? Objects.toString(overrides.get(entry.getKey()), entry.getValue())
                    : readPresetSourceValue(entry.getKey(), entry.getValue());
            lines.add(entry.getKey() + "=" + value);
        }
        for (Map.Entry<String, String> entry : PRESET_CONFIG_KEYS.entrySet()) {
            String value = overrides.containsKey(entry.getKey())
                    ? Objects.toString(overrides.get(entry.getKey()), entry.getValue())
                    : readPresetConfigValue(entry.getKey(), entry.getValue());
            lines.add(entry.getKey() + "=" + value);
        }
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return path;
    }

    private TalkPreset findPresetByName(String displayName) {
        String target = normalizePresetDisplayName(displayName);
        String sanitizedTarget = sanitizePresetFileBase(displayName);
        for (TalkPreset preset : loadTalkPresets()) {
            if (preset.name.equalsIgnoreCase(target) || preset.name.equalsIgnoreCase(sanitizedTarget)) return preset;
        }
        return null;
    }

    private void deletePresetByName(String displayName) throws IOException {
        TalkPreset preset = findPresetByName(displayName);
        if (preset != null && preset.path != null) Files.deleteIfExists(preset.path);
    }

    public void saveSelectedPresetSnapshotIfAny() {
        String selected = normalizePresetDisplayName(prefs.get(PREF_SELECTED_PRESET, ""));
        if (selected.isBlank()) return;
        try {
            TalkPreset preset = findPresetByName(selected);
            String actualName = (preset != null && preset.name != null && !preset.name.isBlank())
                    ? preset.name
                    : sanitizePresetFileBase(selected);
            Path savedPath = saveCurrentSettingsAsPreset(actualName);
            String canonicalName = presetNameFromPath(savedPath);
            if (!canonicalName.isBlank() && !canonicalName.equals(selected)) {
                prefs.put(PREF_SELECTED_PRESET, canonicalName);
                try { prefs.sync(); } catch (Exception ignore) {}
            }
            Config.log("[Preset] autosave: " + canonicalName);
        } catch (Exception ex) {
            Config.logError("[Preset] autosave failed: " + selected, ex);
        }
    }

    private void applyPresetByName(String displayName) {
        TalkPreset preset = findPresetByName(displayName);
        if (preset == null) {
            JOptionPane.showMessageDialog(window,
                    uiOr("ui.main.preset.notFound", "Preset not found: %s").formatted(displayName),
                    "MobMate",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            for (Map.Entry<String, String> entry : preset.values.entrySet()) {
                if (PRESET_KEYS.containsKey(entry.getKey())) {
                    prefs.put(entry.getKey(), entry.getValue());
                } else if (PRESET_CONFIG_KEYS.containsKey(entry.getKey())) {
                    writePresetConfigValue(
                            entry.getKey(),
                            entry.getValue(),
                            PRESET_CONFIG_QUOTED_KEYS.contains(entry.getKey())
                    );
                }
            }
            prefs.put(PREF_SELECTED_PRESET, preset.name);
            try { prefs.sync(); } catch (Exception ignore) {}
            Config.log("[Preset] apply: " + preset.name);
            softRestart();
        } catch (Exception e) {
            Config.logError("[Preset] apply failed: " + preset.name, e);
            JOptionPane.showMessageDialog(window,
                    uiOr("ui.main.preset.applyFailed", "Preset apply failed:\n%s").formatted(e.getMessage()),
                    "MobMate",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void populatePresetCombo(JComboBox<String> combo) {
        presetComboUpdating = true;
        try {
            combo.removeAllItems();
            combo.addItem(presetPlaceholderText());
            for (TalkPreset preset : loadTalkPresets()) {
                combo.addItem(preset.name);
            }
            String selected = prefs.get(PREF_SELECTED_PRESET, "");
            if (!selected.isBlank()) {
                combo.setSelectedItem(selected);
                if (combo.getSelectedIndex() < 0) {
                    String sanitized = sanitizePresetFileBase(selected);
                    combo.setSelectedItem(sanitized);
                    if (combo.getSelectedIndex() >= 0) {
                        prefs.put(PREF_SELECTED_PRESET, sanitized);
                        try { prefs.sync(); } catch (Exception ignore) {}
                    }
                }
            }
            if (combo.getSelectedIndex() < 0) combo.setSelectedIndex(0);
        } finally {
            presetComboUpdating = false;
        }
    }

    private void refreshPresetQuickMenu(JMenu presetMenu) {
        presetMenu.removeAll();

        List<TalkPreset> presets = loadTalkPresets();
        String selected = normalizePresetDisplayName(prefs.get(PREF_SELECTED_PRESET, ""));

        if (presets.isEmpty()) {
            JMenuItem emptyItem = new JMenuItem(uiOr("ui.main.preset.none", "(No presets)"));
            emptyItem.setEnabled(false);
            presetMenu.add(emptyItem);
        } else {
            ButtonGroup group = new ButtonGroup();
            for (TalkPreset preset : presets) {
                JRadioButtonMenuItem item = new JRadioButtonMenuItem(
                        preset.name,
                        !selected.isBlank() && preset.name.equalsIgnoreCase(selected)
                );
                item.setToolTipText(uiOr("ui.main.preset.tip", "Apply saved preset"));
                item.addActionListener(e -> {
                    if (preset.name.equals(prefs.get(PREF_SELECTED_PRESET, ""))) return;
                    saveSelectedPresetSnapshotIfAny();
                    applyPresetByName(preset.name);
                });
                group.add(item);
                presetMenu.add(item);
            }
        }

        presetMenu.addSeparator();

        JMenuItem saveItem = new JMenuItem(uiOr("ui.main.preset.add.tip", "Save current settings as preset"));
        saveItem.addActionListener(e -> promptAndSavePreset(prefs.get(PREF_SELECTED_PRESET, "")));
        presetMenu.add(saveItem);

        JMenuItem deleteItem = new JMenuItem(uiOr("ui.main.preset.delete.tip", "Delete selected preset"));
        deleteItem.setEnabled(!selected.isBlank() && findPresetByName(selected) != null);
        deleteItem.addActionListener(e -> deleteSelectedPresetWithConfirm());
        presetMenu.add(deleteItem);
    }

    private void promptAndSavePreset(String initialValue) {
        String initial = normalizePresetDisplayName(initialValue);
        if (presetPlaceholderText().equals(initial)) initial = "";
        String name = JOptionPane.showInputDialog(
                window,
                uiOr("ui.main.preset.name.prompt", "Preset name"),
                initial
        );
        if (name == null) return;
        name = normalizePresetDisplayName(name);
        if (name.isBlank()) return;
        try {
            TalkPreset existing = findPresetByName(name);
            String actualName = sanitizePresetFileBase(name);
            if (existing != null && existing.name != null && !existing.name.isBlank()) {
                actualName = existing.name;
            }
            if (existing != null) {
                int res = JOptionPane.showConfirmDialog(window,
                        uiOr("ui.main.preset.overwrite", "Overwrite preset \"%s\"?").formatted(actualName),
                        "MobMate",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE);
                if (res != JOptionPane.YES_OPTION) return;
            }
            Path savedPath = saveCurrentSettingsAsPreset(name);
            actualName = presetNameFromPath(savedPath);
            prefs.put(PREF_SELECTED_PRESET, actualName);
            try { prefs.sync(); } catch (Exception ignore) {}
        } catch (Exception ex) {
            Config.logError("[Preset] save failed: " + name, ex);
            JOptionPane.showMessageDialog(window,
                    uiOr("ui.main.preset.saveFailed", "Preset save failed:\n%s").formatted(ex.getMessage()),
                    "MobMate",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteSelectedPresetWithConfirm() {
        String selected = normalizePresetDisplayName(prefs.get(PREF_SELECTED_PRESET, ""));
        if (selected.isBlank() || presetPlaceholderText().equals(selected)) return;
        int res = JOptionPane.showConfirmDialog(window,
                uiOr("ui.main.preset.deleteConfirm", "Delete preset \"%s\"?").formatted(selected),
                "MobMate",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (res != JOptionPane.YES_OPTION) return;
        try {
            deletePresetByName(selected);
            prefs.remove(PREF_SELECTED_PRESET);
            try { prefs.sync(); } catch (Exception ignore) {}
        } catch (Exception ex) {
            Config.logError("[Preset] delete failed: " + selected, ex);
            JOptionPane.showMessageDialog(window,
                    uiOr("ui.main.preset.deleteFailed", "Preset delete failed:\n%s").formatted(ex.getMessage()),
                    "MobMate",
                    JOptionPane.ERROR_MESSAGE);
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
        final boolean historyWasOpen =
                (historyFrame != null && historyFrame.isVisible());
        final boolean hearingWasOpen =
                (hearingFrame != null && hearingFrame.isVisible());
        Config.log("[SOFT_RESTART] historyWasOpen=" + historyWasOpen + ", hearingWasOpen=" + hearingWasOpen);
        setHistoryStartupPref(historyWasOpen);
        pendingHistoryRestore = historyWasOpen;
        suppressHistoryCloseCallbacks = true;

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
        synchronized (hearingMoonshineLock) {
            if (hearingMoonshine != null) {
                try { hearingMoonshine.close(); } catch (Throwable ignore) {}
                hearingMoonshine = null;
            }
        }

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
        unloadTalkPostProcessor();
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
        int fontSize = prefs.getInt("ui.font.size", DEFAULT_UI_FONT_SIZE);
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
                    try { MobMateWhisp.this.w.setLanguage(getTalkLanguage()); } catch (Exception ignore) {}
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
                prefs.getFloat("speaker.threshold_target", prefs.getFloat("speaker.threshold_initial", 0.60f))
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

    private static ProcessBuilder createRestartProcessBuilder() throws Exception {
        Path currentPath = Paths.get(
                MobMateWhisp.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI()
        ).toAbsolutePath().normalize();
        Path launchDir = Files.isDirectory(currentPath)
                ? currentPath
                : currentPath.getParent();
        if (launchDir == null) {
            launchDir = Paths.get(".").toAbsolutePath().normalize();
        }

        Path exeCandidate = null;
        String currentName = currentPath.getFileName() != null ? currentPath.getFileName().toString() : "";
        if (currentName.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            exeCandidate = currentPath;
        } else {
            Path siblingExe = launchDir.resolve("MobMateWhisp.exe");
            if (Files.exists(siblingExe)) {
                exeCandidate = siblingExe;
            }
        }

        ProcessBuilder pb;
        if (exeCandidate != null && Files.exists(exeCandidate)) {
            pb = new ProcessBuilder(exeCandidate.toString());
        } else {
            String javaHome = System.getProperty("java.home", "");
            String javawBin = javaHome + File.separator + "bin" + File.separator + "javaw";
            String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
            String javaCmd = new File(javawBin).exists() ? javawBin : javaBin;
            pb = new ProcessBuilder(javaCmd, "-jar", currentPath.toString());
        }

        pb.directory(launchDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return pb;
    }

    private static void restartSelf(boolean issyncneed) {
        try {
            if (issyncneed) {
                Config.mirrorAllToCloud();
            }
            ProcessBuilder pb = createRestartProcessBuilder();
            Config.log("[RESTART] launching: " + String.join(" ", pb.command())
                    + " cwd=" + pb.directory());
            pb.start();
        } catch (Exception e) {
            Config.logError("[RESTART] failed", e);
            return;
        }
        Thread haltWatchdog = new Thread(() -> {
            try {
                Thread.sleep(5000L);
                Runtime.getRuntime().halt(0);
            } catch (InterruptedException ignore) {
            }
        }, "restart-halt-watchdog");
        haltWatchdog.setDaemon(true);
        haltWatchdog.start();
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
    private boolean compactLabels = false;

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
    public void setCompactLabels(boolean compactLabels) {
        this.compactLabels = compactLabels;
        repaint();
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

            // ★dBテキスト（compactLabels時は Hearing 用にかなり短くする）
            String micText;
            String speakerText;
            Font base = getFont();
        boolean autoCompact = compactLabels;
            if (autoCompact) {
                int micDb = (int) Math.round(Math.max(-20.0, dispDb));
                int spDb = (int) Math.round(Math.max(-20.0, dispSpeakerDb));
                micText = String.format(Locale.ROOT, "%+ddB", micDb);
                speakerText = String.format(Locale.ROOT, "%+ddB", spDb);
            } else {
                String micDbStr = String.format(Locale.ROOT, "%+.1f dB", dispDb);
                float effective = userMul * autoMul;
                String gainStr;
                if (auto) {
                    gainStr = String.format(Locale.ROOT, "A-x%.2f", effective);
                } else {
                    gainStr = (userMul > 1.01f) ? String.format(Locale.ROOT, "x%.1f", userMul) : "";
                }
                micText = gainStr.isEmpty() ? micDbStr : (micDbStr + " " + gainStr);
                speakerText = String.format(Locale.ROOT, "%+.1f dB", dispSpeakerDb);
            }

            float textSize = autoCompact
                    ? Math.max(8f, Math.min(base.getSize2D() * 0.70f, h - 6f))
                    : Math.max(9f, Math.min(base.getSize2D() * 0.75f, h - 6f));
            Font small = base.deriveFont(textSize);
            g2.setFont(small);

            FontMetrics fm = g2.getFontMetrics();
            int ty = pad + barH - 4;

            int micTx = pad + 4;
            g2.setColor(new Color(0, 0, 0, 140));
            g2.drawString(micText, micTx + 1, ty + 1);
            g2.setColor(new Color(235, 235, 235, 220));
            g2.drawString(micText, micTx, ty);

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

        int size = MobMateWhisp.DEFAULT_UI_FONT_SIZE;
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
        if (root == null) return;
        SwingUtilities.updateComponentTreeUI(root);
        root.invalidate();
        root.validate();
        root.repaint();
    }

    public static void ensureWindowFitsPreferredSize(Window root) {
        if (root == null) return;

        Dimension preferred = root.getPreferredSize();
        Dimension minimum = root.getMinimumSize();
        if (preferred == null) return;

        Rectangle bounds = root.getBounds();
        int width = Math.max(bounds.width, Math.max(preferred.width, minimum.width));
        int height = Math.max(bounds.height, Math.max(preferred.height, minimum.height));

        Rectangle usable = findUsableScreenBounds(bounds);
        int x = bounds.x;
        int y = bounds.y;
        if (usable != null) {
            width = Math.min(width, usable.width);
            height = Math.min(height, usable.height);
            x = Math.max(usable.x, Math.min(x, usable.x + usable.width - width));
            y = Math.max(usable.y, Math.min(y, usable.y + usable.height - height));
        }

        if (x != bounds.x || y != bounds.y || width != bounds.width || height != bounds.height) {
            root.setBounds(x, y, width, height);
        }
        root.validate();
    }

    private static Rectangle findUsableScreenBounds(Rectangle target) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Rectangle best = null;
        long bestArea = Long.MIN_VALUE;

        for (GraphicsDevice gd : ge.getScreenDevices()) {
            GraphicsConfiguration gc = gd.getDefaultConfiguration();
            Rectangle raw = gc.getBounds();
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            Rectangle usable = new Rectangle(
                    raw.x + insets.left,
                    raw.y + insets.top,
                    Math.max(1, raw.width - insets.left - insets.right),
                    Math.max(1, raw.height - insets.top - insets.bottom)
            );
            Rectangle inter = usable.intersection(target);
            long area = (long) Math.max(0, inter.width) * Math.max(0, inter.height);
            if (area > bestArea) {
                bestArea = area;
                best = usable;
            }
        }

        return best;
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

        // TTS fine-tuned weights (/tts本体)
        java.nio.file.Path gptWeights = gptSoVits.resolve("GPT_weights_v2");
        if (!java.nio.file.Files.isDirectory(gptWeights)) {
            miss.add("GPT-SoVITS/GPT_weights_v2/");
        } else {
            try (var s = java.nio.file.Files.list(gptWeights)) {
                boolean hasCkpt = s.anyMatch(p -> p.getFileName().toString().toLowerCase().endsWith(".ckpt"));
                if (!hasCkpt) miss.add("GPT-SoVITS/GPT_weights_v2/*.ckpt");
            } catch (Exception ignore) {
                miss.add("GPT-SoVITS/GPT_weights_v2/*.ckpt");
            }
        }

        java.nio.file.Path sovitsWeights = gptSoVits.resolve("SoVITS_weights_v2");
        if (!java.nio.file.Files.isDirectory(sovitsWeights)) {
            miss.add("GPT-SoVITS/SoVITS_weights_v2/");
        } else {
            try (var s = java.nio.file.Files.list(sovitsWeights)) {
                boolean hasPth = s.anyMatch(p -> p.getFileName().toString().toLowerCase().endsWith(".pth"));
                if (!hasPth) miss.add("GPT-SoVITS/SoVITS_weights_v2/*.pth");
            } catch (Exception ignore) {
                miss.add("GPT-SoVITS/SoVITS_weights_v2/*.pth");
            }
        }

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
