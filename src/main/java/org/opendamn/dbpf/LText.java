package org.opendamn.dbpf;

import java.nio.charset.StandardCharsets;

/** LTEXT resources: UTF-16LE strings with a 4-byte header (uint24 length + 0x10 marker). */
public final class LText {

    private LText() {}

    public static String decode(byte[] d) {
        if (d.length >= 4 && (d[3] & 0xFF) == 0x10) {
            int chars = (d[0] & 0xFF) | ((d[1] & 0xFF) << 8) | ((d[2] & 0xFF) << 16);
            int bytes = Math.min(chars * 2, d.length - 4);
            return new String(d, 4, bytes & ~1, StandardCharsets.UTF_16LE);
        }
        // Some tools write plain 8-bit text.
        return new String(d, StandardCharsets.ISO_8859_1);
    }

    public static byte[] encode(String s) {
        byte[] body = s.getBytes(StandardCharsets.UTF_16LE);
        int chars = body.length / 2;
        byte[] d = new byte[4 + body.length];
        d[0] = (byte) chars;
        d[1] = (byte) (chars >> 8);
        d[2] = (byte) (chars >> 16);
        d[3] = 0x10;
        System.arraycopy(body, 0, d, 4, body.length);
        return d;
    }
}
