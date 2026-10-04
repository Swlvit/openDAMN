package org.opendamn.sc4;

import java.nio.file.Path;
import java.util.*;
import org.opendamn.dbpf.*;

public class HideDiag {
    public static void main(String[] a) throws Exception {
        PluginIndex idx = PluginIndex.build(List.of(Arrays.stream(a).skip(2).map(Path::of).toArray(Path[]::new)),
                Path.of(a[0]), (d, n, p) -> {});
        DbpfFile hid = DbpfFile.open(Path.of(a[1]));
        int shown = 0;
        for (DbpfFile.Entry e : hid.entries()) {
            if (e.tgi().type() != Tgi.TYPE_EXEMPLAR) continue;
            Exemplar h = Exemplar.parse(hid.read(e));
            List<PluginIndex.Located> all = idx.all(e.tgi());
            System.out.println("== " + e.tgi() + " fmt=" + h.format() + " occurrences=" + all.size());
            for (PluginIndex.Located l : all) {
                if (l.file().path().equals(hid.path())) continue;
                if (l.file().path().toString().contains("DAMN_Hidden")) { System.out.println("  other hidden copy: " + l.file().path()); continue; }
                Exemplar o = PluginIndex.readExemplar(l);
                System.out.println("  orig " + l.file().path().getFileName() + " fmt=" + o.format() + " parent=" + o.parent() + " vs " + h.parent());
                Map<Integer, Property> om = new LinkedHashMap<>(), hm = new LinkedHashMap<>();
                o.properties().forEach(p -> om.put(p.id(), p)); h.properties().forEach(p -> hm.put(p.id(), p));
                for (var k : om.keySet()) if (!hm.containsKey(k)) System.out.println("  removed " + om.get(k));
                for (var k : hm.keySet()) if (!om.containsKey(k)) System.out.println("  added " + hm.get(k));
                for (var k : hm.keySet()) if (om.containsKey(k) && !om.get(k).equals(hm.get(k))) System.out.println("  changed " + om.get(k) + " -> " + hm.get(k));
                List<Integer> oo = new ArrayList<>(om.keySet()); oo.retainAll(hm.keySet());
                List<Integer> ho = new ArrayList<>(hm.keySet()); ho.retainAll(om.keySet());
                if (!oo.equals(ho)) System.out.println("  ORDER differs");
            }
            if (++shown >= 4) break;
        }
    }
}
