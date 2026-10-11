package org.opendamn.damn;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.opendamn.dbpf.DbpfFile;
import org.opendamn.dbpf.DbpfWriter;
import org.opendamn.dbpf.Tgi;

/**
 * The DAMN menu tree stored below {@code Plugins/DAMN/Root}, with all editing operations.
 *
 * <p>Edits are made in memory and written by {@link #save}. Saving is crash-safe: new files
 * are written before old ones are removed, and everything that gets replaced or deleted is
 * moved into a backup folder first.
 */
public final class DamnMenus {

    public static final String INDEX_FILE = "DAMN-index.txt";
    static final Charset INDEX_CHARSET = Charset.forName("windows-1252");

    private final Path damnDir;
    private final Path rootDir;
    private final MenuNode root = new MenuNode("Root");
    private final List<MenuNode> deletedNodes = new ArrayList<>();
    private boolean structureDirty;
    private final Random random = new Random();
    private Set<Integer> usedIds;
    private Consumer<String> log = s -> {};

    private DamnMenus(Path damnDir) {
        this.damnDir = damnDir;
        this.rootDir = damnDir.resolve("Root");
    }

    /** Loads the folder structure (items are loaded lazily). */
    public static DamnMenus load(Path damnDir) throws IOException {
        DamnMenus m = new DamnMenus(damnDir);
        m.root.setDiskPath(m.rootDir);
        if (Files.isDirectory(m.rootDir)) m.scan(m.root, m.rootDir);
        return m;
    }

    public void setLog(Consumer<String> log) {
        this.log = log;
    }

    private void scan(MenuNode node, Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> list = s.sorted().toList();
            for (Path p : list) {
                if (Files.isDirectory(p)) {
                    MenuNode c = new MenuNode(p.getFileName().toString());
                    c.setDiskPath(p);
                    node.addChild(c);
                    scan(c, p);
                } else if (MenuNames.isMenuFile(p)) {
                    node.diskFiles().add(p);
                }
            }
        }
    }

    public Path damnDir() { return damnDir; }
    public Path rootDir() { return rootDir; }
    public Path supportDir() { return damnDir.resolve("Support"); }
    public MenuNode root() { return root; }

    public boolean isDirty() {
        if (structureDirty) return true;
        for (MenuNode n : root.descendantsAndSelf()) if (n.itemsDirty()) return true;
        return false;
    }

    // ------------------------------------------------------------------ items

    /** Loads the items of a menu from its .dat files if not yet loaded. Returns warnings. */
    public List<String> ensureLoaded(MenuNode n) throws IOException {
        if (n.itemsLoaded()) return List.of();
        List<MenuItem> items = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Path f : n.diskFiles()) {
            try {
                MenuFileCodec.ReadResult r = MenuFileCodec.read(f);
                items.addAll(r.items());
                for (String w : r.warnings()) warnings.add(f.getFileName() + ": " + w);
            } catch (RuntimeException e) {
                warnings.add(f.getFileName() + ": unreadable (" + e.getMessage() + ")");
            }
        }
        n.setItems(items);
        return warnings;
    }

    public void markItemsChanged(MenuNode n) {
        if (!n.itemsLoaded()) throw new IllegalStateException("not loaded");
        n.setItemsDirty(true);
    }

    /**
     * Adds items to a menu, skipping duplicates. Returns the items actually added.
     *
     * @param atEnd    append (true) or insert at the top (false)
     */
    public List<MenuItem> addItems(MenuNode n, List<MenuItem> toAdd, boolean atEnd) throws IOException {
        ensureLoaded(n);
        Set<String> present = new HashSet<>();
        for (MenuItem it : n.items()) present.add(it.key());
        List<MenuItem> added = new ArrayList<>();
        for (MenuItem it : toAdd) {
            if (present.add(it.key())) added.add(it.copy());
        }
        if (atEnd) n.items().addAll(added);
        else n.items().addAll(0, added);
        if (!added.isEmpty()) n.setItemsDirty(true);
        return added;
    }

    // ------------------------------------------------------------------ structure

    public MenuNode createMenu(MenuNode parent, String name) {
        checkName(parent, name, null);
        MenuNode c = new MenuNode(name);
        c.setItems(new ArrayList<>());
        c.setItemsDirty(true);
        parent.addChild(c);
        structureDirty = true;
        return c;
    }

    public void rename(MenuNode n, String name) {
        if (n.isRoot()) throw new IllegalArgumentException("The Root menu cannot be renamed.");
        checkName(n.parent(), name, n);
        n.setName(name);
        n.parent().sortChildren();
        structureDirty = true;
    }

    public void move(MenuNode n, MenuNode newParent) {
        if (n.isRoot()) throw new IllegalArgumentException("The Root menu cannot be moved.");
        if (n.isAncestorOf(newParent)) throw new IllegalArgumentException("A menu cannot be moved into itself.");
        if (n.parent() == newParent) return;
        checkName(newParent, n.name(), n);
        n.parent().removeChild(n);
        newParent.addChild(n);
        structureDirty = true;
    }

    public void delete(MenuNode n) {
        if (n.isRoot()) throw new IllegalArgumentException("The Root menu cannot be deleted.");
        n.parent().removeChild(n);
        deletedNodes.add(n);
        structureDirty = true;
    }

    private void checkName(MenuNode parent, String name, MenuNode self) {
        String err = MenuNames.validate(name);
        if (err != null) throw new IllegalArgumentException(err);
        MenuNode existing = parent.child(name);
        if (existing != null && existing != self) {
            throw new IllegalArgumentException("A menu named \"" + name + "\" already exists here.");
        }
    }

    // ------------------------------------------------------------------ saving

    /** Summary of a save. */
    public record SaveResult(int filesWritten, int foldersDeleted, Path backup, List<String> notes) {}

    /**
     * Writes all pending changes and rebuilds the index.
     *
     * @param backupRoot folder in which a timestamped backup of replaced files is created
     * @param fallback   icon used for items without an icon
     */
    public SaveResult save(Path backupRoot, MenuFileCodec.IconFallback fallback) throws IOException {
        List<String> notes = new ArrayList<>();
        Path backup = backupRoot.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")));
        Files.createDirectories(rootDir);

        // 1. Move renamed/moved folders out of the way, 2. delete removed menus (into the
        //    backup), 3. put the moved folders in place. This order handles swaps, a menu
        //    renamed to the name of a deleted one, and menus moved out of deleted ones.
        Path staging = rootDir.resolve(".opendamn-staging-" + System.nanoTime());
        List<MenuNode> staged = stageMovedFolders(staging);

        int foldersDeleted = 0;
        List<Path> doomed = new ArrayList<>();
        for (MenuNode d : deletedNodes) {
            for (MenuNode x : d.descendantsAndSelf()) if (x.diskPath() != null) doomed.add(x.diskPath());
        }
        doomed.sort(Comparator.comparingInt(Path::getNameCount));
        for (Path f : doomed) {
            if (Files.exists(f)) {
                moveToBackup(f, backup);
                foldersDeleted++;
            }
        }
        deletedNodes.clear();
        placeStagedFolders(staged);
        deleteTree(staging);

        // 4. Write changed menus.
        Set<String> names = MenuNames.existingDatNames(rootDir);
        loadUsedIds();
        int written = 0;
        for (MenuNode n : root.descendantsAndSelf()) {
            Path folder = folderOf(n);
            Files.createDirectories(folder);
            n.setDiskPath(folder);
            if (!n.itemsDirty()) continue;
            String name = MenuNames.newDatName(names);
            Path target = folder.resolve(name);
            MenuFileCodec.write(target, n.items(), this::nextId, fallback);
            written++;
            for (Path old : n.diskFiles()) {
                if (Files.exists(old)) moveToBackup(old, backup);
                names.remove(MenuNode.key(old.getFileName().toString()));
            }
            n.diskFiles().clear();
            n.diskFiles().add(target);
            n.setItemsDirty(false);
        }

        // 5. Every menu needs at least one file so it shows up in game even when empty,
        //    and file names must be unique across the tree.
        written += repairFiles(names, backup, notes);

        backupFile(damnDir.resolve("Support").resolve(INDEX_FILE), backup);
        writeIndex();
        structureDirty = false;
        log.accept("Saved DAMN menus: " + written + " file(s) written, " + foldersDeleted + " menu(s) deleted");
        return new SaveResult(written, foldersDeleted, Files.exists(backup) ? backup : null, notes);
    }

    private Path folderOf(MenuNode n) {
        Path p = rootDir;
        for (String s : n.segments()) p = p.resolve(s);
        return p;
    }

    private List<MenuNode> stageMovedFolders(Path staging) throws IOException {
        List<MenuNode> moved = new ArrayList<>();
        for (MenuNode n : root.descendantsAndSelf()) {
            if (n.isRoot() || n.diskPath() == null) continue;
            if (!n.diskPath().equals(folderOf(n))) moved.add(n);
        }
        List<MenuNode> staged = new ArrayList<>();
        if (moved.isEmpty()) return staged;
        // Outermost first; inner folders travel along and are then staged on their own.
        moved.sort(Comparator.comparingInt(n -> n.diskPath().getNameCount()));
        Files.createDirectories(staging);
        int k = 0;
        for (MenuNode n : moved) {
            Path src = n.diskPath();
            if (!Files.isDirectory(src)) continue;
            Path dst = staging.resolve("m" + (k++));
            Files.move(src, dst);
            staged.add(n);
            rebaseAll(src, dst);
        }
        return staged;
    }

    private void placeStagedFolders(List<MenuNode> staged) throws IOException {
        List<MenuNode> order = new ArrayList<>(staged);
        order.sort(Comparator.comparingInt(n -> n.segments().size()));
        for (MenuNode n : order) {
            Path src = n.diskPath();
            Path dst = folderOf(n);
            Files.createDirectories(dst.getParent());
            if (Files.exists(dst)) {
                // Target exists (e.g. a leftover empty folder): merge by moving contents.
                try (Stream<Path> s = Files.list(src)) {
                    for (Path p : (Iterable<Path>) s::iterator) Files.move(p, dst.resolve(p.getFileName()));
                }
                Files.delete(src);
            } else {
                Files.move(src, dst);
            }
            rebaseAll(src, dst);
        }
    }

    /** Updates disk paths of all nodes (live and deleted) after a folder moved. */
    private void rebaseAll(Path from, Path to) {
        List<MenuNode> all = new ArrayList<>(root.descendantsAndSelf());
        for (MenuNode d : deletedNodes) all.addAll(d.descendantsAndSelf());
        for (MenuNode d : all) {
            if (d.diskPath() != null && d.diskPath().startsWith(from)) {
                d.setDiskPath(to.resolve(from.relativize(d.diskPath())));
            }
            List<Path> files = new ArrayList<>();
            for (Path f : d.diskFiles()) files.add(f.startsWith(from) ? to.resolve(from.relativize(f)) : f);
            d.diskFiles().clear();
            d.diskFiles().addAll(files);
        }
    }

    private int repairFiles(Set<String> names, Path backup, List<String> notes) throws IOException {
        int written = 0;
        Map<String, Path> seen = new HashMap<>();
        for (MenuNode n : root.descendantsAndSelf()) {
            List<Path> files = new ArrayList<>(n.diskFiles());
            for (Path f : files) {
                String key = MenuNode.key(f.getFileName().toString());
                Path other = seen.putIfAbsent(key, f);
                if (other != null && !other.equals(f)) {
                    // Duplicate name (usually a folder copied by hand): give this copy a new name.
                    String fresh = MenuNames.newDatName(names);
                    Path renamed = f.resolveSibling(fresh);
                    Files.move(f, renamed);
                    n.diskFiles().set(n.diskFiles().indexOf(f), renamed);
                    seen.put(MenuNode.key(fresh), renamed);
                    notes.add("Renamed duplicate menu file " + f.getFileName() + " in " + n.displayPath());
                }
            }
            if (n.diskFiles().isEmpty() && !n.isRoot()) {
                Path p = folderOf(n).resolve(MenuNames.newDatName(names));
                new DbpfWriter().write(p);
                n.diskFiles().add(p);
                seen.put(MenuNode.key(p.getFileName().toString()), p);
                written++;
            }
        }
        return written;
    }

    // ------------------------------------------------------------------ index

    /** Rebuilds {@code Support/DAMN-index.txt} from what is on disk. */
    public void writeIndex() throws IOException {
        List<String> lines = new ArrayList<>();
        if (Files.isDirectory(rootDir)) {
            try (Stream<Path> s = Files.walk(rootDir)) {
                s.filter(Files::isRegularFile)
                        .filter(MenuNames::isMenuFile)
                        .filter(p -> !rootDir.relativize(p).toString().startsWith("."))
                        .sorted()
                        .forEach(p -> lines.add(indexLine(p)));
            }
        }
        Path support = damnDir.resolve("Support");
        Files.createDirectories(support);
        Path target = support.resolve(INDEX_FILE);
        Path tmp = support.resolve(INDEX_FILE + ".tmp");
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append("\r\n");
        Files.write(tmp, sb.toString().getBytes(INDEX_CHARSET));
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        // Keep the copy embedded in the engine's control script in sync.
        DamnInstaller.updateControl(damnDir, lines, org.opendamn.sc4.GamePaths.toWindowsPath(damnDir.getParent().getParent()), null);
    }

    /**
     * One index line. The in-game script only uses the part after the first {@code Root\},
     * so a relative Windows-style path works regardless of where the game is installed.
     */
    String indexLine(Path file) {
        StringBuilder sb = new StringBuilder("Plugins\\DAMN\\Root");
        for (String s : MenuNames.segmentsOf(rootDir, file)) sb.append('\\').append(s);
        return sb.toString();
    }

    /** Reads the existing index and reports whether it matches the folder contents. */
    public boolean indexIsCurrent() throws IOException {
        Path idx = damnDir.resolve("Support").resolve(INDEX_FILE);
        if (!Files.exists(idx)) return false;
        Set<String> listed = new HashSet<>();
        for (String l : Files.readAllLines(idx, INDEX_CHARSET)) {
            int i = l.indexOf("Root\\");
            if (i >= 0) listed.add(MenuNode.key(l.substring(i + 5)));
        }
        Set<String> actual = new HashSet<>();
        try (Stream<Path> s = Files.walk(rootDir)) {
            s.filter(Files::isRegularFile).filter(MenuNames::isMenuFile)
                    .forEach(p -> actual.add(MenuNode.key(String.join("\\", MenuNames.segmentsOf(rootDir, p)))));
        }
        return listed.containsAll(actual);
    }

    // ------------------------------------------------------------------ IDs

    /** Collects every instance ID used by existing menu files so new IDs never collide. */
    void loadUsedIds() throws IOException {
        if (usedIds != null) return;
        usedIds = new HashSet<>();
        if (!Files.isDirectory(rootDir)) return;
        try (Stream<Path> s = Files.walk(rootDir)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile).filter(MenuNames::isMenuFile)::iterator) {
                try {
                    for (DbpfFile.Entry e : DbpfFile.open(p).entries()) {
                        usedIds.add(e.tgi().instance());
                        usedIds.add(e.tgi().group());
                    }
                } catch (RuntimeException | IOException e) {
                    log.accept("Skipping unreadable menu file " + p + ": " + e.getMessage());
                }
            }
        }
    }

    int nextId() {
        while (true) {
            int id = random.nextInt(Integer.MAX_VALUE - 0x100) + 0x100;
            // Avoid the DAMN engine's own range (0x0dae....).
            if ((id >>> 16) == 0x0DAE || (id >>> 16) == 0xDAE1) continue;
            if (usedIds.add(id)) return id;
        }
    }

    // ------------------------------------------------------------------ helpers

    private void moveToBackup(Path p, Path backup) throws IOException {
        Path rel = damnDir.relativize(p);
        Path dst = backup.resolve(rel);
        Files.createDirectories(dst.getParent());
        try {
            Files.move(p, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Cross-device: copy then delete.
            if (Files.isDirectory(p)) {
                copyTree(p, dst);
                deleteTree(p);
            } else {
                Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING);
                Files.delete(p);
            }
        }
    }

    private void backupFile(Path p, Path backup) throws IOException {
        if (!Files.exists(p)) return;
        Path dst = backup.resolve(damnDir.relativize(p));
        Files.createDirectories(dst.getParent());
        Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    static void copyTree(Path src, Path dst) throws IOException {
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            List<Path> all = s.sorted(Comparator.reverseOrder()).toList();
            for (Path x : all) Files.delete(x);
        }
    }

    /** All menu items in the tree (loads every menu). */
    public List<MenuItem> allItems() throws IOException {
        List<MenuItem> r = new ArrayList<>();
        for (MenuNode n : root.descendantsAndSelf()) {
            ensureLoaded(n);
            r.addAll(n.items());
        }
        return r;
    }

    /** Group of a DAMN LTEXT; exposed for tools that inspect menus. */
    public static int ltextGroup() {
        return Tgi.GROUP_LTEXT_HTML;
    }
}
