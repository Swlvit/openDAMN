package org.opendamn.dbpf;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * QFS / RefPack compression as used by SimCity 4 DBPF files.
 *
 * <p>Layout: uint32 LE compressed size, magic 0x10FB, uint24 BE uncompressed size, then opcodes.
 */
public final class Qfs {

    private static final int MAX_UNCOMPRESSED = 0xFFFFFF;
    private static final int WINDOW = 131072;
    private static final int MAX_CHAIN = 128;

    private Qfs() {}

    public static boolean isCompressed(byte[] data) {
        return data.length >= 9 && (data[4] & 0xFF) == 0x10 && (data[5] & 0xFF) == 0xFB;
    }

    /** Uncompressed size declared in a QFS header. */
    public static int declaredSize(byte[] data) {
        return ((data[6] & 0xFF) << 16) | ((data[7] & 0xFF) << 8) | (data[8] & 0xFF);
    }

    /** Decompresses data when it carries a QFS header, otherwise returns it unchanged. */
    public static byte[] decompressIfNeeded(byte[] data) {
        return isCompressed(data) ? decompress(data) : data;
    }

    public static byte[] decompress(byte[] in) {
        if (!isCompressed(in)) throw new DbpfException("Data is not QFS compressed");
        int outLen = declaredSize(in);
        byte[] out = new byte[outLen];
        int ip = 9;
        int op = 0;
        try {
            while (ip < in.length) {
                int c0 = in[ip++] & 0xFF;
                int literal;
                int copyLen;
                int copyOff;
                if (c0 < 0x80) {
                    int c1 = in[ip++] & 0xFF;
                    literal = c0 & 0x03;
                    copyLen = ((c0 & 0x1C) >> 2) + 3;
                    copyOff = ((c0 & 0x60) << 3) + c1 + 1;
                } else if (c0 < 0xC0) {
                    int c1 = in[ip++] & 0xFF;
                    int c2 = in[ip++] & 0xFF;
                    literal = (c1 >> 6) & 0x03;
                    copyLen = (c0 & 0x3F) + 4;
                    copyOff = ((c1 & 0x3F) << 8) + c2 + 1;
                } else if (c0 < 0xE0) {
                    int c1 = in[ip++] & 0xFF;
                    int c2 = in[ip++] & 0xFF;
                    int c3 = in[ip++] & 0xFF;
                    literal = c0 & 0x03;
                    copyLen = ((c0 & 0x0C) << 6) + c3 + 5;
                    copyOff = ((c0 & 0x10) << 12) + (c1 << 8) + c2 + 1;
                } else if (c0 < 0xFC) {
                    literal = ((c0 & 0x1F) << 2) + 4;
                    System.arraycopy(in, ip, out, op, literal);
                    ip += literal;
                    op += literal;
                    continue;
                } else {
                    literal = c0 & 0x03;
                    System.arraycopy(in, ip, out, op, literal);
                    op += literal;
                    break;
                }
                System.arraycopy(in, ip, out, op, literal);
                ip += literal;
                op += literal;
                int src = op - copyOff;
                if (src < 0) throw new DbpfException("Corrupt QFS stream (bad back-reference)");
                // Overlapping copies are legal and must be done byte by byte.
                for (int k = 0; k < copyLen; k++) out[op++] = out[src + k];
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new DbpfException("Corrupt QFS stream", e);
        }
        return op == outLen ? out : Arrays.copyOf(out, op);
    }

    /**
     * Compresses data. Returns {@code null} when compression would not make the data smaller
     * or the data is too large for the 24-bit size field; callers should then store it raw.
     */
    public static byte[] compress(byte[] in) {
        int n = in.length;
        if (n < 16 || n > MAX_UNCOMPRESSED) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(n / 2 + 16);
        for (int k = 0; k < 9; k++) out.write(0);

        int hashSize = 1 << 16;
        int[] head = new int[hashSize];
        Arrays.fill(head, -1);
        int[] prev = new int[n];

        int pos = 0;
        int litStart = 0;
        while (pos < n) {
            int bestLen = 0;
            int bestOff = 0;
            if (pos + 3 <= n) {
                int h = hash(in, pos);
                int cand = head[h];
                int chain = 0;
                while (cand >= 0 && pos - cand <= WINDOW && chain++ < MAX_CHAIN) {
                    int len = 0;
                    int max = Math.min(1028, n - pos);
                    while (len < max && in[cand + len] == in[pos + len]) len++;
                    if (len > bestLen) {
                        int off = pos - cand;
                        if (usable(len, off)) {
                            bestLen = len;
                            bestOff = off;
                            if (len == max) break;
                        }
                    }
                    cand = prev[cand];
                }
                prev[pos] = head[h];
                head[h] = pos;
            }

            if (bestLen < 3) {
                pos++;
                continue;
            }

            // Flush pending literals in blocks of 4..112, keep 0..3 to attach to the copy opcode.
            while (pos - litStart >= 4) {
                int run = Math.min((pos - litStart) & ~3, 112);
                out.write(0xE0 + (run >> 2) - 1);
                out.write(in, litStart, run);
                litStart += run;
            }
            int lit = pos - litStart;
            int off = bestOff - 1;
            int len = bestLen;
            if (len <= 10 && off < 1024) {
                out.write(((off >> 3) & 0x60) | ((len - 3) << 2) | lit);
                out.write(off & 0xFF);
            } else if (len <= 67 && off < 16384) {
                out.write(0x80 | (len - 4));
                out.write((lit << 6) | (off >> 8));
                out.write(off & 0xFF);
            } else {
                out.write(0xC0 | ((off >> 12) & 0x10) | (((len - 5) >> 6) & 0x0C) | lit);
                out.write((off >> 8) & 0xFF);
                out.write(off & 0xFF);
                out.write((len - 5) & 0xFF);
            }
            out.write(in, litStart, lit);

            // Insert the matched positions into the hash chains.
            int end = pos + bestLen;
            for (int p = pos + 1; p < end && p + 3 <= n; p++) {
                int h = hash(in, p);
                prev[p] = head[h];
                head[h] = p;
            }
            pos = end;
            litStart = pos;
        }

        while (n - litStart >= 4) {
            int run = Math.min((n - litStart) & ~3, 112);
            out.write(0xE0 + (run >> 2) - 1);
            out.write(in, litStart, run);
            litStart += run;
        }
        int rest = n - litStart;
        out.write(0xFC + rest);
        out.write(in, litStart, rest);

        byte[] res = out.toByteArray();
        if (res.length >= n) return null;
        int size = res.length;
        res[0] = (byte) size;
        res[1] = (byte) (size >> 8);
        res[2] = (byte) (size >> 16);
        res[3] = (byte) (size >> 24);
        res[4] = 0x10;
        res[5] = (byte) 0xFB;
        res[6] = (byte) (n >> 16);
        res[7] = (byte) (n >> 8);
        res[8] = (byte) n;
        return res;
    }

    private static boolean usable(int len, int off) {
        if (off > WINDOW) return false;
        if (len == 3) return off <= 1024;
        if (len == 4) return off <= 16384;
        return true;
    }

    private static int hash(byte[] b, int p) {
        int v = (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16);
        return (v * 0x9E3779B1) >>> 16;
    }
}
