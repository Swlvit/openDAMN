package org.opendamn.sc4;

import java.nio.file.Path;
import java.util.*;
import org.opendamn.damn.*;
import org.opendamn.dbpf.*;

/** Read-only: compares menu hidden flags with what the hidden folder actually achieves. */
public class HideCheck {
    public static void main(String[] a) throws Exception {
        Path cache = Path.of(a[0]), damn = Path.of(a[1]), hidden = Path.of(a[2]);
        List<Path> roots = new ArrayList<>();
        for (int k = 3; k < a.length; k++) roots.add(Path.of(a[k]));
        PluginIndex idx = PluginIndex.build(roots, cache, (d, n, p) -> {});
        LotCatalog cat = new LotCatalog(idx);
        DamnMenus m = DamnMenus.load(damn);
        int flagged = 0, effHidden = 0, notInstalled = 0, stillVisible = 0, cohortOnly = 0, overridden = 0;
        List<String> samples = new ArrayList<>();
        for (MenuItem it : m.allItems()) {
            if (!it.hidden() || it.kind() == MenuItem.Kind.NETWORK) continue;
            flagged++;
            PluginIndex.Located eff = idx.effective(it.exemplarTgi());
            if (eff == null) { notInstalled++; continue; }
            LotInfo li = cat.describe(eff);
            boolean fromHidden = eff.file().path().startsWith(hidden);
            if (li != null && li.hidden()) effHidden++;
            else {
                stillVisible++;
                if (!fromHidden) overridden++;
                PluginIndex.Located orig = HiddenLots.originalOf(idx, it.exemplarTgi(), hidden);
                Exemplar oe = orig == null ? null : PluginIndex.readExemplar(orig);
                if (oe != null && !oe.has(Exemplar.P_LOT_RESOURCE_KEY) && !oe.has(Exemplar.P_ITEM_BUTTON_ID)) cohortOnly++;
                if (samples.size() < 12) samples.add(it.linkName() + " " + it.exemplarTgi() + " effective from " + eff.file().path());
            }
        }
        System.out.printf("hidden-flagged items: %d%n  effectively hidden: %d%n  still visible: %d (effective copy NOT from hidden folder: %d, button only in cohort: %d)%n  lot not installed: %d%n",
                flagged, effHidden, stillVisible, overridden, cohortOnly, notInstalled);
        samples.forEach(s -> System.out.println("  " + s));
        List<String> order = HiddenLots.checkLoadOrder(idx, hidden);
        System.out.println("load-order overrides: " + order.size());
        order.stream().limit(8).forEach(s -> System.out.println("  " + s));
    }
}
