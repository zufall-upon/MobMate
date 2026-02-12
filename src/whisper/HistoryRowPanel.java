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
    private final MobMateWhisp mob;

    HistoryRowPanel(MobMateWhisp mobMateWhisp, String text) {
        this.mob = mobMateWhisp;
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

        // ★追加：RadioCmd登録
        JButton addRcButton = new JButton("AddRC");
        addRcButton.addActionListener(e -> showAddRcPopup(addRcButton));

        ngButton.setMargin(new Insets(1, 4, 1, 4));
        dicButton.setMargin(new Insets(1, 4, 1, 4));
        goodButton.setMargin(new Insets(1, 4, 1, 4));
        addRcButton.setMargin(new Insets(1, 4, 1, 4));

        JPanel btnPanel = new JPanel();
        btnPanel.setLayout(new BoxLayout(btnPanel, BoxLayout.X_AXIS));
        btnPanel.add(ngButton);
        btnPanel.add(Box.createHorizontalStrut(4));
        btnPanel.add(goodButton);
        btnPanel.add(Box.createHorizontalStrut(4));
        btnPanel.add(dicButton);
        btnPanel.add(Box.createHorizontalStrut(8));
        btnPanel.add(addRcButton);

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
            label.setForeground(getNgTextColor());
        } else {

            set.remove(word);
            label.setForeground(getNormalTextColor());
        }
        Config.saveIgnoreSet(set);
        LocalWhisperCPP.markIgnoreDirty();
        repaint();
    }
    public void setNG(boolean ng) {
        this.ng = ng;
        if (ng) {
            label.setForeground(getNgTextColor());
        } else {
            label.setForeground(getNormalTextColor());
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
            label.setForeground(getGoodTextColor());
        } else {
            set.remove(word);
            label.setForeground(getNormalTextColor());
        }
        Config.saveGoodSet(set);
        LocalWhisperCPP.markInitialPromptDirty();
        repaint();
    }
    public void setGood(boolean good) {
        this.good = good;
        if (good) {
            label.setForeground(getGoodTextColor());
        } else {
            label.setForeground(getNormalTextColor());
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

        label.setForeground(getDictTextColor());
        repaint();
    }
    // ★AddRC: ポップアップ（p0..p2 / 1..9）を出して登録
    // ★AddRC: ポップアップ（p0..p2 / 1..9）を出して登録（表示は登録済みテキスト）
    private void showAddRcPopup(Component invoker) {
        String text = (word == null) ? "" : word.trim();
        if (text.isEmpty()) return;

        JPopupMenu popup = new JPopupMenu();

        // 表示用：長文を省略
        java.util.function.Function<String, String> ellipsize = (s) -> {
            if (s == null) return "(empty)";
            s = s.trim();
            if (s.isEmpty()) return "(empty)";
            int max = 16; // 好みで調整
            return (s.length() <= max) ? s : (s.substring(0, max) + "…");
        };

        for (int page = 0; page <= 2; page++) {
            JMenu mPage = new JMenu("P" + page + "　");

            for (int d = 1; d <= 9; d++) {
                final int fp = page;
                final int fd = d;

                // ★ここが変更点：登録済みの文を表示
                String existed = "";
                try {
                    existed = mob.peekRadioCmdText(fp, fd); // ★MobMateWhisp側に追加したpublicメソッド
                } catch (Exception ignore) {}

                JMenuItem it = new JMenuItem(d + "  →  " + ellipsize.apply(existed));

                it.addActionListener(ev -> {
                    try {
                        // ★ここが本体：_radiocmd.txt & メモリMapへ登録
                        mob.upsertRadioCmd(fp, fd, text);

                        // 見た目上ちょい色（登録した感）
                        label.setForeground(getRegisteredTextColor());
                        repaint();
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(
                                this,
                                "Failed to add to RadioCmd.\n" + ex.getMessage(),
                                "MobMate",
                                JOptionPane.ERROR_MESSAGE
                        );
                    }
                });

                mPage.add(it);
            }
            popup.add(mPage);
        }

        // ★ここが変更点：右に寄り過ぎないように「左寄せ」で出す（画面外もなるべく避ける）
        popup.pack();
        popup.show(invoker, 0, 0);
    }

    private static String shortText(String s, int max) {
        if (s == null) return "";
        s = s.trim().replace("\r", "").replace("\n", " ");
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    // ===== FlatLaf対応：テーマに応じた色を返す =====
    private Color getNormalTextColor() {
        Color fg = UIManager.getColor("Label.foreground");
        return (fg != null) ? fg : Color.BLACK;
    }

    private Color getNgTextColor() {
        // ダークモード時は明るめ、ライトモード時は暗めのグレー
        return isDarkMode()
                ? new Color(160, 160, 160)  // ダーク時：明るいグレー
                : new Color(140, 140, 140); // ライト時：暗めのグレー
    }

    private Color getGoodTextColor() {
        // オレンジ系（ダークモード時は少し明るく）
        return isDarkMode()
                ? new Color(255, 180, 80)   // ダーク時：明るいオレンジ
                : new Color(255, 153, 51);  // ライト時：通常のオレンジ
    }

    private Color getDictTextColor() {
        // 青系（ダークモード時は明るく）
        return isDarkMode()
                ? new Color(120, 180, 255)  // ダーク時：明るい青
                : new Color(100, 140, 255); // ライト時：通常の青
    }

    private Color getRegisteredTextColor() {
        // グレー系（ダークモード時は明るく）
        return isDarkMode()
                ? new Color(160, 160, 160)  // ダーク時：明るいグレー
                : new Color(120, 120, 120); // ライト時：暗めのグレー
    }

    private boolean isDarkMode() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) return false;
        // 背景色の明るさで判定（0.5以下ならダークモード）
        int rgb = bg.getRed() + bg.getGreen() + bg.getBlue();
        return rgb < 384; // (128 * 3)
    }
}
