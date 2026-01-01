package whisper;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
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

    public HistoryFrame(final MobMateWhisp mobMateWhisp) {
        this.mobMateWhisp = mobMateWhisp;
        setTitle("MobMateWhispTalk - History");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        // TextArea
//        this.t.setFont(new JLabel().getFont());
//        final StringBuilder b = new StringBuilder();
//        for (final String s : mobMateWhisp.getHistory()) {
//            b.append(s);
//            b.append("\n");
//        }
//        this.t.setText(b.toString());
        // ListPanel
        historyListPanel.setLayout(
                new BoxLayout(historyListPanel, BoxLayout.Y_AXIS)
        );
        historyListPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        historyListPanel.add(Box.createVerticalGlue());
        JScrollPane scroll = new JScrollPane(historyListPanel);
        // Buttons
        final JPanel bottom = new JPanel();
        bottom.setLayout(new FlowLayout(FlowLayout.LEFT));
        final JButton copyToClipboard = new JButton("\uD83D\uDCCBCC");
        bottom.add(copyToClipboard);
        final JButton clearButton = new JButton("\uD83D\uDDD1Clr");
        bottom.add(clearButton);
        final JButton openOutTts = new JButton("\uD83D\uDDE3_out");
        bottom.add(openOutTts);
        final JButton openIgnore = new JButton("\uD83D\uDE48_ignr");
        bottom.add(openIgnore);
        final JButton openDict   = new JButton("\uD83D\uDCD6_dict");
        bottom.add(openDict);
        final JButton openGood   = new JButton("★_good");
        bottom.add(openGood);
        openOutTts.addActionListener(e -> openTextFile("_outtts.txt"));
        openIgnore.addActionListener(e -> openTextFile("_ignore.txt"));
        openDict.addActionListener(e -> openTextFile("_dictionary.txt"));
        openGood.addActionListener(e -> openTextFile("_initprmpt_add.txt"));

        bottom.add(openOutTts);
        bottom.add(openIgnore);
        bottom.add(openDict);
        bottom.add(openGood);
        // Main panel
        final JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
//        panel.add(new JScrollPane(this.t), BorderLayout.CENTER);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        this.setContentPane(panel);
        this.setIconImage(new ImageIcon(this.getClass().getResource("inactive.png")).getImage());

        // Listners
        mobMateWhisp.addHistoryListener(this);
        copyToClipboard.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(final ActionEvent e) {
//                String str = HistoryFrame.this.t.getSelectedText();
//                if (str == null || str.isEmpty()) {
//                    // all
//                    str = HistoryFrame.this.t.getText();
//                }
//                final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
//                clipboard.setContents(new StringSelection(str), null);

            }
        });

        clearButton.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(final ActionEvent e) {
                mobMateWhisp.clearHistory();
            }
        });

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

    @Override
    public void stateChanged(ChangeEvent e) {
        historyListPanel.removeAll();

        Object[] arr = mobMateWhisp.getHistory().toArray();

        String ignoreMode = Config.get("ignore.mode", "simple"); // simple | regex
        java.util.List<String> ignoreWords = Config.loadIgnoreWords();
        java.util.List<String> goodWords = Config.loadGoodWords();

        int rowIndex = 0;
        for (int i = arr.length - 1; i >= 0; i--) {
            String s = (String) arr[i];
            HistoryRowPanel row = new HistoryRowPanel(s);

            // --- zebra background ---
            if (rowIndex % 2 == 0) {
                row.setBackground(new Color(245, 245, 245));
            } else {
                row.setBackground(Color.WHITE);
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
}
