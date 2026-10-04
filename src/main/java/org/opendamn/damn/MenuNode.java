package org.opendamn.damn;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** A menu (folder) in the DAMN tree. */
public final class MenuNode {

    private String name;
    private MenuNode parent;
    private final List<MenuNode> children = new ArrayList<>();

    /** Items, loaded lazily; null until loaded. */
    private List<MenuItem> items;
    /** The menu .dat files present on disk when loaded. */
    private final List<Path> diskFiles = new ArrayList<>();
    /** Folder location when loaded from disk; null for menus created in this session. */
    private Path diskPath;

    private boolean itemsDirty;

    public MenuNode(String name) {
        this.name = name;
    }

    public String name() { return name; }
    void setName(String n) { name = n; }
    public MenuNode parent() { return parent; }
    public boolean isRoot() { return parent == null; }

    public List<MenuNode> children() {
        return Collections.unmodifiableList(children);
    }

    void addChild(MenuNode c) {
        c.parent = this;
        children.add(c);
        sortChildren();
    }

    void removeChild(MenuNode c) {
        children.remove(c);
        c.parent = null;
    }

    void sortChildren() {
        children.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
    }

    public MenuNode child(String n) {
        for (MenuNode c : children) if (c.name.equalsIgnoreCase(n)) return c;
        return null;
    }

    /** Path segments below Root, e.g. ["Civic", "Education"]. */
    public List<String> segments() {
        List<String> s = new ArrayList<>();
        for (MenuNode n = this; n != null && !n.isRoot(); n = n.parent) s.add(0, n.name);
        return s;
    }

    public String displayPath() {
        List<String> s = segments();
        return s.isEmpty() ? "Root" : "Root/" + String.join("/", s);
    }

    public boolean isAncestorOf(MenuNode other) {
        for (MenuNode n = other; n != null; n = n.parent) if (n == this) return true;
        return false;
    }

    public boolean itemsLoaded() { return items != null; }

    /** Items; only valid after {@link DamnMenus#ensureLoaded}. */
    public List<MenuItem> items() {
        if (items == null) throw new IllegalStateException("Items of " + displayPath() + " not loaded");
        return items;
    }

    void setItems(List<MenuItem> list) { items = list; }
    public boolean itemsDirty() { return itemsDirty; }
    void setItemsDirty(boolean d) { itemsDirty = d; }
    List<Path> diskFiles() { return diskFiles; }

    /** True if the menu has at least one menu file on disk. */
    public boolean hasDiskFiles() { return !diskFiles.isEmpty(); }
    Path diskPath() { return diskPath; }
    void setDiskPath(Path p) { diskPath = p; }

    /** Number of items when loaded, otherwise -1. */
    public int itemCount() {
        return items == null ? -1 : items.size();
    }

    /** Entries the in-game window must show for this menu: items plus submenus. */
    public int entryCount() {
        return Math.max(0, itemCount()) + children.size();
    }

    public List<MenuNode> descendantsAndSelf() {
        List<MenuNode> r = new ArrayList<>();
        collect(this, r);
        return r;
    }

    private static void collect(MenuNode n, List<MenuNode> r) {
        r.add(n);
        for (MenuNode c : n.children) collect(c, r);
    }

    @Override
    public String toString() {
        return name;
    }

    static String key(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
