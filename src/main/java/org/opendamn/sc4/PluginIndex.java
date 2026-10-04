package org.opendamn.sc4;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.opendamn.dbpf.DbpfFile;
import org.opendamn.dbpf.Exemplar;
import org.opendamn.dbpf.Property;
import org.opendamn.dbpf.Qfs;
import org.opendamn.dbpf.Tgi;

/**
 * Index of every exemplar, cohort, PNG and LTEXT in the game data and plugin folders.
 * Built in parallel and cached on disk; only changed files are re-read.
 */
public final class PluginIndex {

    /** Progress callback: (done, total, current file). */
    public interface Progress {
        void update(int done, int total, Path current);

        default boolean cancelled() {
            return false;
        }
    }

    public static final Set<String> EXTENSIONS = Set.of(".dat", ".sc4lot", ".sc4desc", ".sc4model");
    private static final int CACHE_VERSION = 4;

    private final List<IndexedFile> files;
    private final Map<Tgi, List<Located>> byTgi;
    private final Map<Integer, List<Located>> exemplarsByInstance;
    private final Map<Path, IndexedFile> byPath;
    private final List<String> errors;

    /** A resource together with the file containing it. */
    public record Located(IndexedFile file, IndexedFile.Res res) {
        public Tgi tgi() { return res.tgi(); }
    }

    private PluginIndex(List<IndexedFile> files, List<String> errors) {
        this.files = Collections.unmodifiableList(files);
        this.errors = errors;
        Map<Tgi, List<Located>> m = new HashMap<>();
        Map<Integer, List<Located>> ex = new HashMap<>();
        Map<Path, IndexedFile> bp = new HashMap<>();
        for (IndexedFile f : files) {
            bp.put(f.path(), f);
            for (IndexedFile.Res r : f.resources()) {
                Located l = new Located(f, r);
                m.computeIfAbsent(r.tgi(), k -> new ArrayList<>(1)).add(l);
                if (r.tgi().type() == Tgi.TYPE_EXEMPLAR) {
                    ex.computeIfAbsent(r.tgi().instance(), k -> new ArrayList<>(1)).add(l);
                }
            }
        }
        this.byTgi = m;
        this.exemplarsByInstance = ex;
        this.byPath = bp;
    }

    public static PluginIndex empty() {
        return new PluginIndex(List.of(), List.of());
    }

    public List<IndexedFile> files() { return files; }
    public List<String> errors() { return errors; }
    public IndexedFile file(Path p) { return byPath.get(p); }

    /** All occurrences of a resource in load order. */
    public List<Located> all(Tgi tgi) {
        return byTgi.getOrDefault(tgi, List.of());
    }

    /** The occurrence the game would use (last loaded), or null. */
    public Located effective(Tgi tgi) {
        List<Located> l = byTgi.get(tgi);
        return l == null || l.isEmpty() ? null : l.get(l.size() - 1);
    }

    /** Exemplars with the given instance in any group, in load order. */
    public List<Located> exemplarsWithInstance(int instance) {
        return exemplarsByInstance.getOrDefault(instance, List.of());
    }

    /** Finds an LTEXT by exact key, falling back to the same instance in another group. */
    public Located findText(Tgi key) {
        Located l = effective(key);
        if (l != null) return l;
        for (int off = 1; off <= 0x10; off++) {
            l = effective(key.withGroup(key.group() + off));
            if (l != null) return l;
        }
        return null;
    }

    /** Reads and decompresses a located resource. */
    public static byte[] read(Located l) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(l.file().path().toFile(), "r")) {
            byte[] d = new byte[l.res().size()];
            raf.seek(l.res().offset());
            raf.readFully(d);
            return Qfs.decompressIfNeeded(d);
        }
    }

    public static Exemplar readExemplar(Located l) throws IOException {
        return Exemplar.parse(read(l));
    }

    // ------------------------------------------------------------------ building

    /**
     * Builds (or refreshes) the index.
     *
     * @param roots  folders/files in load order (game data first, then plugin folders)
     * @param cache  cache file (may be null)
     */
    public static PluginIndex build(List<Path> roots, Path cache, Progress progress) throws IOException {
        List<Path> paths = new ArrayList<>();
        for (Path r : roots) collectInLoadOrder(r, paths);

        Map<Path, IndexedFile> cached = cache == null ? Map.of() : readCache(cache);
        AtomicInteger done = new AtomicInteger();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        IndexedFile[] result = new IndexedFile[paths.size()];
        Map<Path, Boolean> seen = new ConcurrentHashMap<>();

        java.util.stream.IntStream.range(0, paths.size()).parallel().forEach(k -> {
            if (progress.cancelled()) return;
            Path p = paths.get(k);
            if (seen.putIfAbsent(p, Boolean.TRUE) != null) return;
            try {
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                IndexedFile c = cached.get(p);
                if (c != null && c.size() == a.size() && c.modified() == a.lastModifiedTime().toMillis()) {
                    result[k] = new IndexedFile(p, c.size(), c.modified(), k, c.resources());
                } else {
                    result[k] = scanFile(p, a, k);
                }
            } catch (Exception e) {
                errors.add(p + ": " + e.getMessage());
            }
            int d = done.incrementAndGet();
            if (d % 64 == 0 || d == paths.size()) progress.update(d, paths.size(), p);
        });

        List<IndexedFile> list = new ArrayList<>();
        for (IndexedFile f : result) if (f != null) list.add(f);
        if (cache != null && !progress.cancelled()) writeCache(cache, list);
        return new PluginIndex(list, errors);
    }

    /** Files of a folder tree in the order SimCity 4 loads them. */
    public static void collectInLoadOrder(Path root, List<Path> out) throws IOException {
        if (Files.isRegularFile(root)) {
            if (isPluginFile(root)) out.add(root);
            return;
        }
        if (!Files.isDirectory(root)) return;
        List<Path> dirs = new ArrayList<>();
        List<Path> plain = new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isDirectory(p)) dirs.add(p);
                else if (isPluginFile(p)) plain.add(p);
            }
        } catch (java.nio.file.AccessDeniedException e) {
            return;
        }
        Comparator<Path> byName = Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT));
        plain.sort(byName);
        dirs.sort(byName);
        // Files of a folder load before its subfolders.
        out.addAll(plain);
        for (Path d : dirs) collectInLoadOrder(d, out);
    }

    public static boolean isPluginFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(n.substring(dot));
    }

    static IndexedFile scanFile(Path p, BasicFileAttributes a, int order) throws IOException {
        if (!DbpfFile.isDbpf(p)) return new IndexedFile(p, a.size(), a.lastModifiedTime().toMillis(), order, new IndexedFile.Res[0]);
        DbpfFile db = DbpfFile.open(p);
        List<IndexedFile.Res> res = new ArrayList<>();
        try (var rd = db.reader()) {
            for (DbpfFile.Entry e : db.entries()) {
                int t = e.tgi().type();
                if (t == Tgi.TYPE_EXEMPLAR || t == Tgi.TYPE_COHORT) {
                    res.add(describeExemplar(rd, e));
                } else if (t == Tgi.TYPE_PNG || t == Tgi.TYPE_LTEXT || t == Tgi.TYPE_LUA || t == Tgi.TYPE_RUL) {
                    res.add(new IndexedFile.Res(e.tgi(), e.offset(), e.size(), -1, null, null, 0));
                }
            }
        }
        return new IndexedFile(p, a.size(), a.lastModifiedTime().toMillis(), order, res.toArray(IndexedFile.Res[]::new));
    }

    private static IndexedFile.Res describeExemplar(DbpfFile.Reader rd, DbpfFile.Entry e) {
        int type = -1;
        Tgi parent = null;
        int[] buildings = null;
        int flags = 0;
        try {
            byte[] d = rd.read(e);
            if (Exemplar.looksLikeExemplar(d)) {
                Exemplar ex = Exemplar.parse(d);
                type = ex.exemplarType();
                if (ex.parent().type() != 0 || ex.parent().instance() != 0) parent = ex.parent();
                if (ex.has(Exemplar.P_LOT_RESOURCE_KEY)) flags |= IndexedFile.Res.F_HAS_LOT_RESOURCE_KEY;
                if (ex.has(Exemplar.P_ITEM_BUTTON_ID)) flags |= IndexedFile.Res.F_HAS_ITEM_BUTTON_ID;
                if (ex.has(Exemplar.P_ITEM_ICON)) flags |= IndexedFile.Res.F_HAS_ITEM_ICON;
                if (type == Exemplar.TYPE_LOT_CONFIG) {
                    buildings = lotBuildings(ex);
                    Property z = ex.get(Exemplar.P_LOT_CONFIG_ZONE_TYPES);
                    if (z != null) {
                        for (long v : z.values()) if (v == 0x0F) flags |= IndexedFile.Res.F_PLOPPED_ZONE;
                    }
                }
            }
        } catch (Exception ex) {
            type = -2; // unreadable
        }
        return new IndexedFile.Res(e.tgi(), e.offset(), e.size(), type, parent, buildings, flags);
    }

    /** Building instance IDs referenced by a lot configuration's lot objects. */
    public static int[] lotBuildings(Exemplar lot) {
        List<Integer> ids = new ArrayList<>();
        for (Property p : lot.properties()) {
            int id = p.id();
            if (Integer.compareUnsigned(id, Exemplar.P_LOT_CONFIG_OBJECT) < 0
                    || Integer.compareUnsigned(id, Exemplar.P_LOT_CONFIG_OBJECT_LAST) > 0) continue;
            long[] v = p.values();
            if (v.length >= 13 && v[0] == 0) {
                for (int k = 12; k < v.length; k++) ids.add((int) v[k]);
            }
        }
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    // ------------------------------------------------------------------ cache

    private static Map<Path, IndexedFile> readCache(Path cache) {
        Map<Path, IndexedFile> m = new HashMap<>();
        if (!Files.exists(cache)) return m;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(cache)), 1 << 16))) {
            if (in.readInt() != CACHE_VERSION) return m;
            int n = in.readInt();
            for (int k = 0; k < n; k++) {
                Path p = Path.of(in.readUTF());
                long size = in.readLong();
                long mod = in.readLong();
                int rc = in.readInt();
                IndexedFile.Res[] rs = new IndexedFile.Res[rc];
                for (int r = 0; r < rc; r++) {
                    Tgi tgi = new Tgi(in.readInt(), in.readInt(), in.readInt());
                    long off = in.readLong();
                    int sz = in.readInt();
                    int exType = in.readInt();
                    Tgi parent = in.readBoolean() ? new Tgi(in.readInt(), in.readInt(), in.readInt()) : null;
                    int bc = in.readInt();
                    int[] b = null;
                    if (bc >= 0) {
                        b = new int[bc];
                        for (int i = 0; i < bc; i++) b[i] = in.readInt();
                    }
                    int flags = in.readInt();
                    rs[r] = new IndexedFile.Res(tgi, off, sz, exType, parent, b, flags);
                }
                m.put(p, new IndexedFile(p, size, mod, 0, rs));
            }
        } catch (IOException | RuntimeException e) {
            return new HashMap<>(); // corrupt cache: rebuild
        }
        return m;
    }

    private static void writeCache(Path cache, List<IndexedFile> files) {
        try {
            Files.createDirectories(cache.toAbsolutePath().getParent());
            Path tmp = cache.resolveSibling(cache.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                    new GZIPOutputStream(Files.newOutputStream(tmp)), 1 << 16))) {
                out.writeInt(CACHE_VERSION);
                out.writeInt(files.size());
                for (IndexedFile f : files) {
                    out.writeUTF(f.path().toString());
                    out.writeLong(f.size());
                    out.writeLong(f.modified());
                    out.writeInt(f.resources().length);
                    for (IndexedFile.Res r : f.resources()) {
                        out.writeInt(r.tgi().type());
                        out.writeInt(r.tgi().group());
                        out.writeInt(r.tgi().instance());
                        out.writeLong(r.offset());
                        out.writeInt(r.size());
                        out.writeInt(r.exType());
                        out.writeBoolean(r.parent() != null);
                        if (r.parent() != null) {
                            out.writeInt(r.parent().type());
                            out.writeInt(r.parent().group());
                            out.writeInt(r.parent().instance());
                        }
                        out.writeInt(r.buildings() == null ? -1 : r.buildings().length);
                        if (r.buildings() != null) for (int b : r.buildings()) out.writeInt(b);
                        out.writeInt(r.flags());
                    }
                }
            }
            Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // The cache is an optimisation only.
        }
    }
}
