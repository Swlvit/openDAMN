package org.opendamn.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.opendamn.Settings;
import org.opendamn.damn.DamnMenus;
import org.opendamn.damn.MenuItem;
import org.opendamn.damn.TemplateStore;
import org.opendamn.sc4.GamePaths;
import org.opendamn.sc4.LotCatalog;
import org.opendamn.sc4.LotFilters;
import org.opendamn.sc4.PluginIndex;
import org.opendamn.sc4.SubmenuIndex;
import org.opendamn.util.Log;

/** Shared application state. All mutation happens on the Swing event thread. */
public final class AppContext {

    public final Settings settings;
    public final TemplateStore templates;
    public final List<MenuItem> clipboard = new ArrayList<>();
    private LotFilters filters;

    private Path gameDir;
    private Path userPlugins;
    private Path overrideDamnDir;
    private Path overrideHiddenDir;
    private Path browseRoot;
    private DamnMenus menus;
    private PluginIndex index = PluginIndex.empty();
    private LotCatalog catalog = new LotCatalog(PluginIndex.empty());
    private SubmenuIndex submenuIndex;
    private final List<Runnable> indexListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> dirtyListeners = new CopyOnWriteArrayList<>();

    public AppContext(Settings settings) {
        this.settings = settings;
        this.templates = new TemplateStore(Settings.configDir().resolve("templates"));
        this.gameDir = settings.getPath(Settings.GAME_DIR);
        this.userPlugins = settings.getPath(Settings.PLUGINS_DIR);
        this.browseRoot = settings.getPath(Settings.BROWSE_DIR);
    }

    public LotFilters filters() {
        if (filters == null) filters = LotFilters.loadDefault();
        return filters;
    }

    // ---- paths

    public Path gameDir() { return gameDir; }
    public Path userPlugins() { return userPlugins; }

    public void setPaths(Path game, Path plugins) {
        this.gameDir = game;
        this.userPlugins = plugins;
        settings.setPath(Settings.GAME_DIR, game);
        settings.setPath(Settings.PLUGINS_DIR, plugins);
        settings.save();
    }

    public Path gamePlugins() {
        return gameDir == null ? null : gameDir.resolve("Plugins");
    }

    public Path damnDir() {
        return gameDir == null ? null : gameDir.resolve("Plugins").resolve("DAMN");
    }

    public boolean damnInstalled() {
        Path d = damnDir();
        return d != null && Files.isDirectory(d.resolve("Support"));
    }

    /** Folder shown in the plugins panel (user plugins unless changed). */
    public Path browseRoot() {
        return browseRoot != null && Files.isDirectory(browseRoot) ? browseRoot : userPlugins;
    }

    public void setBrowseRoot(Path p) {
        browseRoot = p;
        settings.setPath(Settings.BROWSE_DIR, p);
        settings.save();
    }

    public Path hiddenDir() {
        String name = settings.get(Settings.HIDDEN_FOLDER, "z_zDAMN_Hidden");
        return userPlugins == null ? null : userPlugins.resolve(name);
    }

    public int maxItems() {
        return settings.getInt(Settings.MAX_ITEMS, 30);
    }

    /** Folders and files indexed for lots, in game load order. */
    public List<Path> indexRoots() {
        Set<Path> r = new LinkedHashSet<>();
        if (gameDir != null) {
            r.addAll(GamePaths.gameDataFiles(gameDir, settings.getPath(Settings.LANGUAGE_DIR)));
            r.add(gameDir.resolve("Plugins"));
        }
        if (userPlugins != null) r.add(userPlugins);
        Path b = browseRoot();
        if (b != null && r.stream().noneMatch(b::startsWith)) r.add(b);
        return new ArrayList<>(r);
    }

    // ---- menus

    public DamnMenus menus() { return menus; }

    public void loadMenus() throws IOException {
        Path d = damnDir();
        menus = d == null ? null : DamnMenus.load(d);
        if (menus != null) menus.setLog(Log::info);
    }

    // ---- index

    public PluginIndex index() { return index; }
    public LotCatalog catalog() { return catalog; }

    /** Sub-menu scan of the current index, or null until it has been built. */
    public SubmenuIndex submenuIndex() { return submenuIndex; }

    public void setSubmenuIndex(SubmenuIndex si) { this.submenuIndex = si; }

    public void setIndex(PluginIndex idx) {
        this.index = idx;
        this.catalog = new LotCatalog(idx);
        this.submenuIndex = null;
        for (Runnable r : indexListeners) r.run();
    }

    public void onIndexChanged(Runnable r) {
        indexListeners.add(r);
    }

    public void onDirtyChanged(Runnable r) {
        dirtyListeners.add(r);
    }

    public void fireDirty() {
        for (Runnable r : dirtyListeners) r.run();
    }

    public boolean isDirty() {
        return menus != null && menus.isDirty();
    }
}
