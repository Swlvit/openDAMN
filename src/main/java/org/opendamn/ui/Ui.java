package org.opendamn.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.opendamn.util.Images;
import org.opendamn.util.Log;

/** Small Swing helpers shared by the panels. */
public final class Ui {

    private static final Map<byte[], ImageIcon> ICONS = new WeakHashMap<>();
    private static ImageIcon blank;

    private Ui() {}

    /** A 44x44 icon from PNG bytes (cached by array identity). */
    public static Icon icon(byte[] png) {
        if (png == null) return blankIcon();
        synchronized (ICONS) {
            ImageIcon i = ICONS.get(png);
            if (i == null) {
                BufferedImage img = Images.decode(png);
                if (img == null) return blankIcon();
                if (img.getWidth() != Images.ICON || img.getHeight() != Images.ICON) img = Images.toMenuIcon(img);
                i = new ImageIcon(img);
                ICONS.put(png, i);
            }
            return i;
        }
    }

    public static Icon blankIcon() {
        if (blank == null) {
            BufferedImage img = new BufferedImage(Images.ICON, Images.ICON, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x999999));
            g.drawRoundRect(2, 2, Images.ICON - 5, Images.ICON - 5, 8, 8);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
            g.drawString("No icon", 5, 26);
            g.dispose();
            blank = new ImageIcon(img);
        }
        return blank;
    }

    public static Action action(String name, String tooltip, KeyStroke key, ActionListener l) {
        Action a = new AbstractAction(name) {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                l.actionPerformed(e);
            }
        };
        if (tooltip != null) a.putValue(Action.SHORT_DESCRIPTION, tooltip);
        if (key != null) a.putValue(Action.ACCELERATOR_KEY, key);
        return a;
    }

    public static JButton button(String text, String tooltip, ActionListener l) {
        JButton b = new JButton(text);
        if (tooltip != null) b.setToolTipText(tooltip);
        b.addActionListener(l);
        return b;
    }

    public static JComponent titled(JComponent c, String title) {
        c.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(2, 2, 2, 2)));
        return c;
    }

    public static void error(Component parent, String title, String message) {
        Log.warn(title + ": " + message);
        JOptionPane.showMessageDialog(parent, message, title, JOptionPane.ERROR_MESSAGE);
    }

    public static void info(Component parent, String title, String message) {
        JOptionPane.showMessageDialog(parent, message, title, JOptionPane.INFORMATION_MESSAGE);
    }

    public static boolean confirm(Component parent, String title, String message) {
        return JOptionPane.showConfirmDialog(parent, message, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
    }

    /** Shows a long list of lines in a scrollable dialog, with copy and save buttons. */
    public static void showLines(Component parent, String title, String header, java.util.List<String> lines) {
        String text = String.join("\n", lines);
        JTextArea t = new JTextArea(text);
        t.setEditable(false);
        t.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(700, 350));
        javax.swing.JPanel p = new javax.swing.JPanel(new java.awt.BorderLayout(4, 4));
        p.add(new JLabel(header), java.awt.BorderLayout.NORTH);
        p.add(sp);
        Object[] opts = {"Copy to clipboard", "Save as text file…", "Close"};
        while (true) {
            int r = JOptionPane.showOptionDialog(parent, p, title, JOptionPane.DEFAULT_OPTION,
                    JOptionPane.INFORMATION_MESSAGE, null, opts, opts[2]);
            if (r == 0) {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(header + "\n" + text), null);
            } else if (r == 1) {
                javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
                fc.setSelectedFile(new java.io.File(title.replaceAll("[^A-Za-z0-9 _-]", "").trim() + ".txt"));
                if (fc.showSaveDialog(parent) == javax.swing.JFileChooser.APPROVE_OPTION) {
                    try {
                        java.nio.file.Files.writeString(fc.getSelectedFile().toPath(), header + "\n" + text + "\n");
                    } catch (java.io.IOException e) {
                        error(parent, title, e.getMessage());
                    }
                }
            } else {
                return;
            }
        }
    }

    public static Window windowOf(Component c) {
        return c == null ? null : SwingUtilities.getWindowAncestor(c);
    }

    /** Work that may throw; runs off the event thread. */
    public interface Task<T> {
        T run(ProgressDialog.Handle progress) throws Exception;
    }

    /**
     * Runs a task in the background with a modal progress dialog, then hands the result to
     * {@code done} on the event thread. Errors are shown to the user.
     */
    public static <T> void runWithProgress(Component parent, String title, Task<T> task, Consumer<T> done) {
        ProgressDialog dlg = new ProgressDialog(windowOf(parent), title);
        SwingWorker<T, Void> w = new SwingWorker<>() {
            @Override
            protected T doInBackground() throws Exception {
                return task.run(dlg.handle());
            }

            @Override
            protected void done() {
                dlg.dispose();
                try {
                    T r = get();
                    if (done != null) done.accept(r);
                } catch (java.util.concurrent.CancellationException e) {
                    Log.info(title + " cancelled");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    Log.severe(title + " failed", c);
                    error(parent, title, c.getMessage() == null ? c.toString() : c.getMessage());
                }
            }
        };
        w.execute();
        dlg.showLater(w);
    }
}
