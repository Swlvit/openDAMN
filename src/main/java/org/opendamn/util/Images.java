package org.opendamn.util;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.opendamn.dbpf.Qfs;

/** PNG helpers for menu icons and preview images. */
public final class Images {

    public static final int ICON = 44;
    /** Standard SC4 menu icon strips are 4 states of 44x44 side by side. */
    public static final int ICON_STRIP = 176;

    private Images() {}

    public static BufferedImage decode(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            byte[] d = Qfs.isCompressed(data) ? Qfs.decompress(data) : data;
            return ImageIO.read(new ByteArrayInputStream(d));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static byte[] encodePng(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Turns any image into a 44x44 menu icon. Icon strips (176 wide) are cut to their
     * last ("enabled") state; larger images are scaled down keeping the aspect ratio.
     */
    public static BufferedImage toMenuIcon(BufferedImage src) {
        if (src == null) return null;
        BufferedImage img = src;
        if (img.getWidth() == ICON_STRIP && img.getHeight() >= ICON) {
            img = img.getSubimage(ICON * 3, 0, ICON, ICON);
        }
        if (img.getWidth() == ICON && img.getHeight() == ICON) return toArgb(img);
        double s = Math.min((double) ICON / img.getWidth(), (double) ICON / img.getHeight());
        int w = Math.max(1, (int) Math.round(img.getWidth() * s));
        int h = Math.max(1, (int) Math.round(img.getHeight() * s));
        BufferedImage out = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, (ICON - w) / 2, (ICON - h) / 2, w, h, null);
        g.dispose();
        return out;
    }

    /** Normalises icon PNG bytes to a proper 44x44 PNG (fixes odd PNGs the game rejects). */
    public static byte[] normalizeIconPng(byte[] data) {
        BufferedImage img = decode(data);
        if (img == null) return null;
        return encodePng(toMenuIcon(img));
    }

    public static byte[] loadIconFile(Path p) throws IOException {
        BufferedImage img = ImageIO.read(p.toFile());
        if (img == null) throw new IOException("Unsupported image format: " + p.getFileName());
        return encodePng(toMenuIcon(img));
    }

    /** Loads an image file as PNG bytes, scaled down to at most {@code max} pixels. */
    public static byte[] loadPreviewFile(Path p, int max) throws IOException {
        BufferedImage img = ImageIO.read(p.toFile());
        if (img == null) throw new IOException("Unsupported image format: " + p.getFileName());
        if (img.getWidth() > max || img.getHeight() > max) img = scale(img, max);
        return encodePng(toArgb(img));
    }

    public static byte[] readFile(Path p) throws IOException {
        return Files.readAllBytes(p);
    }

    public static BufferedImage scale(BufferedImage img, int max) {
        double s = Math.min((double) max / img.getWidth(), (double) max / img.getHeight());
        int w = Math.max(1, (int) Math.round(img.getWidth() * s));
        int h = Math.max(1, (int) Math.round(img.getHeight() * s));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(img.getScaledInstance(w, h, Image.SCALE_SMOOTH), 0, 0, null);
        g.dispose();
        return out;
    }

    private static BufferedImage toArgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_ARGB) return img;
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    /** A "No icon" placeholder with the first letters of the name. */
    public static byte[] placeholderIcon(String name) {
        BufferedImage img = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x5B7FA6));
        g.fillRoundRect(1, 1, ICON - 2, ICON - 2, 10, 10);
        g.setColor(new Color(0x2E4A6B));
        g.drawRoundRect(1, 1, ICON - 3, ICON - 3, 10, 10);
        g.setColor(Color.WHITE);
        String label = initials(name);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, label.length() > 2 ? 12 : 16));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(label, (ICON - fm.stringWidth(label)) / 2, (ICON + fm.getAscent() - fm.getDescent()) / 2);
        g.dispose();
        return encodePng(img);
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        StringBuilder sb = new StringBuilder();
        for (String w : name.trim().split("[\\s_\\-]+")) {
            if (!w.isEmpty() && Character.isLetterOrDigit(w.charAt(0))) sb.append(Character.toUpperCase(w.charAt(0)));
            if (sb.length() == 3) break;
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }
}
