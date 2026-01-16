package whisper;

import com.sun.jna.WString;
import com.sun.jna.platform.win32.Shell32;
import whisper.Version;

import java.awt.event.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.nio.charset.StandardCharsets;
import javax.sound.sampled.*;
import java.net.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import org.json.JSONArray;
import org.json.JSONObject;

import static whisper.LocalWhisperCPP.dictionaryDirty;

public class MobMateWhisp implements NativeKeyListener {
    private static final String PREF_WIN_X = "ui.window.x";
    private static final String PREF_WIN_Y = "ui.window.y";
    private static final String PREF_WIN_W = "ui.window.w";
    private static final String PREF_WIN_H = "ui.window.h";

    private static final int MIN_AUDIO_DATA_LENGTH = 16000 * 2;

    public static Preferences prefs;
    private String lastOutput = null;
    private String cpugpumode = "";
    private Random rnd = new Random();
    private String[] laughOptions;
    private HistoryFrame historyFrame;

    // Whisper
    private LocalWhisperCPP w;
    private String model;
    private String model_dir;
    private String remoteUrl;
    // Tray icon
    private TrayIcon trayIcon;
    private Image imageRecording;
    private Image imageTranscribing;
    private Image imageInactive;

    // Execution services
    private ExecutorService executorService = Executors.newFixedThreadPool(3);
    private ExecutorService audioService = Executors.newFixedThreadPool(2);

    // Add calibration status tracking field
    private volatile boolean isCalibrationComplete = false;

    // Audio capture
    private AudioFormat audioFormat;

    private boolean recording;
    private boolean transcribing;

    // History
    public List<String> history = new ArrayList<>();
    private List<ChangeListener> historyListeners = new ArrayList<>();

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

    private static final int MAX_BUFFER_SIZE = 16000 * 10;
    private static final int WARN_BUFFER_SIZE = 16000 * 6;
    private static final AtomicLong lastFinalExecutionTime = new AtomicLong(0);
    private static AtomicBoolean isProcessingFinal =
            new AtomicBoolean(false);
    private static final AtomicBoolean isProcessingPartial =
            new AtomicBoolean(false);
    private static final AtomicReference<String> lastPartialResult =
            new AtomicReference<>("");

    private volatile boolean earlyFinalizeRequested = false;
    private volatile long earlyFinalizeUntilMs = 0;
    private static final long EARLY_FINAL_WINDOW_MS = 800;
    private static final Set<String> recentOutputs = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final int MAX_RECENT = 20;

    private volatile String lastCalibratedInputDevice = "";

    private volatile boolean voiceVoxAlive = false;
    private volatile long lastVoiceVoxCheckMs = 0;
    private static final long VOICEVOX_CHECK_INTERVAL_MS = 1000 * 30; // 5秒に1回

    private volatile String pendingLaughText = null;
    private final Object laughLock = new Object();

    private volatile long lastLaughInstantMs = 0L;
    private int laughPartialCount = 0;
    private long lastLaughPartialMs = 0;
    private static final long LAUGH_PARTIAL_WINDOW_MS = 1200;

    // ★ GPU負荷軽減モード
    public static volatile boolean lowGpuMode = false;

    private static long PARTIAL_INTERVAL_MS = 300;
    private static final int  PARTIAL_TAIL_BYTES  = 16000*2;

    // 録音開始要求が詰まった時に固まらないためのフラグ
    private final java.util.concurrent.atomic.AtomicBoolean isStartingRecording = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean primeAgain = false;

    private boolean debug;

    private static final String[] ALLOWED_HOTKEYS = { "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12", "F13", "F14", "F15", "F16", "F17", "F18" };
    private static final int[] ALLOWED_HOTKEYS_CODE = { NativeKeyEvent.VC_F1, NativeKeyEvent.VC_F2, NativeKeyEvent.VC_F3, NativeKeyEvent.VC_F4, NativeKeyEvent.VC_F5, NativeKeyEvent.VC_F6,
            NativeKeyEvent.VC_F7, NativeKeyEvent.VC_F8, NativeKeyEvent.VC_F9, NativeKeyEvent.VC_F10, NativeKeyEvent.VC_F11, NativeKeyEvent.VC_F12, NativeKeyEvent.VC_F13, NativeKeyEvent.VC_F14,
            NativeKeyEvent.VC_F15, NativeKeyEvent.VC_F16, NativeKeyEvent.VC_F17, NativeKeyEvent.VC_F18 };
    // Action
    enum Action {
        COPY_TO_CLIPBOARD_AND_PASTE, TYPE_STRING, NOTHING
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
    public MobMateWhisp(String remoteUrl) throws FileNotFoundException, NativeHookException {
        Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(
                new WString("MobMate.MobMateWhispTalk")
        );
        this.prefs = Preferences.userRoot().node("MobMateWhispTalk");
        UiText.load(UiLang.resolveUiFile(prefs.get("ui.language", "en")));
        Config.syncAllFromCloud();
        Config.log("JVM: " + System.getProperty("java.vm.name"));
        Config.log("JVM vendor: " + System.getProperty("java.vm.vendor"));
        loadWhisperNative();
        this.button = new JButton(UiText.t("ui.main.start"));
        Config.logDebug("Locale=" + java.util.Locale.getDefault() + " / user.language=" + System.getProperty("user.language"));

        int size = prefs.getInt("ui.font.size", 12);
        applyUIFont(size);
        lowGpuMode = prefs.getBoolean("perf.low_gpu_mode", false);
        useAlternateLaugh = prefs.getBoolean("silence.alternate", false);
        autoGainEnabled = prefs.getBoolean("audio.autoGain", true);
        reloadAudioPrefsForMeter();

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

        this.hotkey = this.prefs.get("hotkey", "F9");
        this.shiftHotkey = this.prefs.getBoolean("shift-hotkey", false);
        this.ctrltHotkey = this.prefs.getBoolean("ctrl-hotkey", false);
        this.model = this.prefs.get("model", "ggml-small.bin");
        this.model_dir = this.prefs.get("model_dir", "");

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
            for (File f : dir.listFiles()) {
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
                            e.printStackTrace();
                        }
                    }
                    if (desktop.isSupported(Desktop.Action.OPEN)) {
                        try {
                            desktop.open(dir);
                        } catch (IOException e) {
                            e.printStackTrace();
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
                for (File f : dir.listFiles()) {
                    if (f.getName().endsWith(".bin")) {
                        this.model = f.getName();
                        setModelPref(f.getName());
                        break;
                    }
                }
            }
            this.w = new LocalWhisperCPP(new File(dir, this.model));
            Config.log("MobMateWhispTalk using WhisperCPP with " + this.model);
            this.model_dir = dir.getPath();
            prefs.put("model_dir", model_dir);
        } else {
            Config.log("MobMateWhispTalk using remote speech to text service : " + remoteUrl);
        }
    }
    private Preferences getPrefs() {
        if (prefs == null) {
            prefs = Preferences.userRoot().node("MobMateWhispTalk");
        }
        return prefs;
    }
    void createTrayIcon() {
        this.imageRecording = new ImageIcon(this.getClass().getResource("recording.png")).getImage();
        this.imageInactive = new ImageIcon(this.getClass().getResource("inactive.png")).getImage();
        this.imageTranscribing = new ImageIcon(this.getClass().getResource("transcribing.png")).getImage();
        this.trayIcon = new TrayIcon(this.imageInactive, "Press " + this.hotkey + " to record");
        this.trayIcon.setImageAutoSize(true);
        final SystemTray tray = SystemTray.getSystemTray();
        final Frame frame = new Frame("");
        frame.setUndecorated(true);
        frame.setType(Type.UTILITY);
        // Create a pop-up menu components
        final PopupMenu popup = createPopupMenu();
        this.trayIcon.setPopupMenu(popup);
        this.trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    stopRecording();
                }
            }

        });
        try {
            frame.setResizable(false);
            frame.setVisible(true);
            tray.add(this.trayIcon);
        } catch (AWTException ex) {
            Config.log("TrayIcon could not be added.\n" + ex.getMessage());
        }
        trayIcon.addActionListener(e -> bringToFront(window));
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

        while (got < need && (System.currentTimeMillis() - start) < timeoutMs) {
            int r = line.read(buf, 0, buf.length);
            if (r > 0) {
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


    protected PopupMenu createPopupMenu() {
        final String strAction = this.prefs.get("action", "noting");

        Menu requiredMenu = new Menu(UiText.t("menu.required"));
        final PopupMenu popup = new PopupMenu();
        CheckboxMenuItem autoPaste = new CheckboxMenuItem(UiText.t("menu.autoPaste"));
        autoPaste.setState(strAction.equals("paste"));
        requiredMenu.add(autoPaste);
        CheckboxMenuItem autoType = new CheckboxMenuItem(UiText.t("menu.autoType"));
        autoType.setState(strAction.equals("type"));
        requiredMenu.add(autoType);
        final ItemListener typeListener = new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                if (e.getSource().equals(autoPaste) && e.getStateChange() == ItemEvent.SELECTED) {
                    Config.log("itemStateChanged() PASTE " + e.toString());
                    MobMateWhisp.this.prefs.put("action", "paste");
                    autoType.setState(false);
                } else if (e.getSource().equals(autoType) && e.getStateChange() == ItemEvent.SELECTED) {
                    Config.log("itemStateChanged() TYPE " + e.toString());
                    MobMateWhisp.this.prefs.put("action", "type");
                    autoPaste.setState(false);
                } else {
                    MobMateWhisp.this.prefs.put("action", "nothing");
                }
                try {
                    MobMateWhisp.this.prefs.sync();
                } catch (BackingStoreException e1) {
                    e1.printStackTrace();
                    JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + e1.getMessage());
                }
            }
        };
        autoPaste.addItemListener(typeListener);
        autoType.addItemListener(typeListener);
        CheckboxMenuItem detectSilece = new CheckboxMenuItem(UiText.t("menu.silenceDetection"));
        detectSilece.setState(this.prefs.getBoolean("silence-detection", true));
        detectSilece.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                MobMateWhisp.this.prefs.putBoolean("silence-detection", detectSilece.getState());
                try {
                    MobMateWhisp.this.prefs.sync();
                } catch (BackingStoreException e1) {
                    e1.printStackTrace();
                    JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + e1.getMessage());
                }
            }
        });
        requiredMenu.add(detectSilece);
        // Shift hotkey modifier
        Menu hotkeysMenu = new Menu(UiText.t("menu.keyboardShortcut"));
        final CheckboxMenuItem shiftHotkeyMenuItem = new CheckboxMenuItem("SHIFT");
        shiftHotkeyMenuItem.setState(this.prefs.getBoolean("shift-hotkey", false));
        hotkeysMenu.add(shiftHotkeyMenuItem);
        shiftHotkeyMenuItem.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                MobMateWhisp.this.shiftHotkey = shiftHotkeyMenuItem.getState();
                MobMateWhisp.this.prefs.putBoolean("shift-hotkey", MobMateWhisp.this.shiftHotkey);
                try {
                    MobMateWhisp.this.prefs.sync();
                } catch (BackingStoreException e1) {
                    e1.printStackTrace();
                    JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + e1.getMessage());
                }
                updateToolTip();
            }
        });
        // Ctrl hotkey modifier
        final CheckboxMenuItem ctrlHotkeyMenuItem = new CheckboxMenuItem("CTRL");
        ctrlHotkeyMenuItem.setState(this.prefs.getBoolean("ctrl-hotkey", false));
        hotkeysMenu.add(ctrlHotkeyMenuItem);
        ctrlHotkeyMenuItem.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                MobMateWhisp.this.ctrltHotkey = ctrlHotkeyMenuItem.getState();
                MobMateWhisp.this.prefs.putBoolean("ctrl-hotkey", MobMateWhisp.this.ctrltHotkey);
                try {
                    MobMateWhisp.this.prefs.sync();
                } catch (BackingStoreException e1) {
                    e1.printStackTrace();
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
                        MobMateWhisp.this.prefs.put("hotkey", MobMateWhisp.this.hotkey);
                        try {
                            MobMateWhisp.this.prefs.sync();
                        } catch (BackingStoreException e1) {
                            e1.printStackTrace();
                            JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + e1.getMessage());
                        }
                        hotkeyMenuItem.setState(false);
                        updateToolTip();
                    }
                }
            });
        }
        if (this.remoteUrl == null) {
            Menu modelMenu = new Menu(UiText.t("menu.models"));
            final File dir = new File("models");
            List<CheckboxMenuItem> allModels = new ArrayList<>();
            if (new File(dir, this.model).exists()) {
                for (File f : dir.listFiles()) {
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
                                        MobMateWhisp.this.w = new LocalWhisperCPP(f);
                                    } catch (FileNotFoundException e1) {
                                        JOptionPane.showMessageDialog(null, e1.getMessage());
                                        e1.printStackTrace();
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
        final Menu modeMenu = new Menu(UiText.t("menu.keyTriggerMode"));
        final CheckboxMenuItem pushToTalkItem = new CheckboxMenuItem(UiText.t("menu.pushToTalk"));
        final CheckboxMenuItem pushToTalkDoubleTapItem = new CheckboxMenuItem(UiText.t("menu.pushToTalkDoubleTap"));
        final CheckboxMenuItem startStopItem = new CheckboxMenuItem(UiText.t("menu.startStop"));
        String currentMode = this.prefs.get("trigger-mode", START_STOP);
        pushToTalkItem.setState(PUSH_TO_TALK.equals(currentMode));
        pushToTalkDoubleTapItem.setState(PUSH_TO_TALK_DOUBLE_TAP.equals(currentMode));
        startStopItem.setState(START_STOP.equals(currentMode));
        if (!pushToTalkItem.getState() && !pushToTalkDoubleTapItem.getState() && !startStopItem.getState()) {
            pushToTalkItem.setState(true);
            MobMateWhisp.this.prefs.put("trigger-mode", PUSH_TO_TALK);
            try {
                MobMateWhisp.this.prefs.sync();
            } catch (BackingStoreException ex) {
                ex.printStackTrace();
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
                    MobMateWhisp.this.prefs.put("trigger-mode", PUSH_TO_TALK);
                } else if (source == pushToTalkDoubleTapItem && e.getStateChange() == ItemEvent.SELECTED) {
                    pushToTalkItem.setState(false);
                    pushToTalkDoubleTapItem.setState(true);
                    startStopItem.setState(false);
                    MobMateWhisp.this.prefs.put("trigger-mode", PUSH_TO_TALK_DOUBLE_TAP);
                } else if (source == startStopItem && e.getStateChange() == ItemEvent.SELECTED) {
                    pushToTalkItem.setState(false);
                    pushToTalkDoubleTapItem.setState(false);
                    startStopItem.setState(true);
                    MobMateWhisp.this.prefs.put("trigger-mode", START_STOP);
                } else {
                    // Default to push to talk
                    pushToTalkItem.setState(true);
                    pushToTalkDoubleTapItem.setState(false);
                    startStopItem.setState(false);
                    MobMateWhisp.this.prefs.put("trigger-mode", PUSH_TO_TALK);
                }
                try {
                    MobMateWhisp.this.prefs.sync();
                } catch (BackingStoreException ex) {
                    ex.printStackTrace();
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

        // Get available audio input devices
        final Menu audioInputsItem = new Menu(UiText.t("menu.audioInputs"));
        String audioDevice = this.prefs.get("audio.device", "");
        String previsouAudipDevice = this.prefs.get("audio.device.previous", "");
        List<String> mixers = getInputsMixerNames();
        if (!mixers.isEmpty()) {
            String currentAudioDevice = "";
            if (!audioDevice.isEmpty() && mixers.contains(audioDevice)) {
                currentAudioDevice = audioDevice;
            } else if (!previsouAudipDevice.isEmpty() && mixers.contains(previsouAudipDevice)) {
                currentAudioDevice = previsouAudipDevice;
            } else {
                currentAudioDevice = mixers.get(0);
                this.prefs.put("audio.device", currentAudioDevice);
                try {
                    this.prefs.sync();
                } catch (BackingStoreException e1) {
                    e1.printStackTrace();
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
                // Add action listener to each menu item
                menuItem.addItemListener(new ItemListener() {
                    @Override
                    public void itemStateChanged(ItemEvent e) {
                        if (menuItem.getState()) {
                            for (CheckboxMenuItem m : all2) {
                                m.setState(m == menuItem);
                            }

                            // prefs 更新
                            MobMateWhisp.this.prefs.put("audio.device.previous",
                                    MobMateWhisp.this.prefs.get("audio.device", ""));
                            MobMateWhisp.this.prefs.put("audio.device", name);
                            try {
                                MobMateWhisp.this.prefs.sync();
                            } catch (BackingStoreException ex) {
                                ex.printStackTrace();
                            }

                            // ★追加：入力デバイス変更 → 再キャリブレーション
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
        popup.add(audioInputsItem);

        Menu audioOutputsItem = new Menu(UiText.t("menu.audioOutputs"));
        String outputDevice = this.prefs.get("audio.output.device", "");
        String prevOutputDevice = this.prefs.get("audio.output.device.previous", "");

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
                        } catch (BackingStoreException ex) {
                            ex.printStackTrace();
                        }
                    }
                });
            }
        }
        popup.add(audioOutputsItem);

        // ===== GPU selection (CPU / Vulkan only) =====
        Menu gpuMenu = new Menu(UiText.t("menu.gpuSelect"));
        int gpuIndex = prefs.getInt("vulkan.gpu.index", -1); // -1 = auto
        List<CheckboxMenuItem> items = new ArrayList<>();
        CheckboxMenuItem autoItem = new CheckboxMenuItem(UiText.t("menu.gpu.auto"));
        autoItem.setState(gpuIndex < 0);
        gpuMenu.add(autoItem);
        items.add(autoItem);
        autoItem.addItemListener(e -> {
            if (autoItem.getState()) {
                for (CheckboxMenuItem m : items) m.setState(m == autoItem);
                prefs.putInt("vulkan.gpu.index", -1);
                try { prefs.sync(); } catch (Exception ignore) {}
                JOptionPane.showMessageDialog(
                        null,
                        "GPU selection changed.\nPlease restart MobMate.",
                        "MobMate",
                        JOptionPane.INFORMATION_MESSAGE
                );
                restartSelf(true);
            }
        });
        gpuMenu.addSeparator();
        // --- Vulkan GPUs ---
        int count = VulkanGpuUtil.getGpuCount();
        for (int i = 0; i < count; i++) {
            final int idx = i;
            String name = "Vulkan " + i + ": " + VulkanGpuUtil.getGpuName(i);
            CheckboxMenuItem item = new CheckboxMenuItem(name);
            item.setState(gpuIndex == idx);
            gpuMenu.add(item);
            items.add(item);
            item.addItemListener(e -> {
                if (item.getState()) {
                    for (CheckboxMenuItem m : items) m.setState(m == item);
                    prefs.putInt("vulkan.gpu.index", idx);
                    try { prefs.sync(); } catch (Exception ignore) {}
                    JOptionPane.showMessageDialog(
                            null,
                            "GPU selection changed.\nPlease restart MobMate.",
                            "MobMate",
                            JOptionPane.INFORMATION_MESSAGE
                    );
                    restartSelf(true);
                }
            });
        }
        popup.add(gpuMenu);
        popup.addSeparator();

        Menu ttsVoicesItem = new Menu(UiText.t("menu.voices"));
        String voicePref = prefs.get("tts.windows.voice", "auto");
        List<CheckboxMenuItem> all = new ArrayList<>();
        // --- auto ---
        CheckboxMenuItem autoItem2 =
                new CheckboxMenuItem(UiText.t("menu.voice.auto"));
        autoItem2.setState("auto".equals(voicePref));
        ttsVoicesItem.add(autoItem2);
        all.add(autoItem2);
        autoItem2.addItemListener(e -> {
            if (autoItem2.getState()) {
                for (CheckboxMenuItem m : all) m.setState(m == autoItem2);
                prefs.put("tts.windows.voice", "auto");
                try { prefs.sync(); } catch (Exception ignore) {}
            }
        });
        ttsVoicesItem.addSeparator();
        // --- voices ---
        List<String> voices = getWindowsVoices();
        Collections.sort(voices);
        for (int i = 0; i < voices.size(); i++) {
            String voice = voices.get(i);
            String label = String.format("%02d: %s", i, voice);
            CheckboxMenuItem item = new CheckboxMenuItem(label);
            item.setState(voice.equals(voicePref));
            ttsVoicesItem.add(item);
            all.add(item);
            item.addItemListener(e -> {
                if (item.getState()) {
                    for (CheckboxMenuItem m : all) m.setState(m == item);
                    prefs.put("tts.windows.voice", voice);
                    try { prefs.sync(); } catch (Exception ignore) {}
                }
            });
        }
        ttsVoicesItem.addSeparator();
        if (isVoiceVoxAvailable()) {
            ttsVoicesItem.addSeparator();
            List<VoiceVoxSpeaker> speakers = getVoiceVoxSpeakers(); // id + name
            for (VoiceVoxSpeaker sp : speakers) {
                String key = "" + sp.id();
                String label = key + ":" + sp.name();
                CheckboxMenuItem item = new CheckboxMenuItem(label);
                item.setState(key.equals(voicePref));
                ttsVoicesItem.add(item);
                all.add(item);
                item.addItemListener(e -> {
                    if (item.getState()) {
                        for (CheckboxMenuItem m : all) m.setState(m == item);
                        prefs.put("tts.voice", key);
                        try { prefs.sync(); } catch (Exception ignore) {}
                    }
                });
            }
        }
        popup.add(ttsVoicesItem);


        Menu settingsMenu = new Menu(UiText.t("menu.settings"));

        Menu RecommendMenu = new Menu(UiText.t("menu.recommend"));
        CheckboxMenuItem rmDesktopMic =
                new CheckboxMenuItem(UiText.t("menu.recommend.desktopMic"));
        CheckboxMenuItem rmHeadsetMic =
                new CheckboxMenuItem(UiText.t("menu.recommend.headsetMic"));
        RecommendMenu.add(rmDesktopMic);
        RecommendMenu.add(rmHeadsetMic);
        settingsMenu.add(RecommendMenu);

        Menu performanceMenu = new Menu(UiText.t("menu.performance"));
        CheckboxMenuItem lowGpuItem =
                new CheckboxMenuItem(UiText.t("menu.lowGpuMode"));
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
                    this.w = new LocalWhisperCPP(new File(model_dir, this.model));
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

        CheckboxMenuItem vadLaughItem =
                new CheckboxMenuItem(UiText.t("menu.vadLaugh"));
        vadLaughItem.setState(useAlternateLaugh);
        vadLaughItem.addItemListener(e -> {
            boolean enabled = vadLaughItem.getState();
            MobMateWhisp.useAlternateLaugh = enabled;
            prefs.putBoolean("silence.alternate", enabled);
            try { prefs.sync(); } catch (Exception ignore) {}
        });
        performanceMenu.add(vadLaughItem);

        List<CheckboxMenuItem> gainItems = new ArrayList<>();
        rmDesktopMic.addItemListener(e -> {
            if (!rmDesktopMic.getState()) return;
            rmHeadsetMic.setState(false);
            MobMateWhisp.setSilenceAlternate(false);
            MobMateWhisp.lowGpuMode = true;
            prefs.putBoolean("perf.low_gpu_mode", true);
            prefs.putFloat("audio.inputGainMultiplier", 1.0f);
            for (CheckboxMenuItem it : gainItems) {
                it.setState(false);
                if (it.getLabel().equals(String.format("x %.1f", 1.0f))) {
                    it.setState(true);
                }
            }
            try { prefs.sync(); } catch (Exception ignore) {}
            lowGpuItem.setState(true);
            vadLaughItem.setState(false);
            Config.log("Applied Desktop Mic recommended settings");
            if (isRecording()) {
                stopRecording();
            }
        });
        rmHeadsetMic.addItemListener(e -> {
            if (!rmHeadsetMic.getState()) return;
            rmDesktopMic.setState(false);
            MobMateWhisp.setSilenceAlternate(true);
            MobMateWhisp.lowGpuMode = false;
            prefs.putBoolean("perf.low_gpu_mode", false);
            prefs.putFloat("audio.inputGainMultiplier", 3.4f);
            for (CheckboxMenuItem it : gainItems) {
                it.setState(false);
                if (it.getLabel().equals(String.format("x %.1f", 3.4f))) {
                    it.setState(true);
                }
            }
            try { prefs.sync(); } catch (Exception ignore) {}
            lowGpuItem.setState(false);
            vadLaughItem.setState(true);
            Config.log("Applied Headset Mic recommended settings");
            if (isRecording()) {
                stopRecording();
            }
        });

        Menu inputGainMenu = new Menu(UiText.t("menu.inputGain"));
        boolean autogaintuner = prefs.getBoolean("audio.autoGain", true); // -1 = auto
        float currentGain = prefs.getFloat("audio.inputGainMultiplier", 1.0f);
        CheckboxMenuItem autoItem3 =
                new CheckboxMenuItem(UiText.t("menu.autoGainTuner"));
        autoItem3.setState(autogaintuner);
        autoItem3.addItemListener(e -> {
            prefs.putBoolean("audio.autoGain", autoItem3.getState());
            autoGainEnabled = autoItem3.getState();
            try { prefs.sync(); } catch (Exception ignore) {}
            reloadAudioPrefsForMeter();
        });
        inputGainMenu.add(autoItem3);
        inputGainMenu.addSeparator();
        for (int i = 0; i < 10; i++) {
            float gain = 1.0f + (0.8f * i); // 1.0, 1.8, 2.6 ...
            String label = String.format("x %.1f", gain);
            CheckboxMenuItem item =
                    new CheckboxMenuItem(label, gain == currentGain);
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
        settingsMenu.add(performanceMenu);
        settingsMenu.add(inputGainMenu);

        settingsMenu.add(settingsMenu);
        popup.add(settingsMenu);
        settingsMenu.addSeparator();

//        CheckboxMenuItem openWindowItem = new CheckboxMenuItem("Open Window");
//        openWindowItem.setState(this.prefs.getBoolean("open-window", true));
//        openWindowItem.addItemListener(new ItemListener() {
//            @Override
//            public void itemStateChanged(ItemEvent e) {
//                boolean state = openWindowItem.getState();
//                MobMateWhisp.this.prefs.putBoolean("open-window", state);
//                try {
//                    MobMateWhisp.this.prefs.sync();
//                } catch (BackingStoreException ex) {
//                    ex.printStackTrace();
//                    JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + ex.getMessage());
//                }
//                if (state) {
//                    if (MobMateWhisp.this.window == null || !MobMateWhisp.this.window.isVisible()) {
//                        MobMateWhisp.this.openWindow();
//                    }
//                    if (MobMateWhisp.this.window != null) {
//                        MobMateWhisp.this.window.toFront();
//                        MobMateWhisp.this.window.requestFocus();
//                    }
//                } else {
//                    if (MobMateWhisp.this.window != null && MobMateWhisp.this.window.isVisible()) {
//                        MobMateWhisp.this.window.setVisible(false);
//                    }
//                }
//            }
//        });
//        popup.add(openWindowItem);

//        final MenuItem historyItem = new MenuItem("History");
//        popup.add(historyItem);

        Menu fontSizeMenu = new Menu(UiText.t("menu.fontSize"));
        int currentSize = prefs.getInt("ui.font.size", 12);
        List<CheckboxMenuItem> fontItems = new ArrayList<>();
        for (int size = 12; size <= 24; size += 2) {
            String label = size + " px";
            CheckboxMenuItem item = new CheckboxMenuItem(label);
            item.setState(size == currentSize);
            final int fontSize = size;
            item.addItemListener(e -> {
                if (item.getState()) {
                    for (CheckboxMenuItem m : fontItems) {
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
                        window.pack();
                    }
                }
            });
            fontItems.add(item);
            fontSizeMenu.add(item);
        }
        settingsMenu.add(fontSizeMenu);

        // ===== VOICEVOX感情自動寄せ =====
        CheckboxMenuItem autoEmotionItem = new CheckboxMenuItem(UiText.t("menu.voicevox.autoEmotion"));
        boolean currentAutoEmotion = prefs.getBoolean("voicevox.auto_emotion", true); // デフォルトtrue
        autoEmotionItem.setState(currentAutoEmotion);
        autoEmotionItem.addItemListener(e -> {
            boolean enabled = autoEmotionItem.getState();
            prefs.putBoolean("voicevox.auto_emotion", enabled);
            try {
                prefs.sync();
            } catch (Exception ignore) {}
            Config.log("VOICEVOX auto emotion set to: " + enabled);
        });
        settingsMenu.add(autoEmotionItem);

        boolean readyToZip = Config.getBool("log.debug", false);
        Menu debugMenu = new Menu("MobMateWhispTalk v" + Version.APP_VERSION);
        if (!readyToZip) {
            MenuItem enableDebugItem = new MenuItem(UiText.t("menu.debug.enableLog"));
            enableDebugItem.addActionListener(e -> {
                try {
                    Config.appendOutTts("log.debug=true");
                    Config.appendOutTts("log.vad.detailed=true");
                    JOptionPane.showMessageDialog(
                            null,
                            "Debug logging enabled temporarily.\n" +
                                    "Restart the app and reproduce the issue.\n" +
                                    "(Automatically turns off after ~500 messages.)",
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
            MenuItem zipLogsItem = new MenuItem(UiText.t("menu.debug.createZip"));
            zipLogsItem.addActionListener(e -> {
                try {
                    File zip = DebugLogZipper.createZip();
                    JOptionPane.showMessageDialog(
                            null,
                            "Debug logs have been collected.\n\n" +
                                    "Please attach this zip file and send it to support.\n\n" +
                                    zip.getAbsolutePath(),
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
        MenuItem exitItem = new MenuItem(UiText.t("menu.exit"));
        popup.add(exitItem);
        exitItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Config.mirrorAllToCloud();
                System.exit(0);

            }
        });
//        historyItem.addActionListener(new ActionListener() {
//            @Override
//            public void actionPerformed(ActionEvent e) {
//                showHistory();
//
//            }
//        });
        return popup;
    }

    public int getUIFontSize() {
        return prefs.getInt("ui.font.size", 12); // default 12
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
                    } catch (Exception e) {
                        e.printStackTrace();
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
                        } catch (Exception e) {
                            e.printStackTrace();
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
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
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
                SwingUtilities.invokeLater(() -> toggleRecordingFromUI());
                break;
            }
        }
    }
    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
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
                Config.logDebug("Audio device setting: '" + audioDevice + "'");
                TargetDataLine line = audioDevice.isEmpty() ?
                        getFirstTargetDataLine() : getTargetDataLine(audioDevice);
                if (line == null) {
                    Config.log("No audio input device available for calibration");
                    SwingUtilities.invokeLater(() -> {
                        window.setTitle(UiText.t("ui.calibrating.skipped"));
                        button.setEnabled(true);
                        button.setText(UiText.t("ui.main.start"));
                        isCalibrationComplete = true;
                    });
                    return;
                }
                line.open(audioFormat);
                line.start();

                byte[] buffer = new byte[1600];
                for (int i = 0; i < sharedNoiseProfile.CALIBRATION_FRAMES; i++) {
                    int bytesRead = line.read(buffer, 0, buffer.length);
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
                e.printStackTrace();
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
//                    autoGainMultiplier = prefs.getFloat("audio.inputGainMultiplier", isLow ? 5.8f : 1.8f);
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
            this.prefs.flush();
        } catch (BackingStoreException e) {
            e.printStackTrace();
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

        // ★保険：もしaudioServiceが詰まってRunnableが動かない場合、数秒でUIを戻す
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

        final String audioDevice = this.prefs.get("audio.device", "");
        final String previsouAudipDevice = this.prefs.get("audio.device.previous", "");

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
                        SwingUtilities.invokeLater(() -> window.setTitle(UiText.t("ui.title.rec")));

                        // --- ここから先は元の処理（録音ループ） ---
                        byte[] data = new byte[4096];
                        boolean detectSilence = MobMateWhisp.this.prefs.getBoolean("silence-detection", true);

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
                            Config.logDebug("★調整後 SILENCE_FRAMES_FOR_FINAL: " + SILENCE_FRAMES_FOR_FINAL);

                            final int PARTIAL_MIN_BYTES = 16000 * 3;
                            final long PARTIAL_MIN_INTERVAL_MS = 500;
                            final int PREROLL_BYTES = (int) (16000 / 5);
                            final int MIN_AUDIO_DATA_LENGTH = 16000 * 2;
                            long lastFinalMs = 0;
                            final long FINAL_COOLDOWN_MS = 200;
                            final long FORCE_FINAL_MS = 2500;
                            long lastPartialMs = 0;
                            isSpeaking = false;
                            speechStartTime = 0;
                            lastSpeechEndTime = 0;
                            int frameCount = 0;
                            boolean firstPartialSent = false;
                            boolean forceFinalizePending = false;
                            final LaughDetector laughDet = new LaughDetector();

                            while (isRecording()) {
                                int n = targetDataLine.read(data, 0, data.length);
                                if (n <= 0) continue;

                                preRoll.write(data, 0, n);
                                if (preRoll.size() > PREROLL_BYTES) {
                                    byte[] pr = preRoll.toByteArray();
                                    preRoll.reset();
                                    preRoll.write(pr, pr.length - PREROLL_BYTES, PREROLL_BYTES);
                                }

                                int peak = vad.getPeak(data, n);
                                int avg  = vad.getAvg(data, n);
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

                                if (frameCount == 0) {
                                    SwingUtilities.invokeLater(() ->
                                            window.setTitle(UiText.t("ui.title.rec"))
                                    );
                                }

                                boolean speech = (vad instanceof ImprovedVAD) ?
                                        ((ImprovedVAD) vad).isSpeech(data, n, buffer.size()) :
                                        vad.isSpeech(data, n);

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
                                maybeRescueSpeakFromPartial(action, now, currentUtteranceSeq);
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
                                        SwingUtilities.invokeLater(() -> window.setTitle(UiText.t("ui.title.rec")));
                                    }
                                    buffer.write(data, 0, n);
                                    partialBuf.write(data, 0, n);
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
//                                        if (p != null && !p.isBlank()) {
////                                            setTranscribing(true);
////                                            final String show = p;
////                                            SwingUtilities.invokeLater(() -> {
////                                                window.setTitle("[TRANS]:" + show);
////                                                MobMateWhisp.this.window.setIconImage(imageTranscribing);
////                                            });
//                                        } else {
//                                            SwingUtilities.invokeLater(() -> window.setTitle(UiText.t("ui.title.rec")));
//                                        }
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
                                            ? 16000       // 約1秒
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
                                                    Config.logDebug("★Final send");
                                                    transcribe(finalChunk, action, true, uttSeq);
                                                }
                                            } catch (Exception ex) {
                                                Config.logError("Final error", ex);
                                            } finally {
                                                isProcessingFinal.set(false);
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
                        ex.printStackTrace();
                        setRecording(false);
                    }

                } catch (LineUnavailableException e) {
                    Config.logDebug("Audio input device not available (used by an other process?)");
                } catch (Exception e) {
                    e.printStackTrace();
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
            e.printStackTrace();
            JOptionPane.showMessageDialog(null, "Error starting recording: " + e.getMessage());
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
        // 発声
        CompletableFuture.runAsync(() -> {
            speak(s);
            Config.appendOutTts(s);
        });
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
    private volatile long lastPartialKickMs = 0L;
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
    private void handleFinalText(String finalStr, Action action, boolean flgRescue) {
        if (finalStr == null) return;
        String s = finalStr.replace('\n',' ').replace('\r',' ').replace('\t',' ').trim();
        if (s.isEmpty()) return;

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

        if (flgRescue) {
            if (lastSpeakStr.equals(s)) {
                lastSpeakStr = "";
                Config.logDebug("★Final skip (lastSpeak): " + s);
                return;
            }
        }

        final String out = s;
        lastSpeakStr = out;

        // 履歴
        SwingUtilities.invokeLater(() -> {
            addHistory(out);
        });

        // アクション（タイプ/貼り付け）
        SwingUtilities.invokeLater(() -> {
            if (action == Action.TYPE_STRING) {
                try {
                    new RobotTyper().typeString(out, 11);
                } catch (Exception ignore) {}
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

        // ★読み上げ（必ず実行）
        CompletableFuture.runAsync(() -> {
            speak(out);
            Config.appendOutTts(out);
            Config.logDebug("speak(final): " + out);
        });
    }
    private boolean containsLaughTokenByConfig(String text) {
        if (text == null || text.isBlank()) return false;
        if (!Config.getBool("laughs.enable", true)) return false;

        String[] tokens = getLaughDetectTokens();
        if (tokens == null || tokens.length == 0) return false;

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

        // ★超即時 short-partial
        if (shouldInstantSpeakShortPartialForLowGain(s)
                && (nowMs - lastPartialUpdateMs) <= 80
                && (nowMs - lastRescueSpeakMs) >= 500) {
            lastRescueSpeakMs = nowMs;
            MobMateWhisp.setLastPartial("");
            Config.logDebug("★Instant short partial speak: " + s);
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
        boolean stale = (nowMs - lastPartialUpdateMs) >= PARTIAL_RESCUE_STALE_MS;
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



    public String transcribe(byte[] audioData, final Action action, boolean isEndOfCapture, long utteranceSeq) throws Exception {
        TL_FINAL_UTTERANCE_SEQ.set(utteranceSeq);
        try {
            return transcribe(audioData, action, isEndOfCapture);
        } finally {
            TL_FINAL_UTTERANCE_SEQ.remove();
        }
    }
    public String transcribe(byte[] audioData, final Action action, boolean isEndOfCapture) throws Exception {
        String str = "";
        if (MobMateWhisp.this.remoteUrl == null) {
            if (!transcribeBusy.compareAndSet(false, true)) {
                return "";
            }
            try {
                setTranscribing(true);
                str = w.transcribeRaw(audioData, action, MobMateWhisp.this);
                if (str == null) {
                    return "";
                }
            } finally {
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

//        final String suffix = "Thank you.";
//        if (str.endsWith(suffix)) {
//            str = str.substring(0, str.length() - suffix.length()).trim();
//        }

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
//                CompletableFuture.runAsync(() -> {
//                    speak(finalStr);
//                    Config.appendOutTts(finalStr);
//                    Config.logDebug("speak:" + finalStr);
//                });
//                SwingUtilities.invokeLater(() -> addHistory(finalStr));
            }
//            SwingUtilities.invokeLater(() -> {
//                addHistory(finalStr);
//            });
        } else {
            if (vad instanceof ImprovedVAD) {
                if (vad.getNoiseProfile().isLowGainMic) {
                    if (vad instanceof ImprovedVAD) {
                        if (((ImprovedVAD) vad).shouldFinalizeEarlyByText(finalStr)) {
                            earlyFinalizeRequested = true;
                            earlyFinalizeUntilMs = System.currentTimeMillis() + 800;
                        }
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
                    } catch (AWTException e) {
                        e.printStackTrace();
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
                        } catch (NativeHookException e1) {
                            e1.printStackTrace();
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
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        robot.keyRelease(KeyEvent.VK_V);
                        robot.keyRelease(KeyEvent.VK_CONTROL);
                        Config.logDebug("Pasting : " + finalStr + " DONE");
                    } catch (AWTException e) {
                        e.printStackTrace();
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
//                    CompletableFuture.runAsync(() -> {
//                        speak(finalStr);
//                        Config.appendOutTts(finalStr);
//                        Config.logDebug("speak:" + finalStr);
//                    });
//                    SwingUtilities.invokeLater(() -> {
//                        window.setTitle(cpugpumode);
//                    });
                }
            }
        });

        SwingUtilities.invokeLater(() -> {
            window.setTitle(cpugpumode);
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
//                    try {
//                        this.w = new LocalWhisperCPP(new File(model_dir, this.model));
//                    } catch (FileNotFoundException ex) {
//                        throw new RuntimeException(ex);
//                    }
                }

                if (tr) {
                    MobMateWhisp.this.label.setText(UiText.t("ui.main.transcribing"));
                } else if (rec) {
                    MobMateWhisp.this.label.setText(UiText.t("ui.main.recording"));
                } else {
                    MobMateWhisp.this.label.setText(UiText.t("ui.main.ready"));
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
    }
    public void setModelPref(String name) {
        this.prefs.put("model", name);
        try {
            this.prefs.flush();
        } catch (BackingStoreException e) {
            e.printStackTrace();
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
        System.setProperty("jna.encoding", "UTF-8");
        ensureInitialConfig();

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | UnsupportedLookAndFeelException e) {
                e.printStackTrace();
            }
            try {
                Boolean debug = false;
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

                boolean openWindow = r.prefs.getBoolean("open-window", true);
                if (forceOpenWindow) {
                    openWindow = true;
                }
                try {
                    r.createTrayIcon();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (openWindow) {
                    r.openWindow();
                }
            } catch (Throwable e) {
                JOptionPane.showMessageDialog(null, "Error :\n" + e.getMessage());
                e.printStackTrace();
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
        this.window.setIconImage(this.imageInactive);
        this.window.setFocusable(true);
        this.window.setFocusableWindowState(true);

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.add(Box.createHorizontalGlue());
        p.add(Box.createHorizontalStrut(6));
        MobMateWhisp.this.window.setTitle(cpugpumode);

        // ★入力音量メーター
//        gainLabel = new JLabel("-dB| ░░░░░░░░");
//        gainLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
//        gainLabel.setForeground(Color.DARK_GRAY);
        gainMeter = new GainMeter();

        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.X_AXIS));
        gainMeter.setFont(bottomPanel.getFont());
        bottomPanel.add(gainMeter);
        bottomPanel.add(Box.createHorizontalGlue());

        p.add(Box.createHorizontalStrut(8));
        p.add(bottomPanel);
        p.add(Box.createHorizontalStrut(6));
        p.add(this.button);

        final JButton historyButton = new JButton(UiText.t("ui.main.history"));
        p.add(Box.createHorizontalStrut(6));
        p.add(historyButton);
        final JButton prefButton = new JButton(UiText.t("ui.main.prefs"));
        p.add(Box.createHorizontalStrut(6));
        p.add(prefButton);

        this.window.setContentPane(p);
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
                Config.mirrorAllToCloud();
                System.exit(0);
            }
        });
        SwingUtilities.updateComponentTreeUI(this.window);
        this.window.invalidate();
        this.window.validate();
        this.window.repaint();

        // ★メーター更新タイマー開始
        startMeterUpdateTimer();

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
//        prefs.putBoolean("wizard.completed", false);
        SwingUtilities.invokeLater(() -> {
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

                try { prefs.sync(); } catch (Exception ignore) {}

                final String afterIn = prefs.get("audio.device", "");
                final String afterOut = prefs.get("audio.output.device", "");

                boolean inChanged = !java.util.Objects.equals(beforeIn, afterIn);
                boolean outChanged = !java.util.Objects.equals(beforeOut, afterOut);

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
                t.printStackTrace();
            }
        });
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
        boolean autoGainEnabledNow = prefs.getBoolean("audio.autoGain", true);
        float userGain = prefs.getFloat("audio.inputGainMultiplier", 1.0f);

        // 「最近喋ってたか？」判定：ここが無音で0へ戻すコア
        long now = System.currentTimeMillis();
        boolean recentSpeech = (now - lastSpeechAtMs) <= 180; // 180msは体感いい感じ

        gainMeter.setValue(level, db, autoGainEnabledNow, autoGainMultiplier, userGain, recentSpeech);
    }
    //    private void startMeterUpdateTimer() {
//        meterUpdateTimer = new Timer(100, e -> {
//            updateGainMeter(currentInputLevel, currentInputDb);
//        });
//        meterUpdateTimer.start();
//    }
//    private void updateGainMeter(int level, double db) {
//        if (gainLabel == null) return;
//        String bar = createMeterBar(level);
//        String dbStr = String.format("%+.1f", db);
//
//        // ★ゲイン情報も表示
//        float gain = prefs.getFloat("audio.inputGainMultiplier", 1.0f);
//        String gainStr = "";
//        if (autoGainEnabled) {
//            gainStr = String.format("(A-x%.2f)", autoGainMultiplier);
//        } else if (gain > 1.01f) {
//            gainStr = String.format("(x%.1f)", gain);
//        }
//        gainLabel.setText(String.format("%sdB%s|%s", dbStr, gainStr, bar));
//        // 色の変更
//        if (level >= 80) {
//            gainLabel.setForeground(Color.RED);
//        } else if (level >= 60) {
//            gainLabel.setForeground(new Color(255, 140, 0));
//        } else if (level >= 30) {
//            gainLabel.setForeground(new Color(34, 139, 34));
//        } else {
//            gainLabel.setForeground(Color.DARK_GRAY);
//        }
//    }
//    // ★メーターバー作成
//    private String createMeterBar(int level) {
//        int bars = (level * 8) / 100; // 0-8の範囲
//        bars = Math.max(0, Math.min(8, bars));
//        StringBuilder sb = new StringBuilder();
//        for (int i = 0; i < 8; i++) {
//            if (i < bars) {
//                sb.append("█");
//            } else {
//                sb.append("░");
//            }
//        }
//        return sb.toString();
//    }
//    // ★音量レベル計算
    private void updateInputLevelWithGain(byte[] pcm, int len, boolean speech) {
        if (pcm == null || len <= 0) return;

        float userGain = prefs.getFloat("audio.inputGainMultiplier", 1.0f);
        boolean autoGainEnabledNow = prefs.getBoolean("audio.autoGain", true);

        float gain = userGain * autoGainMultiplier;
        boolean applyGain = gain > 1.01f;

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
        text = Config.applyDictionary(text); // Dictorary apply
        if (isVoiceVoxAvailable()) {
            String api = Config.getString("voicevox.api", "");int base = Integer.parseInt(this.prefs.get("tts.voice", "3"));
            String uiLang = this.prefs.get("ui.language", "en"); // "ja","en","ko","zh_cn","zh_tw" 想定
            // ★感情自動寄せの有効/無効判定
            boolean autoEmotion = prefs.getBoolean("voicevox.auto_emotion", true);
            int styleId;
            if (autoEmotion) {
                styleId = VoiceVoxStyles.pickStyleId(text, base, api, uiLang);
            } else {
                styleId = base; // 感情寄せOFFなら設定IDをそのまま使用
            }
            speakVoiceVox(text, String.valueOf(styleId), api);
        } else if (isXttsAvailable()) {
            try { speakXtts(text); } catch (Exception e) {}
        } else {
            speakWindows(text);
        }
    }
    private boolean isVoiceVoxAvailable() {
        long now = System.currentTimeMillis();
        if (now - lastVoiceVoxCheckMs < VOICEVOX_CHECK_INTERVAL_MS) {
            return voiceVoxAlive;
        }
        lastVoiceVoxCheckMs = now;
        try {
            String base = Config.getString("voicevox.api", "");
            if (base.isEmpty()) {
                voiceVoxAlive = false;
                return false;
            }
            URL url = new URL(base + "/speakers");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(100);
            conn.setReadTimeout(100);
            conn.connect();
            voiceVoxAlive = (conn.getResponseCode() == 200);
        } catch (Exception e) {
            Config.logDebug("[VV] /tts err");
            voiceVoxAlive = false;
        }
        return voiceVoxAlive;
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
        } catch (Exception e) {}
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
                    URLEncoder.encode(text, "UTF-8") +
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
            Path tmp = Files.createTempFile("vv_", ".wav");
            try (InputStream in = s.getInputStream();
                 OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
            }
            if (!waitForValidWav(tmp.toFile(), 3000)) {
                Config.logDebug("[VV] WAV invalid timeout");
                return;
            }
            Config.logDebug("[VV] saved: " + tmp);
            playViaPowerShellAsync(tmp.toFile());
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    Files.deleteIfExists(tmp);
                } catch (Exception ignore) {}
            }).start();
        } catch (Exception ex) {
            ex.printStackTrace();
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
            Path tmp = Files.createTempFile("win_", ".wav");
            String wavPath = tmp.toAbsolutePath().toString().replace("\\", "\\\\");
            String escapedText = text.replace("'", "''");
            String voice = prefs.get("tts.windows.voice", "auto");
            StringBuilder ps = new StringBuilder();
            ps.append("Add-Type -AssemblyName System.Speech;");
            ps.append("$s = New-Object System.Speech.Synthesis.SpeechSynthesizer;");
            if (!"auto".equalsIgnoreCase(voice)) {
                ps.append("$s.SelectVoice('").append(voice.replace("'", "''")).append("');");
            }
            ps.append("$s.SetOutputToWaveFile('").append(wavPath).append("');");
            ps.append("$s.Speak('").append(escapedText).append("');");
            ps.append("$s.Dispose();");
            new ProcessBuilder(
                    "powershell",
                    "-NoLogo",
                    "-NoProfile",
                    "-Command",
                    ps.toString()
            ).redirectErrorStream(true)
                    .start()
                    .waitFor();
            if (!waitForValidWav(tmp.toFile(), 3000)) {
                Config.logDebug("[WIN] WAV invalid timeout");
                return;
            }
            playViaPowerShellAsync(tmp.toFile());
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    Files.deleteIfExists(tmp);
                } catch (Exception ignore) {}
            }).start();
        } catch (Exception ex) {
            ex.printStackTrace();
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
        } catch (Exception e) {
            e.printStackTrace();
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
        } catch (Exception e) {
            e.printStackTrace();
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
            ProcessBuilder pb = new ProcessBuilder(
                    "cmd", "/c", "start", "/min", vvExe
            );
            pb.directory(f.getParentFile());
            pb.start();
            Config.logDebug("VOICEVOX started.");
        } catch (Exception ex) {
            ex.printStackTrace();
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
        if (replace == null || replace.length == 0) return s;
        String[] tokens = getLaughDetectTokens();
        if (tokens == null || tokens.length == 0) return s;
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
            if (sb.length() > 0) sb.append("|");
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
//                new Backend("CUDA", new File(libsDir, "cuda"),
//                        new String[]{
//                                "ggml-cuda.dll",
//                                "ggml-base.dll",
//                                "ggml-cpu.dll",
//                                "ggml.dll",
//                                "cudart64_110.dll"
//                        },
//                        "whisper.dll"
//                ),
                new Backend("Vulkan", new File(libsDir, "vulkan"),
                        new String[]{
                                "ggml-vulkan.dll",
                                "ggml-base.dll",
                                "ggml-cpu.dll",
                                "ggml.dll",
                                "vulkan-1.dll",
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
            System.setProperty("GGML_VULKAN_DEVICE", String.valueOf(gpuIndex));
            Config.log("Using Vulkan GPU index: " + gpuIndex);
        } else {
            System.clearProperty("GGML_VULKAN_DEVICE");
            Config.log("Using Vulkan GPU auto selection");
        }
        for (Backend b : backends) {
            Config.log("Checking backend: " + b.name + " in " + b.dir);
            if (!b.dir.exists()) {
                Config.log(" → Directory does not exist, skipping.");
                continue;
            }
            Config.log("Copying backend DLLs to exe folder...");
            List<File> copied = copyDllsToExeDir(b, exeDir);
//            if (copied == null || copied.isEmpty()) {
//                Config.log("DLL copy failed. Skip backend: " + b.name);
//                continue;
//            }
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
    private File getExeDir() {
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
    }private List<File> copyDllsToExeDir(Backend b, File exeDir) {
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
        prefs = Preferences.userRoot().node("MobMateWhispTalk");
        prefs.put("ui.language", suffix);
        UiText.load(UiLang.resolveUiFile(suffix));
        copyPreset("libs/preset/_outtts_" + suffix + ".txt", "_outtts.txt");
        copyPreset("libs/preset/_dictionary_" + suffix + ".txt", "_dictionary.txt");
        copyPreset("libs/preset/_ignore_" + suffix + ".txt", "_ignore.txt");
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
        } catch (IOException e) {
            e.printStackTrace();
        }
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
    private int targetLevel;         // 0-100
    private double targetDb;         // -60..0
    private boolean auto;
    private float autoMul;
    private float userMul;

    private float dispLevel = 0f;    // 表示用（スムージング後）
    private double dispDb = -60.0;

    private int peakHold = 0;        // 0-100
    private long peakHoldAt = 0;

    private final Timer animTimer;

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

    private void animateStep() {
        // 攻撃(上がる)は速く、リリース(下がる)はゆっくり
        float attack = 0.35f;
        float release = 0.12f;

        float t = targetLevel;
        float a = (t > dispLevel) ? attack : release;
        dispLevel += (t - dispLevel) * a;

        double td = targetDb;
        double ad = (td > dispDb) ? 0.35 : 0.12;
        dispDb += (td - dispDb) * ad;

        if (targetLevel == 0 && dispLevel < 0.6f) dispLevel = 0f;
        if (targetDb <= -59.9 && dispDb < -59.5) dispDb = -60.0;

        // ピークホールド（700ms保持→ゆっくり落下）
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

        repaint();
    }

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
            int barX = pad;
            int barY = pad;
            int barW = w - pad * 2;
            int barH = h - pad * 2;

            int lvl = Math.max(0, Math.min(100, Math.round(dispLevel)));
            int fillW = (int) (barW * (lvl / 100.0));
            g2.setColor(colorFor(lvl));
            g2.fillRoundRect(barX, barY, Math.max(0, fillW), barH, 8, 8);

            // 目盛り
            g2.setColor(new Color(255, 255, 255, 35));
            for (int i = 1; i < 10; i++) {
                int x = barX + (barW * i) / 10;
                g2.drawLine(x, barY + 2, x, barY + barH - 2);
            }

            // ピークホールド線
            int peakX = barX + (barW * peakHold) / 100;
            g2.setColor(new Color(255, 255, 255, 170));
            g2.drawLine(peakX, barY, peakX, barY + barH);

            // 枠線
            g2.setColor(new Color(255, 255, 255, 60));
            g2.drawRoundRect(0, 0, w - 1, h - 1, 10, 10);

            // 文字（dB + gain）
            String dbStr = String.format("%+.1f dB", dispDb);
            float effective = userMul * autoMul;
            String gainStr;
            if (auto) {
                // 実効倍率を表示（必要なら内訳も表示）
                gainStr = String.format("A-x%.2f", effective);          // ←これが欲しいやつ
                // gainStr = String.format("A-x%.2f (U-x%.2f)", effective, userMul); // 内訳出すならこっち
            } else {
                gainStr = (userMul > 1.01f) ? String.format("x%.1f", userMul) : "";
            }
            String text = gainStr.isEmpty() ? dbStr : (dbStr + "  " + gainStr);

            g2.setFont(getFont().deriveFont(Font.BOLD, Math.max(11f, h * 0.55f)));
            FontMetrics fm = g2.getFontMetrics();
            int tx = 8;
            int ty = (h - fm.getHeight()) / 2 + fm.getAscent();

            g2.setColor(new Color(0,0,0,160));
            g2.drawString(text, tx + 1, ty + 1);
            g2.setColor(Color.WHITE);
            g2.drawString(text, tx, ty);

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
}
