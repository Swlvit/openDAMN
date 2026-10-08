package org.opendamn.sc4;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.opendamn.dbpf.Exemplar;
import org.opendamn.dbpf.LText;
import org.opendamn.dbpf.Property;
import org.opendamn.dbpf.Tgi;
import org.opendamn.damn.MenuItem;
import org.opendamn.damn.MenuNode;

/**
 * Reads the sub-menus created by memo33's Submenus DLL and the SC4ResourceLoadingHooks DLL.
 *
 * <p>A sub-menu is a button exemplar ({@code 6534284A / 2A3858E4}, exemplar type {@code 0x28})
 * carrying its own ID, parent and order. Lots join one of two ways: the lot's own
 * {@code Building Submenus} list (0xAA1DD399), or a separate <em>patch cohort</em>
 * ({@code 05342861 / B03697D1}) holding {@code Exemplar Patch Targets} (0x0062E78A) plus the
 * sub-menu ID the game injects into every target.
 *
 * <p>This matters for hiding: OpenDAMN's hidden copies keep their {@code Building Submenus}
 * property (only the Lot Resource Key is stripped), so a hidden lot leaves the game's own menus
 * but stays in its sub-menu. This class reports that, and the other ways a sub-menu ends up
 * empty.
 *
 * <p>Scanning is index-driven: candidate files are found by filtering {@link PluginIndex}, so
 * the ~350 relevant files are picked out of tens of thousands without a second walk of the
 * plugin folders, and only those are parsed.
 */
public final class SubmenuIndex {

    /** Type of a building/flora exemplar. */
    public static final int TYPE_EXEMPLAR_BUILDING = 0x6534284A;
    /** Group holding sub-menu button exemplars. */
    public static final int GROUP_SUBMENU_BUTTON = 0x2A3858E4;
    /** Group holding exemplar patch cohorts. */
    public static final int GROUP_EXEMPLAR_PATCH = 0xB03697D1;
    /** Exemplar Type value marking a sub-menu button. */
    public static final int EXT_SUBMENU_BUTTON = 0x28;

    /** Exemplar Patch Targets: group/instance pairs this cohort applies to. */
    public static final int P_PATCH_TARGETS = 0x0062E78A;

    /** One sub-menu button. */
    public record Button(int id, String name, int parentId, int order, Path file) {
        public String hex() { return Tgi.hexUpper(id); }
    }

    /** What happened to one lot referenced by a sub-menu. */
    public enum State {
        /** The lot exists and is not hidden. */
        VISIBLE,
        /** The lot exists but the hidden folder overrides it. */
        HIDDEN,
        /** No copy of the lot exists outside the hidden folder. */
        DANGLING
    }

    /** One lot referenced by a sub-menu. */
    public record Target(int submenuId, Tgi exemplar, State state, Path file) {}

    /** Which side of the app a row came from. */
    public enum Source {
        /** A sub-menu lists this lot, whether through a patch cohort or its own tag. */
        SUBMENU("sub-menu"),
        /** A DAMN menu has an entry for this lot. */
        DAMN("DAMN menu");

        public final String label;

        Source(String label) { this.label = label; }
    }

    /** One row of the report. */
    public record Row(Tgi exemplar, String lot, int submenuId, String submenu,
                      int submenuSize, int hiddenInSubmenu, String note, Source source) {
        public String submenuHex() { return Tgi.hexUpper(submenuId); }

        /** Group for display, e.g. "Missing lots (sub-menu)". */
        public String group() {
            return "Missing lots (" + source.label + ")";
        }
    }

    /** Health of one sub-menu. */
    public record SubmenuStatus(int id, String name, boolean hasButton, int members,
                                int dangling, int hidden, int visible) {
        /**
         * The game hides a sub-menu with nothing to show. Sub-menus with no members at all are
         * simply unused (the curated list ships dozens), so they are not reported as broken.
         */
        public boolean empty() { return hasButton && members > 0 && visible == 0; }

        public String hex() { return Tgi.hexUpper(id); }

        public String reason() {
            if (!hasButton) return "no button in any plugin";
            if (members == 0) return "no members";
            if (visible > 0) return "";
            if (dangling > 0 && hidden > 0) {
                return dangling + " member(s) missing, " + hidden + " hidden";
            }
            if (dangling > 0) return "all " + dangling + " member(s) are missing";
            return "all " + hidden + " member(s) are hidden";
        }
    }

    /** Everything the viewer shows. */
    public record Report(List<Row> hiddenWithSubmenu, List<Row> hiddenUntagged,
                         List<Row> missingLots, List<Row> missingMenuLots, List<Row> needsButton,
                         List<SubmenuStatus> brokenSubmenus, List<SubmenuStatus> allSubmenus) {
        public int totalReferenced() { return allSubmenus.size(); }
    }

    private final Map<Integer, Button> buttons = new TreeMap<>();
    private final Map<Integer, List<Target>> members = new TreeMap<>();
    private final Map<Integer, List<Target>> tagged = new TreeMap<>();
    private final PluginIndex index;
    private final Path hiddenDir;

    private SubmenuIndex(PluginIndex index, Path hiddenDir) {
        this.index = index;
        this.hiddenDir = hiddenDir;
    }

    public Map<Integer, Button> buttons() { return buttons; }

    /** Sub-menu ID to the lots a patch cohort injects it into. */
    public Map<Integer, List<Target>> members() { return members; }

    // ------------------------------------------------------------------ scanning

    /**
     * Scans the plugin index for sub-menu buttons and patch cohorts. Reads only the files that
     * actually contain one of those two resource kinds.
     */
    public static SubmenuIndex build(PluginIndex index, Path hiddenDir, PluginIndex.Progress p)
            throws IOException {
        SubmenuIndex si = new SubmenuIndex(index, hiddenDir);
        List<Location> buttonFiles = new ArrayList<>();
        List<Location> patchFiles = new ArrayList<>();
        List<Path> hiddenFiles = new ArrayList<>();
        Path hiddenAbs = hiddenDir == null ? null : hiddenDir.toAbsolutePath().normalize();

        for (IndexedFile f : index.files()) {
            boolean button = false;
            boolean patch = false;
            boolean hidden = hiddenAbs != null && isUnder(f.path(), hiddenAbs);
            for (IndexedFile.Res r : f.resources()) {
                int t = r.tgi().type();
                if (t == TYPE_EXEMPLAR_BUILDING) {
                    if (r.tgi().group() == GROUP_SUBMENU_BUTTON) button = true;
                    if (hidden) hiddenFiles.add(f.path());
                } else if (t == Tgi.TYPE_COHORT && r.tgi().group() == GROUP_EXEMPLAR_PATCH) {
                    patch = true;
                }
            }
            if (button) buttonFiles.add(new Location(f));
            if (patch) patchFiles.add(new Location(f));
        }
        if (p.cancelled()) return si;

        int done = 0;
        int total = buttonFiles.size() + patchFiles.size() + hiddenFiles.size();
        for (Location l : buttonFiles) {
            if (p.cancelled()) return si;
            p.update(++done, total, l.file.path());
            si.readButtons(l);
        }
        for (Location l : patchFiles) {
            if (p.cancelled()) return si;
            p.update(++done, total, l.file.path());
            si.readPatches(l);
        }
        // The hidden folder is where hidden lots keep the sub-menu tags they were hidden with.
        for (Path f : new java.util.LinkedHashSet<>(hiddenFiles)) {
            if (p.cancelled()) return si;
            p.update(++done, total, f);
            si.readHiddenTags(f);
        }
        return si;
    }

    private record Location(IndexedFile file) {}

    private void readButtons(Location l) {
        for (IndexedFile.Res r : l.file.resources()) {
            if (r.tgi().type() != TYPE_EXEMPLAR_BUILDING || r.tgi().group() != GROUP_SUBMENU_BUTTON) continue;
            Exemplar ex = read(l.file, r);
            if (ex == null || ex.exemplarType() != EXT_SUBMENU_BUTTON) continue;
            long[] bid = values(ex, Exemplar.P_ITEM_BUTTON_ID);
            int id = bid.length > 0 ? (int) bid[0] : r.tgi().instance();
            long[] parent = values(ex, Exemplar.P_ITEM_SUBMENU_PARENT);
            long[] order = values(ex, Exemplar.P_ITEM_ORDER);
            String name = nameOf(ex, id, l.file.path().getFileName().toString());
            buttons.putIfAbsent(id, new Button(id, name,
                    parent.length > 0 ? (int) parent[0] : 0,
                    order.length > 0 ? (int) order[0] : 0,
                    l.file.path()));
        }
    }

    private void readPatches(Location l) {
        for (IndexedFile.Res r : l.file.resources()) {
            if (r.tgi().type() != Tgi.TYPE_COHORT || r.tgi().group() != GROUP_EXEMPLAR_PATCH) continue;
            Exemplar ex = read(l.file, r);
            if (ex == null) continue;
            long[] subs = values(ex, Exemplar.P_BUILDING_SUBMENUS);
            long[] targets = values(ex, P_PATCH_TARGETS);
            if (subs.length == 0) continue;
            for (long s : subs) {
                int submenu = (int) s;
                List<Target> list = members.computeIfAbsent(submenu, k -> new ArrayList<>());
                for (int i = 0; i + 1 < targets.length; i += 2) {
                    Tgi t = new Tgi(TYPE_EXEMPLAR_BUILDING, (int) targets[i], (int) targets[i + 1]);
                    list.add(target(submenu, t, l.file.path()));
                }
            }
        }
    }

    /** A lot in the hidden folder joins its sub-menu through its own Building Submenus list. */
    private void readHiddenTags(Path file) {
        IndexedFile f = index.file(file);
        if (f == null) return;
        for (IndexedFile.Res r : f.resources()) {
            if (r.tgi().type() != TYPE_EXEMPLAR_BUILDING) continue;
            Exemplar ex = read(f, r);
            if (ex == null || !ex.has(Exemplar.P_BUILDING_SUBMENUS)) continue;
            Tgi t = r.tgi();
            for (long s : values(ex, Exemplar.P_BUILDING_SUBMENUS)) {
                int submenu = (int) s;
                tagged.computeIfAbsent(submenu, k -> new ArrayList<>()).add(new Target(submenu, t, State.HIDDEN, file));
            }
        }
    }

    private Exemplar read(IndexedFile file, IndexedFile.Res res) {
        try {
            return PluginIndex.readExemplar(new PluginIndex.Located(file, res));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Whether the game would show this lot: it is hidden when the copy that loads last lives in
     * the hidden folder, and dangling when no copy exists at all. Note a lot whose <em>only</em>
     * copy is in the hidden folder is hidden, not dangling - its plugin was removed, but
     * unhiding it still matters.
     */
    private Target target(int submenuId, Tgi t, Path file) {
        Path hiddenAbs = hiddenDir == null ? null : hiddenDir.toAbsolutePath().normalize();
        PluginIndex.Located eff = index.effective(t);
        if (eff == null) return new Target(submenuId, t, State.DANGLING, file);
        boolean hidden = hiddenAbs != null && isUnder(eff.file().path(), hiddenAbs);
        return new Target(submenuId, t, hidden ? State.HIDDEN : State.VISIBLE, file);
    }

    private static boolean isUnder(Path p, Path dir) {
        return p.toAbsolutePath().normalize().startsWith(dir);
    }

    private static long[] values(Exemplar ex, int id) {
        Property p = ex.get(id);
        return p == null || p.isString() ? new long[0] : p.values();
    }

    /** Prefers the sub-menu's name text, then the exemplar name, then the file name. */
    private String nameOf(Exemplar ex, int id, String fallback) {
        Property key = ex.get(Exemplar.P_USER_VISIBLE_NAME_KEY);
        if (key != null && !key.isString() && key.values().length >= 3) {
            Tgi t = new Tgi((int) key.values()[0], (int) key.values()[1], (int) key.values()[2]);
            if (t.type() != Tgi.TYPE_LTEXT) t = t.withGroup(t.group());
            try {
                PluginIndex.Located l = index.findText(t);
                if (l != null) {
                    String s = LText.decode(PluginIndex.read(l));
                    if (s != null && !s.isBlank()) return s.strip();
                }
            } catch (IOException | RuntimeException ignored) {
                // Fall through to the exemplar name.
            }
        }
        String name = ex.getString(Exemplar.P_EXEMPLAR_NAME);
        return name == null || name.isBlank() ? fallback : name.strip();
    }

    private String nameOfLot(Tgi t) {
        for (IndexedFile f : index.files()) {
            for (IndexedFile.Res r : f.resources()) {
                if (!r.tgi().equals(t)) continue;
                Exemplar ex = read(f, r);
                if (ex != null) {
                    String n = ex.getString(Exemplar.P_EXEMPLAR_NAME);
                    if (n != null && !n.isBlank()) return n.strip();
                }
            }
        }
        return Tgi.hex(t.group()) + ":" + Tgi.hex(t.instance());
    }

    // ------------------------------------------------------------------ reporting

    /**
     * Cross-references the sub-menu data with the DAMN menus. Loads every menu first, unless
     * the caller has already done so.
     */
    public Report classify(Collection<MenuNode> menus) throws IOException {
        Map<Tgi, String> lotNames = new HashMap<>();
        Set<Tgi> inMenus = new HashSet<>();
        // DAMN menu entries whose lot no longer exists anywhere. Only the first menu that holds a
        // lot names it, so a lot in several menus is listed once.
        Map<Tgi, String> missingFromMenus = new LinkedHashMap<>();
        if (menus != null) {
            for (MenuNode n : menus) {
                for (MenuNode d : n.descendantsAndSelf()) {
                    if (!d.itemsLoaded()) continue;
                    for (MenuItem it : d.items()) {
                        Tgi t = it.exemplarTgi();
                        inMenus.add(t);
                        lotNames.putIfAbsent(t, it.linkName());
                        if (index.effective(t) == null) missingFromMenus.putIfAbsent(t, it.linkName());
                    }
                }
            }
        }

        // Sub-menus mentioned by a cohort or by a tag, whether or not a button exists.
        Map<Integer, List<Target>> all = new TreeMap<>();
        for (var e : members.entrySet()) all.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
        for (var e : tagged.entrySet()) all.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());

        List<SubmenuStatus> statuses = new ArrayList<>();
        for (var e : all.entrySet()) {
            int id = e.getKey();
            Button b = buttons.get(id);
            // A lot reachable both ways is listed once.
            Map<Tgi, Target> unique = new LinkedHashMap<>();
            for (Target t : e.getValue()) unique.putIfAbsent(t.exemplar(), t);
            int dangling = 0;
            int hidden = 0;
            int visible = 0;
            for (Target t : unique.values()) {
                switch (t.state()) {
                    case DANGLING -> dangling++;
                    case HIDDEN -> hidden++;
                    case VISIBLE -> visible++;
                }
            }
            statuses.add(new SubmenuStatus(id, b != null ? b.name() : "(no button)",
                    b != null, unique.size(), dangling, hidden, visible));
        }
        for (Button b : buttons.values()) {
            if (all.containsKey(b.id())) continue;
            statuses.add(new SubmenuStatus(b.id(), b.name(), true, 0, 0, 0, 0));
        }
        statuses.sort(Comparator.comparing(SubmenuStatus::name, String.CASE_INSENSITIVE_ORDER));

        List<Row> hiddenWithSubmenu = new ArrayList<>();
        List<Row> hiddenUntagged = new ArrayList<>();
        List<Row> missingLots = new ArrayList<>();
        List<Row> needsButton = new ArrayList<>();
        Map<Integer, SubmenuStatus> byId = new HashMap<>();
        for (SubmenuStatus s : statuses) byId.put(s.id(), s);

        // Reuse the same deduplicated targets the counts were built from, so every referenced lot
        // lands in exactly one place. Walking `tagged`/`members` separately used to drop lots that
        // were only dangling.
        for (var e : all.entrySet()) {
            int id = e.getKey();
            Button b = buttons.get(id);
            SubmenuStatus st = byId.get(id);
            Map<Tgi, Boolean> taggedHere = new HashMap<>();
            for (Target t : tagged.getOrDefault(id, List.of())) taggedHere.put(t.exemplar(), true);

            Map<Tgi, Target> unique = new LinkedHashMap<>();
            for (Target t : e.getValue()) unique.putIfAbsent(t.exemplar(), t);

            for (Target t : unique.values()) {
                // Tags are only visible to us in the hidden folder, so a lot that still loads from
                // a plugin counts as untagged here even when it does carry a tag. Only hidden lots
                // are reported, which is exactly the set we can classify accurately.
                boolean report = t.state() == State.DANGLING || t.state() == State.HIDDEN;
                if (!report) continue;

                boolean hasTag = taggedHere.containsKey(t.exemplar());
                String note;
                if (b == null) {
                    note = "no sub-menu button exists for " + Tgi.hexUpper(id);
                } else if (t.state() == State.DANGLING) {
                    note = "this lot no longer exists in any plugin";
                } else if (hasTag) {
                    note = "";
                } else {
                    note = "listed by a patch cohort, no sub-menu tag of its own";
                }

                Row row = new Row(t.exemplar(), lotNames.getOrDefault(t.exemplar(), nameOfLot(t.exemplar())),
                        id, b != null ? b.name() : "(no button)",
                        st == null ? 0 : st.members(), st == null ? 0 : st.hidden(), note,
                        Source.SUBMENU);

                if (t.state() == State.DANGLING) missingLots.add(row);
                else if (b == null) needsButton.add(row);
                else if (hasTag) hiddenWithSubmenu.add(row);
                else hiddenUntagged.add(row);
            }
        }
        // DAMN menu entries pointing at a lot no plugin provides. These have no sub-menu at all,
        // so the sub-menu columns are left empty.
        List<Row> missingMenuLots = new ArrayList<>();
        for (var e : missingFromMenus.entrySet()) {
            missingMenuLots.add(new Row(e.getKey(), e.getValue(), 0, "", 0, 0,
                    "in a DAMN menu, but no plugin provides this lot", Source.DAMN));
        }
        missingMenuLots.sort(Comparator.comparing(Row::lot, String.CASE_INSENSITIVE_ORDER));

        sort(hiddenWithSubmenu);
        sort(hiddenUntagged);
        sort(missingLots);
        sort(needsButton);

        List<SubmenuStatus> broken = statuses.stream().filter(SubmenuStatus::empty).toList();
        return new Report(hiddenWithSubmenu, hiddenUntagged, missingLots, missingMenuLots, needsButton,
                broken, statuses);
    }

    private static void sort(List<Row> rows) {
        rows.sort(Comparator.comparing(Row::submenu, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Row::lot, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(r -> Tgi.hex(r.exemplar().instance())));
    }

    /** Sub-menu IDs a hidden lot can be untagged from, keyed by lot. */
    public Map<Tgi, Set<Integer>> taggedSubmenus() {
        Map<Tgi, Set<Integer>> out = new HashMap<>();
        for (var e : tagged.entrySet()) {
            for (Target t : e.getValue()) out.computeIfAbsent(t.exemplar(), k -> new HashSet<>()).add(e.getKey());
        }
        return out;
    }

    /** Human-readable summary of one sub-menu, for the empty-menu list. */
    public static List<String> describe(List<SubmenuStatus> statuses) {
        List<String> lines = new ArrayList<>();
        for (SubmenuStatus s : statuses) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%-44s %-10s", s.name(), s.hex()));
            sb.append(String.format(Locale.ROOT, "  %4d member(s)", s.members()));
            if (s.dangling() > 0) sb.append(", ").append(s.dangling()).append(" missing");
            if (s.hidden() > 0) sb.append(", ").append(s.hidden()).append(" hidden");
            if (s.visible() > 0) sb.append(", ").append(s.visible()).append(" visible");
            String why = s.reason();
            if (!why.isEmpty()) sb.append("   <- ").append(why);
            lines.add(sb.toString());
        }
        return lines;
    }

    /** Human-readable rows, for text export. */
    public static List<String> describe(List<Row> rows, String lotHeader) {
        List<String> lines = new ArrayList<>();
        lines.add(String.format("%-40s %-38s %-12s", lotHeader, "Sub-menu", "Sub-menu ID"));
        for (Row r : rows) {
            lines.add(String.format("%-40s %-38s %-12s%s", r.lot(), r.submenu(), r.submenuHex(),
                    r.note().isEmpty() ? "" : "   " + r.note()));
        }
        return lines;
    }
}
