package whisper;

import com.github.kwhat.jnativehook.NativeHookException;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileNotFoundException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.List;
import java.util.prefs.BackingStoreException;

import static whisper.MobMateWhisp.prefs;
import static whisper.VoicegerManager.checkVoicegerFiles;

/**
 * 初回起動ウィザード（多言語対応）
 */
public class FirstLaunchWizard extends JDialog {

    private final MobMateWhisp mobmatewhisp;
    private boolean completed = false;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardPanel = new JPanel(cardLayout);

    private static final int STEP_INPUT  = 0;
    private static final int STEP_OUTPUT = 1;
    private static final int STEP_VOICEVOX = 2;
    private static final int STEP_XTTS     = 3;
    private static final int STEP_VOICEGER = 4;
    private static final int STEP_COUNT = 5;

    private int step = STEP_INPUT;

    // --- Step1 mic monitor ---
    private TargetDataLine monitorLine;
    private volatile boolean monitoring;

    // --- Step2 output test ---
    private Timer outputTestTimer;
    private volatile boolean outputSpeaking;
    private JProgressBar outputTestBar;

    // --- Step3 VoiceVox UI ---
    private JTextField vvExeField;
    private JTextField vvApiField;
    private JLabel vvStatusLabel;
    private JComboBox<VvStyle> vvSpeakerCombo;

    // ==== Wizard test controls ====
    private volatile boolean inputMeterRunning = false;
    private Thread inputMeterThread;
    private javax.sound.sampled.TargetDataLine inputMeterLine;

    // --- Step 5 Voiceger UI ---
    private JTextField vgDirField;
    private JCheckBox vgEnableCheck;


    public boolean isCompleted() {
        return completed;
    }


    public FirstLaunchWizard(Frame owner, MobMateWhisp mmw) throws FileNotFoundException, NativeHookException {
        super(owner, UiText.t("wizard.title"), true);
        this.mobmatewhisp = mmw;

        setSize(700, 600);
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        cardPanel.add(stepInputDevice(), "step1");
        cardPanel.add(stepOutputDevice(), "step2");
        cardPanel.add(stepVoiceVox(), "step3");
        cardPanel.add(stepXtts(), "step4");
        cardPanel.add(stepVoiceger(), "step5");

        add(cardPanel, BorderLayout.CENTER);
        add(bottomBar(), BorderLayout.SOUTH);

        // 初期 step enter
        onStepEnter(step);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // ×で閉じた扱い
                completed = false;
                prefs.putBoolean("wizard.completed", false);
                try { prefs.sync(); } catch (Exception ignore) {}
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                // 念のため全停止
                onStepExit(step);
            }
        });
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                try { stopAllWizardTests(); } catch (Throwable ignore) {}
            }

            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                try { stopAllWizardTests(); } catch (Throwable ignore) {}
            }
        });

    }
    public void stopAllWizardTests() {
        try { stopMicMonitor(); } catch (Throwable ignore) {}
        try { stopOutputSpeakTest(); } catch (Throwable ignore) {}
    }
    private void stopInputMeter() {
        inputMeterRunning = false;

        if (inputMeterThread != null) {
            try { inputMeterThread.interrupt(); } catch (Exception ignored) {}
            inputMeterThread = null;
        }

        if (inputMeterLine != null) {
            try { inputMeterLine.stop(); } catch (Exception ignored) {}
            try { inputMeterLine.close(); } catch (Exception ignored) {}
            inputMeterLine = null;
        }
    }


    /* =========================
       Step lifecycle
     ========================= */

    private void onStepEnter(int s) {
        if (s == STEP_INPUT) {
            // mic monitor を開始（選択中デバイス）
            String cur = prefs.get("audio.device", "");
            if (!cur.isEmpty()) {
                // Step1 パネル内の bar は rebuild しないので、monitorは step1内で開始済み。
                // ここでは何もしない（リソースリーク防止のため exit 側で止める）
            }
        }
        if (s == STEP_OUTPUT) {
//            if (outputTestBar != null) startOutputSpeakTest(outputTestBar);
        }
        if (s == STEP_VOICEVOX) {
            // Step3 表示時に話者一覧を（できれば）読み込む
            refreshVoiceVoxSpeakersAsync();
        }
        if (s == STEP_VOICEGER) {
            refreshVoicegerStatus();
        }
    }

    private void onStepExit(int s) {
        if (s == STEP_INPUT) {
            stopMicMonitor();
        }
        if (s == STEP_OUTPUT) {
            stopOutputSpeakTest();
        }
        if (s == STEP_VOICEVOX) {
            // 特にループ系なし
        }
        if (s == STEP_VOICEGER) {
            // 何もしなくてOK（止めたいなら stopVoicegerApi(); を呼ぶ）
        }
    }

    /* =========================
       Step 1
     ========================= */

    private JPanel stepInputDevice() {
        JPanel p = basePanel();

        p.add(title("wizard.step1.title"));
        p.add(text("wizard.step1.desc"));

        JComboBox<String> micList = new JComboBox<>();

        // ===== 既存ロジックと同じ取得 =====
        List<String> mixers = mobmatewhisp.getInputsMixerNames();
        Collections.sort(mixers);

        String audioDevice = prefs.get("audio.device", "");
        String previous = prefs.get("audio.device.previous", "");

        String currentAudioDevice = "";
        if (!audioDevice.isEmpty() && mixers.contains(audioDevice)) {
            currentAudioDevice = audioDevice;
        } else if (!previous.isEmpty() && mixers.contains(previous)) {
            currentAudioDevice = previous;
        } else if (!mixers.isEmpty()) {
            currentAudioDevice = mixers.get(0);
            prefs.put("audio.device", currentAudioDevice);
        }

        for (String name : mixers) {
            micList.addItem(name);
        }
        if (!currentAudioDevice.isEmpty()) {
            micList.setSelectedItem(currentAudioDevice);
        }

        p.add(micList);
        p.add(text("wizard.step1.test"));

        JProgressBar level = new JProgressBar(0, 100);
        level.setStringPainted(true);
        p.add(level);

        // ===== 選択変更時の挙動（本体と同じ） =====
        micList.addActionListener(e -> {
            String name = (String) micList.getSelectedItem();
            if (name == null) return;

            // prefs 更新（完全に同じ）
            prefs.put("audio.device.previous", prefs.get("audio.device", ""));
            prefs.put("audio.device", name);
            try {
                prefs.sync();
            } catch (BackingStoreException ex) {
                ex.printStackTrace();
            }

            // モニタ再起動
            stopMicMonitor();
            startMicMonitorByName(name, level);
        });

        // 初期モニタ開始
        if (!currentAudioDevice.isEmpty()) {
            startMicMonitorByName(currentAudioDevice, level);
        }

        return p;
    }

    private AudioFormat getDefaultFormat() {
        return new AudioFormat(16000f, 16, 1, true, false);
    }

    private void startMicMonitorByName(String mixerName, JProgressBar bar) {
        try {
            AudioFormat fmt = getDefaultFormat();
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                if (!info.getName().equals(mixerName)) continue;
                Mixer mixer = AudioSystem.getMixer(info);
                DataLine.Info dlInfo = new DataLine.Info(TargetDataLine.class, fmt);
                if (!mixer.isLineSupported(dlInfo)) continue;

                monitorLine = (TargetDataLine) mixer.getLine(dlInfo);
                monitorLine.open(fmt);
                monitorLine.start();
                monitoring = true;

                Thread t = new Thread(() -> {
                    byte[] buf = new byte[1024];
                    while (monitoring) {
                        int n = monitorLine.read(buf, 0, buf.length);
                        if (n <= 0) continue;

                        double rms = 0;
                        for (int i = 0; i < n; i += 2) {
                            int v = (buf[i + 1] << 8) | (buf[i] & 0xff);
                            rms += v * v;
                        }
                        rms = Math.sqrt(rms / (n / 2.0));

                        int lv = (int) Math.min(100, rms / 300);
                        SwingUtilities.invokeLater(() -> bar.setValue(lv));
                    }
                }, "WizardMicMonitor");
                t.setDaemon(true);
                t.start();
                return;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void stopMicMonitor() {
        monitoring = false;
        if (monitorLine != null) {
            try {
                monitorLine.stop();
                monitorLine.close();
            } catch (Exception ignored) {}
            monitorLine = null;
        }
    }

    /* =========================
       Step 2
     ========================= */

    private JPanel stepOutputDevice() {
        JPanel p = basePanel();

        p.add(title("wizard.step2.title"));
        p.add(text("wizard.step2.desc"));
        p.add(text("wizard.step2.note"));

        JComboBox<String> outputList = new JComboBox<>();
        List<String> outputMixers = mobmatewhisp.getOutputMixerNames();
        Collections.sort(outputMixers);

        String outputDevice = prefs.get("audio.output.device", "");
        String prevOutputDevice = prefs.get("audio.output.device.previous", "");

        String current = "";
        if (!outputDevice.isEmpty() && outputMixers.contains(outputDevice)) {
            current = outputDevice;
        } else if (!prevOutputDevice.isEmpty() && outputMixers.contains(prevOutputDevice)) {
            current = prevOutputDevice;
        } else if (!outputMixers.isEmpty()) {
            current = outputMixers.get(0);
            prefs.put("audio.output.device", current);
        }

        for (String name : outputMixers) {
            outputList.addItem(name);
        }
        if (!current.isEmpty()) {
            outputList.setSelectedItem(current);
        }

        p.add(outputList);
        p.add(text("wizard.step2.test"));

        outputTestBar = new JProgressBar(0, 100);
        outputTestBar.setStringPainted(true);
        p.add(outputTestBar);
        p.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) == 0) return;

            if (p.isShowing()) {
                System.out.println("[Wizard] Step2 showing -> startOutputSpeakTest");
                SwingUtilities.invokeLater(() -> startOutputSpeakTest(outputTestBar));
            } else {
                System.out.println("[Wizard] Step2 hidden -> stopOutputSpeakTest");
                stopOutputSpeakTest();
            }
        });


        // 出力デバイス変更 → prefs反映
        outputList.addActionListener(e -> {
            String name = (String) outputList.getSelectedItem();
            if (name == null) return;

            prefs.put("audio.output.device.previous", prefs.get("audio.output.device", ""));
            prefs.put("audio.output.device", name);
            try {
                prefs.sync();
            } catch (BackingStoreException ex) {
                ex.printStackTrace();
            }
        });

        return p;
    }

    // ★ Step2 表示中だけ回す
    private void startOutputSpeakTest(JProgressBar bar) {
        stopOutputSpeakTest();

        outputSpeaking = false;

        // Swing Timer は EDT で動く（EDTが詰まってると発火しない）
        outputTestTimer = new javax.swing.Timer(1500, e -> {
            System.out.println("[Wizard] Step2 timer tick. outputSpeaking=" + outputSpeaking);

            if (outputSpeaking) return;
            outputSpeaking = true;

            bar.setValue(30 + (int) (Math.random() * 50));

            new Thread(() -> {
                try {
                    System.out.println("[Wizard] speakWindows: start");
                    mobmatewhisp.speakWindows("test now.");
                    System.out.println("[Wizard] speakWindows: done");
                } catch (Throwable ex) {
                    System.out.println("[Wizard] speakWindows: ERROR " + ex);
                    ex.printStackTrace();
                } finally {
                    try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                    outputSpeaking = false;
                }
            }, "WizardOutputSpeakTest").start();
        });

        outputTestTimer.setRepeats(true);
        outputTestTimer.setCoalesce(true); // EDT詰まり時のイベント溜めを抑える
        outputTestTimer.start();
        System.out.println("[Wizard] Step2 timer started");
    }

    private void stopOutputSpeakTest() {
        if (outputTestTimer != null) {
            outputTestTimer.stop();
            outputTestTimer = null;
            System.out.println("[Wizard] Step2 timer stopped");
        }
        outputSpeaking = false;
    }

    /* =========================
       Step 3 (VOICEVOX)
     ========================= */
    private JPanel stepVoiceVox() {
        JPanel p = basePanel();

        p.add(title("wizard.step3.title"));
        p.add(text("wizard.step3.desc"));

        // 既存値
        String curExe = Config.getString("voicevox.exe", "");
        String curApi = Config.getString("voicevox.api", "http://127.0.0.1:50021");
        String curSpeaker = prefs.get("tts.voice", "3");

        // --- exe row ---
        JPanel rowExe = new JPanel(new BorderLayout(8, 0));
        rowExe.setOpaque(false);

        vvExeField = new JTextField(curExe);
        JButton browse = new JButton(UiText.t("wizard.step3.browse"));

        browse.addActionListener(e -> chooseVoiceVoxExe());

        rowExe.add(new JLabel(UiText.t("wizard.step3.exeLabel")), BorderLayout.WEST);
        rowExe.add(vvExeField, BorderLayout.CENTER);
        rowExe.add(browse, BorderLayout.EAST);

        // --- api row ---
        JPanel rowApi = new JPanel(new BorderLayout(8, 0));
        rowApi.setOpaque(false);

        vvApiField = new JTextField(curApi);
        rowApi.add(new JLabel(UiText.t("wizard.step3.apiLabel")), BorderLayout.WEST);
        rowApi.add(vvApiField, BorderLayout.CENTER);

        // --- buttons row ---
        JPanel rowBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        rowBtns.setOpaque(false);

        JButton launch = new JButton(UiText.t("wizard.step3.launch"));
        JButton reload = new JButton(UiText.t("wizard.step3.reloadSpeakers"));
        JButton testSpeak = new JButton(UiText.t("wizard.step3.testSpeak"));

        vvStatusLabel = new JLabel(" ");
        vvStatusLabel.setForeground(Color.DARK_GRAY);

        launch.addActionListener(e -> launchVoiceVox());
        reload.addActionListener(e -> refreshVoiceVoxSpeakersAsync());
        testSpeak.addActionListener(e -> new Thread(() -> mobmatewhisp.speak("testです"), "WizardVVTestSpeak").start());

        rowBtns.add(launch);
        rowBtns.add(reload);
        rowBtns.add(testSpeak);
        rowBtns.add(vvStatusLabel);

        // --- speaker row ---
        JPanel rowSpk = new JPanel(new BorderLayout(8, 0));
        rowSpk.setOpaque(false);

        vvSpeakerCombo = new JComboBox<>();
        vvSpeakerCombo.setPrototypeDisplayValue(new VvStyle(0, "XXXXXXXXXXXXXXX (XXXXXXXXXXXXXXX)"));
        vvSpeakerCombo.addItemListener(e -> {
            if (e.getStateChange() != ItemEvent.SELECTED) return;

            Object sel = e.getItem();
            if (!(sel instanceof VvStyle)) return;

            VvStyle s = (VvStyle) sel;

            prefs.put("tts.voice", String.valueOf(s.id));
            try {
                prefs.sync();
            } catch (BackingStoreException ex) {
                ex.printStackTrace();
            }
        });
        rowSpk.add(new JLabel(UiText.t("wizard.step3.speakerLabel")), BorderLayout.WEST);
        rowSpk.add(vvSpeakerCombo, BorderLayout.CENTER);

        p.add(rowExe);
        p.add(Box.createVerticalStrut(8));
        p.add(rowApi);
        p.add(Box.createVerticalStrut(8));
        p.add(rowSpk);
        p.add(Box.createVerticalStrut(8));
        p.add(rowBtns);

        p.add(Box.createVerticalStrut(12));
        p.add(text("wizard.step3.outtts"));
        p.add(text("wizard.step3.done"));

        // 初期：話者を読み込んで選択反映（非同期）
        refreshVoiceVoxSpeakersAsync(() -> selectSpeakerId(curSpeaker));

        return p;
    }

    private void chooseVoiceVoxExe() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("VOICEVOX (*.exe)", "exe"));
        fc.setDialogTitle(UiText.t("wizard.step3.pickExe"));
        if (!vvExeField.getText().isBlank()) {
            try {
                File cur = new File(vvExeField.getText().trim());
                if (cur.exists()) {
                    fc.setCurrentDirectory(cur.getParentFile());
                    fc.setSelectedFile(cur);
                }
            } catch (Exception ignore) {}
        }
        int r = fc.showOpenDialog(this);
        if (r == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            if (f != null) vvExeField.setText(f.getAbsolutePath());
        }
    }

    private void launchVoiceVox() {
        String exe = vvExeField.getText().trim();
        if (exe.isEmpty()) {
            vvStatusLabel.setText(UiText.t("wizard.step3.errNoExe"));
            return;
        }
        File f = new File(exe);
        if (!f.exists()) {
            vvStatusLabel.setText(UiText.t("wizard.step3.errExeNotFound"));
            return;
        }

        vvStatusLabel.setText(UiText.t("wizard.step3.launching"));

        new Thread(() -> {
            try {
                // start "" /min "VOICEVOX.exe"
                new ProcessBuilder("cmd", "/c", "start", "", "/min", exe)
                        .directory(f.getParentFile())
                        .start();
                SwingUtilities.invokeLater(() -> vvStatusLabel.setText(UiText.t("wizard.step3.launched")));

                // 起動後、少し待って話者自動再読込
                Thread.sleep(1200);
                refreshVoiceVoxSpeakersAsync();
            } catch (Exception ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() -> vvStatusLabel.setText(UiText.t("wizard.step3.launchFailed")));
            }
        }, "WizardVVLaunch").start();
    }

    private record VvStyle(int id, String label) {
        @Override public String toString() { return label; }
    }

    private void refreshVoiceVoxSpeakersAsync() {
        refreshVoiceVoxSpeakersAsync(null);
    }

    private void refreshVoiceVoxSpeakersAsync(Runnable after) {
        String api = (vvApiField != null) ? vvApiField.getText().trim() : Config.getString("voicevox.api", "http://127.0.0.1:50021");
        if (api.isEmpty()) api = "http://127.0.0.1:50021";

        final String apiBase = api;

        vvStatusLabel.setText(UiText.t("wizard.step3.loadingSpeakers"));

        new Thread(() -> {
            List<VvStyle> list = fetchVoiceVoxSpeakers(apiBase);

            SwingUtilities.invokeLater(() -> {
                vvSpeakerCombo.removeAllItems();
                if (list.isEmpty()) {
                    vvSpeakerCombo.addItem(new VvStyle(-1, UiText.t("wizard.step3.noSpeakers")));
                    vvStatusLabel.setText(UiText.t("wizard.step3.notConnected"));
                } else {
                    for (VvStyle s : list) vvSpeakerCombo.addItem(s);
                    vvStatusLabel.setText(UiText.t("wizard.step3.loaded") + " (" + list.size() + ")");
                }
                if (after != null) after.run();
            });
        }, "WizardVVSpeakersLoad").start();
    }

    private List<VvStyle> fetchVoiceVoxSpeakers(String base) {
        List<VvStyle> list = new ArrayList<>();
        try {
            URL url = new URL(base + "/speakers");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(1200);
            conn.connect();
            if (conn.getResponseCode() != 200) return list;

            String json = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.size(); i++) {
                JSONObject sp = arr.getJSONObject(i);
                String speakerName = sp.getString("name");
                JSONArray styles = sp.getJSONArray("styles");
                for (int j = 0; j < styles.size(); j++) {
                    JSONObject st = styles.getJSONObject(j);
                    int id = st.getInt("id");
                    String styleName = st.getString("name");
                    list.add(new VvStyle(id, speakerName + " (" + styleName + ")"));
                }
            }
        } catch (Exception ignore) {
            // 失敗は普通に起きる（起動前など）
        }
        return list;
    }

    private void selectSpeakerId(String idStr) {
        if (idStr == null || idStr.isBlank()) return;
        int id;
        try { id = Integer.parseInt(idStr.trim()); } catch (Exception e) { return; }

        ComboBoxModel<VvStyle> m = vvSpeakerCombo.getModel();
        for (int i = 0; i < m.getSize(); i++) {
            VvStyle s = m.getElementAt(i);
            if (s != null && s.id == id) {
                vvSpeakerCombo.setSelectedItem(s);
                prefs.put("tts.voice", String.valueOf(s.id));
                return;
            }
        }
    }

    // ★ 完了押下時に _outtts.txt と prefs に保存
    private void saveVoiceVoxSettings() {
        String exe = (vvExeField != null) ? vvExeField.getText().trim() : "";
        String api = (vvApiField != null) ? vvApiField.getText().trim() : "";

        if (!api.isEmpty()) {
            writeOutttsKey("voicevox.api", api, false);
        } else {
            // 空ならデフォルト
            writeOutttsKey("voicevox.api", "http://127.0.0.1:50021", false);
        }

        if (!exe.isEmpty()) {
            writeOutttsKey("voicevox.exe", exe, true); // exeはクオート推奨
        }

        Object sel = (vvSpeakerCombo != null) ? vvSpeakerCombo.getSelectedItem() : null;
        if (sel instanceof VvStyle s && s.id >= 0) {
            prefs.put("tts.voice", String.valueOf(s.id));
            writeOutttsKey("voicevox.speaker", String.valueOf(s.id), false);
        }

        try { prefs.sync(); } catch (Exception ignore) {}

        // Config はキャッシュなので再読込
        Config.reload();
        Config.mirrorAllToCloud();
    }

    // ===== _outtts.txt 安全更新（設定領域だけ）=====
    private static final String OUTTTS_MARKER = "↑Settings↓Logs below";

    private void writeOutttsKey(String key, String value, boolean quote) {
        try {
            Path f = Paths.get(System.getProperty("user.dir"), "_outtts.txt");
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

            String formatted = quote ? ("\"" + value.replace("\"", "") + "\"") : value;

            boolean replaced = false;
            for (int i = 0; i < settings.size(); i++) {
                String raw = settings.get(i);
                String t = raw.trim();
                if (t.startsWith("#") || !t.contains("=")) continue;
                int eq = t.indexOf('=');
                if (eq <= 0) continue;
                String k = t.substring(0, eq).trim();
                if (k.equals(key)) {
                    settings.set(i, key + "=" + formatted);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                // 末尾に追記（空行を跨いで入れたければここ調整）
                settings.add(key + "=" + formatted);
            }

            List<String> out = new ArrayList<>(settings);
            if (markerIndex >= 0) {
                out.addAll(lines.subList(markerIndex, lines.size()));
            }

            Files.write(f, out, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "Failed to save _outtts.txt:\n" + ex.getMessage());
        }
    }


    /* =========================
       Step 4 (XTTS)
     ========================= */
    private JPanel stepXtts() {
        JPanel p = basePanel();

        p.add(title("wizard.step4.title"));
        p.add(text("wizard.step4.desc"));

        JLabel status = new JLabel(" ");
        status.setForeground(Color.DARK_GRAY);

        JButton checkBtn = new JButton(UiText.t("wizard.step4.check"));
        JButton openBtn  = new JButton(UiText.t("wizard.step4.openOuttts"));
        JButton learnBtn = new JButton(UiText.t("wizard.step4.learnMore"));

        checkBtn.addActionListener(e -> {
            boolean ok = isXttsApiConfigured();
            status.setText(ok
                    ? UiText.t("wizard.step4.ok")
                    : UiText.t("wizard.step4.needConfig"));
        });

        openBtn.addActionListener(e -> openOutTtsFile());
        learnBtn.addActionListener(e -> openBrowser("https://github.com/coqui-ai/TTS"));

        p.add(checkBtn);
        p.add(status);
        p.add(Box.createVerticalStrut(8));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        row.add(openBtn);
        row.add(learnBtn);
        p.add(row);

        return p;
    }
    private boolean isXttsApiConfigured() {
        String api = Config.getString("xtts.api", "");
        String health = Config.getString("xtts.apichk", "");
        return api != null && !api.isBlank() && health != null && !health.isBlank();
    }
    private void openOutTtsFile() {
        try {
            Path p = Paths.get("_outtts.txt"); // 必要ならパスは実環境に合わせて
            if (!Files.exists(p)) {
                JOptionPane.showMessageDialog(this, "_outtts.txt not found:\n" + p.toAbsolutePath());
                return;
            }
            Desktop.getDesktop().open(p.toFile());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Failed to open _outtts.txt:\n" + ex.getMessage());
            ex.printStackTrace();
        }
    }
    private void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                JOptionPane.showMessageDialog(this, "Open this link manually:\n" + url);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Failed to open browser:\n" + ex.getMessage());
            ex.printStackTrace();
        }
    }

    /* =========================
       Step 5 (Voiceger)
     ========================= */
    // Voiceger step UI
    private JTextField vgRootField;
    private JTextArea vgStatusArea;
    private JProgressBar vgProgress;
    private JButton vgBrowseBtn;
    private JButton vgOpenBtn;
    private JButton vgInstallBtn;
    private JButton vgStartApiBtn;
    private JButton vgStopApiBtn;

    private volatile Process voicegerApiProc;
    private final Object voicegerApiLock = new Object();
    private java.util.concurrent.ScheduledExecutorService vgTailExec;
    private volatile boolean vgTailRunning = false;
    private JLabel vgStatusLabel;

    private static final String VOICEGER_BASE = "http://127.0.0.1:8501";
    private static final String VOICEGER_HEALTH = VOICEGER_BASE + "/health"; // FastAPIならここが返る
    private static final String VOICEGER_OFFICIAL_URL = "https://zunko.jp/voiceger.php";
    private static final String VOICEGER_GITHUB_URL   = "https://github.com/zunzun999/voiceger_v2";
    private static final String PYTHON_WINDOWS_URL    = "https://www.python.org/downloads/windows/";

    private JPanel stepVoiceger() {
        JPanel p = basePanel();

        // --- 説明 ---
        JTextArea desc = new JTextArea(UiText.t("ui.voiceger.desc"));
        desc.setEditable(false);
        desc.setOpaque(false);
        desc.setLineWrap(true);
        desc.setWrapStyleWord(true);
        desc.setBorder(BorderFactory.createEmptyBorder(0, 0, 14, 0));
        p.add(desc);

        // --- フォルダ行（横いっぱい確保）---
        JPanel dirRow = new JPanel(new GridBagLayout());
        dirRow.setOpaque(false);

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 2, 2, 2);
        gc.gridy = 0;

        gc.gridx = 0;
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        dirRow.add(new JLabel(UiText.t("ui.voiceger.installFolderLabel")), gc);

        vgRootField = new JTextField(prefs.get("voiceger.install_dir", ""), 10);

        // ★潰れ対策：高さを固定気味に
        Dimension tf = new Dimension(10, vgRootField.getPreferredSize().height);
        vgRootField.setPreferredSize(tf);
        vgRootField.setMinimumSize(new Dimension(120, tf.height));

        gc.gridx = 1;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        dirRow.add(vgRootField, gc);

        vgBrowseBtn = new JButton(UiText.t("ui.voiceger.browse"));
        vgBrowseBtn.addActionListener(e -> pickVoicegerInstallDir());
        gc.gridx = 2;
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        dirRow.add(vgBrowseBtn, gc);

        vgOpenBtn = new JButton(UiText.t("ui.voiceger.open"));
        vgOpenBtn.addActionListener(e -> openVoicegerInstallDir());
        gc.gridx = 3;
        dirRow.add(vgOpenBtn, gc);

        // BoxLayout配下で横幅MAXにする
        dirRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, dirRow.getPreferredSize().height));
        p.add(dirRow);

        p.add(Box.createVerticalStrut(10));

        // --- ボタン（確実に2段で固定）---
        JPanel btnGrid = new JPanel(new GridBagLayout());
        btnGrid.setOpaque(false);

        GridBagConstraints bc = new GridBagConstraints();
        bc.insets = new Insets(2, 2, 2, 2);
        bc.anchor = GridBagConstraints.WEST;
        bc.fill = GridBagConstraints.NONE;
        bc.weightx = 0;

        bc.gridy = 0;

        bc.gridx = 0;
        JButton officialDlBtn = new JButton(UiText.t("ui.voiceger.link.official"));
        officialDlBtn.addActionListener(e -> openBrowser(VOICEGER_OFFICIAL_URL));
        btnGrid.add(officialDlBtn, bc);

        bc.gridx = 1;
        JButton githubBtn = new JButton(UiText.t("ui.voiceger.link.github"));
        githubBtn.addActionListener(e -> openBrowser(VOICEGER_GITHUB_URL));
        btnGrid.add(githubBtn, bc);

        bc.gridx = 2;
        JButton pythonBtn = new JButton(UiText.t("ui.voiceger.link.python"));
        pythonBtn.addActionListener(e -> openBrowser(PYTHON_WINDOWS_URL));
        btnGrid.add(pythonBtn, bc);

        bc.gridx = 3;
        vgInstallBtn = new JButton(UiText.t("ui.voiceger.btn.setup"));
        vgInstallBtn.addActionListener(e -> runInstallVoicegerAsync());
        btnGrid.add(vgInstallBtn, bc);
        bc.gridy = 1;

        bc.gridx = 0;
        vgStartApiBtn = new JButton(UiText.t("ui.voiceger.btn.start"));
        vgStartApiBtn.addActionListener(e -> startVoicegerApiAsync());
        btnGrid.add(vgStartApiBtn, bc);

        bc.gridx = 1;
        vgStopApiBtn = new JButton(UiText.t("ui.voiceger.btn.stop"));
        vgStopApiBtn.addActionListener(e -> stopVoicegerApi());
        btnGrid.add(vgStopApiBtn, bc);
        bc.gridx = 2;
        bc.weightx = 1.0;
        bc.fill = GridBagConstraints.HORIZONTAL;
        btnGrid.add(Box.createHorizontalGlue(), bc);
        btnGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, btnGrid.getPreferredSize().height));
        p.add(btnGrid);

        p.add(Box.createVerticalStrut(10));

        // --- インジケーター（最初は非表示）---
        vgProgress = new JProgressBar();
        vgProgress.setIndeterminate(true);
        vgProgress.setVisible(false);
        vgProgress.setMaximumSize(new Dimension(Integer.MAX_VALUE, vgProgress.getPreferredSize().height));
        p.add(vgProgress);

        p.add(Box.createVerticalStrut(8));

        // --- ログ（6行＋スクロール）---
        vgStatusArea = new JTextArea(6, 60);
        vgStatusArea.setEditable(false);
        vgStatusArea.setLineWrap(true);
        vgStatusArea.setWrapStyleWord(true);

        JScrollPane logSp = new JScrollPane(vgStatusArea);
        logSp.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        logSp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        // BoxLayout配下で横に伸びるように
        Dimension spPref = logSp.getPreferredSize();
        logSp.setMaximumSize(new Dimension(Integer.MAX_VALUE, spPref.height));

        p.add(logSp);

        return p;
    }
    private void setVoicegerBusy(boolean busy, String status) {
        SwingUtilities.invokeLater(() -> {
            if (vgProgress != null) vgProgress.setVisible(busy);
            if (vgStatusArea != null && status != null) {
                vgStatusArea.setText(status);
                vgStatusArea.setCaretPosition(0);
            }
            if (vgBrowseBtn != null) vgBrowseBtn.setEnabled(!busy);
            if (vgOpenBtn != null) vgOpenBtn.setEnabled(!busy);
            if (vgInstallBtn != null) vgInstallBtn.setEnabled(!busy);
            if (vgStartApiBtn != null) vgStartApiBtn.setEnabled(!busy);
            if (vgStopApiBtn != null) vgStopApiBtn.setEnabled(true); // 停止は常に押せても良い

            revalidate();
            repaint();
        });
    }
    private void pickVoicegerInstallDir() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle(UiText.t("ui.voiceger.pickDirTitle"));

        String cur = vgRootField != null ? vgRootField.getText().trim() : "";
        if (!cur.isBlank()) {
            File f = new File(cur);
            if (f.exists()) fc.setCurrentDirectory(f);
        }

        int r = fc.showOpenDialog(this);
        if (r != JFileChooser.APPROVE_OPTION) return;

        File dir = fc.getSelectedFile();
        if (dir == null) return;

        vgRootField.setText(dir.getAbsolutePath());
        prefs.put("voiceger.install_dir", dir.getAbsolutePath());
        try { prefs.sync(); } catch (Exception ignore) {}

        // voiceger.dir も同じ場所に寄せる（以後の処理が楽）
        prefs.put("voiceger.dir", dir.getAbsolutePath());
        try { prefs.sync(); } catch (Exception ignore) {}

        refreshVoicegerStatus();
    }
    private void openVoicegerInstallDir() {
        try {
            Path dir = getVoicegerInstallDir();
            if (dir == null) return;
            Desktop.getDesktop().open(dir.toFile());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, UiText.t("ui.voiceger.err.openFolder") + "\n" + ex.getMessage());
        }
    }
    private Path getVoicegerInstallDir() {
        String s = (vgRootField != null) ? vgRootField.getText().trim() : "";
        if (s.isBlank()) s = prefs.get("voiceger.install_dir", "").trim();
        if (s.isBlank()) return null;
        return Paths.get(s).toAbsolutePath();
    }
    private void refreshVoicegerStatus() {
        try {
            Path root = getVoicegerRoot();
            if (root == null) {
                vgStatusArea.setText("Root not set.");
                return;
            }
            Path voicegerDir = resolveVoicegerDir(root);
            Path apiPy = resolveVoicegerApiPy(voicegerDir);
            String s = "voicegerDir=" + voicegerDir +
                    " / api=" + (apiPy != null && Files.exists(apiPy) ? "OK" : "MISSING") +
                    " / API up=" + (httpOk("http://127.0.0.1:8501/health") ? "YES" : "NO");
            vgStatusArea.setText(s);
        } catch (Exception e) {
            vgStatusArea.setText("Error: " + e.getMessage());
        }
    }
    private Path getVoicegerRoot() {
        String s = (vgRootField != null) ? vgRootField.getText().trim() : "";
        if (s.isBlank()) s = prefs.get("voiceger.root", "").trim();
        if (s.isBlank()) return null;
        return Paths.get(s).toAbsolutePath();
    }
    private void startTailInstallLog(Path logFile) {
        stopTailInstallLog();
        vgTailExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "voiceger-install-tail");
            t.setDaemon(true);
            return t;
        });
        vgTailRunning = true;

        vgTailExec.scheduleAtFixedRate(() -> {
            if (!vgTailRunning) return;
            try {
                String tail = readTailText(logFile, 80); // 80行くらい
                SwingUtilities.invokeLater(() -> {
                    if (vgStatusArea != null) {
                        vgStatusArea.setText(tail.isEmpty() ? "(log empty yet)" : tail);
                        vgStatusArea.setCaretPosition(vgStatusArea.getDocument().getLength());
                    }
                });
            } catch (Exception ignore) {}
        }, 0, 500, java.util.concurrent.TimeUnit.MILLISECONDS); // 0.5秒更新
    }

    private void stopTailInstallLog() {
        vgTailRunning = false;
        if (vgTailExec != null) {
            try { vgTailExec.shutdownNow(); } catch (Exception ignore) {}
            vgTailExec = null;
        }
    }

    /** ファイル末尾n行を読む（大きくても軽めに） */
    private String readTailText(Path file, int lines) throws Exception {
        if (file == null || !java.nio.file.Files.exists(file)) return "";
        java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>(lines + 5);
        try (java.io.BufferedReader br = java.nio.file.Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            String s;
            while ((s = br.readLine()) != null) {
                q.addLast(s);
                while (q.size() > lines) q.removeFirst();
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String s : q) sb.append(s).append("\n");
        return sb.toString();
    }


    /** Root直下に voiceger_v2 が居ればそれを採用。無ければRoot自体をVoicegerDir扱い */
    private Path resolveVoicegerDir(Path root) {
//        Path v2 = root.resolve("voiceger_v2");
//        Path v2 = root.resolve("");
//        if (Files.isDirectory(v2)) return v2;
        return root;
    }

    private Path resolveVoicegerApiPy(Path voicegerDir) {
        return voicegerDir.resolve("_internal").resolve("voiceger_api.py");
    }

    /** InstallVoiceger.ps1 を appフォルダから呼ぶ（既に君が作ったps1/batがある想定） */
    private void runInstallVoicegerAsync() {
        new Thread(() -> {

            setVoicegerBusy(true, UiText.t("ui.voiceger.busy.installing"));

            try {
                Path installDir = getVoicegerInstallDir();
                if (installDir == null) {
                    SwingUtilities.invokeLater(() -> vgStatusArea.setText(UiText.t("ui.voiceger.status.installDirNotSet")));
                    return;
                }
                Files.createDirectories(installDir);

                // ★MobMateWhispTalk.exe と同じ場所にある前提で探す
                Path appDir = getAppDir();
                Path srcBat = appDir.resolve("InstallVoiceger.bat");
                Path srcPs1 = appDir.resolve("InstallVoiceger.ps1");

                if (!Files.exists(srcBat) || !Files.exists(srcPs1)) {
                    SwingUtilities.invokeLater(() -> vgStatusArea.setText(
                            UiText.t("ui.voiceger.status.installerNotFound") + " " + appDir));
                    return;
                }

                // ★選んだ voiceger インストール先へコピー
                Path dstBat = installDir.resolve("InstallVoiceger.bat");
                Path dstPs1 = installDir.resolve("InstallVoiceger.ps1");
                Files.copy(srcBat, dstBat, StandardCopyOption.REPLACE_EXISTING);
                Files.copy(srcPs1, dstPs1, StandardCopyOption.REPLACE_EXISTING);

                SwingUtilities.invokeLater(() -> vgStatusArea.setText(UiText.t("ui.voiceger.status.installingSeeLog")));

                Path log = installDir.resolve("InstallVoiceger.log");
                startTailInstallLog(log);
                ProcessBuilder pb = new ProcessBuilder("cmd", "/c", dstBat.toAbsolutePath().toString());
                pb.environment().put("PYTHONPATH", installDir.resolve("_internal").toString());
                pb.directory(installDir.toFile());
                pb.redirectErrorStream(true);
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));

                Process p = pb.start();
                int code = p.waitFor();
                stopTailInstallLog();

                SwingUtilities.invokeLater(() -> {
                    vgStatusArea.setText(code == 0
                            ? UiText.t("ui.voiceger.status.setupDone")
                            : (UiText.t("ui.voiceger.status.setupFailed") + " code=" + code));

                    // MobMate側の参照先もここに固定
                    prefs.put("voiceger.install_dir", installDir.toString());
                    prefs.put("voiceger.dir", installDir.toString());
                    prefs.putBoolean("voiceger.enabled", true);
                    try { prefs.sync(); } catch (Exception ignore) {}

                    refreshVoicegerStatus();
                });

            } catch (Exception ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() -> vgStatusArea.setText(UiText.t("ui.voiceger.status.setupError") + " " + ex.getMessage()));
            } finally {
                stopTailInstallLog();
                // 成否メッセージは既存の setText があるなら、それを残してOK
                setVoicegerBusy(false, null);
            }
        }, "WizardVoicegerInstall").start();
    }
    private Path getAppDir() {
        try {
            // launch4j / exe / jar の場所を拾う
            File f = new File(MobMateWhisp.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            // jarなら親、ディレクトリならそこ
            return f.isFile() ? f.getParentFile().toPath() : f.toPath();
        } catch (Exception e) {
            // だめなら user.dir
            return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        }
    }
    private void startVoicegerApiAsync() {
        setVoicegerBusy(true, UiText.t("ui.voiceger.busy.startingApi"));

        new Thread(() -> {
            try {
                Path root = getVoicegerRoot();
                if (root == null) {
                    SwingUtilities.invokeLater(() -> vgStatusArea.setText(UiText.t("ui.voiceger.status.rootNotSet")));
                    return;
                }
                Path voicegerDir = resolveVoicegerDir(root);
                prefs.put("voiceger.dir", voicegerDir.toString());
                prefs.putBoolean("voiceger.enabled", true);
                try { prefs.sync(); } catch (Exception ignore) {}

                // 既に立ってるならOK
                if (httpOk(VOICEGER_HEALTH)) {
                    SwingUtilities.invokeLater(() -> vgStatusArea.setText(UiText.t("ui.voiceger.status.apiAlreadyUp") + " " + VOICEGER_BASE));
                    return;
                }

                Path apiPy = resolveVoicegerApiPy(voicegerDir);
                if (!Files.exists(apiPy)) {
                    SwingUtilities.invokeLater(() -> vgStatusArea.setText(UiText.t("ui.voiceger.status.apiPyNotFound") + " " + apiPy));
                    return;
                }

                Path py = detectEmbeddedPython(voicegerDir);
                if (py == null) {
                    SwingUtilities.invokeLater(() -> vgStatusArea.setText(UiText.t("ui.voiceger.status.pythonNotFound")));
                    return;
                }

                Path log = voicegerDir.resolve("voiceger_api.log");
                SwingUtilities.invokeLater(() -> vgStatusArea.setText(UiText.t("ui.voiceger.status.apiStartingSeeLog")));

                synchronized (voicegerApiLock) {
                    if (voicegerApiProc != null && voicegerApiProc.isAlive()) {
                        try { voicegerApiProc.destroy(); } catch (Exception ignore) {}
                    }

                    ProcessBuilder pb = new ProcessBuilder(
                            py.toAbsolutePath().toString(),
                            apiPy.toAbsolutePath().toString()
                    );
                    Map<String, String> env = pb.environment();
                    env.putIfAbsent("NUMBA_DISABLE_CACHING", "1");
                    env.putIfAbsent("NUMBA_DISABLE_JIT", "1");
                    // ★追加：Voiceger API ログ抑制（デフォルトOFF）
                    boolean vgDebug = prefs.getBoolean("voiceger.debug", false);
                    env.put("VOICEGER_DEBUG", vgDebug ? "1" : "0");

                    pb.directory(voicegerDir.toFile());
                    pb.redirectErrorStream(true);
                    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
                    voicegerApiProc = pb.start();
                    long pid = voicegerApiProc.pid();
                    SwingUtilities.invokeLater(() -> vgStatusArea.append("\n" + UiText.t("ui.voiceger.status.apiPid") + "=" + pid + "\n"));
                }

                long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < end) {
                    if (httpOk(VOICEGER_HEALTH)) {
                        SwingUtilities.invokeLater(() -> vgStatusArea.setText(
                                UiText.t("ui.voiceger.status.apiUp") + " " + VOICEGER_BASE + "\n" +
                                        UiText.t("ui.voiceger.status.openHealth") + " " + VOICEGER_BASE + "/health"
                        ));
                        return;
                    }
                    Thread.sleep(300);
                }

                SwingUtilities.invokeLater(() -> vgStatusArea.setText(UiText.t("ui.voiceger.status.apiTimeout")));

            } catch (Exception ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() -> vgStatusArea.setText(UiText.t("ui.voiceger.status.apiStartError") + " " + ex.getMessage()));
            } finally {
                setVoicegerBusy(false, null); // ここで表示を上書きしない
            }
        }, "WizardVoicegerApiStart").start();
    }

    private void stopVoicegerApi() {
        new Thread(() -> {
            setVoicegerBusy(true, UiText.t("ui.voiceger.busy.stoppingApi"));

            Process p;
            synchronized (voicegerApiLock) {
                p = voicegerApiProc;
            }

            try {
                if (p == null) {
                    SwingUtilities.invokeLater(() -> {
                        if (vgStatusArea != null) vgStatusArea.setText(UiText.t("ui.voiceger.status.apiNotRunning"));
                    });
                    return;
                }

                long pid = -1;
                try { pid = p.pid(); } catch (Exception ignore) {}

                // 1) まずは優しく（ツリーごと）
                try { destroyProcessTree(p, false); } catch (Exception ignore) {}

                // 2) /health が落ちる or プロセス終了を待つ（最大3秒）
                long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                while (System.nanoTime() < end) {
                    boolean alive = false;
                    try { alive = p.isAlive(); } catch (Exception ignore) {}
                    boolean up = false;
                    try { up = httpOk("http://127.0.0.1:8501/health"); } catch (Exception ignore) {}

                    if (!alive && !up) break;
                    try { Thread.sleep(120); } catch (InterruptedException ignore) {}
                }

                // 3) まだ残ってたら強制（ツリーごと）
                boolean stillAlive = false;
                try { stillAlive = p.isAlive(); } catch (Exception ignore) {}
                boolean stillUp = false;
                try { stillUp = httpOk("http://127.0.0.1:8501/health"); } catch (Exception ignore) {}

                if (stillAlive || stillUp) {
                    try { destroyProcessTree(p, true); } catch (Exception ignore) {}
                    try { p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignore) {}
                }

                // 4) それでも残るケース向け：taskkillフォールバック（ツリーごと）
                //   ※ pid が取れたときだけ
                boolean finalUp = false;
                try { finalUp = httpOk("http://127.0.0.1:8501/health"); } catch (Exception ignore) {}
                if (finalUp && pid > 0) {
                    try {
                        new ProcessBuilder("cmd", "/c", "taskkill", "/PID", String.valueOf(pid), "/T", "/F")
                                .redirectErrorStream(true)
                                .start()
                                .waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception ignore) {}
                }

                synchronized (voicegerApiLock) {
                    voicegerApiProc = null;
                }

                SwingUtilities.invokeLater(() -> {
                    if (vgStatusArea != null) vgStatusArea.setText(UiText.t("ui.voiceger.status.apiStopped"));
                });

            } finally {
                setVoicegerBusy(false, null);
                // 最終手段：8501番を掴んでるPIDを落とす（孤児プロセス対策）
                killListeningPort(8501);
            }
        }, "WizardVoicegerApiStop").start();
    }
    /** Windowsで子プロセスが残りやすいので、プロセスツリーを殺す */
    private static void destroyProcessTree(Process p, boolean force) {
        ProcessHandle ph = p.toHandle();

        // 子→親の順で
        ph.descendants().forEach(h -> {
            try {
                if (force) h.destroyForcibly();
                else h.destroy();
            } catch (Exception ignore) {}
        });

        try {
            if (force) ph.destroyForcibly();
            else ph.destroy();
        } catch (Exception ignore) {}

        // ついでに Process API 側も
        try {
            if (force) p.destroyForcibly();
            else p.destroy();
        } catch (Exception ignore) {}
    }
    private static void killListeningPort(int port) {
        try {
            // netstatで LISTENING の PID を拾う
            Process p = new ProcessBuilder("cmd", "/c",
                    "netstat -ano -p tcp | findstr :" + port + " | findstr LISTENING")
                    .redirectErrorStream(true)
                    .start();

            String out;
            try (java.io.InputStream is = p.getInputStream()) {
                out = new String(is.readAllBytes(), java.nio.charset.Charset.defaultCharset());
            }
            try { p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignore) {}

            // 例: TCP  127.0.0.1:8501  0.0.0.0:0  LISTENING  30044
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\sLISTENING\\s+(\\d+)\\s*$", java.util.regex.Pattern.MULTILINE)
                    .matcher(out);

            java.util.HashSet<String> pids = new java.util.HashSet<>();
            while (m.find()) pids.add(m.group(1));

            for (String pid : pids) {
                new ProcessBuilder("cmd", "/c", "taskkill", "/PID", pid, "/T", "/F")
                        .redirectErrorStream(true)
                        .start()
                        .waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception ignore) {
            // 失敗してもStopの流れ自体は止めない
        }
    }
    private Path detectEmbeddedPython(Path voicegerDir) {
        // まずは定番
        // voicegerDir = VOICEGER_DIR を想定
        Path p1 = voicegerDir.resolve("_internal").resolve("py").resolve("python.exe");
        if (Files.exists(p1)) return p1;

        // 旧/別配置の保険（あれば）
        Path p2 = voicegerDir.resolve("voiceger_v2").resolve("_internal").resolve("py").resolve("python.exe");
        if (Files.exists(p2)) return p2;

        return null;
    }


    private boolean httpOk(String urlStr) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(urlStr).openConnection();
            con.setConnectTimeout(400);
            con.setReadTimeout(400);
            con.setRequestMethod("GET");
            int code = con.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception ignore) {
            return false;
        } finally {
            if (con != null) try { con.disconnect(); } catch (Exception ignore) {}
        }
    }



    /* =========================
       Bottom bar
     ========================= */

    private JPanel bottomBar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton back = new JButton(UiText.t("wizard.back"));
        JButton next = new JButton(UiText.t("wizard.next"));

        final int LAST_STEP = STEP_COUNT - 1;

        back.setEnabled(false);

        back.addActionListener(e -> {
            int prev = step;
            step--;
            if (step < 0) step = 0;

            onStepExit(prev);
            cardLayout.previous(cardPanel);
            onStepEnter(step);

            next.setText(step == LAST_STEP ? UiText.t("wizard.finish") : UiText.t("wizard.next"));
            back.setEnabled(step > 0);
        });

        next.addActionListener(e -> {
            int prev = step;
            step++;

            if (step > LAST_STEP) {
                dispose();
                return;
            }

            onStepExit(prev);
            cardLayout.next(cardPanel);
            onStepEnter(step);

            next.setText(step == LAST_STEP ? UiText.t("wizard.finish") : UiText.t("wizard.next"));
            back.setEnabled(step > 0);
        });

        p.add(back);
        p.add(next);
        return p;
    }


    /* =========================
       UI helpers
     ========================= */

    private JPanel basePanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        return p;
    }

    private JLabel title(String key) {
        JLabel l = new JLabel(UiText.t(key));
        l.setFont(l.getFont().deriveFont(Font.BOLD, 16f));
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        return l;
    }

    private JTextArea text(String key) {
        JTextArea t = new JTextArea(UiText.t(key));
        t.setEditable(false);
        t.setOpaque(false);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        return t;
    }
}
