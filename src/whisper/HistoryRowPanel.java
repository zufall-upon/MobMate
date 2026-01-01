package whisper;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashSet;

import static whisper.Config.loadIgnoreSet;

public class HistoryRowPanel extends JPanel {
    private final JLabel label;
    private boolean ng = false;
    private boolean good = false;
    private final String word;

    HistoryRowPanel(String text) {
        this.word = text;
        setLayout(new BorderLayout());
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setMaximumSize(
                new Dimension(Integer.MAX_VALUE, getPreferredSize().height)
        );

        label = new JLabel(text);
        add(label, BorderLayout.CENTER);

        JButton ngButton = new JButton("NG");
        ngButton.addActionListener(e -> toggleNG());
        JButton dicButton = new JButton("Dic");
        dicButton.addActionListener(e -> addToDictionary());
        JButton goodButton = new JButton("★");
        goodButton.addActionListener(e -> toggleGood());
        ngButton.setMargin(new Insets(1, 4, 1, 4));
        dicButton.setMargin(new Insets(1, 4, 1, 4));
        goodButton.setMargin(new Insets(1, 4, 1, 4));

        JPanel btnPanel = new JPanel();
        btnPanel.setLayout(new BoxLayout(btnPanel, BoxLayout.X_AXIS));
        btnPanel.add(ngButton);
        btnPanel.add(Box.createHorizontalStrut(4));
        btnPanel.add(goodButton);
        btnPanel.add(Box.createHorizontalStrut(4));
        btnPanel.add(dicButton);
        add(btnPanel, BorderLayout.EAST);
    }

    private void toggleNG() {
        ng = !ng;
        if (!ng) {
            setNG(false);
        } else {
            setNG(true);
            setGood(false);
        }
        LinkedHashSet<String> set = Config.loadIgnoreSet();
        if (ng) {

            set.remove(word);
            set.add(word);
            label.setForeground(Color.LIGHT_GRAY);
        } else {

            set.remove(word);
            label.setForeground(Color.BLACK);
        }
        Config.saveIgnoreSet(set);
        LocalWhisperCPP.markIgnoreDirty();
        repaint();
    }
    public void setNG(boolean ng) {
        this.ng = ng;
        if (ng) {
            label.setForeground(Color.LIGHT_GRAY);
        } else {
            label.setForeground(Color.BLACK);
        }
    }

    private void toggleGood() {
        good = !good;
        if (!good) {
            setGood(false);
        } else {
            setGood(true);
            setNG(false);
        }
        LinkedHashSet<String> set = Config.loadGoodSet();
        if (good) {
            set.remove(word);
            set.add(word);
            label.setForeground(new Color(255, 153, 51));
        } else {
            set.remove(word);
            label.setForeground(Color.BLACK);
        }
        Config.saveGoodSet(set);
        LocalWhisperCPP.markInitialPromptDirty();
        repaint();
    }
    public void setGood(boolean good) {
        this.good = good;
        if (good) {
            label.setForeground(new Color(255, 153, 51));
        } else {
            label.setForeground(Color.BLACK);
        }
    }

    private void addToDictionary() {
        String key = word.trim();
        if (key.isEmpty()) return;

        registerDictionaryEntry(key);
        applyDictionaryRegisteredStyle();
    }
    private void registerDictionaryEntry(String key) {

        Config.addDictionaryEntry(key, key);


        LocalWhisperCPP.markDictionaryDirty();
    }
    private void applyDictionaryRegisteredStyle() {

        label.setForeground(new Color(100, 140, 255));
        repaint();
    }

}
