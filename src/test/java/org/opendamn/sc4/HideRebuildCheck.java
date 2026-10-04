package org.opendamn.sc4;

import java.nio.file.*;
import java.util.*;
import org.opendamn.damn.*;
import org.opendamn.dbpf.*;

/** Rebuilds the hidden folder into a temp dir (real files untouched) and verifies the result. */
public class HideRebuildCheck {
    public static void main(String[] a) throws Exception {
        Path cache = Path.of(a[0]), damn = Path.of(a[1]), realHidden = Path.of(a[2]), out = Path.of(a[3]);
        List<Path> roots = new ArrayList<>();
        for (int k = 4; k < a.length; k++) roots.add(Path.of(a[k]));
        PluginIndex idx = PluginIndex.build(roots, cache, (d, n, p) -> {});
        DamnMenus m = DamnMenus.load(damn);
        for (MenuNode n : m.root().descendantsAndSelf()) m.ensureLoaded(n);
        // Treat the real hidden folder as the "hidden dir" for originalOf, but write elsewhere:
        long t = System.currentTimeMillis();
        HiddenLots.Result r = HiddenLots.rebuildInto(m.root().descendantsAndSelf(), idx, realHidden, out, HiddenLots.SaveFormat.ORIGINAL);
        System.out.println("rebuild: " + r.hidden() + " hidden, " + r.filesWritten() + " files, problems " + r.problems().size() + " in " + (System.currentTimeMillis()-t) + " ms");
        // Verify every copy: no button props, other props identical to original, same as DAMN Manager's copy where it exists.
        int copies = 0, same = 0, differ = 0, bad = 0, oldOnly = 0;
        Set<Tgi> ours = new HashSet<>();
        try (var s = Files.list(out)) {
            for (Path f : s.toList()) {
                DbpfFile d = DbpfFile.open(f);
                for (var e : d.entries()) {
                    if (e.tgi().type() != Tgi.TYPE_EXEMPLAR) continue;
                    copies++; ours.add(e.tgi());
                    Exemplar c = Exemplar.parse(d.read(e));
                    if (c.has(Exemplar.P_LOT_RESOURCE_KEY) || c.has(Exemplar.P_ITEM_BUTTON_ID)) bad++;
                    Exemplar o = PluginIndex.readExemplar(HiddenLots.originalOf(idx, e.tgi(), realHidden));
                    o.remove(Exemplar.P_LOT_RESOURCE_KEY); o.remove(Exemplar.P_ITEM_BUTTON_ID);
                    if (!o.properties().equals(c.properties()) || !o.parent().equals(c.parent())) bad++;
                    var old = idx.all(e.tgi()).stream().filter(l -> l.file().path().startsWith(realHidden)).findFirst();
                    if (old.isPresent()) {
                        Exemplar oc = PluginIndex.readExemplar(old.get());
                        if (oc.properties().equals(c.properties())) same++; else differ++;
                    }
                }
            }
        }
        for (IndexedFile f : idx.files()) if (f.path().startsWith(realHidden))
            for (var res : f.resources()) if (res.tgi().type() == Tgi.TYPE_EXEMPLAR && !ours.contains(res.tgi())) oldOnly++;
        System.out.printf("copies %d, invalid %d, identical to DAMN Manager copy %d, different %d, old copies no longer needed %d%n",
                copies, bad, same, differ, oldOnly);
        r.problems().stream().limit(5).forEach(System.out::println);
    }
}
