package whisper;

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
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.nio.charset.StandardCharsets;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.net.*;

import javax.swing.*;
import javax.sound.sampled.AudioFileFormat;
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

public class MobMateWhisp implements NativeKeyListener {
    private static final int MIN_AUDIO_DATA_LENGTH = (int) (16000 * 2.1);

    private Preferences prefs;
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
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private ExecutorService audioService = Executors.newSingleThreadExecutor();

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

    protected JFrame window;
    final JButton button = new JButton("Start");

    final JLabel label = new JLabel("Idle");

    private Process psProcess;
    private BufferedWriter psWriter;
    private BufferedReader psReader;

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
        Config.log("JVM: " + System.getProperty("java.vm.name"));
        Config.log("JVM vendor: " + System.getProperty("java.vm.vendor"));
        // whisper.dll loadcpu/cuda/vulkan
        loadWhisperNative();

        String lang = Config.get("language");
        if (lang == null) lang = "auto";

        if (MobMateWhisp.ALLOWED_HOTKEYS.length != MobMateWhisp.ALLOWED_HOTKEYS_CODE.length) {
            throw new IllegalStateException("ALLOWED_HOTKEYS size mismatch");
        }

        this.prefs = Preferences.userRoot().node("MobMateWhispTalk");
        this.hotkey = this.prefs.get("hotkey", "F9");
        this.shiftHotkey = this.prefs.getBoolean("shift-hotkey", false);
        this.ctrltHotkey = this.prefs.getBoolean("ctrl-hotkey", false);
        this.model = this.prefs.get("model", "ggml-small.bin");

        GlobalScreen.registerNativeHook();
        GlobalScreen.addNativeKeyListener(this);

        String laughSetting = Config.getString("laughs", "ワハハハハハ");
        laughOptions = laughSetting.split(",");
        // trim
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
                                "Setup guide:\nhttps://github.com/zufall-upon/MobMate#virtual-audio-setup",
                        "MobMate Setup",
                        JOptionPane.INFORMATION_MESSAGE
                );

                if (Desktop.isDesktopSupported()) {
                    try {
                        Desktop.getDesktop().browse(new URI("https://github.com/zufall-upon/MobMate#virtual-audio-setup"));
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
//                    MobMateWhisp.this.prefs.clear();
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
        Menu hotkeysMenu = new Menu("Keyboard shortcut");
        // Shift hotkey modifier
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
        final Menu audioInputsItem = new Menu("Audio inputs");
        String audioDevice = this.prefs.get("audio.device", "");
        String previsouAudipDevice = this.prefs.get("audio.device.previous", "");
        // Get available audio input devices

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
            List<CheckboxMenuItem> all = new ArrayList<>();
            for (String name : mixers) {

                CheckboxMenuItem menuItem = new CheckboxMenuItem(name);
                if (currentAudioDevice.equals(name)) {
                    menuItem.setState(true);
                }
                audioInputsItem.add(menuItem);
                all.add(menuItem);
                // Add action listener to each menu item
                menuItem.addItemListener(new ItemListener() {

                    @Override
                    public void itemStateChanged(ItemEvent e) {
                        if (menuItem.getState()) {

                            for (CheckboxMenuItem m : all) {
                                final boolean selected = m.getLabel().equals(name);
                                m.setState(selected);

                            }
                            // Set preference
                            MobMateWhisp.this.prefs.put("audio.device.previous", MobMateWhisp.this.prefs.get("audio.device", ""));
                            MobMateWhisp.this.prefs.put("audio.device", name);
                            try {
                                MobMateWhisp.this.prefs.sync();
                            } catch (BackingStoreException e1) {
                                e1.printStackTrace();
                            }
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
            List<CheckboxMenuItem> all = new ArrayList<>();

            for (int i = 0; i < outputMixers.size(); i++) {
                String name = outputMixers.get(i);

                // ① 表示名: "番号: 名前"
                String displayName = String.format("%02d: %s", i, name);

                CheckboxMenuItem item = new CheckboxMenuItem(displayName);

                // ② 既存の保存名は「名前」なので変換
                item.setState(outputDevice.equals(name));

                audioOutputsItem.add(item);
                all.add(item);

                final String mixerName = name; // capture

                item.addItemListener(e -> {
                    if (item.getState()) {
                        for (CheckboxMenuItem m : all) {
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

        popup.addSeparator();
        MenuItem exitItem = new MenuItem("Exit");
        popup.add(exitItem);
        exitItem.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
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
        // Return first
        for (Mixer.Info mixerInfo : mixers) {
            final Mixer mixer = AudioSystem.getMixer(mixerInfo);
            final Line.Info[] targetLines = mixer.getTargetLineInfo();

            Line.Info lInfo = null;
            for (Line.Info lineInfo : targetLines) {
                if (lineInfo.getLineClass().getName().contains("TargetDataLine")) {
                    try {
                        return (TargetDataLine) mixer.getLine(lInfo);
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

                SwingUtilities.invokeLater(new Runnable() {

                    @Override
                    public void run() {
                        final String strAction = MobMateWhisp.this.prefs.get("action", "noting");
                        Action action = Action.NOTHING;
                        if (strAction.equals("paste")) {
                            action = Action.COPY_TO_CLIPBOARD_AND_PASTE;
                        } else if (strAction.equals("type")) {
                            action = Action.TYPE_STRING;
                        }

                        if (!isRecording()) {
                            MobMateWhisp.this.recordingStartTime = System.currentTimeMillis();
                            startRecording(action);
                        } else {
                            stopRecording();
                        }
                    }
                });
                break;
            }
        }

    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
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
                this.hotkeyPressed = false;

                SwingUtilities.invokeLater(new Runnable() {

                    @Override
                    public void run() {

                        String currentMode = MobMateWhisp.this.prefs.get("trigger-mode", PUSH_TO_TALK);
                        if (currentMode.equals(PUSH_TO_TALK)) {
                            stopRecording();
                        } else if (currentMode.equals(PUSH_TO_TALK_DOUBLE_TAP)) {
                            long delta = System.currentTimeMillis() - MobMateWhisp.this.recordingStartTime;
                            if (delta > 300) {
                                stopRecording();
                            }
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

    private void startRecording(Action action) {
        Config.log("MobMateWhispTalk.startRecording()" + action);
        if (isRecording()) {
            // Prevent multiple recordings
            return;
        }

        setRecording(true);
        try {
            String audioDevice = this.prefs.get("audio.device", "");
            String previsouAudipDevice = this.prefs.get("audio.device.previous", "");

            // Create a thread to capture the audio data
            this.audioService.execute(new Runnable() {

                @Override
                public void run() {
                    TargetDataLine targetDataLine;
                    try {
                        targetDataLine = getTargetDataLine(audioDevice);
                        if (targetDataLine == null) {
                            targetDataLine = getTargetDataLine(previsouAudipDevice);
                            if (targetDataLine == null) {
                                targetDataLine = getFirstTargetDataLine();
                            } else {
                                Config.log("Using previous audio device : " + previsouAudipDevice);
                            }
                            if (targetDataLine == null) {
                                JOptionPane.showMessageDialog(null, "Cannot find any input audio device");
                                setRecording(false);
                                return;
                            } else {
                                Config.log("Using default audio device");
                            }
                        } else {
                            Config.log("Using audio device : " + audioDevice);
                        }

                        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                        try {
                            targetDataLine.open(MobMateWhisp.this.audioFormat);
                            targetDataLine.start();

                            setRecording(true);

                            // 0.25s
                            byte[] data = new byte[3000];
                            boolean detectSilence = MobMateWhisp.this.prefs.getBoolean("silence-detection", true);
                            if (detectSilence) {
                                while (isRecording()) {
                                    int numBytesRead = targetDataLine.read(data, 0, data.length);
                                    if (numBytesRead > 0) {
                                        byteArrayOutputStream.write(data, 0, numBytesRead);
                                    }
                                    // === periodic flush ===
                                    if (byteArrayOutputStream.size() > 16000 * 3) {
                                        final byte[] chunk = byteArrayOutputStream.toByteArray();
                                        byteArrayOutputStream.reset();

                                        executorService.execute(() -> {
                                            try {
                                                String partial = transcribe(chunk, action, false);

                                                if (partial != null && !partial.isEmpty()) {
                                                    // === Real-time update here ===
                                                    SwingUtilities.invokeLater(() -> {
//                                                        history.add(partial);
                                                        fireHistoryChanged();
                                                        window.setTitle(partial);
                                                    });
                                                }
                                            } catch (IOException e) {
                                                e.printStackTrace();
                                            }
                                        });
                                    }
                                    Thread.sleep(10);
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
                            Config.log("Audio input device not available (used by an other process?)");
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            try {
                                targetDataLine.stop();
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

                        MobMateWhisp.this.executorService.execute(new Runnable() {

                            @Override
                            public void run() {
                                try {
                                    transcribe(audioData, action, true);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        });

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

    public String transcribe(byte[] audioData, final Action action, boolean isEndOfCapture) throws IOException {
        int threshold = Config.getInt("silence_hard", 100);
        if (detectSilence(audioData, audioData.length, threshold)) {
            if (this.debug) {
                Config.logDebug("Silence detected");
            }
            if (!isEndOfCapture) return "";
            return "";
        }
        int maxAmp = getMaxAmplitude(audioData);
        if (maxAmp < threshold + 500) {   // ←閾値。600〜1500推奨
            if (debug) {
                Config.logDebug("Filtered low amplitude segment: " + maxAmp);
            }
            return "";
        }
        if (audioData.length < MIN_AUDIO_DATA_LENGTH) {
            byte[] n = new byte[MIN_AUDIO_DATA_LENGTH];
            System.arraycopy(audioData, 0, n, 0, audioData.length);
            audioData = n;
        }

        setTranscribing(true);

        String str;
        if (MobMateWhisp.this.remoteUrl == null) {
            str = this.w.transcribeRaw(audioData);
        } else {
            // Save the recorded audio to a WAV file for remote
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String fileName = timestamp + ".wav";
            final File out = File.createTempFile("rec_", fileName);
            try (AudioInputStream audioInputStream = new AudioInputStream(new ByteArrayInputStream(audioData), this.audioFormat, audioData.length / this.audioFormat.getFrameSize())) {
                AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, out);
                str = processRemote(out, action);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "Error processing record : " + e.getMessage());
                e.printStackTrace();
                setTranscribing(false);
                return "";
            } finally {
                if (this.debug) {
                    Config.logDebug("Audio record stored in : " + out.getAbsolutePath());
                } else {
                    boolean deleted = out.delete();
                    if (!deleted) {
                        Logger.getGlobal().warning("cannot delete " + out.getAbsolutePath());
                    }
                }
            }
        }
        str = str.replace('\n', ' ');
        str = str.replace('\r', ' ');
        str = str.replace('\t', ' ');
        str = str.trim();
        // if (str.matches("[A-Za-z& ]+")) return "";
        // === dedupe ===
        if (lastOutput != null && lastOutput.equals(str)) {
            if (debug) Config.log("Duplicate skipped: " + str);
            return "";
        }
        // === early noise filters ===
        if (str.matches("^\\[.*\\]$")) {
            if (debug) Config.log("Bracket noise skipped: " + str);
            return "";
        }
        if (str.matches("^[A-Za-z0-9& ]{1,12}$")) {
            if (str.length() < 2) {
                if (debug) Config.log("Short alpha skipped: " + str);
                return "";
            }
        }
        lastOutput = str;

        final String suffix = "Thank you.";
        if (str.endsWith(suffix)) {
            str = str.substring(0, str.length() - suffix.length());
        }
        final String finalStr = str;

        // if (!isEndOfCapture) {
        //     str += " ";
        // }
        // === partial output ===
        SwingUtilities.invokeLater(() -> {
            if (action.equals(Action.TYPE_STRING)) {
                // same
            } else if (action.equals(Action.COPY_TO_CLIPBOARD_AND_PASTE)) {
                // same
            }

            // === NEW: interim immediate output ===

            // === GUI update ===
            if (finalStr == null || finalStr.trim().isEmpty()) {
                return;
            }
            history.add(finalStr);
            fireHistoryChanged();
            window.setTitle(finalStr);
        });

        SwingUtilities.invokeLater(new Runnable() {

            @Override
            public void run() {
                if (action.equals(Action.TYPE_STRING)) {
                    try {
                        RobotTyper typer = new RobotTyper();
                        Config.log("Typing : " + finalStr);
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
                        Config.log("Warning : cannot get previous clipboard content");
                    }
                    final Transferable toPaste = previous;
                    clipboard.setContents(new StringSelection(finalStr), null);
                    try {
                        Robot robot = new Robot();
                        Config.log("Pasting : " + finalStr);
                        robot.keyPress(KeyEvent.VK_CONTROL);
                        robot.keyPress(KeyEvent.VK_V);
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        robot.keyRelease(KeyEvent.VK_V);
                        robot.keyRelease(KeyEvent.VK_CONTROL);
                        Config.log("Pasting : " + finalStr + " DONE");

                    } catch (AWTException e) {
                        e.printStackTrace();
                    }
                    if (toPaste != null) {
                        Thread t = new Thread(new Runnable() {
                            public void run() {
                                if (toPaste != null) {
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                    Config.log("Restoring previous clipboard content");
                                    clipboard.setContents(toPaste, null);

                                }
                            }
                        });
                        t.start();
                    }

                }
                // Invoke later to be sure paste is done
                SwingUtilities.invokeLater(new Runnable() {

                    @Override
                    public void run() {
                        // === LOG APPEND ===
                        String s = finalStr == null ? "" : finalStr;
                        Config.appendOutTts(finalStr);

                        // === GUI UPDATE: append + autoscroll ===
                        if (MobMateWhisp.this.window != null) {
                            SwingUtilities.invokeLater(() -> {
                                java.awt.Container c = MobMateWhisp.this.window.getContentPane();
                                if (c instanceof JPanel) {
                                    MobMateWhisp.this.window.setTitle(finalStr);
                                }
                            });
                        }
//                        MobMateWhisp.this.history.add(finalStr);
                        fireHistoryChanged();
                    }
                });
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
        Config.log("Response from remote whisper.cpp (" + (t2 - t1) + " ms): " + string);
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
        this.window.setFocusable(false);
        this.window.setFocusableWindowState(false);
        JPanel p = new JPanel();
        p.setPreferredSize(new Dimension(350, 30));
        p.setLayout(new FlowLayout(FlowLayout.RIGHT));
        p.add(this.label);
        p.add(this.button);

        MobMateWhisp.this.window.setTitle(cpugpumode);

        final JButton historyButton = new JButton("\uD83D\uDCDC History");
        p.add(historyButton);

        final JButton prefButton = new JButton("⚙ Prefs");
        p.add(prefButton);
        this.window.setContentPane(p);
        this.label.setText("\uD83D\uDD34 Transcribing..");
        this.window.pack();
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
        this.button.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                final String strAction = MobMateWhisp.this.prefs.get("action", "noting");
                Action action = Action.NOTHING;
                if (strAction.equals("paste")) {
                    action = Action.COPY_TO_CLIPBOARD_AND_PASTE;
                } else if (strAction.equals("type")) {
                    action = Action.TYPE_STRING;
                }

                if (!isRecording()) {
                    MobMateWhisp.this.recordingStartTime = System.currentTimeMillis();
                    startRecording(action);
                } else {
                    stopRecording();
                }

            }
        });
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

        startTtsWatcher();
        this.window.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        this.window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (isRecording()) {
                    stopRecording();
                }
                //TODO PowerShell server stop
                //stopPsServer();

                System.exit(0);
            }
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
        historyFrame = new HistoryFrame(MobMateWhisp.this);
        historyFrame.setSize(300, 400);
        historyFrame.setLocation(15, 80);
        historyFrame.setVisible(true);
    }

    private static boolean detectSilence(byte[] buffer, int bytesRead, int threshold) {
        int maxAmplitude = 0;
        // 16-bit audio = 2 bytes per sample
        for (int i = 0; i < bytesRead; i += 2) {
            int sample = (buffer[i + 1] << 8) | (buffer[i] & 0xFF);
            maxAmplitude = Math.max(maxAmplitude, Math.abs(sample));
        }
//        log("max=" + maxAmplitude + " thresh=" + threshold);
        return maxAmplitude < threshold;
    }
    private static int getMaxAmplitude(byte[] buffer) {
        int max = 0;
        for (int i = 0; i < buffer.length; i += 2) {
            int sample = (buffer[i + 1] << 8) | (buffer[i] & 0xFF);
            max = Math.max(max, Math.abs(sample));
        }
        return max;
    }

    private void startTtsWatcher() {
        Thread t = new Thread(() -> {
            Path watchFile = Paths.get("_outtts.txt");
            String last = "";

            while (true) {
                try {
                    if (!Files.exists(watchFile)) {
                        Thread.sleep(200);
                        continue;
                    }

                    List<String> lines =
                            Files.readAllLines(watchFile, StandardCharsets.UTF_8);

                    if (lines.isEmpty()) {
                        Thread.sleep(200);
                        continue;
                    }

                    String cur = lines.get(lines.size() - 1).trim();

                    if (!cur.isEmpty() && !cur.equals(last)) {
                        String finalStr = cur.trim();
                        if (!finalStr.isEmpty()) {
                            speak(finalStr);
                            logToHistory("speak:" + finalStr);
                        }
                        last = finalStr;
                    }

                    Thread.sleep(200);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        t.setDaemon(true);
        t.start();
    }
    private void speakVoiceVox(String text, String speakerId, String base) {
        try {
            // 1) audio_query
            String queryUrl = base + "/audio_query?text=" +
                    URLEncoder.encode(text, "UTF-8") +
                    "&speaker=" + speakerId;

            HttpURLConnection q = (HttpURLConnection) new URL(queryUrl).openConnection();
            q.setRequestMethod("POST");
            q.setDoOutput(true);
            q.getOutputStream().write(new byte[0]);

            String queryJson = new String(q.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            // 2) synthesis
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

//            logToHistory("[VV] saved: " + tmp);;
            if (!waitForValidWav(tmp.toFile(), 3000)) {
                logToHistory("[VV] WAV invalid timeout");
                return;
            }
            Config.log("[VV] saved: " + tmp);

//            playWav(tmp.toFile());
            playViaPowerShell(tmp.toFile());

            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    Files.deleteIfExists(tmp);
                } catch (Exception ignore) {}
            }).start();

        } catch (Exception ex) {
            ex.printStackTrace();
            logToHistory("[VV ERROR] " + ex.getMessage());
        }
    }
//    private void playWav(File f) throws Exception {
//
//        String deviceName = prefs.get("audio.output.device", "").trim();
//        Mixer.Info targetMixerInfo = null;
//
//        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
//            if (info.getName().equals(deviceName)) {
//                targetMixerInfo = info;
//                break;
//            }
//        }
//
//        Mixer mixer = null;
//        Clip clip = null;
//
//        if (targetMixerInfo != null) {
//            mixer = AudioSystem.getMixer(targetMixerInfo);
//            mixer.open();
//            clip = (Clip) mixer.getLine(new Line.Info(Clip.class));
//            Config.log("Using mixer: " + targetMixerInfo.getName());
//        } else {
//            clip = AudioSystem.getClip();
//            Config.log("Using DEFAULT mixer");
//        }
//
//        AudioInputStream ais = AudioSystem.getAudioInputStream(f);
//
//        AudioFormat base = ais.getFormat();
//        AudioFormat decoded = new AudioFormat(
//                AudioFormat.Encoding.PCM_SIGNED,
//                base.getSampleRate(),
//                16,
//                base.getChannels(),
//                base.getChannels() * 2,
//                base.getSampleRate(),
//                false
//        );
//
//        AudioInputStream dais = AudioSystem.getAudioInputStream(decoded, ais);
//
//        clip.open(dais);
//        clip.start();
//        clip.drain();
//
//        while (clip.isRunning()) {
//            Thread.sleep(100);
//        }
//        Config.log("[VV] speaked");
//        clip.close();
//        dais.close();
//        ais.close();
//
//        if (mixer != null) mixer.close();
//    }
    private void speakWindows(String text) {
        try {
            Path tmp = Files.createTempFile("win_", ".wav");
            String wavPath = tmp.toAbsolutePath().toString().replace("\\", "\\\\");

            // PowerShell
            String ps =
                    "Add-Type -AssemblyName System.Speech;" +
                            "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer;" +
                            "$s.SetOutputToWaveFile('" + wavPath + "');" +
                            "$s.Speak('" + text.replace("'", "''") + "');" +
                            "$s.Dispose();";

            new ProcessBuilder("powershell", "-NoLogo", "-NoProfile", "-Command", ps)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
            if (!waitForValidWav(tmp.toFile(), 3000)) {
                logToHistory("[WIN] WAV invalid timeout");
                return;
            }

//            playWav(tmp.toFile());
            playViaPowerShell(tmp.toFile());

            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    Files.deleteIfExists(tmp);
                } catch (Exception ignore) {}
            }).start();

        } catch (Exception ex) {
            ex.printStackTrace();
            logToHistory("[WIN ERROR] " + ex.getMessage());
        }
    }
//    private void playViaPowerShell(File wavFile) {
//        try {
//            String deviceName = prefs.get("audio.output.device", "").trim();
//            int devIndex = findOutputDeviceIndex(deviceName);
//            new ProcessBuilder(
//                    "powershell",
//                    "-ExecutionPolicy", "Bypass",
//                    "-File", "PlayWav.ps1",
//                    "-WavPath", wavFile.getAbsolutePath(),
//                    "-DeviceNumber", "" + devIndex
//            ).start();
//            Config.log("[VV] playViaPowerShell:" + "PlayWav.ps1_" + wavFile.getAbsolutePath() + "-DeviceNumber" + devIndex);
//
//        } catch (Exception ex) {
//            ex.printStackTrace();
//        }
//    }
    private void playViaPowerShell(File wavFile) {
        try {
            startPsServer();

            int devIndex = findOutputDeviceIndex(prefs.get("audio.output.device", ""));

            psWriter.write(
                    "PLAY \"" + wavFile.getAbsolutePath() + "\" " + devIndex + "\n"
            );
            psWriter.flush();

            String resp = psReader.readLine();
            if (!"DONE".equals(resp)) {
//                Config.log("ERR? " + resp);
            }

        } catch (Exception e) {
            Config.log("Pipe dead → restarting...");
            psProcess = null;
            try {
                startPsServer();
            } catch (Exception e2) {}
        }
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
            playViaPowerShell(f);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;

        text = normalizeLaugh(text);
        text = w.applyDictionary(text); // Dictorary apply

        if (isVoiceVoxAvailable()) {
            speakVoiceVox(text, Config.getString("voicevox.speaker", "3"), Config.getString("voicevox.api", ""));
        } else {
            speakWindows(text);
        }
    }
    private boolean isVoiceVoxAvailable() {
        try {
            URL url = new URL(Config.getString("voicevox.api", "") + "/speakers");
            logToHistory(url.toString());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(200);
            conn.connect();
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
    private void logToHistory(String msg) {
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

    String[] laughDetectJa;
    String[] laughDetectAuto;
    String[] laughReplace;
    boolean laughEnable = true;
    private String normalizeLaugh(String s) {
        if (s == null || s.isEmpty()) return s;
        if (!Config.getBool("laughs.enable", true)) return s;
        String[] replace = Config.splitCsv(Config.get("laughs.replace"));
        if (replace == null || replace.length == 0) return s;
        if (!isLaughDetected(s)) return s;

        String pick = replace[rnd.nextInt(replace.length)];
        if (pick.contains("/") || pick.contains("\\")) {
            playLaughSound(pick);
        }
        return pick;
    }
    private boolean isLaughDetected(String s) {
        String lang = Config.get("language");
        if (lang == null || lang.isBlank()) lang = "auto";
        if ("auto".equalsIgnoreCase(lang)) {
            String[] keys = Config.splitCsv(Config.get("laughs.detect.auto"));
            if (keys == null || keys.length == 0) {
                keys = Config.splitCsv(Config.get("laughs.detect"));
            }
            return Config.matchAny(s, keys) || Config.matchAny(s.toLowerCase(Locale.ROOT), keys);
        } else {
            String[] keys = Config.splitCsv(Config.get("laughs.detect"));
            return Config.matchAny(s, keys) || Config.matchAny(s.toLowerCase(Locale.ROOT), keys);
        }
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
                new Backend("CUDA", new File(libsDir, "cuda"),
                        new String[]{
                                "ggml-cuda.dll",
                                "ggml-base.dll",
                                "ggml-cpu.dll",
                                "ggml.dll",
                                "cudart64_110.dll"
                        },
                        "whisper.dll"
                ),
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
        for (Backend b : backends) {
            Config.log("Checking backend: " + b.name + " in " + b.dir);
            if (!b.dir.exists()) {
                Config.log(" → Directory does not exist, skipping.");
                continue;
            }
            Config.log("Copying backend DLLs to exe folder...");
            List<File> copied = copyDllsToExeDir(b, exeDir);
            try{ Thread.sleep(300); } catch (Exception e) {}
            Config.log("Trying to load backend: " + b.name);
            if (safeLoad(b.dir + File.separator + b.mainDll)) {
                System.out.println("Loaded " + b.name + " backend successfully.");
                if (b.name != "CPU") {
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
    // Backend データ構造
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
//                    Config.log("Copied: " + src + " → " + dst);
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
//            Config.log("Copied main DLL: " + mainDst);
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

        restartSelf();
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
    private static void restartSelf() {
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
                // launch4j exe
                pb = new ProcessBuilder(current.getAbsolutePath());
            } else {
                // jar 実行
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
