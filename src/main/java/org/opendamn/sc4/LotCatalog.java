package org.opendamn.sc4;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.opendamn.damn.MenuItem;
import org.opendamn.dbpf.Exemplar;
import org.opendamn.dbpf.LText;
import org.opendamn.dbpf.Property;
import org.opendamn.dbpf.Tgi;

/** Finds lots (buildings and flora) in plugin files and describes them. */
public final class LotCatalog {

    private final PluginIndex index;
    private final ExemplarResolver resolver;
    /** building/flora exemplar instance -> lot configurations that place it. */
    private final Map<Integer, List<PluginIndex.Located>> lotsByBuilding = new HashMap<>();

    public LotCatalog(PluginIndex index) {
        this.index = index;
        this.resolver = new ExemplarResolver(index);
        for (IndexedFile f : index.files()) {
            for (IndexedFile.Res r : f.resources()) {
                if (r.exType() == Exemplar.TYPE_LOT_CONFIG && r.buildings() != null) {
                    PluginIndex.Located l = new PluginIndex.Located(f, r);
                    for (int b : r.buildings()) lotsByBuilding.computeIfAbsent(b, k -> new ArrayList<>(1)).add(l);
                }
            }
        }
    }

    public PluginIndex index() { return index; }
    public ExemplarResolver resolver() { return resolver; }

    /**
     * Lots defined in the given files, in file/load order.
     *
     * @param errors receives problems with individual exemplars
     */
    public List<LotInfo> lotsIn(Collection<Path> files, Consumer<String> errors) {
        Set<Path> wanted = new HashSet<>(files);
        List<LotInfo> out = new ArrayList<>();
        Set<Tgi> seen = new HashSet<>();
        for (IndexedFile f : index.files()) {
            if (!wanted.contains(f.path())) continue;
            for (IndexedFile.Res r : f.resources()) {
                if (r.tgi().type() != Tgi.TYPE_EXEMPLAR) continue;
                int t = r.exType();
                if (t != Exemplar.TYPE_BUILDING && t != Exemplar.TYPE_FLORA && t != -1) continue;
                if (!seen.add(r.tgi())) continue;
                try {
                    LotInfo li = describe(new PluginIndex.Located(f, r));
                    if (li != null) out.add(li);
                } catch (IOException | RuntimeException e) {
                    errors.accept(f.path().getFileName() + " " + r.tgi() + ": " + e.getMessage());
                }
            }
        }
        return out;
    }

    /** Describes an exemplar, or returns null when it is not a building or flora item. */
    public LotInfo describe(PluginIndex.Located l) throws IOException {
        Map<Integer, Property> props = resolver.resolve(l);
        int type = (int) longOf(props, Exemplar.P_EXEMPLAR_TYPE, -1);
        if (type != Exemplar.TYPE_BUILDING && type != Exemplar.TYPE_FLORA) return null;

        LotInfo li = new LotInfo();
        li.exemplar = l.tgi();
        li.file = l.file().path();
        li.properties = props;
        li.kind = type == Exemplar.TYPE_FLORA ? MenuItem.Kind.FLORA : MenuItem.Kind.BUILDING;
        li.exemplarName = stringOf(props, Exemplar.P_EXEMPLAR_NAME);
        li.plopCost = longOf(props, Exemplar.P_PLOP_COST, LotInfo.MISSING);
        li.monthlyCost = longOf(props, Exemplar.P_BUDGET_COST, LotInfo.MISSING);
        li.bulldozeCost = longOf(props, Exemplar.P_BULLDOZE_COST, LotInfo.MISSING);
        li.itemOrder = longOf(props, Exemplar.P_ITEM_ORDER, Long.MAX_VALUE);
        li.budgetDepartment = valuesOf(props, Exemplar.P_BUDGET_DEPARTMENT);
        li.budgetPurpose = valuesOf(props, Exemplar.P_BUDGET_PURPOSE);

        Property og = props.get(Exemplar.P_OCCUPANT_GROUPS);
        if (og != null) {
            li.occupantGroups = og.valueString();
            for (long v : og.values()) {
                if (v == 0x150A) li.landmark = true;
                if (v == 0x150B) li.reward = true;
            }
        }

        boolean hasIcon = props.containsKey(Exemplar.P_ITEM_ICON);
        if (li.kind == MenuItem.Kind.FLORA) {
            boolean button = props.containsKey(Exemplar.P_ITEM_BUTTON_ID);
            li.ploppable = button || hasIcon;
            li.hidden = hasIcon && !button;
        } else {
            boolean lrk = props.containsKey(Exemplar.P_LOT_RESOURCE_KEY);
            PluginIndex.Located lot = lotConfigFor(l.tgi().instance());
            boolean ploppedZone = false;
            if (lot != null) {
                li.lotConfig = lot.tgi();
                Map<Integer, Property> lp = resolver.resolve(lot);
                li.lotProperties = lp;
                li.lotConfigName = stringOf(lp, Exemplar.P_EXEMPLAR_NAME);
                Property size = lp.get(Exemplar.P_LOT_CONFIG_SIZE);
                if (size != null && size.values().length >= 2) {
                    li.width = (int) size.values()[0];
                    li.depth = (int) size.values()[1];
                }
                Property z = lp.get(Exemplar.P_LOT_CONFIG_ZONE_TYPES);
                if (z != null) for (long v : z.values()) if (v == 0x0F) ploppedZone = true;
            }
            li.ploppable = hasIcon || lrk || ploppedZone;
            li.hidden = li.ploppable && !lrk;
        }

        li.iconTgi = findIcon(props, l.tgi().instance());
        li.hasIcon = li.iconTgi != null;
        li.previewTgi = findPreview(l.tgi().instance(), li.iconTgi);
        li.name = nameOf(props, li.lotProperties);
        li.description = descriptionOf(props);
        return li;
    }

    /** The lot configuration that places a building (prefers ploppable ones). */
    public PluginIndex.Located lotConfigFor(int buildingInstance) {
        List<PluginIndex.Located> lots = lotsByBuilding.get(buildingInstance);
        if (lots == null || lots.isEmpty()) return null;
        for (PluginIndex.Located l : lots) if (l.res().has(IndexedFile.Res.F_PLOPPED_ZONE)) return l;
        return lots.get(lots.size() - 1);
    }

    private Tgi findIcon(Map<Integer, Property> props, int instance) {
        long iconId = longOf(props, Exemplar.P_ITEM_ICON, -1);
        if (iconId >= 0) {
            Tgi t = new Tgi(Tgi.TYPE_PNG, Tgi.GROUP_PNG_MENU_ICON, (int) iconId);
            if (index.effective(t) != null) return t;
        }
        Tgi t = new Tgi(Tgi.TYPE_PNG, Tgi.GROUP_PNG_MENU_ICON, instance);
        return index.effective(t) != null ? t : null;
    }

    /** A preview image: a PNG with the exemplar's instance in a group other than the icon group. */
    private Tgi findPreview(int instance, Tgi icon) {
        for (IndexedFile f : candidateFiles(instance)) {
            for (IndexedFile.Res r : f.resources()) {
                Tgi t = r.tgi();
                if (t.type() == Tgi.TYPE_PNG && t.instance() == instance && t.group() != Tgi.GROUP_PNG_MENU_ICON
                        && !t.equals(icon)) {
                    return t;
                }
            }
        }
        return null;
    }

    private List<IndexedFile> candidateFiles(int instance) {
        List<IndexedFile> r = new ArrayList<>();
        for (PluginIndex.Located l : index.exemplarsWithInstance(instance)) r.add(l.file());
        return r;
    }

    private String nameOf(Map<Integer, Property> props, Map<Integer, Property> lotProps) {
        String n = textFromKey(props.get(Exemplar.P_USER_VISIBLE_NAME_KEY));
        if (n == null || n.isBlank()) n = stringOf(props, Exemplar.P_ITEM_NAME);
        if (n.isBlank()) n = textFromKey(lotProps.get(Exemplar.P_USER_VISIBLE_NAME_KEY));
        if (n == null || n.isBlank()) n = stringOf(lotProps, Exemplar.P_ITEM_NAME);
        return n == null ? "" : n.strip();
    }

    private String descriptionOf(Map<Integer, Property> props) {
        Property key = props.get(Exemplar.P_ITEM_DESCRIPTION_KEY);
        if (key != null && key.isString()) return key.displayText(); // common authoring mistake
        String d = textFromKey(key);
        if (d == null || d.isBlank()) d = stringOf(props, Exemplar.P_ITEM_DESCRIPTION);
        return d == null ? "" : d.strip();
    }

    /** Looks up an LTEXT given a Type/Group/Instance key property. */
    public String textFromKey(Property key) {
        if (key == null || key.isString() || key.values().length < 3) return null;
        Tgi t = new Tgi((int) key.values()[0], (int) key.values()[1], (int) key.values()[2]);
        if (t.type() != Tgi.TYPE_LTEXT) t = new Tgi(Tgi.TYPE_LTEXT, t.group(), t.instance());
        PluginIndex.Located l = index.findText(t);
        if (l == null) return null;
        try {
            return LText.decode(PluginIndex.read(l));
        } catch (IOException e) {
            return null;
        }
    }

    /** Reads a PNG resource, or null. */
    public byte[] png(Tgi t) {
        if (t == null) return null;
        PluginIndex.Located l = index.effective(t);
        if (l == null) return null;
        try {
            return PluginIndex.read(l);
        } catch (IOException e) {
            return null;
        }
    }

    static long longOf(Map<Integer, Property> props, int id, long def) {
        Property p = props.get(id);
        return p == null ? def : p.getLong(0, def);
    }

    static String stringOf(Map<Integer, Property> props, int id) {
        Property p = props.get(id);
        return p != null && p.isString() ? p.displayText() : "";
    }

    static String valuesOf(Map<Integer, Property> props, int id) {
        Property p = props.get(id);
        return p == null ? "" : p.valueString();
    }
}
