package org.opendamn.sc4;

import java.nio.file.Path;
import java.util.*;
import org.opendamn.damn.*;
import org.opendamn.dbpf.*;

/** Read-only: every building/flora whose name matches, with effective hidden state and DAMN membership. */
public class NameTrace {
    public static void main(String[] a) throws Exception {
        Path cache = Path.of(a[0]), damn = Path.of(a[1]);
        String q = a[2].toLowerCase();
        List<Path> roots = new ArrayList<>();
        for (int k = 3; k < a.length; k++) roots.add(Path.of(a[k]));
        PluginIndex idx = PluginIndex.build(roots, cache, (d, n, p) -> {});
        LotCatalog cat = new LotCatalog(idx);
        Map<Tgi, String> inDamn = new HashMap<>();
        DamnMenus m = DamnMenus.load(damn);
        for (MenuNode n : m.root().descendantsAndSelf()) { m.ensureLoaded(n); for (MenuItem it : n.items()) inDamn.put(it.exemplarTgi(), n.displayPath() + (it.hidden() ? " [hidden]" : "")); }
        Set<Tgi> seen = new HashSet<>();
        for (IndexedFile f : idx.files()) for (IndexedFile.Res r : f.resources()) {
            if (r.tgi().type() != Tgi.TYPE_EXEMPLAR || (r.exType() != 2 && r.exType() != 15) || !seen.add(r.tgi())) continue;
            PluginIndex.Located eff = idx.effective(r.tgi());
            LotInfo li = cat.describe(eff);
            if (li == null) continue;
            String nm = (li.displayName() + " " + li.exemplarName() + " " + li.lotConfigName()).toLowerCase();
            if (!nm.contains(q)) continue;
            System.out.println(li.displayName() + " | ex=" + li.exemplarName() + " | " + r.tgi() + " | ploppable=" + li.ploppable() + " visibleInGame=" + (li.ploppable() && !li.hidden())
                    + " | effective: " + eff.file().path().getFileName() + " | DAMN: " + inDamn.getOrDefault(r.tgi(), "-"));
        }
    }
}
