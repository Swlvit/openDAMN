package org.opendamn.dbpf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a DBPF 1.0 package (index version 7.0) as used by SimCity 4.
 * Files are written to a temporary file first and then moved into place, so a crash
 * never leaves a half-written plugin behind.
 */
public final class DbpfWriter {

    private record Item(Tgi tgi, byte[] stored, int rawSize, boolean compressed) {}

    private final Map<Tgi, Item> items = new LinkedHashMap<>();

    /** Adds (or replaces) an entry, stored uncompressed. */
    public DbpfWriter add(Tgi tgi, byte[] data) {
        return add(tgi, data, false);
    }

    /**
     * Adds (or replaces) an entry. When {@code compress} is true the data is QFS compressed
     * if that makes it smaller. Already-compressed data is stored as is.
     */
    public DbpfWriter add(Tgi tgi, byte[] data, boolean compress) {
        if (tgi.type() == Tgi.TYPE_DIR) return this; // regenerated on write
        if (Qfs.isCompressed(data)) {
            items.put(tgi, new Item(tgi, data, Qfs.declaredSize(data), true));
        } else if (compress) {
            byte[] c = Qfs.compress(data);
            items.put(tgi, c != null ? new Item(tgi, c, data.length, true) : new Item(tgi, data, data.length, false));
        } else {
            items.put(tgi, new Item(tgi, data, data.length, false));
        }
        return this;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int size() {
        return items.size();
    }

    public void write(Path target) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".opendamn-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                writeTo(out);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public byte[] toBytes() {
        try {
            var bos = new java.io.ByteArrayOutputStream();
            writeTo(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void writeTo(OutputStream out) throws IOException {
        List<Item> list = new ArrayList<>(items.values());
        List<Item> compressed = list.stream().filter(Item::compressed).toList();
        if (!compressed.isEmpty()) {
            ByteBuffer d = ByteBuffer.allocate(compressed.size() * 16).order(ByteOrder.LITTLE_ENDIAN);
            for (Item it : compressed) {
                d.putInt(it.tgi().type()).putInt(it.tgi().group()).putInt(it.tgi().instance()).putInt(it.rawSize());
            }
            list.add(new Item(Tgi.DIRECTORY, d.array(), d.capacity(), false));
        }

        long offset = 96;
        long[] offsets = new long[list.size()];
        for (int k = 0; k < list.size(); k++) {
            offsets[k] = offset;
            offset += list.get(k).stored().length;
        }
        if (offset + 20L * list.size() > 0xFFFFFFFFL) throw new IOException("DBPF file would exceed 4 GB");

        int now = (int) (System.currentTimeMillis() / 1000L);
        ByteBuffer h = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN);
        h.put(new byte[] {'D', 'B', 'P', 'F'});
        h.putInt(1).putInt(0);          // version 1.0
        h.putInt(0).putInt(0).putInt(0); // unused
        h.putInt(now).putInt(now);      // created / modified
        h.putInt(7);                    // index major version
        h.putInt(list.size());
        h.putInt((int) offset);
        h.putInt(20 * list.size());
        h.putInt(0).putInt(0).putInt(0); // holes
        h.putInt(0);                    // index minor version
        out.write(h.array());

        for (Item it : list) out.write(it.stored());

        ByteBuffer idx = ByteBuffer.allocate(20 * list.size()).order(ByteOrder.LITTLE_ENDIAN);
        for (int k = 0; k < list.size(); k++) {
            Item it = list.get(k);
            idx.putInt(it.tgi().type()).putInt(it.tgi().group()).putInt(it.tgi().instance());
            idx.putInt((int) offsets[k]).putInt(it.stored().length);
        }
        out.write(idx.array());
    }
}
