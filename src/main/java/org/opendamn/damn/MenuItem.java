package org.opendamn.damn;

import java.nio.file.Path;
import java.util.Objects;
import org.opendamn.dbpf.Tgi;

/** One entry in a DAMN menu: a lot (building/flora), network or zone that can be plopped. */
public final class MenuItem {

    public enum Kind {
        BUILDING("building"), FLORA("flora"), NETWORK("network"), ZONE("zone");

        public final String luaName;

        Kind(String luaName) {
            this.luaName = luaName;
        }

        public static Kind fromLua(String s) {
            for (Kind k : values()) if (k.luaName.equalsIgnoreCase(s.trim())) return k;
            return BUILDING;
        }
    }

    private String linkName = "";
    private String infoText1 = "";
    private String infoText2 = "";
    private String description = "";
    private String info = "";
    private boolean hidden;
    private Kind kind = Kind.BUILDING;

    /** Group/instance of the building or flora exemplar (for networks: instance = button ID). */
    private int exemplarGroup;
    private int exemplarInstance;

    /** 44x44 menu icon as PNG bytes (may be null; a placeholder is generated on save). */
    private byte[] icon;
    /** Preview image PNG embedded in the menu file (null when absent or external). */
    private byte[] preview;
    /** Preview image reference to a PNG inside another plugin (used when {@link #preview} is null). */
    private Tgi previewRef;

    /** Where the lot was found when it was added (not saved in the menu file). */
    private transient Path sourceFile;

    public MenuItem copy() {
        MenuItem m = new MenuItem();
        m.linkName = linkName;
        m.infoText1 = infoText1;
        m.infoText2 = infoText2;
        m.description = description;
        m.info = info;
        m.hidden = hidden;
        m.kind = kind;
        m.exemplarGroup = exemplarGroup;
        m.exemplarInstance = exemplarInstance;
        m.icon = icon;
        m.preview = preview;
        m.previewRef = previewRef;
        m.sourceFile = sourceFile;
        return m;
    }

    /** Identity of the plopped object; two items with the same key are duplicates. */
    public String key() {
        return kind == Kind.NETWORK || kind == Kind.ZONE
                ? kind.luaName + ":" + Tgi.hex(exemplarInstance)
                : Tgi.hex(exemplarGroup) + ":" + Tgi.hex(exemplarInstance);
    }

    public Tgi exemplarTgi() {
        return new Tgi(Tgi.TYPE_EXEMPLAR, exemplarGroup, exemplarInstance);
    }

    public String linkName() { return linkName; }
    public void setLinkName(String s) { linkName = Objects.requireNonNullElse(s, ""); }
    public String infoText1() { return infoText1; }
    public void setInfoText1(String s) { infoText1 = Objects.requireNonNullElse(s, ""); }
    public String infoText2() { return infoText2; }
    public void setInfoText2(String s) { infoText2 = Objects.requireNonNullElse(s, ""); }
    public String description() { return description; }
    public void setDescription(String s) { description = Objects.requireNonNullElse(s, ""); }
    public String info() { return info; }
    public void setInfo(String s) { info = Objects.requireNonNullElse(s, ""); }
    public boolean hidden() { return hidden; }
    public void setHidden(boolean h) { hidden = h; }
    public Kind kind() { return kind; }
    public void setKind(Kind k) { kind = Objects.requireNonNull(k); }
    public int exemplarGroup() { return exemplarGroup; }
    public int exemplarInstance() { return exemplarInstance; }

    public void setExemplar(int group, int instance) {
        exemplarGroup = group;
        exemplarInstance = instance;
    }

    public byte[] icon() { return icon; }
    public void setIcon(byte[] png) { icon = png; }
    public byte[] preview() { return preview; }
    public void setPreview(byte[] png) { preview = png; if (png != null) previewRef = null; }
    public Tgi previewRef() { return previewRef; }
    public void setPreviewRef(Tgi ref) { previewRef = ref; if (ref != null) preview = null; }
    public Path sourceFile() { return sourceFile; }
    public void setSourceFile(Path p) { sourceFile = p; }

    @Override
    public String toString() {
        return linkName + " [" + key() + "]";
    }
}
