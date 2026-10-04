package org.opendamn.dbpf;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read access to a DBPF package. Only the header and index are read on open;
 * resource data is read on demand.
 */
public final class DbpfFile {

    /** One index entry. */
    public record Entry(Tgi tgi, long offset, int size) {}

    private final Path path;
    private final int majorVersion;
    private final int minorVersion;
    private final List<Entry> entries;
    private final Map<Tgi, Entry> byTgi;
    private final Map<Tgi, Integer> compressedSizes;

    private DbpfFile(Path path, int major, int minor, List<Entry> entries, Map<Tgi, Integer> dir) {
        this.path = path;
        this.majorVersion = major;
        this.minorVersion = minor;
        this.entries = Collections.unmodifiableList(entries);
        this.byTgi = new HashMap<>(entries.size() * 2);
        for (Entry e : entries) byTgi.putIfAbsent(e.tgi(), e);
        this.compressedSizes = dir;
    }

    /** Quick check of the magic bytes without parsing the index. */
    public static boolean isDbpf(Path p) {
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
            if (raf.length() < 96) return false;
            byte[] m = new byte[4];
            raf.readFully(m);
            return m[0] == 'D' && m[1] == 'B' && m[2] == 'P' && m[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    public static DbpfFile open(Path p) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
            long fileLen = raf.length();
            if (fileLen < 96) throw new DbpfException("File too small to be DBPF: " + p);
            byte[] hdr = new byte[96];
            raf.readFully(hdr);
            ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
            if (h.getInt(0) != 0x46504244) throw new DbpfException("Not a DBPF file: " + p);
            int major = h.getInt(4);
            int minor = h.getInt(8);
            if (major > 1) throw new DbpfException("Unsupported DBPF version " + major + "." + minor + ": " + p);
            int indexMajor = h.getInt(32);
            int count = h.getInt(36);
            long indexOffset = Integer.toUnsignedLong(h.getInt(40));
            int indexMinor = h.getInt(60);
            int entrySize = (indexMajor == 7 && indexMinor >= 1) ? 24 : 20;
            if (count < 0 || indexOffset + (long) count * entrySize > fileLen) {
                throw new DbpfException("Corrupt DBPF index: " + p);
            }

            byte[] idx = new byte[count * entrySize];
            raf.seek(indexOffset);
            raf.readFully(idx);
            ByteBuffer b = ByteBuffer.wrap(idx).order(ByteOrder.LITTLE_ENDIAN);
            List<Entry> list = new ArrayList<>(count);
            for (int k = 0; k < count; k++) {
                int base = k * entrySize;
                Tgi tgi = new Tgi(b.getInt(base), b.getInt(base + 4), b.getInt(base + 8));
                int instanceHi = entrySize == 24 ? 4 : 0;
                long off = Integer.toUnsignedLong(b.getInt(base + 12 + instanceHi));
                int size = b.getInt(base + 16 + instanceHi);
                if (size < 0 || off + size > fileLen) {
                    throw new DbpfException("Corrupt DBPF entry " + tgi + " in " + p);
                }
                list.add(new Entry(tgi, off, size));
            }

            Map<Tgi, Integer> dir = new HashMap<>();
            for (Entry e : list) {
                if (e.tgi().type() == Tgi.TYPE_DIR) {
                    byte[] d = new byte[e.size()];
                    raf.seek(e.offset());
                    raf.readFully(d);
                    ByteBuffer db = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
                    for (int k = 0; k + 16 <= d.length; k += 16) {
                        dir.put(new Tgi(db.getInt(k), db.getInt(k + 4), db.getInt(k + 8)), db.getInt(k + 12));
                    }
                }
            }
            return new DbpfFile(p, major, minor, list, dir);
        }
    }

    public Path path() { return path; }
    public int majorVersion() { return majorVersion; }
    public int minorVersion() { return minorVersion; }
    public List<Entry> entries() { return entries; }

    public Entry find(Tgi tgi) {
        return byTgi.get(tgi);
    }

    public List<Entry> findByType(int type) {
        List<Entry> r = new ArrayList<>();
        for (Entry e : entries) if (e.tgi().type() == type) r.add(e);
        return r;
    }

    /** True if the directory record lists this entry as compressed. */
    public boolean isListedCompressed(Tgi tgi) {
        return compressedSizes.containsKey(tgi);
    }

    /** Raw bytes of an entry exactly as stored in the file. */
    public byte[] readRaw(Entry e) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            return readRaw(raf, e);
        }
    }

    /** Entry data, decompressed if needed. */
    public byte[] read(Entry e) throws IOException {
        return Qfs.decompressIfNeeded(readRaw(e));
    }

    public byte[] read(Tgi tgi) throws IOException {
        Entry e = find(tgi);
        return e == null ? null : read(e);
    }

    /** Reads several entries using a single open file handle. */
    public Reader reader() throws IOException {
        return new Reader(new RandomAccessFile(path.toFile(), "r"));
    }

    private static byte[] readRaw(RandomAccessFile raf, Entry e) throws IOException {
        byte[] d = new byte[e.size()];
        raf.seek(e.offset());
        raf.readFully(d);
        return d;
    }

    /** Batch reader over a DBPF file. */
    public final class Reader implements AutoCloseable {
        private final RandomAccessFile raf;

        private Reader(RandomAccessFile raf) {
            this.raf = raf;
        }

        public byte[] readRaw(Entry e) throws IOException {
            return DbpfFile.readRaw(raf, e);
        }

        public byte[] read(Entry e) throws IOException {
            return Qfs.decompressIfNeeded(readRaw(e));
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }

    @Override
    public String toString() {
        return path.toString();
    }
}
