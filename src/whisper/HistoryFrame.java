package whisper;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.File;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

public class HistoryFrame extends JFrame implements ChangeListener {
    private final MobMateWhisp mobMateWhisp;
//    private final JTextArea t = new JTextArea();
    private final JPanel historyListPanel = new JPanel();
    private final JLabel partialLabel = new JLabel(" ");
    private final JComboBox<String> talkLangCombo = new JComboBox<>();
    private final JComboBox<String> talkTranslateTargetCombo = new JComboBox<>(LanguageOptions.translationTargets());
    private final JButton approvePendingButton = new JButton(UiText.t("ui.history.confirm.approve"));
    private final JButton cancelPendingButton = new JButton(UiText.t("ui.history.confirm.cancel"));
    private boolean adjustingTalkControls = false;
    public static final int HISTORY_MAX_LINES = 100;
    private final java.util.concurrent.BlockingQueue<String> radioSpeakQueue =
            new java.util.concurrent.LinkedBlockingQueue<>();
    private volatile boolean radioSpeakWorkerStarted = false;


    private void updateTitle() {
        String mode = mobMateWhisp.getCpuGpuMode(); // "Vulkan MODE" or "CPU MODE"
        String demo = SteamHelper.isDemoMode() ? " [TRIAL]" : "";
        setTitle("MobMate Talk [" + ((mode.equals("")) ? "CPU MODE" : mode) + "]" + demo);
    }
    public HistoryFrame(final MobMateWhisp mobMateWhisp) {
        this.mobMateWhisp = mobMateWhisp;
        updateTitle();
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        // ===== History List =====
        historyListPanel.setLayout(
                new BoxLayout(historyListPanel, BoxLayout.Y_AXIS)
        );
        historyListPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        historyListPanel.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(historyListPanel);

        // ★ADD: partial preview ラベルのスタイル設定
        partialLabel.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 14));
        partialLabel.setForeground(new Color(110, 150, 210));
        partialLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        partialLabel.setOpaque(true);

        // ===== Speak Input (★追加) =====
        final JTextField speakField = new JTextField();
        speakField.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        speakField.setForeground(Color.GRAY);
        speakField.setText("Type here and press Enter to speak");

        speakField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (speakField.getText().startsWith("Type")) {
                    speakField.setText("");
                    speakField.setForeground(Color.BLACK);
                }
            }

            @Override
            public void focusLost(FocusEvent e) {
                if (speakField.getText().isEmpty()) {
                    speakField.setForeground(Color.GRAY);
                    speakField.setText("Type here and press Enter to speak");
                }
            }
        });

        speakField.addActionListener(e -> {
            String text = speakField.getText().trim();
            if (text.isEmpty() || text.startsWith("Type")) return;

            speakField.setText("");
            speakField.requestFocus();
            ensureRadioSpeakWorker();
            radioSpeakQueue.offer(text);

        });

        JPanel speakPanel = new JPanel(new BorderLayout());
        speakPanel.add(speakField, BorderLayout.CENTER);

        // ===== Buttons =====
        final JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new FlowLayout(FlowLayout.LEFT));

        final JButton clearButton = new JButton("×Clr");
        final JButton openOutTts = new JButton("out");
        final JButton openIgnore = new JButton("ignr");
        final JButton openDict   = new JButton("Dict");
        final JButton openGood   = new JButton("Good");
        final JButton openRadio  = new JButton("RCmd");

        buttonPanel.add(clearButton);
        buttonPanel.add(openOutTts);
        buttonPanel.add(openIgnore);
        buttonPanel.add(openDict);
        buttonPanel.add(openGood);
        buttonPanel.add(openRadio);
        buttonPanel.add(approvePendingButton);
        buttonPanel.add(cancelPendingButton);

        openOutTts.addActionListener(e -> openTextFile("_outtts.txt"));
        openIgnore.addActionListener(e -> openTextFile("_ignore.txt"));
        openDict.addActionListener(e -> openTextFile("_dictionary.txt"));
        openGood.addActionListener(e -> openTextFile("_initprmpt_add.txt"));
        openRadio.addActionListener(e -> openTextFile("_radiocmd.txt"));
        approvePendingButton.addActionListener(e -> mobMateWhisp.approvePendingConfirm());
        cancelPendingButton.addActionListener(e -> mobMateWhisp.cancelPendingConfirm());

        clearButton.addActionListener(e -> mobMateWhisp.clearHistory());

        // ===== Bottom (二段構成) =====
        JPanel bottom = new JPanel();
        bottom.setLayout(new BorderLayout(4, 4));
        bottom.add(speakPanel, BorderLayout.NORTH);
        bottom.add(buttonPanel, BorderLayout.SOUTH);

        // ===== Talk controls =====
        talkLangCombo.setRenderer(LanguageOptions.whisperRenderer());
        talkTranslateTargetCombo.setRenderer(LanguageOptions.translationRenderer());
        talkLangCombo.setPreferredSize(new Dimension(132, 28));
        talkTranslateTargetCombo.setPreferredSize(new Dimension(138, 28));
        talkLangCombo.addActionListener(e -> {
            if (adjustingTalkControls) return;
            String selected = Objects.toString(talkLangCombo.getSelectedItem(), "");
            if (!mobMateWhisp.requestTalkLanguageChange(this, selected)) {
                refreshTalkControls();
            }
        });
        talkTranslateTargetCombo.addActionListener(e -> {
            if (adjustingTalkControls) return;
            String selected = Objects.toString(talkTranslateTargetCombo.getSelectedItem(), "OFF");
            mobMateWhisp.setTalkTranslateTarget(selected);
            mobMateWhisp.prewarmPiperPlusForTalkTargetSelection(this, selected);
        });

        JPanel talkControlPanel = new JPanel();
        talkControlPanel.setLayout(new BoxLayout(talkControlPanel, BoxLayout.X_AXIS));
        talkControlPanel.add(new JLabel("Lang:"));
        talkControlPanel.add(Box.createHorizontalStrut(6));
        talkControlPanel.add(talkLangCombo);
        talkControlPanel.add(Box.createHorizontalStrut(10));
        talkControlPanel.add(new JLabel("To:"));
        talkControlPanel.add(Box.createHorizontalStrut(6));
        talkControlPanel.add(talkTranslateTargetCombo);

        JPanel topPanel = new JPanel(new BorderLayout(8, 0));
        topPanel.add(partialLabel, BorderLayout.CENTER);
        topPanel.add(talkControlPanel, BorderLayout.EAST);

        // ===== Main panel =====
        final JPanel panel = new JPanel(new BorderLayout());
        panel.add(topPanel, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);

        this.setContentPane(panel);
        this.setIconImage(
                new ImageIcon(this.getClass().getResource("inactive.png")).getImage()
        );

        // ===== Listeners =====
        refreshTalkControls();
        refreshConfirmControls();
        mobMateWhisp.addHistoryListener(this);

        this.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(final WindowEvent e) {
                mobMateWhisp.removeHistoryListener(HistoryFrame.this);
            }
        });
    }

//    @Override
//    public void stateChanged(final ChangeEvent e) {
//        final StringBuilder b = new StringBuilder();
//        for (final String s : this.mobMateWhisp.getHistory()) {
//            b.append(s);
//            b.append("\n");
//        }
//        this.t.setText(b.toString());
//
//    }

    private void ensureRadioSpeakWorker() {
        if (radioSpeakWorkerStarted) return;
        radioSpeakWorkerStarted = true;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    String s = radioSpeakQueue.take();
                    mobMateWhisp.speakTalkTextForUi(s);
                } catch (Exception ex) {
                    Config.logError("RadioSpeak worker error: " + ex.getMessage(), ex);
                }
            }
        }, "RadioSpeakWorker");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void stateChanged(ChangeEvent e) {
        historyListPanel.removeAll();

        MobMateWhisp.HistoryEntry[] arr = mobMateWhisp.getHistory().toArray(new MobMateWhisp.HistoryEntry[0]);

        String ignoreMode = Config.get("ignore.mode", "simple"); // simple | regex
        java.util.List<String> ignoreWords = Config.loadIgnoreWords();
        java.util.List<String> goodWords = Config.loadGoodWords();

        // ★追加：最大500行まで（古いものは捨てる）
        int minIndex = Math.max(0, arr.length - HISTORY_MAX_LINES);

        int rowIndex = 0;
        for (int i = arr.length - 1; i >= minIndex; i--) {
            MobMateWhisp.HistoryEntry entry = arr[i];
            String displayText = entry.displayText();
            String rawText = entry.rawText();
            HistoryRowPanel row = new HistoryRowPanel(mobMateWhisp, displayText, rawText);

            // --- zebra background (FlatLaf対応) ---
            Color baseColor = UIManager.getColor("Panel.background");
            if (baseColor == null) baseColor = getBackground();

            if (rowIndex % 2 == 0) {
                // 偶数行：ベース色をやや明るく/暗く
                row.setBackground(adjustBrightness(baseColor, 0.05f));
            } else {
                // 奇数行：ベース色そのまま
                row.setBackground(baseColor);
            }
            row.setOpaque(true);

            if (ignoreWords.contains(rawText)) {
                row.setNG(true);
            } else if (goodWords.contains(rawText)) {
                row.setGood(true);
            }
            historyListPanel.add(row);
            rowIndex++;
        }

        historyListPanel.add(Box.createVerticalGlue());
        historyListPanel.revalidate();
        historyListPanel.repaint();
        refreshConfirmControls();
    }

    public void refresh() {
        stateChanged(null);
    }

    private void openTextFile(String filename) {
        try {
//            File dir = new File(
//                    System.getProperty("user.home"),
//                    "Documents/MobMateWhispTalk"
//            );
//            if (!dir.exists()) {
//                dir.mkdirs();
//            }
            File f = new File(filename);
            if (!f.exists()) {
                f.createNewFile();
            }
            Desktop.getDesktop().open(f);

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    this,
                    "Failed to open " + filename,
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
            ex.printStackTrace();
        }
    }
    public void updateIcon(boolean recording, boolean transcribing,
                           Image imageRecording,
                           Image imageTranscribing,
                           Image imageInactive) {
        if (transcribing) {
            setIconImage(imageTranscribing);
        } else if (recording) {
            setIconImage(imageRecording);
        } else {
            setIconImage(imageInactive);
        }
    }
    // ===== FlatLaf用：色の明るさ調整 =====
    private Color adjustBrightness(Color color, float factor) {
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        float newBrightness = Math.max(0f, Math.min(1f, hsb[2] + factor));
        return Color.getHSBColor(hsb[0], hsb[1], newBrightness);
    }
    public void refreshTalkControls() {
        SwingUtilities.invokeLater(() -> {
            adjustingTalkControls = true;
            try {
                String[] langOptions = mobMateWhisp.getTalkLanguageOptions();
                talkLangCombo.removeAllItems();
                for (String lang : langOptions) {
                    talkLangCombo.addItem(lang);
                }
                String talkLang = mobMateWhisp.getTalkLanguage();
                if (talkLangCombo.getItemCount() == 0) {
                    talkLangCombo.addItem(talkLang);
                }
                talkLangCombo.setSelectedItem(talkLang);
                if (talkLangCombo.getSelectedItem() == null && talkLangCombo.getItemCount() > 0) {
                    talkLangCombo.setSelectedIndex(0);
                }

                talkTranslateTargetCombo.setSelectedItem(mobMateWhisp.getTalkTranslateTarget());
                if (talkTranslateTargetCombo.getSelectedItem() == null) {
                    talkTranslateTargetCombo.setSelectedItem("OFF");
                }
            } finally {
                adjustingTalkControls = false;
            }
        });
    }
    public void refreshConfirmControls() {
        SwingUtilities.invokeLater(() -> {
            boolean enabled = mobMateWhisp.isTtsConfirmModeEnabled() && mobMateWhisp.hasPendingConfirm();
            String tooltip = String.join("\n", mobMateWhisp.getPendingConfirmPreviewList(4));
            approvePendingButton.setEnabled(enabled);
            cancelPendingButton.setEnabled(enabled);
            approvePendingButton.setVisible(mobMateWhisp.isTtsConfirmModeEnabled());
            cancelPendingButton.setVisible(mobMateWhisp.isTtsConfirmModeEnabled());
            approvePendingButton.setToolTipText(tooltip);
            cancelPendingButton.setToolTipText(tooltip);
        });
    }
    // ★ADD: Moonshine partial プレビュー更新（MobMateWhispから呼ばれる）
    public void setPartialPreview(String text) {
        SwingUtilities.invokeLater(() -> {
            if (text == null || text.isBlank()) {
                partialLabel.setText(" ");
                partialLabel.setBackground(UIManager.getColor("Panel.background"));
            } else {
                partialLabel.setText("⏳ " + text);
                Color bg = UIManager.getColor("Panel.background");
                if (bg == null) bg = getBackground();
                // ★背景に溶け込む控えめなブレンド（青+8だけ）
                partialLabel.setBackground(new Color(
                        Math.max(0, bg.getRed()   - 3),
                        Math.max(0, bg.getGreen() - 3),
                        Math.min(255, bg.getBlue() + 8)
                ));
            }
            partialLabel.setOpaque(true);
            partialLabel.repaint();
        });
    }
}

