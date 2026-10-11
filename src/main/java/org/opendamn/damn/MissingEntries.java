package org.opendamn.damn;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Takes DAMN menu entries whose lot no longer exists out of the menus and keeps them in an
 * archive folder outside the game.
 *
 * <p>The archive is laid out like {@code Plugins/DAMN/Root} and also holds the same tree as a
 * ZIP, so "Import menu from ZIP" on the Root menu puts every entry back where it was. The
 * archive is written and read back before the menus are touched; if the check fails, nothing
 * is removed.
 */
public final class MissingEntries {

    private MissingEntries() {}

    /** What would be removed. */
    public record Plan(Map<MenuNode, List<MenuItem>> remove, List<MenuNode> emptied) {
        public int items() {
            return remove.values().stream().mapToInt(List::size).sum();
        }
    }

    /** What was archived. */
    public record Result(Path folder, int items, int menuFiles) {}

    /**
     * Finds the entries to remove. Networks and zones are never removed: they have no exemplar,
     * so "missing" means nothing for them.
     *
     * <p>A menu is listed as emptied when it loses entries (or sub-menus) and is left with no
     * entries and no sub-menus. Menus that still have sub-menus are kept.
     */
    public static Plan plan(DamnMenus menus, Predicate<MenuItem> missing) throws IOException {
        Map<MenuNode, List<MenuItem>> remove = new LinkedHashMap<>();
        for (MenuNode n : menus.root().descendantsAndSelf()) {
            menus.ensureLoaded(n);
            List<MenuItem> gone = new ArrayList<>();
            for (MenuItem it : n.items()) {
                if (it.kind() == MenuItem.Kind.NETWORK || it.kind() == MenuItem.Kind.ZONE) continue;
                if (missing.test(it)) gone.add(it);
            }
            if (!gone.isEmpty()) remove.put(n, gone);
        }

        // Pre-order reversed puts every sub-menu before its parent.
        List<MenuNode> bottomUp = new ArrayList<>(menus.root().descendantsAndSelf());
        Collections.reverse(bottomUp);
        Set<MenuNode> emptied = new LinkedHashSet<>();
        for (MenuNode n : bottomUp) {
            if (n.isRoot()) continue;
            int left = n.items().size() - remove.getOrDefault(n, List.of()).size();
            boolean childrenGone = n.children().stream().allMatch(emptied::contains);
            boolean lostSomething = remove.containsKey(n) || n.children().stream().anyMatch(emptied::contains);
            if (left == 0 && childrenGone && lostSomething) emptied.add(n);
        }
        return new Plan(remove, new ArrayList<>(emptied));
    }

    /**
     * Writes the archive into a new, timestamped folder below {@code archiveParent} and checks it
     * by reading every file back. Does not change the menus.
     */
    public static Result archive(DamnMenus menus, Plan plan, Path archiveParent,
                                 MenuFileCodec.IconFallback fallback) throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path folder = archiveParent.resolve("OpenDAMN-removed-" + stamp);
        for (int k = 2; Files.exists(folder); k++) folder = archiveParent.resolve("OpenDAMN-removed-" + stamp + "-" + k);
        Path root = folder.resolve("Root");
        Files.createDirectories(root);

        // File names must stay unique across the whole Root tree once restored, and the IDs
        // inside them must not collide with the live menus.
        menus.loadUsedIds();
        Set<String> names = MenuNames.existingDatNames(menus.rootDir());

        List<String> lines = new ArrayList<>();
        lines.add("DAMN menu entries removed by OpenDAMN on " + LocalDateTime.now().withNano(0));
        lines.add("No installed plugin provided these lots.");
        lines.add("To restore: select Root in OpenDAMN, then DAMN > Import menu from ZIP... and choose");
        lines.add("restore-into-Root.zip from this folder.");
        lines.add("");
        lines.add("Menu\tEntry\tExemplar TGI");

        int files = 0;
        for (var e : plan.remove().entrySet()) {
            Path dir = folderFor(root, e.getKey());
            Files.createDirectories(dir);
            MenuFileCodec.write(dir.resolve(MenuNames.newDatName(names)), e.getValue(), menus::nextId, fallback);
            files++;
            for (MenuItem it : e.getValue()) {
                lines.add(e.getKey().displayPath() + "\t" + it.linkName() + "\t" + it.exemplarTgi());
            }
        }
        for (MenuNode n : plan.emptied()) {
            Files.createDirectories(folderFor(root, n));
        }
        if (!plan.emptied().isEmpty()) {
            lines.add("");
            lines.add("Menus removed because nothing was left in them:");
            for (MenuNode n : plan.emptied()) lines.add(n.displayPath());
        }
        Files.write(folder.resolve("removed-entries.txt"), lines, StandardCharsets.UTF_8);
        zip(root, folder.resolve("restore-into-Root.zip"));

        int readBack = 0;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile).filter(MenuNames::isMenuFile)::iterator) {
                readBack += MenuFileCodec.read(f).items().size();
            }
        }
        if (readBack != plan.items()) {
            throw new IOException("Archive check failed: " + plan.items() + " entries were written but "
                    + readBack + " could be read back from " + folder + ". Your menus were not changed.");
        }
        return new Result(folder, plan.items(), files);
    }

    /** Removes the planned entries and empty menus from the in-memory tree. Save to apply. */
    public static void apply(DamnMenus menus, Plan plan) {
        for (var e : plan.remove().entrySet()) {
            Set<MenuItem> gone = Collections.newSetFromMap(new IdentityHashMap<>());
            gone.addAll(e.getValue());
            e.getKey().items().removeIf(gone::contains);
            menus.markItemsChanged(e.getKey());
        }
        // Deleting a menu deletes its sub-menus, so only the outermost emptied menus are deleted.
        Set<MenuNode> emptied = Collections.newSetFromMap(new IdentityHashMap<>());
        emptied.addAll(plan.emptied());
        for (MenuNode n : plan.emptied()) {
            if (n.parent() != null && !emptied.contains(n.parent())) menus.delete(n);
        }
    }

    private static Path folderFor(Path root, MenuNode n) {
        Path p = root;
        for (String s : n.segments()) p = p.resolve(s);
        return p;
    }

    /** Zips {@code root} as {@code Root/...}, the layout "Import menu from ZIP" expects. */
    private static void zip(Path root, Path zip) throws IOException {
        List<Path> all = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted().forEach(all::add);
        }
        try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream out = new ZipOutputStream(os)) {
            for (Path p : all) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                boolean dir = Files.isDirectory(p);
                out.putNextEntry(new ZipEntry("Root/" + rel + (dir && !rel.isEmpty() ? "/" : "")));
                if (!dir) Files.copy(p, out);
                out.closeEntry();
            }
        }
    }
}
