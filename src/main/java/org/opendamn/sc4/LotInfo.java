package org.opendamn.sc4;

import java.nio.file.Path;
import java.util.Map;
import org.opendamn.damn.MenuItem;
import org.opendamn.dbpf.Property;
import org.opendamn.dbpf.Tgi;

/** Everything shown about a lot in the lots list and lot details panels. */
public final class LotInfo {

    public enum Category { PLOPPABLE, GROWABLE, LANDMARK, REWARD, FLORA, OTHER }

    public static final long MISSING = -99;

    Tgi exemplar;
    Tgi lotConfig;
    Path file;
    MenuItem.Kind kind = MenuItem.Kind.BUILDING;
    String exemplarName = "";
    String lotConfigName = "";
    String name = "";
    String description = "";
    int width = -1;
    int depth = -1;
    boolean ploppable;
    boolean landmark;
    boolean reward;
    boolean hidden;
    boolean hasIcon;
    long plopCost = MISSING;
    long monthlyCost = MISSING;
    long bulldozeCost = MISSING;
    long itemOrder = Long.MAX_VALUE;
    String occupantGroups = "";
    String budgetDepartment = "";
    String budgetPurpose = "";
    Tgi iconTgi;
    Tgi previewTgi;
    Map<Integer, Property> properties = Map.of();
    Map<Integer, Property> lotProperties = Map.of();

    public Tgi exemplar() { return exemplar; }
    public Tgi lotConfig() { return lotConfig; }
    public Path file() { return file; }
    public MenuItem.Kind kind() { return kind; }
    public String exemplarName() { return exemplarName; }
    public String lotConfigName() { return lotConfigName; }
    public String name() { return name; }
    public String description() { return description; }
    public int width() { return width; }
    public int depth() { return depth; }
    public boolean ploppable() { return ploppable; }
    public boolean landmark() { return landmark; }
    public boolean reward() { return reward; }
    public boolean hidden() { return hidden; }
    public boolean hasIcon() { return hasIcon; }
    public long plopCost() { return plopCost; }
    public long monthlyCost() { return monthlyCost; }
    public long bulldozeCost() { return bulldozeCost; }
    public long itemOrder() { return itemOrder; }
    public String occupantGroups() { return occupantGroups; }
    public String budgetDepartment() { return budgetDepartment; }
    public String budgetPurpose() { return budgetPurpose; }
    public Tgi iconTgi() { return iconTgi; }
    public Tgi previewTgi() { return previewTgi; }
    /** Resolved building/flora exemplar properties (including cohort values). */
    public Map<Integer, Property> properties() { return properties; }
    /** Resolved lot configuration properties (empty for flora). */
    public Map<Integer, Property> lotProperties() { return lotProperties; }

    public String sizeText() {
        if (kind == MenuItem.Kind.FLORA) return "flora";
        if (kind == MenuItem.Kind.NETWORK) return "unknown";
        return width > 0 ? width + " x " + depth : "unknown";
    }

    public Category category() {
        if (kind == MenuItem.Kind.FLORA) return Category.FLORA;
        if (reward) return Category.REWARD;
        if (landmark) return Category.LANDMARK;
        if (ploppable) return Category.PLOPPABLE;
        return Category.GROWABLE;
    }

    /** Display name: the in-game name if known, otherwise the exemplar name. */
    public String displayName() {
        if (!name.isBlank()) return name;
        if (!exemplarName.isBlank()) return exemplarName;
        return lotConfigName;
    }

    /** Stable identity, matching {@link MenuItem#key()}. */
    public String key() {
        return Tgi.hex(exemplar.group()) + ":" + Tgi.hex(exemplar.instance());
    }

    @Override
    public String toString() {
        return displayName();
    }
}
