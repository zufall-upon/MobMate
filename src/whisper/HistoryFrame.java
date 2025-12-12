package whisper;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

public class HistoryFrame extends JFrame implements ChangeListener {
    private final MobMateWhisp mobMateWhisp;
    private final JTextArea t = new JTextArea();

    public HistoryFrame(final MobMateWhisp mobMateWhisp) {
        this.mobMateWhisp = mobMateWhisp;
        setTitle("MobMateWhispTalk - History");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        // TextArea
        this.t.setFont(new JLabel().getFont());
        final StringBuilder b = new StringBuilder();
        for (final String s : mobMateWhisp.getHistory()) {
            b.append(s);
            b.append("\n");
        }
        this.t.setText(b.toString());
        // Buttons
        final JPanel bottom = new JPanel();
        bottom.setLayout(new FlowLayout(FlowLayout.LEFT));
        final JButton copyToClipboard = new JButton("Copy to clipboard");
        bottom.add(copyToClipboard);
        final JButton clearButton = new JButton("Clear history");
        bottom.add(clearButton);
        // Main panel
        final JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        panel.add(new JScrollPane(this.t), BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        this.setContentPane(panel);
        this.setIconImage(new ImageIcon(this.getClass().getResource("inactive.png")).getImage());

        // Listners
        mobMateWhisp.addHistoryListener(this);
        copyToClipboard.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(final ActionEvent e) {
                String str = HistoryFrame.this.t.getSelectedText();
                if (str == null || str.isEmpty()) {
                    // all
                    str = HistoryFrame.this.t.getText();
                }

                final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                clipboard.setContents(new StringSelection(str), null);

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

    @Override
    public void stateChanged(final ChangeEvent e) {
        final StringBuilder b = new StringBuilder();
        for (final String s : this.mobMateWhisp.getHistory()) {
            b.append(s);
            b.append("\n");
        }
        this.t.setText(b.toString());

    }
}
