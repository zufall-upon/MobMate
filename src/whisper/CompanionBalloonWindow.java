package whisper;

import javax.swing.*;
import java.awt.*;

final class CompanionBalloonWindow extends JWindow {

    private final CompanionFrame owner;
    private final java.util.prefs.Preferences prefs;
    private final BalloonPanel balloonPanel = new BalloonPanel();
    private final Timer hideTimer;

    CompanionBalloonWindow(CompanionFrame owner, java.util.prefs.Preferences prefs) {
        super(owner);
        this.owner = owner;
        this.prefs = prefs;
        setFocusableWindowState(false);
        setAlwaysOnTop(true);
        setBackground(new Color(0, 0, 0, 0));
        setContentPane(balloonPanel);
        hideTimer = new Timer(6200, e -> hideBalloon());
        hideTimer.setRepeats(false);
        hideTimer.setCoalesce(true);
    }

    void showMessage(String text) {
        String normalized = (text == null) ? "" : text.trim();
        if (normalized.isBlank() || owner == null || !owner.isShowing()) {
            hideBalloon();
            return;
        }
        balloonPanel.setMessage(normalized, useLeftPointer());
        pack();
        setVisible(true);
        SwingUtilities.invokeLater(() -> {
            if (!isVisible()) {
                return;
            }
            reposition();
            hideTimer.restart();
        });
    }

    void reposition() {
        if (owner == null || !owner.isShowing()) {
            return;
        }
        Rectangle ownerBounds = new Rectangle(owner.getLocationOnScreen(), owner.getSize());
        GraphicsConfiguration gc = owner.getGraphicsConfiguration();
        Rectangle screen = (gc == null)
                ? GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds()
                : gc.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        Rectangle usable = new Rectangle(
                screen.x + insets.left,
                screen.y + insets.top,
                Math.max(100, screen.width - insets.left - insets.right),
                Math.max(100, screen.height - insets.top - insets.bottom)
        );

        int margin = 10;
        boolean leftPointer = useLeftPointer();
        int x = leftPointer
                ? ownerBounds.x + ownerBounds.width - Math.max(48, getWidth() / 3)
                : ownerBounds.x - getWidth() + Math.max(48, (ownerBounds.width * 2) / 3);
        int y = ownerBounds.y - getHeight() + 18;

        x = Math.max(usable.x + margin, Math.min(x, usable.x + usable.width - getWidth() - margin));
        y = Math.max(usable.y + margin, Math.min(y, usable.y + usable.height - getHeight() - margin));
        setLocation(x, y);
    }

    void hideBalloon() {
        hideTimer.stop();
        setVisible(false);
    }

    void shutdown() {
        hideTimer.stop();
        setVisible(false);
        dispose();
    }

    private boolean useLeftPointer() {
        String hearingPos = prefs.get("hearing.overlay.position", "bottom_left");
        return "bottom_right".equalsIgnoreCase(hearingPos) || "top_center".equalsIgnoreCase(hearingPos);
    }

    private static final class BalloonPanel extends JPanel {
        private static final int ARC = 24;
        private static final int POINTER_H = 18;
        private static final int POINTER_W = 28;

        private final JTextArea textArea = new JTextArea();
        private boolean pointerLeft = false;

        BalloonPanel() {
            setOpaque(false);
            setLayout(new BorderLayout());
            textArea.setOpaque(false);
            textArea.setEditable(false);
            textArea.setFocusable(false);
            textArea.setWrapStyleWord(true);
            textArea.setLineWrap(true);
            textArea.setBorder(BorderFactory.createEmptyBorder(16, 18, 18 + POINTER_H, 18));
            textArea.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
            textArea.setForeground(new Color(52, 40, 32));
            add(textArea, BorderLayout.CENTER);
        }

        void setMessage(String text, boolean pointerLeft) {
            this.pointerLeft = pointerLeft;
            textArea.setText(text == null ? "" : text);
            int width = Math.min(420, Math.max(170, measureTextWidth(textArea.getText()) + 46));
            textArea.setSize(width - 36, Short.MAX_VALUE);
            Dimension pref = textArea.getPreferredSize();
            setPreferredSize(new Dimension(width, Math.max(86, pref.height + 10)));
            revalidate();
            repaint();
        }

        private int measureTextWidth(String text) {
            FontMetrics fm = textArea.getFontMetrics(textArea.getFont());
            int widest = 0;
            for (String line : (text == null ? "" : text).split("\\R", -1)) {
                widest = Math.max(widest, fm.stringWidth(line));
            }
            return widest;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                int bodyH = h - POINTER_H;

                g2.setColor(new Color(0, 0, 0, 48));
                g2.fillRoundRect(4, 6, w - 8, bodyH - 2, ARC, ARC);

                GradientPaint gp = new GradientPaint(0, 0, new Color(255, 249, 236), 0, bodyH, new Color(255, 237, 213));
                g2.setPaint(gp);
                g2.fillRoundRect(0, 0, w, bodyH, ARC, ARC);

                Polygon pointer = new Polygon();
                int baseX = pointerLeft ? 48 : (w - 48);
                pointer.addPoint(baseX - (POINTER_W / 2), bodyH - 2);
                pointer.addPoint(baseX + (POINTER_W / 2), bodyH - 2);
                pointer.addPoint(baseX, h - 2);
                g2.fillPolygon(pointer);

                g2.setColor(new Color(220, 176, 121, 220));
                g2.setStroke(new BasicStroke(2f));
                g2.drawRoundRect(1, 1, w - 3, bodyH - 3, ARC, ARC);
                g2.drawLine(baseX - (POINTER_W / 2), bodyH - 2, baseX, h - 3);
                g2.drawLine(baseX + (POINTER_W / 2), bodyH - 2, baseX, h - 3);
            } finally {
                g2.dispose();
            }
        }
    }
}
