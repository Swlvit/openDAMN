package org.opendamn.sc4;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.opendamn.damn.MenuItem;
import org.opendamn.damn.MenuNode;
import org.opendamn.dbpf.DbpfWriter;
import org.opendamn.dbpf.Exemplar;
import org.opendamn.dbpf.Tgi;

/**
 * Hides lots from the game's own menus by overriding their exemplars with copies that lack
 * the menu button property ({@code LotResourceKey} for buildings, {@code Item Button ID} for
 * flora). The copies live in a folder that loads after all other plugins.
 *
 * <p>Unlike the original tools, the folder is regenerated from the current original
 * exemplars on every save: each lot has exactly one copy, copies never go stale, and copies
 * of lots that are no longer installed are dropped.
 */
public final class HiddenLots {

    public enum SaveFormat { ORIGINAL, BINARY, TEXT }

    public record Result(int hidden, int filesWritten, int filesRemoved, List<String> problems) {}

    private HiddenLots() {}

    /** True for files this tool (or DAMN Manager) generated in the hidden folder. */
    public static boolean isManagedFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.startsWith("damn_") && n.endsWith(".dat");
    }

    /** DAMN Manager compatible file name for a menu, e.g. {@code DAMN_Root_Civic_Education.dat}. */
    public static String fileNameFor(MenuNode menu) {
        StringBuilder sb = new StringBuilder("DAMN_Root");
        for (String s : menu.segments()) sb.append('_').append(s);
        return sb.append(".dat").toString();
    }

    /**
     * Rewrites the hidden folder.
     *
     * @param menus      all menus in tree order, with items loaded
     * @param index      plugin index (used to find the original exemplars)
     * @param hiddenDir  the hidden lots folder (must load after all other plugins)
     */
    public static Result rebuild(List<MenuNode> menus, PluginIndex index, Path hiddenDir, SaveFormat format)
            throws IOException {
        return rebuildInto(menus, index, hiddenDir, hiddenDir, format);
    }

    /**
     * Like {@link #rebuild} but writes into {@code outDir}; {@code hiddenDir} is only used to
     * recognise (and skip) existing copies when looking for the original exemplars.
     */
    public static Result rebuildInto(List<MenuNode> menus, PluginIndex index, Path hiddenDir, Path outDir,
                                     SaveFormat format) throws IOException {
        List<String> problems = new ArrayList<>();
        Path hiddenAbs = hiddenDir.toAbsolutePath().normalize();

        // Each hidden lot goes into the file of the first menu that contains it.
        Map<String, Map<Tgi, MenuItem>> perFile = new LinkedHashMap<>();
        Map<String, String> fileMenu = new HashMap<>();
        Set<Tgi> assigned = new HashSet<>();
        for (MenuNode n : menus) {
            for (MenuItem it : n.items()) {
                if (!it.hidden()) continue;
                if (it.kind() == MenuItem.Kind.NETWORK || it.kind() == MenuItem.Kind.ZONE) continue;
                if (assigned.add(it.exemplarTgi())) {
                    perFile.computeIfAbsent(fileNameFor(n), k -> new LinkedHashMap<>()).put(it.exemplarTgi(), it);
                    fileMenu.putIfAbsent(fileNameFor(n), n.displayPath());
                }
            }
        }

        Map<String, DbpfWriter> out = new LinkedHashMap<>();
        int hidden = 0;
        for (var e : perFile.entrySet()) {
            DbpfWriter w = new DbpfWriter();
            for (var item : e.getValue().entrySet()) {
                PluginIndex.Located orig = originalOf(index, item.getKey(), hiddenAbs);
                if (orig == null) {
                    problems.add("Not installed, cannot hide: " + item.getValue().linkName() + "  [" + item.getKey()
                            + "]  in menu " + fileMenu.get(e.getKey()));
                    continue;
                }
                Exemplar ex;
                try {
                    ex = PluginIndex.readExemplar(orig);
                } catch (RuntimeException bad) {
                    problems.add("Cannot read exemplar of " + item.getValue().linkName() + " in "
                            + orig.file().path().getFileName() + ": " + bad.getMessage());
                    continue;
                }
                boolean removed = ex.remove(Exemplar.P_LOT_RESOURCE_KEY) != null;
                removed |= ex.remove(Exemplar.P_ITEM_BUTTON_ID) != null;
                if (!removed) {
                    // Nothing to remove: the lot is not in the game menus anyway (or the
                    // property comes from a parent cohort, which we must not touch).
                    continue;
                }
                switch (format) {
                    case BINARY -> ex.setFormat(Exemplar.Format.BINARY);
                    case TEXT -> ex.setFormat(Exemplar.Format.TEXT);
                    case ORIGINAL -> { }
                }
                w.add(item.getKey(), ex.encode(), false);
                hidden++;
            }
            if (!w.isEmpty()) out.put(e.getKey(), w);
        }

        Files.createDirectories(outDir);
        int written = 0;
        for (var e : out.entrySet()) {
            e.getValue().write(outDir.resolve(e.getKey()));
            written++;
        }
        int removedFiles = 0;
        Set<String> keep = new HashSet<>();
        for (String k : out.keySet()) keep.add(k.toLowerCase(Locale.ROOT));
        try (Stream<Path> s = Files.list(outDir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(p) && isManagedFile(p)
                        && !keep.contains(p.getFileName().toString().toLowerCase(Locale.ROOT))) {
                    Files.delete(p);
                    removedFiles++;
                }
            }
        }
        return new Result(hidden, written, removedFiles, problems);
    }

    /** The exemplar the game would use if the hidden folder did not exist. */
    public static PluginIndex.Located originalOf(PluginIndex index, Tgi tgi, Path hiddenAbs) {
        List<PluginIndex.Located> all = index.all(tgi);
        for (int k = all.size() - 1; k >= 0; k--) {
            PluginIndex.Located l = all.get(k);
            if (!l.file().path().toAbsolutePath().normalize().startsWith(hiddenAbs)) return l;
        }
        return null;
    }

    /**
     * Checks that the hidden folder really loads last: returns plugin files that load after
     * it and override an exemplar it hides.
     */
    public static List<String> checkLoadOrder(PluginIndex index, Path hiddenDir) {
        Path hiddenAbs = hiddenDir.toAbsolutePath().normalize();
        List<String> issues = new ArrayList<>();
        Map<Tgi, Integer> hiddenOrder = new HashMap<>();
        for (IndexedFile f : index.files()) {
            if (!f.path().toAbsolutePath().normalize().startsWith(hiddenAbs)) continue;
            for (IndexedFile.Res r : f.resources()) {
                if (r.tgi().type() == Tgi.TYPE_EXEMPLAR) hiddenOrder.merge(r.tgi(), f.order(), Math::max);
            }
        }
        for (var e : hiddenOrder.entrySet()) {
            PluginIndex.Located eff = index.effective(e.getKey());
            if (eff != null && eff.file().order() > e.getValue()) {
                issues.add(e.getKey() + " is overridden by " + eff.file().path());
            }
        }
        return issues;
    }
}
