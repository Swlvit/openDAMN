package org.opendamn.ui;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;

/** Displays an image with a zoom popup (25%–400%). */
final class ImageView extends JComponent {

    private BufferedImage image;
    private double zoom = 1.0;
    private final ButtonGroup group = new ButtonGroup();
    private final JPopupMenu popup = new JPopupMenu();

    ImageView() {
        for (int z : new int[] {25, 50, 100, 150, 200, 300, 400}) {
            JRadioButtonMenuItem mi = new JRadioButtonMenuItem(z + "%", z == 100);
            mi.addActionListener(e -> setZoom(z / 100.0));
            group.add(mi);
            popup.add(mi);
        }
        setComponentPopupMenu(popup);
    }

    /** Adds extra popup entries (e.g. change/remove image) below the zoom options. */
    JPopupMenu popup() {
        return popup;
    }

    void setImage(BufferedImage img, boolean resetZoom) {
        image = img;
        if (resetZoom) setZoom(1.0);
        else {
            revalidate();
            repaint();
        }
    }

    BufferedImage image() {
        return image;
    }

    void setZoom(double z) {
        zoom = z;
        for (var e = group.getElements(); e.hasMoreElements();) {
            var b = e.nextElement();
            b.setSelected(b.getText().equals(Math.round(z * 100) + "%"));
        }
        revalidate();
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        if (image == null) return new Dimension(64, 64);
        return new Dimension((int) (image.getWidth() * zoom) + 4, (int) (image.getHeight() * zoom) + 4);
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (image == null) {
            g.setColor(getForeground());
            g.drawString("No image", 6, 20);
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                zoom < 1 ? RenderingHints.VALUE_INTERPOLATION_BICUBIC : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.drawImage(image, 2, 2, (int) (image.getWidth() * zoom), (int) (image.getHeight() * zoom), null);
        g2.dispose();
    }
}
