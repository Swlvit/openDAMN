package org.opendamn.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Modal progress dialog that only appears if the work takes longer than a moment. */
public final class ProgressDialog extends JDialog {

    /** Thread-safe progress reporting from background work. */
    public final class Handle {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        public void update(int done, int total, String text) {
            SwingUtilities.invokeLater(() -> {
                if (total > 0) {
                    bar.setIndeterminate(false);
                    bar.setMaximum(total);
                    bar.setValue(done);
                    bar.setString(done + " / " + total);
                    bar.setStringPainted(true);
                }
                if (text != null) label.setText(shorten(text));
            });
        }

        public void message(String text) {
            update(0, 0, text);
        }

        public boolean cancelled() {
            return cancelled.get();
        }
    }

    private final JLabel label = new JLabel(" ");
    private final JProgressBar bar = new JProgressBar();
    private final Handle handle = new Handle();
    private Timer timer;

    ProgressDialog(Window owner, String title) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        label.setPreferredSize(new Dimension(460, 20));
        bar.setIndeterminate(true);
        p.add(label, BorderLayout.NORTH);
        p.add(bar, BorderLayout.CENTER);
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> handle.cancelled.set(true));
        JPanel south = new JPanel();
        south.add(cancel);
        p.add(south, BorderLayout.SOUTH);
        setContentPane(p);
        pack();
        setLocationRelativeTo(owner);
    }

    Handle handle() {
        return handle;
    }

    /** Shows the dialog after a short delay unless the work finished already. */
    void showLater(Future<?> work) {
        timer = new Timer(400, e -> {
            if (!work.isDone() && !isDisplayable()) return;
            if (!work.isDone()) setVisible(true);
        });
        timer.setRepeats(false);
        timer.start();
    }

    @Override
    public void dispose() {
        if (timer != null) timer.stop();
        super.dispose();
    }

    private static String shorten(String s) {
        return s.length() > 90 ? "…" + s.substring(s.length() - 89) : s;
    }
}
