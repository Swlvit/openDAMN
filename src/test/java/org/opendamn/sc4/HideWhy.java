package org.opendamn.sc4;

import java.nio.file.Path;
import java.util.*;
import org.opendamn.damn.*;
import org.opendamn.dbpf.*;

/** Read-only: classifies hidden-flagged items that are still visible. */
public class HideWhy {
    public static void main(String[] a) throws Exception {
        Path cache = Path.of(a[0]), damn = Path.of(a[1]), hidden = Path.of(a[2]);
        List<Path> roots = new ArrayList<>();
        for (int k = 3; k < a.length; k++) roots.add(Path.of(a[k]));
        PluginIndex idx = PluginIndex.build(roots, cache, (d, n, p) -> {});
        DamnMenus m = DamnMenus.load(damn);
        Map<String, Integer> why = new TreeMap<>();
        Map<String, List<String>> ex = new TreeMap<>();
        Path gameDir = roots.get(0).getParent();
        for (MenuItem it : m.allItems()) {
            if (!it.hidden() || it.kind() == MenuItem.Kind.NETWORK) continue;
            PluginIndex.Located eff = idx.effective(it.exemplarTgi());
            if (eff == null) continue;
            Exemplar e = PluginIndex.readExemplar(eff);
            boolean ownLrk = e.has(Exemplar.P_LOT_RESOURCE_KEY), ownBtn = e.has(Exemplar.P_ITEM_BUTTON_ID);
            Map<Integer, Property> res = new ExemplarResolver(idx).resolve(eff);
            boolean anyLrk = res.containsKey(Exemplar.P_LOT_RESOURCE_KEY), anyBtn = res.containsKey(Exemplar.P_ITEM_BUTTON_ID);
            boolean inHidden = eff.file().path().startsWith(hidden);
            boolean maxis = eff.file().path().startsWith(gameDir) ;
            String k;
            if (!anyLrk && !anyBtn) continue; // hidden
            if (inHidden) k = "copy in hidden folder still has button (" + (ownLrk||ownBtn ? "own" : "cohort") + ")";
            else if (maxis) k = "no copy: game file " + eff.file().path().getFileName();
            else {
                boolean copyExists = !idx.all(it.exemplarTgi()).stream().filter(l -> l.file().path().startsWith(hidden)).toList().isEmpty();
                k = copyExists ? "copy exists but overridden later" : "no copy in hidden folder (plugin)";
            }
            why.merge(k, 1, Integer::sum);
            ex.computeIfAbsent(k, x -> new ArrayList<>());
            if (ex.get(k).size() < 4) ex.get(k).add(it.linkName() + " " + it.exemplarTgi() + " @ " + eff.file().path().getFileName()
                    + " parent=" + e.parent() + " type=" + e.exemplarType());
        }
        why.forEach((k, v) -> { System.out.println(v + "\t" + k); ex.get(k).forEach(s -> System.out.println("\t\t" + s)); });
    }
}
