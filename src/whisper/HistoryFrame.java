package whisper;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.File;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

public class HistoryFrame extends JFrame implements ChangeListener {
    private final MobMateWhisp mobMateWhisp;
//    private final JTextArea t = new JTextArea();
    private final JPanel historyListPanel = new JPanel();
    public static final int HISTORY_MAX_LINES = 100;
    private final java.util.concurrent.BlockingQueue<String> radioSpeakQueue =
            new java.util.concurrent.LinkedBlockingQueue<>();
    private volatile boolean radioSpeakWorkerStarted = false;


    private void updateTitle() {
        String mode = mobMateWhisp.getCpuGpuMode(); // "Vulkan MODE" or "CPU MODE"
        String demo = SteamHelper.isDemoMode() ? " [TRIAL]" : "";
        setTitle("History [" + ((mode.equals(""))? "CPU MODE": mode) + "]" + demo);
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

            SwingUtilities.invokeLater(() -> {
                mobMateWhisp.addHistory(text);      // ★ここで1回だけ追加
                Config.appendOutTts(text);          // outttsは従来通り1回
            });
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

        openOutTts.addActionListener(e -> openTextFile("_outtts.txt"));
        openIgnore.addActionListener(e -> openTextFile("_ignore.txt"));
        openDict.addActionListener(e -> openTextFile("_dictionary.txt"));
        openGood.addActionListener(e -> openTextFile("_initprmpt_add.txt"));
        openRadio.addActionListener(e -> openTextFile("_radiocmd.txt"));

        clearButton.addActionListener(e -> mobMateWhisp.clearHistory());

        // ===== Bottom (二段構成) =====
        JPanel bottom = new JPanel();
        bottom.setLayout(new BorderLayout(4, 4));
        bottom.add(speakPanel, BorderLayout.NORTH);
        bottom.add(buttonPanel, BorderLayout.SOUTH);

        // ===== Main panel =====
        final JPanel panel = new JPanel(new BorderLayout());
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);

        this.setContentPane(panel);
        this.setIconImage(
                new ImageIcon(this.getClass().getResource("inactive.png")).getImage()
        );

        // ===== Listeners =====
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
                    mobMateWhisp.speak(s);
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

        Object[] arr = mobMateWhisp.getHistory().toArray();

        String ignoreMode = Config.get("ignore.mode", "simple"); // simple | regex
        java.util.List<String> ignoreWords = Config.loadIgnoreWords();
        java.util.List<String> goodWords = Config.loadGoodWords();

        // ★追加：最大500行まで（古いものは捨てる）
        int minIndex = Math.max(0, arr.length - HISTORY_MAX_LINES);

        int rowIndex = 0;
        for (int i = arr.length - 1; i >= minIndex; i--) {
            String s = (String) arr[i];
            HistoryRowPanel row = new HistoryRowPanel(mobMateWhisp, s);

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

            if (ignoreWords.contains(s)) {
                row.setNG(true);
            } else if (goodWords.contains(s)) {
                row.setGood(true);
            }
            historyListPanel.add(row);
            rowIndex++;
        }

        historyListPanel.add(Box.createVerticalGlue());
        historyListPanel.revalidate();
        historyListPanel.repaint();
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
}
