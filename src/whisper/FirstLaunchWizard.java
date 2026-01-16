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
import java.util.*;
import java.util.List;
import java.util.prefs.BackingStoreException;

import static whisper.MobMateWhisp.prefs;

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
    private static final int STEP_COUNT = 3;

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


    public boolean isCompleted() {
        return completed;
    }

    public FirstLaunchWizard(Frame owner, MobMateWhisp mmw) throws FileNotFoundException, NativeHookException {
        super(owner, UiText.t("wizard.title"), true);
        this.mobmatewhisp = mmw;

        setSize(560, 380);
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        cardPanel.add(stepInputDevice(), "step1");
        cardPanel.add(stepOutputDevice(), "step2");
        cardPanel.add(stepVoiceVox(), "step3");
        cardPanel.add(stepXtts(), "step4");

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
       Bottom bar
     ========================= */

    private JPanel bottomBar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton back = new JButton(UiText.t("wizard.back"));
        JButton next = new JButton(UiText.t("wizard.next"));

        final int LAST_STEP = 3; // 0..3（= 4ページ）

        back.setEnabled(false);

        back.addActionListener(e -> {
            step--;
            cardLayout.previous(cardPanel);

            next.setText(step == LAST_STEP ? UiText.t("wizard.finish") : UiText.t("wizard.next"));
            back.setEnabled(step > 0);
        });

        next.addActionListener(e -> {
            step++;

            if (step > LAST_STEP) {
                dispose();
                return;
            }

            cardLayout.next(cardPanel);

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
