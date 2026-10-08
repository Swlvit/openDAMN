package org.opendamn.sc4;

import java.nio.file.Path;
import java.util.*;
import org.opendamn.damn.DamnMenus;
import org.opendamn.dbpf.Tgi;

/**
 * Manual check: scans the real plugins for sub-menus and prints the report counts.
 * Usage: SubmenuCheck &lt;plugins&gt; &lt;hiddenDir&gt; &lt;damnDir&gt;
 */
public class SubmenuCheck {
    public static void main(String[] a) throws Exception {
        Path plugins = Path.of(a[0]);
        Path hidden = Path.of(a[1]);
        Path damnDir = Path.of(a[2]);

        long t0 = System.nanoTime();
        PluginIndex idx = PluginIndex.build(List.of(plugins), null, new PluginIndex.Progress() {
            public void update(int d, int n, Path cur) {}
        });
        System.out.printf("index: %d files in %.1fs%n", idx.files().size(), (System.nanoTime() - t0) / 1e9);

        long t1 = System.nanoTime();
        SubmenuIndex si = SubmenuIndex.build(idx, hidden, new PluginIndex.Progress() {
            public void update(int d, int n, Path cur) {}
        });
        System.out.printf("submenu scan: %.1fs%n", (System.nanoTime() - t1) / 1e9);

        System.out.println();
        System.out.println("buttons parsed:            " + si.buttons().size());
        System.out.println("submenus with members:     " + si.members().size());

        int raw = 0;
        Map<SubmenuIndex.State, Integer> states = new EnumMap<>(SubmenuIndex.State.class);
        for (var e : si.members().entrySet()) {
            raw += e.getValue().size();
            for (var t : e.getValue()) states.merge(t.state(), 1, Integer::sum);
        }
        System.out.println("membership pairs (raw):    " + raw);
        System.out.println("  DANGLING:                " + states.getOrDefault(SubmenuIndex.State.DANGLING, 0));
        System.out.println("  HIDDEN:                  " + states.getOrDefault(SubmenuIndex.State.HIDDEN, 0));
        System.out.println("  VISIBLE:                 " + states.getOrDefault(SubmenuIndex.State.VISIBLE, 0));

        // Distinct lots carrying a sub-menu tag in the hidden folder.
        Map<Integer, Set<Tgi>> tagIds = new TreeMap<>();
        for (var e : si.members().entrySet()) { /* patch route needs no tag */ }
        System.out.println();
        DamnMenus m = DamnMenus.load(damnDir);
        int loaded = 0;
        for (var n : m.root().descendantsAndSelf()) { m.ensureLoaded(n); loaded++; }
        System.out.println("menus loaded:              " + loaded);

        SubmenuIndex.Report r = si.classify(m.root().descendantsAndSelf());
        System.out.println();
        System.out.println("REPORT");
        System.out.println("  hidden with submenu:     " + r.hiddenWithSubmenu().size());
        System.out.println("  hidden, no tag:          " + r.hiddenUntagged().size());
        System.out.println("  missing lots (sub-menu): " + r.missingLots().size());
        System.out.println("  missing lots (DAMN menu):" + r.missingMenuLots().size());
        System.out.println("  needs button:            " + r.needsButton().size());
        System.out.println("  submenus total:          " + r.allSubmenus().size());
        System.out.println("  submenus broken/empty:   " + r.brokenSubmenus().size());

        System.out.println();
        System.out.println("--- broken submenus ---");
        SubmenuIndex.describe(r.brokenSubmenus()).forEach(s -> System.out.println("  " + s));

        System.out.println();
        System.out.println("--- sample: hidden with submenu ---");
        r.hiddenWithSubmenu().stream().limit(12).forEach(row ->
                System.out.printf("  %-38s %-36s %s%n", row.lot(), row.submenu(), row.submenuHex()));

        System.out.println();
        System.out.println("--- sample: hidden, no tag ---");
        r.hiddenUntagged().stream().limit(8).forEach(row ->
                System.out.printf("  %-38s %-36s %s%n", row.lot(), row.submenu(), row.submenuHex()));

        System.out.println();
        System.out.println("--- grouping labels ---");
        System.out.println("  sub-menu row group: " + (r.missingLots().isEmpty() ? "(none)"
                : r.missingLots().get(0).group()));
        System.out.println("  DAMN row group:     " + (r.missingMenuLots().isEmpty() ? "(none)"
                : r.missingMenuLots().get(0).group()));

        System.out.println();
        System.out.println("--- sample: missing lots (DAMN menu) ---");
        r.missingMenuLots().stream().limit(8).forEach(row ->
                System.out.printf("  %-42s %-12s %s%n", row.lot(), row.source().label, row.note()));

        System.out.println();
        System.out.println("--- sample: missing lots ---");
        r.missingLots().stream().limit(8).forEach(row ->
                System.out.printf("  %-38s %-36s %s%n", row.lot(), row.submenu(), row.submenuHex()));

        System.out.println();
        System.out.println("--- sample: needs button ---");
        r.needsButton().stream().limit(8).forEach(row ->
                System.out.printf("  %-38s %-36s %s%n", row.lot(), row.submenu(), row.submenuHex()));
    }
}
