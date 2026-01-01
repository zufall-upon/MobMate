package whisper;

import whisper.Version;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.awt.*;
import java.awt.Window.Type;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.nio.charset.StandardCharsets;
import javax.sound.sampled.AudioSystem;
import java.net.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.*;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
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
    private List<String> history = new ArrayList<>();
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
    final JButton button = new JButton("Start");
    final JLabel label = new JLabel("Idle");

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

    private final boolean useAlternateLaugh =
            Boolean.parseBoolean(Config.loadSetting("silence.alternate", "false"));
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
    private static final long PARTIAL_INTERVAL_MS = 300;
    private static final int  PARTIAL_TAIL_BYTES  = 16000*2;

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

    private boolean debug;

    private static final String[] ALLOWED_HOTKEYS = { "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12", "F13", "F14", "F15", "F16", "F17", "F18" };
    private static final int[] ALLOWED_HOTKEYS_CODE = { NativeKeyEvent.VC_F1, NativeKeyEvent.VC_F2, NativeKeyEvent.VC_F3, NativeKeyEvent.VC_F4, NativeKeyEvent.VC_F5, NativeKeyEvent.VC_F6,
            NativeKeyEvent.VC_F7, NativeKeyEvent.VC_F8, NativeKeyEvent.VC_F9, NativeKeyEvent.VC_F10, NativeKeyEvent.VC_F11, NativeKeyEvent.VC_F12, NativeKeyEvent.VC_F13, NativeKeyEvent.VC_F14,
            NativeKeyEvent.VC_F15, NativeKeyEvent.VC_F16, NativeKeyEvent.VC_F17, NativeKeyEvent.VC_F18 };
    // Action
    enum Action {
        COPY_TO_CLIPBOARD_AND_PASTE, TYPE_STRING, NOTHING
    }

    public MobMateWhisp(String remoteUrl) throws FileNotFoundException, NativeHookException {
        this.prefs = Preferences.userRoot().node("MobMateWhispTalk");
        Config.syncAllFromCloud();
        Config.log("JVM: " + System.getProperty("java.vm.name"));
        Config.log("JVM vendor: " + System.getProperty("java.vm.vendor"));
        loadWhisperNative();

        int size = prefs.getInt("ui.font.size", 12);
        applyUIFont(size);

        if (window != null) {
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
        long timeoutMs = 1800;

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

        final PopupMenu popup = new PopupMenu();

        CheckboxMenuItem autoPaste = new CheckboxMenuItem("Auto paste");
        autoPaste.setState(strAction.equals("paste"));
        popup.add(autoPaste);

        CheckboxMenuItem autoType = new CheckboxMenuItem("Auto type");
        autoType.setState(strAction.equals("type"));
        popup.add(autoType);

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
        CheckboxMenuItem detectSilece = new CheckboxMenuItem("Silence detection");
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
        popup.add(detectSilece);

        // Shift hotkey modifier
        Menu hotkeysMenu = new Menu("Keyboard shortcut");
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
            Menu modelMenu = new Menu("Models");
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
            popup.add(modelMenu);
        }
        popup.add(hotkeysMenu);

        final Menu modeMenu = new Menu("Key trigger mode");
        final CheckboxMenuItem pushToTalkItem = new CheckboxMenuItem("Push to talk");
        final CheckboxMenuItem pushToTalkDoubleTapItem = new CheckboxMenuItem("Push to talk + double tap");
        final CheckboxMenuItem startStopItem = new CheckboxMenuItem("Start / Stop");
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
        popup.add(modeMenu);

        // Get available audio input devices
        final Menu audioInputsItem = new Menu("Audio inputs");
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
                                label.setText("Input changed, calibrating...");
                                primeVadByEmptyRecording();
                            });
                        }
                    }
                });
            }
        }
        popup.add(audioInputsItem);

        Menu audioOutputsItem = new Menu("Audio outputs");
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
        Menu gpuMenu = new Menu("GPU select (restart required)");
        int gpuIndex = prefs.getInt("vulkan.gpu.index", -1); // -1 = auto
        List<CheckboxMenuItem> items = new ArrayList<>();
        CheckboxMenuItem autoItem = new CheckboxMenuItem("Auto");
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

        CheckboxMenuItem openWindowItem = new CheckboxMenuItem("Open Window");
        openWindowItem.setState(this.prefs.getBoolean("open-window", true));
        openWindowItem.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                boolean state = openWindowItem.getState();
                MobMateWhisp.this.prefs.putBoolean("open-window", state);
                try {
                    MobMateWhisp.this.prefs.sync();
                } catch (BackingStoreException ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + ex.getMessage());
                }
                if (state) {
                    if (MobMateWhisp.this.window == null || !MobMateWhisp.this.window.isVisible()) {
                        MobMateWhisp.this.openWindow();
                    }
                    if (MobMateWhisp.this.window != null) {
                        MobMateWhisp.this.window.toFront();
                        MobMateWhisp.this.window.requestFocus();
                    }
                } else {
                    if (MobMateWhisp.this.window != null && MobMateWhisp.this.window.isVisible()) {
                        MobMateWhisp.this.window.setVisible(false);
                    }
                }
            }
        });
        popup.add(openWindowItem);

        final MenuItem historyItem = new MenuItem("History");
        popup.add(historyItem);
        Menu ttsVoicesItem = new Menu("Voices choices");
        String voicePref = prefs.get("tts.windows.voice", "auto");
        List<CheckboxMenuItem> all = new ArrayList<>();
        // --- auto ---
        CheckboxMenuItem autoItem2 =
                new CheckboxMenuItem("auto (System default)");
        autoItem.setState("auto".equals(voicePref));
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

        Menu fontSizeMenu = new Menu("Font size");
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
        popup.add(fontSizeMenu);
        popup.addSeparator();

        boolean readyToZip = Config.getBool("log.debug", false);
        Menu debugMenu = new Menu("MobMateWhispTalk v" + Version.APP_VERSION);
        if (!readyToZip) {
            MenuItem enableDebugItem = new MenuItem("[Debug] DetailLog Output ON");
            enableDebugItem.addActionListener(e -> {
                try {
                    Config.appendOutTts("log.debug=true");
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
            MenuItem zipLogsItem = new MenuItem("[Debug End] DetailLog to Zip file.");
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
        MenuItem exitItem = new MenuItem("Exit");
        popup.add(exitItem);
        exitItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Config.mirrorAllToCloud();
                System.exit(0);

            }
        });
        historyItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showHistory();

            }
        });
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

    private List<String> getInputsMixerNames() {
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

    private List<String> getOutputMixerNames() {
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
        final String strAction = prefs.get("action", "noting");
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
        if (isPriming) return;
        isPriming = true;
        try {
            Config.reload();
            prefs.sync();
        } catch (Exception e) {
            Config.logDebug("Settings reload failed: " + e.getMessage());
        }
        SwingUtilities.invokeLater(() -> {
            label.setText("Calibrating audio environment...");
            button.setEnabled(false);
            button.setText("Calibrating...");
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
                        label.setText("No audio device - calibration skipped");
                        button.setEnabled(true);
                        button.setText("Start");
                        isCalibrationComplete = true;
                    });
                    return;
                }
                line.open(audioFormat);
                line.start();

                // Perform calibration with noise profile
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
                    // Update progress on UI thread
                    final int progress = (i * 100) / sharedNoiseProfile.CALIBRATION_FRAMES;
                    SwingUtilities.invokeLater(() -> {
                        label.setText("Calibrating... " + progress + "%");
                    });
                    try {
                        Thread.sleep(50); // 50ms between samples
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                line.stop();
                line.close();
                // Force calibration completion
                sharedNoiseProfile.forceCalibrate();
                vadPrimed = true;
                Config.log("VAD calibration completed");
                // Update UI on EDT
                SwingUtilities.invokeLater(() -> {
                    isCalibrationComplete = true;
                    button.setEnabled(true);
                    button.setText("Start");
                    label.setText("Ready - Calibration complete");
                    updateToolTip();
                });
            } catch (Exception e) {
                Config.log("Calibration failed: " + e.getMessage());
                e.printStackTrace();

                SwingUtilities.invokeLater(() -> {
                    label.setText("Calibration failed - " + e.getMessage());
                    button.setEnabled(true);
                    button.setText("Start");
                    isCalibrationComplete = true; // Allow operation even if calibration failed
                });
            } finally {
                isPriming = false;
            }
        });
        boolean isLow = vad.getNoiseProfile().isLowGainMic;
        w.setLowGainMic(isLow);
        Config.log("LowGainMic detected = " + isLow);
    }

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
        if (isRecording()) {
            return;
        }
        setRecording(true);
        try {
            String audioDevice = this.prefs.get("audio.device", "");
            String previsouAudipDevice = this.prefs.get("audio.device.previous", "");
            this.audioService.execute(new Runnable() {
                @Override
                public void run() {
                    final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    final ByteArrayOutputStream laughBuffer = new ByteArrayOutputStream();
                    final int LAUGH_WINDOW_BYTES = 16000 / 2;
                    TargetDataLine targetDataLine;
                    try {
                        targetDataLine = getTargetDataLine(audioDevice);
                        if (targetDataLine == null) {
                            targetDataLine = getTargetDataLine(previsouAudipDevice);
                            if (targetDataLine == null) {
                                targetDataLine = getFirstTargetDataLine();
                            } else {
                                Config.logDebug("Using previous audio device : " + previsouAudipDevice);
                            }
                            if (targetDataLine == null) {
                                JOptionPane.showMessageDialog(null, "Cannot find any input audio device");
                                setRecording(false);
                                return;
                            } else {
                                Config.logDebug("Using default audio device");
                            }
                        } else {
                            Config.logDebug("Using audio device : " + audioDevice);
                        }
                        boolean finalAlreadySent = false;
                        try {
                            targetDataLine.open(MobMateWhisp.this.audioFormat);
                            targetDataLine.start();
                            setRecording(true);
                            SwingUtilities.invokeLater(() ->
                                    window.setTitle("Calibrating microphone...")
                            );
                            String effectiveDevice =
                                    (audioDevice != null && !audioDevice.isBlank()) ? audioDevice :
                                            (previsouAudipDevice != null && !previsouAudipDevice.isBlank()) ? previsouAudipDevice :
                                                    "DEFAULT";

                            SwingUtilities.invokeLater(() -> window.setTitle("Calibrating mic (" + effectiveDevice + ")..."));
                            ensureVADCalibrationForDevice(targetDataLine, effectiveDevice);
                            SwingUtilities.invokeLater(() -> window.setTitle("Listening..."));

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
                                final int PREROLL_BYTES = (int)(16000 / 10);
                                final int MIN_AUDIO_DATA_LENGTH = 16000 * 2;
                                long lastFinalMs = 0;
                                final long FINAL_COOLDOWN_MS = 200;
                                final long FORCE_FINAL_MS = 2500;
                                long lastPartialMs = 0;
                                boolean isSpeaking = false;
                                long speechStartTime = 0;
                                long lastSpeechEndTime = 0;
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


                                    final boolean alt = Config.getBool("silence.alternate", false);
                                    final long MIN_SPEECH_MS_FOR_LAUGH = alt ? 450 : 220;
                                    long now2 = System.currentTimeMillis();

                                    // ★修正：speech中かつ最小時間経過後にのみ検知
                                    if (useAlternateLaugh && isSpeaking &&
                                            (now2 - speechStartTime) > MIN_SPEECH_MS_FOR_LAUGH) {

                                        boolean laughHit = laughDet.updateAndDetectLaugh(
                                                data, n, peak, now2, true);

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
                                                        history.add(laughText);
                                                        fireHistoryChanged();
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
                                                        window.setTitle(String.format("Calibrating... (%d/%d) - Stay quiet",
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
                                                window.setTitle("Listening...")
                                        );
                                    }

                                    boolean speech = (vad instanceof ImprovedVAD) ?
                                        ((ImprovedVAD) vad).isSpeech(data, n, buffer.size()) :
                                        vad.isSpeech(data, n);
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
                                            Config.logDebug("★発話開始");
                                            SwingUtilities.invokeLater(() -> window.setTitle("Speaking..."));
                                        }
                                        buffer.write(data, 0, n);
                                        partialBuf.write(data, 0, n);
                                        lastSpeechEndTime = now;

                                        if (now - lastPartialMs >= PARTIAL_INTERVAL_MS) {
                                            lastPartialMs = now;
                                            kickPartialTranscribe(buffer);
                                            String p = MobMateWhisp.getLastPartial();
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
                                                                SwingUtilities.invokeLater(() -> {
                                                                    history.add(laughOut);
                                                                    fireHistoryChanged();
                                                                    window.setTitle("Listening...");
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
                                            if (p != null && !p.isBlank()) {
                                                final String show = p;
                                                SwingUtilities.invokeLater(() -> window.setTitle("Speaking: " + show));
                                            } else {
                                                SwingUtilities.invokeLater(() -> window.setTitle("Speaking..."));
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

                                        boolean meetsMinBytes = buffer.size() >= MIN_AUDIO_DATA_LENGTH;

                                        // ★救済: short-final を Partial 文字列から起こす
                                        boolean enableRescue = Config.getBool("vad.final_rescue_from_partial", true);
                                        boolean hasPartialText = (latestText != null && !latestText.trim().isEmpty());

                                        // 「短い発話で、partial が出てる」なら救済対象
                                        boolean doRescueFromPartial = enableRescue && !meetsMinBytes && hasPartialText;

                                        // ★BGMっぽい partial は救済しない（必要なら条件足してOK）
                                        // 例: "BGM" という文字列が出てるやつは救済不要
                                        if (doRescueFromPartial) {
                                            String t = latestText.trim();
                                            if (t.equalsIgnoreCase("BGM") || t.startsWith("BGM")) {
                                                doRescueFromPartial = false;
                                            }
                                        }

                                        // 送るべき Final 判定（救済なら true）
                                        boolean shouldSendFinal = meetsMinBytes || doRescueFromPartial;

                                        if (shouldSendFinal && nowMs - lastFinalMs >= FINAL_COOLDOWN_MS) {

                                            lastFinalExecutionTime.set(nowMs);
                                            isProcessingFinal.set(true);

                                            final boolean useRescueText = doRescueFromPartial;
                                            final String rescueText = useRescueText ? latestText.trim() : null;

                                            // ★通常ルート用
                                            final byte[] finalChunk = buffer.toByteArray();

                                            // ---- 状態リセット（重要）----
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

                                            // ★partial を残すと「partialで止まった」見え方になるのでここで消す
                                            MobMateWhisp.setLastPartial("");

                                            executorService.submit(() -> {
                                                try {
                                                    if (useRescueText) {
                                                        Config.logDebug("★Final rescue from partial: " + rescueText);
                                                        handleFinalText(rescueText, action); // ★通常finalと同じ処理へ
                                                    } else {
                                                        Config.logDebug("★Final send");
                                                        transcribe(finalChunk, action, true);
                                                    }
                                                } catch (Exception ex) {
                                                    Config.logError("Final error", ex);
                                                } finally {
                                                    isProcessingFinal.set(false);
                                                    SwingUtilities.invokeLater(() -> window.setTitle("Listening."));
                                                }
                                            });

                                            continue;
                                        }

                                        // ★Final条件未満で救済もしない → ここで捨てて状態リセット（BGM化の温床を断つ）
                                        if (!meetsMinBytes && !doRescueFromPartial) {
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
                                            SwingUtilities.invokeLater(() -> window.setTitle("Listening."));
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
                        } catch (LineUnavailableException e) {
                            Config.logDebug("Audio input device not available (used by an other process?)");
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            try {
                                targetDataLine.stop();
                                SwingUtilities.invokeLater(() -> {
                                    window.setTitle("Listening...");
                                });
                                MobMateWhisp.setLastPartial("");
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            try {
                                targetDataLine.close();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        final byte[] audioData = byteArrayOutputStream.toByteArray();
                        setRecording(false);
                        if (finalAlreadySent) {
                            return;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    setRecording(false);
                    setTranscribing(false);
                }
            });
        } catch (Exception e) {
            setRecording(false);
            e.printStackTrace();
            JOptionPane.showMessageDialog(null, "Error starting recording: " + e.getMessage());
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
    private void kickPartialTranscribe(ByteArrayOutputStream buffer) {
        if (isProcessingFinal.get()) return;
        if (!isProcessingPartial.compareAndSet(false, true)) return;
        byte[] all = buffer.toByteArray();
        boolean lowGain = vad.getNoiseProfile().isLowGainMic;
        int minPartialBytes = lowGain
                ? Math.max(9600, Config.getInt("vad.min_partial_bytes.low", 9600))  // ★12000→9600（約0.6秒）
                : Math.max(12000, Config.getInt("vad.min_partial_bytes", 12000));
        if (all.length < minPartialBytes) {
            isProcessingPartial.set(false);
            return;
        }
        final byte[] chunk = tailBytes(all, PARTIAL_TAIL_BYTES);
        executorService.submit(() -> {
            try {
                transcribe(chunk, Action.NOTHING, false);
            } catch (Exception ex) {
                Config.logError("Partial error", ex);
            } finally {
                isProcessingPartial.set(false);
            }
        });
    }
    // === Final確定の共通処理（rescueでも必ずここを通す） ===
    private void handleFinalText(String finalStr, Action action) {
        if (finalStr == null) return;
        String s = finalStr.replace('\n',' ').replace('\r',' ').replace('\t',' ').trim();
        if (s.isEmpty()) return;

        // ignore（final扱いのときだけ）
        if (LocalWhisperCPP.isIgnoredStatic(s)) {
            Config.logDebug("★Final skip (NG): " + s);
            return;
        }

        final String out = s;

        // 履歴
        SwingUtilities.invokeLater(() -> {
            history.add(out);
            fireHistoryChanged();
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

        // 読み上げ + outtts
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

    public String transcribe(byte[] audioData, final Action action, boolean isEndOfCapture) throws Exception {
        String str = "";
        if (MobMateWhisp.this.remoteUrl == null) {
            if (!transcribeBusy.compareAndSet(false, true)) {
                return "";
            }
            try {
                str = w.transcribeRaw(audioData);
                if (str == null) {
                    return "";
                }
                setTranscribing(true);
            } finally {
                transcribeBusy.set(false);
            }
        }
        str = str.replace('\n', ' ');
        str = str.replace('\r', ' ');
        str = str.replace('\t', ' ');
        str = str.trim();

        if (!isEndOfCapture) {
            if (!str.isEmpty()) {
                MobMateWhisp.setLastPartial(str);
                Config.logDebug("★Cached partial: " + MobMateWhisp.getLastPartial());
            }
        } else {
            MobMateWhisp.setLastPartial("");
        }
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
        final String suffix = "Thank you.";
        if (str.endsWith(suffix)) {
            str = str.substring(0, str.length() - suffix.length()).trim();
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
            SwingUtilities.invokeLater(() -> {
                history.add(finalStr);
                fireHistoryChanged();
            });
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
                    CompletableFuture.runAsync(() -> {
                        speak(finalStr);
                        Config.appendOutTts(finalStr);
                        Config.logDebug("speak:" + finalStr);
                    });
                    SwingUtilities.invokeLater(() -> {
                        window.setTitle(cpugpumode);
                    });
                }
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
        updateIcon();
    }
    private void updateIcon() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (MobMateWhisp.this.window != null) {
                    if (isRecording()) {
                        MobMateWhisp.this.button.setText("\uD83D\uDFE2 Stop");
                        MobMateWhisp.this.label.setText("\uD83C\uDFA4 Recording");
                    } else {
                        MobMateWhisp.this.button.setText("\uD83C\uDFA4 Start");
                        if (isTranscribing()) {
                            MobMateWhisp.this.label.setText("\uD83D\uDD34 Transcribing");
                        } else {
                            MobMateWhisp.this.label.setText("\uD83D\uDFE2 Idle");
                        }
                    }
                    if (isRecording()) {
                        MobMateWhisp.this.window.setIconImage(MobMateWhisp.this.imageRecording);
                    } else {
                        if (isTranscribing()) {
                            MobMateWhisp.this.window.setIconImage(MobMateWhisp.this.imageTranscribing);
                        } else {
                            MobMateWhisp.this.window.setIconImage(MobMateWhisp.this.imageInactive);
                        }
                    }
                }
                if (MobMateWhisp.this.trayIcon != null) {
                    if (isRecording()) {
                        MobMateWhisp.this.trayIcon.setImage(MobMateWhisp.this.imageRecording);
                    } else {
                        if (isTranscribing()) {
                            MobMateWhisp.this.trayIcon.setImage(MobMateWhisp.this.imageTranscribing);
                        } else {
                            MobMateWhisp.this.trayIcon.setImage(MobMateWhisp.this.imageInactive);
                        }
                    }
                }
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

    private void openWindow() {
        this.window = new JFrame("MobMateWhispTalk");
        this.window.setIconImage(this.imageInactive);
        this.window.setFocusable(true);
        this.window.setFocusableWindowState(true);

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.add(Box.createHorizontalGlue());
        p.add(Box.createHorizontalStrut(6));
        p.add(this.label);
        p.add(Box.createHorizontalStrut(6));
        p.add(this.button);
        MobMateWhisp.this.window.setTitle(cpugpumode);

        final JButton historyButton = new JButton("\uD83D\uDCDC History");
        p.add(Box.createHorizontalStrut(6));
        p.add(historyButton);
        final JButton prefButton = new JButton("⚙ Prefs");
        p.add(Box.createHorizontalStrut(6));
        p.add(prefButton);

        this.window.setContentPane(p);
        this.label.setText("\uD83D\uDD34 Transcribing");
        this.window.pack();
        this.window.setMinimumSize(
                new Dimension(510, this.window.getHeight())
        );
        this.label.setText("\uD83D\uDFE2 Idle");
        this.window.setResizable(false);
        this.window.setVisible(true);
        this.window.setLocationRelativeTo(null);
        this.window.setVisible(true);
        this.window.toFront();
        this.window.requestFocus();
        this.window.setLocation(15, 15);

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
                Config.mirrorAllToCloud();
                System.exit(0);
            }
        });
        SwingUtilities.updateComponentTreeUI(this.window);
        this.window.invalidate();
        this.window.validate();
        this.window.repaint();
        SwingUtilities.invokeLater(() -> {
            Timer timer = new Timer(1000, e -> {
                button.setEnabled(false);
                button.setText("Calibrating...");
                label.setText("Calibrating audio environment");
                primeVadByEmptyRecording();
            });
            timer.setRepeats(false);
            timer.start();
        });

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
    public void showHistory() {
        if (historyFrame != null && historyFrame.isShowing()) {
            historyFrame.toFront();
            historyFrame.requestFocus();
            return;
        }
        historyFrame = new HistoryFrame(this);
        historyFrame.setMinimumSize(new Dimension(510, 200));
        historyFrame.setSize(510, 400);
        historyFrame.setLocation(15, 80);
        historyFrame.refresh();
        historyFrame.setVisible(true);
        SwingUtilities.updateComponentTreeUI(historyFrame);
    }
    public void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        text = normalizeLaugh(text);
        text = Config.applyDictionary(text); // Dictorary apply
        if (isVoiceVoxAvailable()) {
            speakVoiceVox(text, this.prefs.get("tts.voice", "3"), Config.getString("voicevox.api", ""));
        } else if (isXttsAvailable()) {
            speakXtts(text);
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
    private boolean isXttsAvailable() {
        Config.logDebug("[XTTS] /tts try");
        try {
            String base = Config.getString("xtts.apichk", "");
            if (base.isEmpty()) return false;
            Config.logDebug(base.toString());
            Config.logDebug("[VV] /tts try2");
            URL url = new URL(base + "");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(100);
            conn.setReadTimeout(100);
            conn.connect();
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            Config.logDebug("[XTTS] /tts err");
            return false;
        }
    }
    private void speakXtts(String text) {
        try {
            Config.logDebug("[XTTS] /tts spk try");
            String base = Config.getString("xtts.api", "");
            String language = Config.getString("xtts.language", "en");
            HttpURLConnection conn =
                    (HttpURLConnection) new URL(base + "").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Content-Type", "application/json");
            String json = """
                {
                  "text": "%s",
                  "language": "%s"
                }
                """.formatted(
                        escapeJson(text),
                        language
                    );
            Config.logDebug("[XTTS] /tts spk try2" + json);
            conn.getOutputStream()
                    .write(json.getBytes(StandardCharsets.UTF_8));
            Path tmp = Files.createTempFile("xtts_", ".wav");
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
            }
            if (!waitForValidWav(tmp.toFile(), 5000)) {
                Config.logDebug("[XTTS] WAV invalid timeout");
                return;
            }
            Config.log("[XTTS] saved: " + tmp);
            playViaPowerShellAsync(tmp.toFile());
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    Files.deleteIfExists(tmp);
                } catch (Exception ignore) {}
            }).start();
        } catch (Exception ex) {
            ex.printStackTrace();
            Config.logDebug("[XTTS ERROR] " + ex.getMessage());
        }
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
    private void speakWindows(String text) {
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
                        throw new IOException("tts_agent bad response: " + resp);
                    }

                } catch (Exception e) {
                    Config.log("TTS pipe dead → restarting agent");
                    stopPsServerLocked();
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
                history.add(msg);
                fireHistoryChanged();
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
        Config.log("=== MobMateWhisp Started ===");
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
            case 1: suffix = "_ja"; break;
            case 2: suffix = "_zh_cn"; break;
            case 3: suffix = "_zh_tw"; break;
            case 4: suffix = "_ko"; break;
            case 0:
            default: suffix = "_en"; break;
        }
        copyPreset("libs/preset/_outtts" + suffix + ".txt", "_outtts.txt");
        copyPreset("libs/preset/_dictionary" + suffix + ".txt", "_dictionary.txt");
        copyPreset("libs/preset/_ignore" + suffix + ".txt", "_ignore.txt");
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
