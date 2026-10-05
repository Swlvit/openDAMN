package org.opendamn.sc4;

import java.nio.file.Path;
import java.util.*;
import org.opendamn.damn.*;
import org.opendamn.dbpf.*;

/** Read-only: traces every DAMN item whose name matches args[3] through the plugin index. */
public class LotTrace {
    public static void main(String[] a) throws Exception {
        Path cache = Path.of(a[0]), damn = Path.of(a[1]), hidden = Path.of(a[2]);
        String q = a[3].toLowerCase();
        List<Path> roots = new ArrayList<>();
        for (int k = 4; k < a.length; k++) roots.add(Path.of(a[k]));
        PluginIndex idx = PluginIndex.build(roots, cache, (d, n, p) -> {});
        DamnMenus m = DamnMenus.load(damn);
        for (MenuNode n : m.root().descendantsAndSelf()) {
            m.ensureLoaded(n);
            for (MenuItem it : n.items()) {
                if (!it.linkName().toLowerCase().contains(q)) continue;
                System.out.println("== " + it.linkName() + " in " + n.displayPath() + " hidden=" + it.hidden() + " kind=" + it.kind() + " " + it.exemplarTgi());
                System.out.println("   expected hidden file: " + HiddenLots.fileNameFor(n));
                for (PluginIndex.Located l : idx.all(it.exemplarTgi())) {
                    Exemplar e = PluginIndex.readExemplar(l);
                    System.out.println("   occ order=" + l.file().order() + " " + l.file().path().toString().replace(roots.get(roots.size()-1).toString(), "<P>")
                            + " type=" + e.exemplarType() + " fmt=" + e.format() + " LRK=" + e.has(Exemplar.P_LOT_RESOURCE_KEY) + " BTN=" + e.has(Exemplar.P_ITEM_BUTTON_ID) + " parent=" + e.parent());
                }
                List<PluginIndex.Located> sameInst = idx.exemplarsWithInstance(it.exemplarInstance());
                for (PluginIndex.Located l : sameInst) if (!l.tgi().equals(it.exemplarTgi()))
                    System.out.println("   OTHER GROUP " + l.tgi() + " in " + l.file().path().getFileName() + " exType=" + l.res().exType() + " LRK=" + l.res().has(1));
                var lot = new LotCatalog(idx).lotConfigFor(it.exemplarInstance());
                System.out.println("   lot config: " + (lot == null ? "none" : lot.tgi() + " in " + lot.file().path().getFileName()));
            }
        }
    }
}
