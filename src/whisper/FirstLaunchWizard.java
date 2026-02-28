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
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Clipboard;

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
    // ===== Control Center (tabbed) mode =====
    private boolean controlCenterMode = false; // wizardではなく設定画面として使う
    private JTabbedPane controlTabs;
    private int lastStepForTabs = STEP_INPUT; // onStepExit/onStepEnter 用

    private static final int STEP_ENGINE   = 0;  // ★v1.5.0: 認識エンジン選択
    private static final int STEP_INPUT    = 1;
    private static final int STEP_OUTPUT   = 2;
    private static final int STEP_VOICEVOX = 3;
    private static final int STEP_XTTS     = 4;
    private static final int STEP_VOICEGER = 5;
    private static final int STEP_COUNT    = 6;

    private int step = STEP_ENGINE;

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
        // 初回だけは「ウィザード(Card)」で最短成功体験、2回目以降は「Control Center(Tab)」
        boolean completed = prefs.getBoolean("wizard.completed", false);
        boolean never = prefs.getBoolean("wizard.never", false);
        if (completed || never) controlCenterMode = true;

        setSize(760, 760);
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        if (controlCenterMode) {
            controlTabs = buildControlCenterTabs();
            add(controlTabs, BorderLayout.CENTER);
            add(controlCenterBottomBar(), BorderLayout.SOUTH);
        } else {
            // ===== legacy wizard (CardLayout) =====
            cardPanel.add(stepRecogEngine(), "step0");  // ★v1.5.0
            cardPanel.add(stepInputDevice(), "step1");
            cardPanel.add(stepOutputDevice(), "step2");
            cardPanel.add(stepVoiceVox(), "step3");
            cardPanel.add(stepXtts(), "step4");
            cardPanel.add(stepVoiceger(), "step5");

            add(cardPanel, BorderLayout.CENTER);
            add(bottomBar(), BorderLayout.SOUTH);

            // 初期 step enter
            onStepEnter(step);
        }

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // ×で閉じた扱い
//                completed = false;
//                prefs.putBoolean("wizard.completed", false);

                // リソース解放（念のため）
                try {
                    if (controlCenterMode) {
                        if (lastStepForTabs >= 0) onStepExit(lastStepForTabs);
                    } else {
                        onStepExit(step);
                    }
                } catch (Exception ignore) {}

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
            // ★ここを追加：VOICEVOXのexe/api/speakerを _outtts.txt に保存
            saveVoiceVoxSettings();
        }
        if (s == STEP_VOICEGER) {
            // 何もしなくてOK（止めたいなら stopVoicegerApi(); を呼ぶ）
        }
    }


    /* =========================
       Step 0: Recognition Engine (v1.5.0)
     ========================= */

    private JPanel stepRecogEngine() {
        JPanel p = basePanel();

        p.add(title("wizard.engine.title"));
        p.add(text("wizard.engine.desc"));

        // ════════ エンジン選択 ════════
        String currentEngine = prefs.get("recog.engine", "whisper");
        boolean hasDlc = SteamHelper.hasVulkanDlc();

        JRadioButton rbMoonshine = new JRadioButton();
        JRadioButton rbWhisper   = new JRadioButton();
        ButtonGroup engineGroup = new ButtonGroup();
        engineGroup.add(rbMoonshine);
        engineGroup.add(rbWhisper);

        // --- Moonshine カード ---
        JPanel moonCard = new JPanel();
        moonCard.setLayout(new BoxLayout(moonCard, BoxLayout.Y_AXIS));
        moonCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(76, 175, 80), 2),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));

        rbMoonshine.setText("Moonshine (CPU) — " + UiText.t("wizard.engine.moon.tag"));
        rbMoonshine.setFont(rbMoonshine.getFont().deriveFont(Font.BOLD, 14f));
        moonCard.add(rbMoonshine);

        JTextArea moonDesc = new JTextArea(UiText.t("wizard.engine.moon.pros"));
        moonDesc.setEditable(false);
        moonDesc.setOpaque(false);
        moonDesc.setLineWrap(true);
        moonDesc.setWrapStyleWord(true);
        moonDesc.setFont(moonDesc.getFont().deriveFont(12f));
        moonDesc.setBorder(BorderFactory.createEmptyBorder(4, 24, 4, 0));
        moonCard.add(moonDesc);

        p.add(moonCard);
        p.add(Box.createVerticalStrut(8));

        // --- Whisper カード ---
        JPanel whisperCard = new JPanel();
        whisperCard.setLayout(new BoxLayout(whisperCard, BoxLayout.Y_AXIS));
        whisperCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(100, 149, 237), 2),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));

        String whisperTag = hasDlc
                ? UiText.t("wizard.engine.whi.tag.dlc")
                : UiText.t("wizard.engine.whi.tag.nodlc");
        rbWhisper.setText("Whisper (GPU) — " + whisperTag);
        rbWhisper.setFont(rbWhisper.getFont().deriveFont(Font.BOLD, 14f));
        if (!hasDlc) {
            rbWhisper.setForeground(Color.GRAY);
        }
        whisperCard.add(rbWhisper);

        JTextArea whisperDesc = new JTextArea(
                hasDlc ? UiText.t("wizard.engine.whi.pros")
                        : UiText.t("wizard.engine.whi.nodlc"));
        whisperDesc.setEditable(false);
        whisperDesc.setOpaque(false);
        whisperDesc.setLineWrap(true);
        whisperDesc.setWrapStyleWord(true);
        whisperDesc.setFont(whisperDesc.getFont().deriveFont(12f));
        whisperDesc.setBorder(BorderFactory.createEmptyBorder(4, 24, 4, 0));
        whisperCard.add(whisperDesc);

        p.add(whisperCard);
        p.add(Box.createVerticalStrut(12));

        // ════════ Moonshine モデル言語選択 ════════
        JPanel modelPanel = new JPanel();
        modelPanel.setLayout(new BoxLayout(modelPanel, BoxLayout.Y_AXIS));
        modelPanel.setBorder(BorderFactory.createTitledBorder(
                UiText.t("wizard.engine.model.title")));

        // exe直下の moonshine_model/ をスキャンして言語フォルダを列挙
        JComboBox<String> langCombo = new JComboBox<>();
        File moonBaseDir = new File(mobmatewhisp.getExeDir(), "moonshine_model");
        File[] langDirs = moonBaseDir.isDirectory()
                ? moonBaseDir.listFiles(File::isDirectory) : null;

        JLabel pathLabel = new JLabel(" ");
        pathLabel.setFont(pathLabel.getFont().deriveFont(11f));
        pathLabel.setForeground(Color.GRAY);

        if (langDirs != null && langDirs.length > 0) {
            Arrays.sort(langDirs, Comparator.comparing(File::getName));
            for (File d : langDirs) {
                langCombo.addItem(d.getName());
            }
            // 既存設定があればそれを選択
            String savedPath = prefs.get("moonshine.model_path", "");
            if (!savedPath.isEmpty()) {
                // パスから言語名を逆引き
                for (int i = 0; i < langCombo.getItemCount(); i++) {
                    if (savedPath.contains(langCombo.getItemAt(i))) {
                        langCombo.setSelectedIndex(i);
                        break;
                    }
                }
            }
            // 選択中のモデルパスを表示
            updateMoonModelPath(langCombo, moonBaseDir, pathLabel);

            langCombo.addActionListener(e -> {
                updateMoonModelPath(langCombo, moonBaseDir, pathLabel);
            });
        } else {
            langCombo.addItem(UiText.t("wizard.engine.model.notfound"));
            langCombo.setEnabled(false);
            pathLabel.setText(UiText.t("wizard.engine.model.notfound.hint"));
        }

        JPanel langRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        langRow.add(new JLabel(UiText.t("wizard.engine.model.lang")));
        langRow.add(langCombo);
        modelPanel.add(langRow);
        modelPanel.add(pathLabel);

        // 「フォルダを開く」ボタン
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton openFolder = new JButton(UiText.t("wizard.engine.model.open"));
        openFolder.addActionListener(e -> {
            try {
                if (moonBaseDir.isDirectory()) {
                    Desktop.getDesktop().open(moonBaseDir);
                } else {
                    // フォルダがなければ作る
                    moonBaseDir.mkdirs();
                    Desktop.getDesktop().open(moonBaseDir);
                }
            } catch (Exception ex) {
                Config.logError("Failed to open moonshine_model dir", ex);
            }
        });
        btnRow.add(openFolder);

        // 「他の言語モデルをダウンロード」リンク
        JButton dlLink = new JButton(UiText.t("wizard.engine.model.download"));
        dlLink.setBorderPainted(false);
        dlLink.setContentAreaFilled(false);
        dlLink.setForeground(new Color(70, 130, 230));
        dlLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        dlLink.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(
                        new URI("https://github.com/usefulsensors/moonshine"));
            } catch (Exception ex) {
                Config.logError("Failed to open Moonshine URL", ex);
            }
        });
        btnRow.add(dlLink);
        modelPanel.add(btnRow);

        // ライセンス表記
        JLabel licenseLabel = new JLabel(
                "Moonshine © Useful Sensors, Inc. — MIT License");
        licenseLabel.setFont(licenseLabel.getFont().deriveFont(10f));
        licenseLabel.setForeground(Color.GRAY);
        licenseLabel.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 0));
        modelPanel.add(licenseLabel);

        p.add(modelPanel);

        // ════════ エンジン選択の反映 ════════
        // 初回自動推薦: DLC無し→Moonshine, DLC有り→Whisper
        if ("moonshine".equalsIgnoreCase(currentEngine)) {
            rbMoonshine.setSelected(true);
        } else if ("whisper".equalsIgnoreCase(currentEngine)) {
            rbWhisper.setSelected(true);
        } else {
            // 未設定時の自動推薦
            if (hasDlc) {
                rbWhisper.setSelected(true);
            } else {
                rbMoonshine.setSelected(true);
            }
        }

        // モデルパネルの表示制御
        modelPanel.setVisible(rbMoonshine.isSelected());

        rbMoonshine.addActionListener(e -> {
            prefs.put("recog.engine", "moonshine");
            try { prefs.flush(); } catch (Exception ignore) {}
            modelPanel.setVisible(true);
            Config.log("[Wizard] Engine → Moonshine");
        });
        rbWhisper.addActionListener(e -> {
            if (!hasDlc) {
                // DLC無しの場合は警告
                int confirm = JOptionPane.showConfirmDialog(this,
                        UiText.t("wizard.engine.whi.nodlc.confirm"),
                        "MobMate", JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (confirm != JOptionPane.YES_OPTION) {
                    rbMoonshine.setSelected(true);
                    return;
                }
            }
            prefs.put("recog.engine", "whisper");
            try { prefs.flush(); } catch (Exception ignore) {}
            modelPanel.setVisible(false);
            Config.log("[Wizard] Engine → Whisper");
        });

        return p;
    }

    /** Moonshineモデルパスを解決してprefsに保存＋ラベル更新 */
    private void updateMoonModelPath(JComboBox<String> combo, File baseDir, JLabel label) {
        String langName = (String) combo.getSelectedItem();
        if (langName == null || langName.isEmpty()) return;

        File langDir = new File(baseDir, langName);
        if (!langDir.isDirectory()) {
            label.setText("⚠ " + langDir.getAbsolutePath() + " not found");
            return;
        }

        // 言語ディレクトリ内でモデルを探す
        // 構造例: moonshine_model/ja/quantized/base-ja/
        // または: moonshine_model/ja/  (直接モデルファイルが入っている)
        File modelDir = findDeepestModelDir(langDir);
        String modelPath = modelDir.getAbsolutePath();

        prefs.put("moonshine.model_path", modelPath);
        try { prefs.flush(); } catch (Exception ignore) {}

        // パスが長い場合は末尾を表示
        String display = modelPath;
        if (display.length() > 60) {
            display = "..." + display.substring(display.length() - 57);
        }
        label.setText("→ " + display);
        Config.log("[Wizard] Moonshine model path: " + modelPath);
    }

    /** モデルディレクトリを再帰探索（.onnx ファイルがある最深ディレクトリを返す）*/
    private File findDeepestModelDir(File dir) {
        // このディレクトリに.onnxファイルがあればここがモデルディレクトリ
        File[] onnxFiles = dir.listFiles((d, name) ->
                name.endsWith(".onnx") || name.endsWith(".bin"));
        if (onnxFiles != null && onnxFiles.length > 0) {
            return dir;
        }
        // サブディレクトリを再帰探索
        File[] subDirs = dir.listFiles(File::isDirectory);
        if (subDirs != null) {
            for (File sub : subDirs) {
                File found = findDeepestModelDir(sub);
                if (found != sub || found == sub) {
                    // 再帰で見つかったらそれを返す
                    File[] check = found.listFiles((d, name) ->
                            name.endsWith(".onnx") || name.endsWith(".bin"));
                    if (check != null && check.length > 0) {
                        return found;
                    }
                }
            }
            // .onnxが見つからなかった場合、最初のサブディレクトリを再帰
            if (subDirs.length > 0) {
                return findDeepestModelDir(subDirs[0]);
            }
        }
        // 見つからなければ元のディレクトリを返す
        return dir;
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
   Control Center (tabbed UI)
 ========================= */

    private JTabbedPane buildControlCenterTabs() {
        JTabbedPane tabs = new JTabbedPane();

        // 0=QuickStart, 1=Input, 2=Output, 3=VOICEVOX, 4=XTTS, 5=Voiceger, 6=Diagnostics
//        tabs.addTab(UiText.t("wizard.tab.quick"), quickStartPanel(tabs));
        tabs.addTab("Engine", stepRecogEngine());  // ★v1.5.0
        tabs.addTab("InputDevice", stepInputDevice());
        tabs.addTab("OutputDevice", stepOutputDevice());
        tabs.addTab("VOICEVOX", stepVoiceVox());
        tabs.addTab("XTTS", stepXtts());
        tabs.addTab("Voiceger", stepVoiceger());
//        tabs.addTab(UiText.t("wizard.tab.diagnostics"), diagnosticsPanel());

        // 初期 enter
        lastStepForTabs = tabIndexToStep(tabs.getSelectedIndex());
        if (lastStepForTabs >= 0) onStepEnter(lastStepForTabs);

        tabs.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                int newStep = tabIndexToStep(tabs.getSelectedIndex());
                if (newStep == lastStepForTabs) return;

                if (lastStepForTabs >= 0) onStepExit(lastStepForTabs);
                if (newStep >= 0) onStepEnter(newStep);
                lastStepForTabs = newStep;
            }
        });

        return tabs;
    }

    private int tabIndexToStep(int tabIndex) {
        switch (tabIndex) {
            case 0: return STEP_ENGINE;
            case 1: return STEP_INPUT;
            case 2: return STEP_OUTPUT;
            case 3: return STEP_VOICEVOX;
            case 4: return STEP_XTTS;
            case 5: return STEP_VOICEGER;
            default: return -1;
        }
    }

    private JPanel controlCenterBottomBar() {
        JPanel p = new JPanel(new BorderLayout());

        JLabel hint = new JLabel(UiText.t("wizard.cc.hint"));
        hint.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        p.add(hint, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton close = new JButton(UiText.t("wizard.finish"));
        close.addActionListener(e -> {
            // タブ移動で leave が呼ばれないケースがあるので念のため
            if (lastStepForTabs >= 0) onStepExit(lastStepForTabs);
            completed = true;
//            prefs.putBoolean("wizard.completed", true);
            try { prefs.sync(); } catch (Exception ignore) {}
            dispose();
        });

        right.add(close);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private JPanel quickStartPanel(JTabbedPane tabs) {
        JPanel p = basePanel();
        p.add(title("wizard.quick.title"));
        p.add(text("wizard.quick.desc"));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton goAudio = new JButton(UiText.t("wizard.quick.goAudio"));
        goAudio.addActionListener(e -> tabs.setSelectedIndex(1));

        JButton goOutput = new JButton(UiText.t("wizard.quick.goOutput"));
        goOutput.addActionListener(e -> tabs.setSelectedIndex(2));

        JButton goVoiceger = new JButton(UiText.t("wizard.quick.goVoiceger"));
        goVoiceger.addActionListener(e -> tabs.setSelectedIndex(5));

        row.add(goAudio);
        row.add(goOutput);
        row.add(goVoiceger);
        p.add(row);

        p.add(text("wizard.quick.tip1"));
        p.add(text("wizard.quick.tip2"));
        return p;
    }

    private JPanel diagnosticsPanel() {
        JPanel p = basePanel();
        p.add(title("wizard.diag.title"));
        p.add(text("wizard.diag.desc"));

        JTextArea area = new JTextArea(14, 60);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);

        JScrollPane sp = new JScrollPane(area);
        p.add(sp);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT));

        JButton gen = new JButton(UiText.t("wizard.diag.gen"));
        gen.addActionListener(e -> area.setText(buildSystemSummary()));

        JButton copy = new JButton(UiText.t("wizard.diag.copy"));
        copy.addActionListener(e -> {
            if (area.getText().trim().isEmpty()) area.setText(buildSystemSummary());
            copyToClipboard(area.getText());
        });

        btns.add(gen);
        btns.add(copy);
        p.add(btns);

        return p;
    }

    private String buildSystemSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== MobMateWhispTalk Summary ===\n");
        sb.append("os=").append(System.getProperty("os.name")).append(" ").append(System.getProperty("os.version")).append("\n");
        sb.append("java=").append(System.getProperty("java.version")).append("\n");

        sb.append("\n[Audio]\n");
        sb.append("input.device=").append(prefs.get("audio.device", "")).append("\n");
        sb.append("output.device=").append(prefs.get("audio.output", "")).append("\n");

        sb.append("\n[TTS]\n");
        sb.append("tts.engine=").append(prefs.get("tts.engine", "")).append("\n");
        sb.append("voicevox.exe=").append(prefs.get("voicevox.exe", "")).append("\n");
        sb.append("voicevox.speaker=").append(prefs.get("voicevox.speaker", "")).append("\n");
        sb.append("xtts.url=").append(prefs.get("xtts.url", "")).append("\n");

        sb.append("\n[Voiceger]\n");
        sb.append("voiceger.enabled=").append(prefs.getBoolean("voiceger.enabled", false)).append("\n");
        sb.append("voiceger.dir=").append(prefs.get("voiceger.dir", "")).append("\n");
        sb.append("voiceger.port=").append(prefs.getInt("voiceger.port", 8501)).append("\n");

        sb.append("\n[Hints]\n");
        sb.append("- If input meter does not move: check Windows mic level / mic boost / enhancements / default device.\n");
        sb.append("- If Voiceger fails: open voiceger_api.log and copy the last 50 lines.\n");
        return sb.toString();
    }

    private void copyToClipboard(String s) {
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            cb.setContents(new StringSelection(s), null);
        } catch (Exception ex) {
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
