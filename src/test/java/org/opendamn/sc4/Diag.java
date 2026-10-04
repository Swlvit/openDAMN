package org.opendamn.sc4;

import java.nio.file.Path;
import java.util.*;
import org.opendamn.damn.*;
import org.opendamn.dbpf.*;

/** Manual check: which exemplars do real DAMN items point at? */
public class Diag {
    public static void main(String[] a) throws Exception {
        PluginIndex idx = PluginIndex.build(List.of(Arrays.stream(a).skip(2).map(Path::of).toArray(Path[]::new)),
                Path.of(a[0]), (d, n, p) -> {});
        DamnMenus m = DamnMenus.load(Path.of(a[1]));
        Map<String, Integer> stats = new TreeMap<>();
        int shown = 0;
        for (MenuItem it : m.allItems()) {
            Tgi t = it.exemplarTgi();
            String kind = it.kind().luaName;
            PluginIndex.Located l = idx.effective(t);
            String r;
            if (l == null) {
                var any = idx.exemplarsWithInstance(it.exemplarInstance());
                r = any.isEmpty() ? "missing" : "otherGroup:" + any.get(0).res().exType();
            } else r = "type:" + l.res().exType() + (l.res().has(1) ? "+LRK" : "") + (l.res().has(4) ? "+icon" : "");
            stats.merge(kind + " " + r, 1, Integer::sum);
            if (kind.equals("network") && shown++ < 5) System.out.println("NET " + it.linkName() + " " + it.key() + " " + r);
        }
        stats.forEach((k, v) -> System.out.println(v + "\t" + k));
    }
}
