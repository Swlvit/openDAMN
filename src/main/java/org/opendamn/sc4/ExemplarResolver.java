package org.opendamn.sc4;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.opendamn.dbpf.Exemplar;
import org.opendamn.dbpf.Property;
import org.opendamn.dbpf.Tgi;

/**
 * Resolves exemplar properties the way the game does: own properties first, then the
 * parent cohort chain. Parsed exemplars are cached.
 */
public final class ExemplarResolver {

    private final PluginIndex index;
    private final Map<Tgi, Exemplar> cache = new HashMap<>();

    public ExemplarResolver(PluginIndex index) {
        this.index = index;
    }

    public PluginIndex index() {
        return index;
    }

    /** Parsed exemplar of a located resource (cached by TGI + file). */
    public Exemplar parse(PluginIndex.Located l) throws IOException {
        Tgi key = l.tgi();
        Exemplar e = l == index.effective(key) ? cache.get(key) : null;
        if (e == null) {
            e = PluginIndex.readExemplar(l);
            if (l == index.effective(key)) cache.put(key, e);
        }
        return e;
    }

    /** Properties of an exemplar merged with its cohort chain (own values win). */
    public Map<Integer, Property> resolve(PluginIndex.Located l) throws IOException {
        Map<Integer, Property> out = new LinkedHashMap<>();
        Exemplar e = parse(l);
        List<Exemplar> chain = new ArrayList<>();
        chain.add(e);
        Tgi parent = e.parent();
        for (int depth = 0; depth < 8 && parent != null && (parent.type() != 0 || parent.instance() != 0); depth++) {
            PluginIndex.Located pl = index.effective(parent);
            if (pl == null) {
                // Parent cohorts are sometimes referenced with the exemplar type.
                pl = index.effective(new Tgi(Tgi.TYPE_COHORT, parent.group(), parent.instance()));
            }
            if (pl == null) break;
            Exemplar pe = parse(pl);
            chain.add(pe);
            parent = pe.parent();
        }
        for (Exemplar x : chain) {
            for (Property p : x.properties()) out.putIfAbsent(p.id(), p);
        }
        return out;
    }

    public void clear() {
        cache.clear();
    }
}
