package org.opendamn.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.WindowConstants;
import org.opendamn.Settings;
import org.opendamn.damn.DamnInstaller;
import org.opendamn.damn.DamnMenus;
import org.opendamn.damn.ItemTemplate;
import org.opendamn.damn.MenuItem;
import org.opendamn.damn.MenuNode;
import org.opendamn.sc4.GamePaths;
import org.opendamn.sc4.HiddenLots;
import org.opendamn.sc4.LotInfo;
import org.opendamn.sc4.PluginIndex;
import org.opendamn.util.Images;
import org.opendamn.util.Log;

/** The main window: plugins and lots on top, DAMN menus below. */
public final class MainWindow extends JFrame implements LotsPanel.Actions, MenuTreePanel.Actions, MenuItemsPanel.Actions {

    final AppContext ctx;
    private final PluginFolderPanel folders;
    private final LotsPanel lots;
    private final LotDetailsPanel lotDetails;
    private final MenuTreePanel tree;
    private final MenuItemsPanel items;
    private final ItemDetailsPanel itemDetails;
    private final JLabel status = new JLabel(" ");
    private final JSplitPane top, bottom, vertical, topRight, bottomRight;
    private PluginFolderPanel.Selection lastSelection;

    public MainWindow(AppContext ctx) {
        super("OpenDAMN");
        this.ctx = ctx;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exit();
            }
        });

        folders = new PluginFolderPanel(ctx, this::folderSelected);
        lots = new LotsPanel(ctx, this, lotDetailsShow());
        lotDetails = new LotDetailsPanel(ctx);
        tree = new MenuTreePanel(ctx, this, this::menuSelected);
        itemDetails = new ItemDetailsPanel(ctx, this::itemApplied);
        items = new MenuItemsPanel(ctx, this, it -> itemDetails.load(currentMenu(), it));

        topRight = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Ui.titled(lots, "Lots"), Ui.titled(lotDetails, "Lot details"));
        top = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Ui.titled(folders, "Plugins (folder)"), topRight);
        bottomRight = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Ui.titled(items, "DAMN menu items"),
                Ui.titled(itemDetails, "Menu item details"));
        bottom = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Ui.titled(tree, "DAMN menus"), bottomRight);
        vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        for (JSplitPane sp : new JSplitPane[] {top, bottom, vertical, topRight, bottomRight}) sp.setContinuousLayout(true);
        topRight.setResizeWeight(0.62);
        bottomRight.setResizeWeight(0.5);
        top.setResizeWeight(0.2);
        bottom.setResizeWeight(0.2);
        vertical.setResizeWeight(0.45);

        JPanel content = new JPanel(new BorderLayout());
        content.add(vertical);
        status.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        content.add(status, BorderLayout.SOUTH);
        setContentPane(content);
        setJMenuBar(menuBar());
        restoreLayout();

        ctx.onDirtyChanged(this::updateTitle);
        ctx.onIndexChanged(() -> {
            if (lastSelection != null) folderSelected(lastSelection);
        });
        Log.addListener(e -> {
            if (e.level().compareTo(Log.Level.INFO) >= 0) {
                javax.swing.SwingUtilities.invokeLater(() -> status.setText(e.message()));
            }
        });
        updateTitle();
    }

    private java.util.function.Consumer<LotInfo> lotDetailsShow() {
        return l -> lotDetails.show(l);
    }

    // ------------------------------------------------------------------ menu bar

    private JMenuBar menuBar() {
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(Ui.action("Save changes", "Write all menu changes to the game", KeyStroke.getKeyStroke("ctrl S"),
                e -> save()));
        file.addSeparator();
        file.add(Ui.action("Game and plugin folders…", null, null, e -> Dialogs.setupPaths(this, ctx, false)));
        file.add(Ui.action("Settings…", null, KeyStroke.getKeyStroke("ctrl COMMA"), e -> Dialogs.settings(this, ctx)));
        file.addSeparator();
        file.add(Ui.action("Exit", null, KeyStroke.getKeyStroke("ctrl Q"), e -> exit()));
        mb.add(file);

        JMenu damn = new JMenu("DAMN");
        damn.add(Ui.action("Install / configure DAMN…", "Install the DAMN plugin or change the in-game window",
                null, e -> Dialogs.installer(this, ctx)));
        damn.add(Ui.action("Rebuild index", "Recreate DAMN-index.txt from the folders on disk", null, e -> reindex()));
        damn.add(Ui.action("Update hidden lots", "Rewrite the hidden lot copies from the current plugins", null,
                e -> rebuildHidden(true)));
        damn.add(Ui.action("Check hidden folder load order", null, null, e -> checkHiddenOrder()));
        damn.addSeparator();
        damn.add(Ui.action("Export menu to ZIP…", null, null, e -> Dialogs.exportZip(this, ctx, tree.selected())));
        damn.add(Ui.action("Import menu from ZIP…", null, null, e -> Dialogs.importZip(this, ctx, tree.selected())));
        damn.add(Ui.action("Export menu as web page…", null, null, e -> Dialogs.exportHtml(this, ctx, tree.selected())));
        damn.addSeparator();
        damn.add(Ui.action("Health check…", "Look for problems in the DAMN setup (read-only)", null,
                e -> Dialogs.health(this, ctx)));
        damn.add(Ui.action("Statistics…", null, null, e -> Dialogs.statistics(this, ctx)));
        mb.add(damn);

        JMenu add = new JMenu("Add");
        add.add(Ui.action("Original SimCity lots…", "Add Maxis lots from the game files", null,
                e -> Dialogs.maxisLots(this, ctx, tree.selected())));
        add.add(Ui.action("Network items…", "Add roads, rails, highways and other networks", null,
                e -> Dialogs.networks(this, ctx, tree.selected())));
        mb.add(add);

        JMenu tools = new JMenu("Tools");
        tools.add(Ui.action("Rescan plugins", "Update the plugin index (only changed files are read)",
                KeyStroke.getKeyStroke("F5"), e -> rescan(false)));
        tools.add(Ui.action("Full rescan", "Rebuild the plugin index from scratch", null, e -> rescan(true)));
        tools.addSeparator();
        tools.add(Ui.action("Item templates…", null, null, e -> Dialogs.templates(this, ctx)));
        tools.add(Ui.action("View clipboard…", null, null, e -> viewClipboard()));
        tools.add(Ui.action("Log viewer…", null, KeyStroke.getKeyStroke("ctrl L"), e -> Dialogs.log(this)));
        tools.addSeparator();
        tools.add(Ui.action("Restore default layout", null, null, e -> defaultLayout()));
        mb.add(tools);

        JMenu help = new JMenu("Help");
        help.add(Ui.action("About OpenDAMN…", null, null, e -> Dialogs.about(this)));
        mb.add(help);
        return mb;
    }

    // ------------------------------------------------------------------ startup

    /** Called once the window is visible: checks paths, loads menus, builds the index. */
    public void start() {
        if (ctx.gameDir() == null || !Files.isDirectory(ctx.gameDir())) {
            // Use the detected installation directly; only ask when nothing was found.
            List<GamePaths.Install> found = GamePaths.detect();
            if (!found.isEmpty()) {
                GamePaths.Install i = found.get(0);
                ctx.setPaths(i.gameDir(), i.userPlugins());
                if (ctx.settings.get(Settings.HIDDEN_FOLDER, null) == null) {
                    ctx.settings.set(Settings.HIDDEN_FOLDER, Dialogs.detectHiddenFolder(i.userPlugins()));
                    ctx.settings.save();
                }
                Log.info("Using " + i.gameDir() + " and " + i.userPlugins());
            } else {
                toFront();
                if (!Dialogs.setupPaths(this, ctx, true)) {
                    status.setText("Choose the game folder in File > Game and plugin folders.");
                    return;
                }
            }
        }
        reloadAll();
    }

    void reloadAll() {
        try {
            ctx.loadMenus();
        } catch (IOException e) {
            Ui.error(this, "DAMN menus", "Cannot read the DAMN menus: " + e.getMessage());
        }
        folders.reload();
        if (!ctx.damnInstalled()) {
            if (Ui.confirm(this, "DAMN", "The DAMN plugin is not installed in\n" + ctx.damnDir()
                    + "\n\nInstall it now?")) {
                Dialogs.installer(this, ctx);
            }
        }
        loadAllMenus();
        rescan(false);
    }

    /** Loads every menu so counts and colours are accurate. */
    private void loadAllMenus() {
        DamnMenus m = ctx.menus();
        if (m == null) {
            tree.reload();
            return;
        }
        Ui.runWithProgress(this, "Loading DAMN menus", p -> {
            List<String> warnings = new ArrayList<>();
            List<MenuNode> all = m.root().descendantsAndSelf();
            int k = 0;
            for (MenuNode n : all) {
                p.update(++k, all.size(), n.displayPath());
                for (String w : m.ensureLoaded(n)) warnings.add(n.displayPath() + ": " + w);
            }
            return warnings;
        }, warnings -> {
            tree.reload();
            for (String w : warnings) Log.warn(w);
            if (!warnings.isEmpty()) status.setText(warnings.size() + " problem(s) while reading menus — see the log.");
            if (m != null) {
                try {
                    if (!m.indexIsCurrent()) {
                        Log.info("DAMN-index.txt is out of date; it will be rebuilt on the next save.");
                    }
                } catch (IOException e) {
                    Log.warn("Cannot check the index: " + e.getMessage());
                }
            }
            updateTitle();
        });
    }

    void rescan(boolean full) {
        List<Path> roots = ctx.indexRoots();
        if (roots.isEmpty()) return;
        Path cache = ctx.settings.cacheFile();
        Ui.runWithProgress(this, "Indexing plugins", p -> {
            if (full) Files.deleteIfExists(cache);
            long t = System.currentTimeMillis();
            PluginIndex idx = PluginIndex.build(roots, cache, new PluginIndex.Progress() {
                @Override
                public void update(int done, int total, Path current) {
                    p.update(done, total, current.toString());
                }

                @Override
                public boolean cancelled() {
                    return p.cancelled();
                }
            });
            Log.info("Indexed " + idx.files().size() + " plugin files in " + (System.currentTimeMillis() - t) + " ms");
            for (String e : idx.errors()) Log.warn("Unreadable plugin: " + e);
            return idx;
        }, idx -> {
            ctx.setIndex(idx);
            status.setText("Indexed " + idx.files().size() + " plugin files"
                    + (idx.errors().isEmpty() ? "" : ", " + idx.errors().size() + " unreadable (see log)"));
        });
    }

    // ------------------------------------------------------------------ selection

    private void folderSelected(PluginFolderPanel.Selection sel) {
        lastSelection = sel;
        Ui.runWithProgress(this, "Reading lots", p -> {
            List<Path> files = sel.files();
            Set<Path> known = new java.util.HashSet<>();
            for (var f : ctx.index().files()) known.add(f.path());
            if (!known.containsAll(files)) {
                // Files added since the last scan: refresh the index first.
                PluginIndex idx = PluginIndex.build(ctx.indexRoots(), ctx.settings.cacheFile(),
                        (d, t, c) -> p.update(d, t, c.toString()));
                javax.swing.SwingUtilities.invokeAndWait(() -> ctx.setIndex(idx));
            }
            List<String> errors = new ArrayList<>();
            List<LotInfo> found = ctx.catalog().lotsIn(files, errors::add);
            return new Object[] {found, errors};
        }, r -> {
            @SuppressWarnings("unchecked")
            List<LotInfo> found = (List<LotInfo>) r[0];
            @SuppressWarnings("unchecked")
            List<String> errors = (List<String>) r[1];
            lots.setLots(found);
            for (String e : errors) Log.warn(e);
            if (!errors.isEmpty() && ctx.settings.getBool(Settings.SHOW_LOT_ERRORS, false)) {
                Ui.showLines(this, "Lot errors", errors.size() + " lot(s) could not be read:", errors);
            }
            status.setText(found.size() + " lot(s) in " + sel.folder().getFileName()
                    + (sel.subfolders() ? " and subfolders" : ""));
        });
    }

    private void menuSelected(MenuNode n) {
        itemDetails.load(null, null);
        if (n != null && !n.itemsLoaded()) {
            try {
                for (String w : ctx.menus().ensureLoaded(n)) Log.warn(w);
            } catch (IOException e) {
                Ui.error(this, "Menu", e.getMessage());
            }
        }
        items.show(n);
        lots.updateAddEnabled();
    }

    // ------------------------------------------------------------------ adding lots

    @Override
    public boolean canAdd() {
        return ctx.menus() != null && tree.selected() != null;
    }

    @Override
    public void addLots(List<LotInfo> l) {
        addLotsTo(tree.selected(), -1, l);
    }

    @Override
    public void dropLots(MenuNode target, Transfers.Lots l) {
        addLotsTo(target, -1, l.lots());
    }

    @Override
    public void dropLots(MenuNode target, int index, Transfers.Lots l) {
        addLotsTo(target, index, l.lots());
    }

    private void addLotsTo(MenuNode target, int index, List<LotInfo> l) {
        if (target == null || l.isEmpty()) return;
        ItemTemplate tpl = ctx.templates.get(ctx.settings.get(Settings.TEMPLATE, "Standard"));
        boolean empty = ctx.settings.getBool(Settings.EMPTY_UNFILLED, true);
        List<MenuItem> created = new ArrayList<>();
        for (LotInfo lot : l) created.add(itemFor(lot, tpl, empty));
        insertItems(target, index, created);
    }

    MenuItem itemFor(LotInfo lot, ItemTemplate tpl, boolean emptyUnfilled) {
        MenuItem m = tpl.apply(lot, ctx.catalog(), emptyUnfilled);
        byte[] icon = ctx.catalog().png(lot.iconTgi());
        m.setIcon(icon != null ? Images.normalizeIconPng(icon) : Images.placeholderIcon(m.linkName()));
        if (ctx.settings.getBool(Settings.ADD_PREVIEW, false) && lot.previewTgi() != null) {
            m.setPreview(ctx.catalog().png(lot.previewTgi()));
        }
        if (ctx.settings.getBool(Settings.HIDE_NEW, false) && lot.kind() != MenuItem.Kind.NETWORK) m.setHidden(true);
        if (ctx.settings.getBool(Settings.CLEAN_CHARS, false)) {
            m.setLinkName(clean(m.linkName()));
            m.setInfoText1(clean(m.infoText1()));
            m.setInfoText2(clean(m.infoText2()));
        }
        return m;
    }

    private static String clean(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            boolean ok = Character.isLetterOrDigit(c) && c < 128 || "^!$%&/() =?<>+-_#*.,;:'".indexOf(c) >= 0;
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    /** Adds items, honouring the limit and duplicate rules, and reports what was skipped. */
    void insertItems(MenuNode target, int index, List<MenuItem> newItems) {
        try {
            DamnMenus m = ctx.menus();
            m.ensureLoaded(target);
            int room = ctx.maxItems() - target.entryCount();
            List<MenuItem> toAdd = newItems;
            List<MenuItem> overflow = List.of();
            if (newItems.size() > room) {
                int choice = javax.swing.JOptionPane.showOptionDialog(this,
                        "\"" + target.name() + "\" can show " + ctx.maxItems() + " entries in game and has room for "
                                + Math.max(0, room) + " more.\nAdd all " + newItems.size()
                                + " anyway? (Entries over the limit are not visible in game.)",
                        "Menu limit", javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE,
                        null, new Object[] {"Add all", "Add " + Math.max(0, room), "Cancel"}, "Add " + Math.max(0, room));
                if (choice == 2 || choice < 0) return;
                if (choice == 1) {
                    toAdd = newItems.subList(0, Math.max(0, room));
                    overflow = newItems.subList(Math.max(0, room), newItems.size());
                }
            }
            boolean atEnd = ctx.settings.getBool(Settings.ADD_AT_END, true);
            List<MenuItem> added;
            if (index >= 0) {
                added = m.addItems(target, toAdd, true);
                target.items().removeAll(identity(added));
                target.items().addAll(Math.min(index, target.items().size()), added);
            } else {
                added = m.addItems(target, toAdd, atEnd);
            }
            int dupes = toAdd.size() - added.size();
            ctx.fireDirty();
            tree.select(target);
            items.show(target);
            items.select(added);
            tree.refreshLabels();
            String msg = "Added " + added.size() + " item(s) to " + target.displayPath()
                    + (dupes > 0 ? "; " + dupes + " already there" : "")
                    + (!overflow.isEmpty() ? "; " + overflow.size() + " left out (limit)" : "");
            status.setText(msg);
            Log.info(msg);
        } catch (IOException e) {
            Ui.error(this, "Add", e.getMessage());
        }
    }

    private static List<MenuItem> identity(List<MenuItem> l) {
        Set<MenuItem> s = Collections.newSetFromMap(new IdentityHashMap<>());
        s.addAll(l);
        return new ArrayList<>(s);
    }

    // ------------------------------------------------------------------ clipboard

    @Override
    public void copyLots(List<LotInfo> l) {
        ItemTemplate tpl = ctx.templates.get(ctx.settings.get(Settings.TEMPLATE, "Standard"));
        boolean empty = ctx.settings.getBool(Settings.EMPTY_UNFILLED, true);
        List<MenuItem> its = new ArrayList<>();
        for (LotInfo lot : l) its.add(itemFor(lot, tpl, empty));
        copyItems(its);
    }

    @Override
    public void copyItems(List<MenuItem> l) {
        int n = 0;
        Set<String> keys = new java.util.HashSet<>();
        for (MenuItem it : ctx.clipboard) keys.add(it.key());
        for (MenuItem it : l) {
            if (keys.add(it.key())) {
                ctx.clipboard.add(it.copy());
                n++;
            }
        }
        status.setText(n + " item(s) copied to the clipboard (" + ctx.clipboard.size() + " in clipboard)");
    }

    @Override
    public void cutItems(MenuNode from, List<MenuItem> l) {
        copyItems(l);
        from.items().removeAll(identity(l));
        ctx.menus().markItemsChanged(from);
        ctx.fireDirty();
        tree.refreshLabels();
    }

    @Override
    public void paste(MenuNode into) {
        if (ctx.clipboard.isEmpty()) {
            status.setText("The clipboard is empty.");
            return;
        }
        List<MenuItem> copies = new ArrayList<>();
        for (MenuItem it : ctx.clipboard) copies.add(it.copy());
        insertItems(into, -1, copies);
        if (ctx.settings.getBool(Settings.CLEAR_CLIPBOARD, false)) ctx.clipboard.clear();
    }

    @Override
    public void clearClipboard() {
        ctx.clipboard.clear();
        status.setText("Clipboard cleared.");
    }

    @Override
    public void viewClipboard() {
        Dialogs.clipboard(this, ctx);
    }

    @Override
    public void dropItems(MenuNode target, Transfers.Items t, boolean move) {
        if (target == t.source()) return;
        List<MenuItem> copies = new ArrayList<>();
        for (MenuItem it : t.items()) copies.add(it.copy());
        int before = target.itemsLoaded() ? target.items().size() : -1;
        insertItems(target, -1, copies);
        if (move && target.itemsLoaded() && target.items().size() > before) {
            // Remove only those that actually arrived (duplicates stay where they were).
            Set<String> arrived = new java.util.HashSet<>();
            for (MenuItem it : target.items()) arrived.add(it.key());
            List<MenuItem> gone = new ArrayList<>();
            for (MenuItem it : t.items()) if (arrived.contains(it.key())) gone.add(it);
            t.source().items().removeAll(identity(gone));
            ctx.menus().markItemsChanged(t.source());
            ctx.fireDirty();
            tree.refreshLabels();
        }
    }

    // ------------------------------------------------------------------ tree actions

    @Override
    public void hideAll(MenuNode n, boolean hide) {
        int count = 0;
        try {
            for (MenuNode d : n.descendantsAndSelf()) {
                ctx.menus().ensureLoaded(d);
                boolean changed = false;
                for (MenuItem it : d.items()) {
                    if (it.kind() == MenuItem.Kind.NETWORK || it.hidden() == hide) continue;
                    it.setHidden(hide);
                    changed = true;
                    count++;
                }
                if (changed) ctx.menus().markItemsChanged(d);
            }
        } catch (IOException e) {
            Ui.error(this, "Hide", e.getMessage());
        }
        ctx.fireDirty();
        items.refresh();
        status.setText((hide ? "Hid " : "Unhid ") + count + " lot(s); save to apply.");
    }

    @Override
    public void openFolder(MenuNode n) {
        Path p = ctx.menus().rootDir();
        for (String s : n.segments()) p = p.resolve(s);
        PluginFolderPanel.openInFileManager(Files.isDirectory(p) ? p : ctx.menus().rootDir());
    }

    // ------------------------------------------------------------------ save

    void save() {
        DamnMenus m = ctx.menus();
        if (m == null) return;
        if (itemDetails.hasPendingEdits()) itemDetails.apply();
        Path hiddenDir = ctx.hiddenDir();
        HiddenLots.SaveFormat fmt = HiddenLots.SaveFormat.valueOf(ctx.settings.get(Settings.HIDE_FORMAT, "ORIGINAL"));
        PluginIndex idx = ctx.index();
        Ui.runWithProgress(this, "Saving", p -> {
            p.message("Writing menus…");
            DamnMenus.SaveResult r = m.save(ctx.settings.backupDir(), it -> Images.placeholderIcon(it.linkName()));
            HiddenLots.Result h = null;
            if (hiddenDir != null) {
                p.message("Updating hidden lots…");
                for (MenuNode n : m.root().descendantsAndSelf()) m.ensureLoaded(n);
                h = HiddenLots.rebuild(m.root().descendantsAndSelf(), idx, hiddenDir, fmt);
            }
            return new Object[] {r, h};
        }, res -> {
            DamnMenus.SaveResult r = (DamnMenus.SaveResult) res[0];
            HiddenLots.Result h = (HiddenLots.Result) res[1];
            tree.refreshLabels();
            items.refresh();
            updateTitle();
            StringBuilder sb = new StringBuilder("Saved: " + r.filesWritten() + " menu file(s) written");
            if (r.foldersDeleted() > 0) sb.append(", ").append(r.foldersDeleted()).append(" menu(s) removed");
            if (h != null) sb.append(", ").append(h.hidden()).append(" lot(s) hidden");
            status.setText(sb.toString());
            Log.info(sb.toString());
            List<String> notes = new ArrayList<>(r.notes());
            if (h != null) notes.addAll(h.problems());
            if (!notes.isEmpty()) Ui.showLines(this, "Saved with notes", sb.toString(), notes);
        });
    }

    private void reindex() {
        if (ctx.menus() == null) return;
        try {
            ctx.menus().writeIndex();
            status.setText("DAMN index rebuilt.");
        } catch (IOException e) {
            Ui.error(this, "Rebuild index", e.getMessage());
        }
    }

    private void rebuildHidden(boolean confirm) {
        if (ctx.menus() == null || ctx.hiddenDir() == null) return;
        if (confirm && !Ui.confirm(this, "Update hidden lots", "Rewrite all hidden lot copies in\n" + ctx.hiddenDir()
                + "\nfrom the currently installed plugins?")) return;
        HiddenLots.SaveFormat fmt = HiddenLots.SaveFormat.valueOf(ctx.settings.get(Settings.HIDE_FORMAT, "ORIGINAL"));
        DamnMenus m = ctx.menus();
        Ui.runWithProgress(this, "Updating hidden lots", p -> {
            for (MenuNode n : m.root().descendantsAndSelf()) m.ensureLoaded(n);
            return HiddenLots.rebuild(m.root().descendantsAndSelf(), ctx.index(), ctx.hiddenDir(), fmt);
        }, h -> {
            String msg = h.hidden() + " lot(s) hidden in " + h.filesWritten() + " file(s); " + h.filesRemoved()
                    + " old file(s) removed.";
            if (h.problems().isEmpty()) Ui.info(this, "Hidden lots", msg);
            else Ui.showLines(this, "Hidden lots", msg, h.problems());
        });
    }

    private void checkHiddenOrder() {
        if (ctx.hiddenDir() == null) return;
        List<String> issues = HiddenLots.checkLoadOrder(ctx.index(), ctx.hiddenDir());
        if (issues.isEmpty()) Ui.info(this, "Load order", "The hidden lots folder loads last. Everything is fine.");
        else Ui.showLines(this, "Load order", "These plugins load after the hidden folder and undo hiding:", issues);
    }

    // ------------------------------------------------------------------ window

    private void updateTitle() {
        String d = ctx.isDirty() ? "* " : "";
        String where = ctx.gameDir() == null ? "" : " — " + ctx.gameDir().getFileName();
        setTitle(d + "OpenDAMN" + where);
    }

    void exit() {
        if (itemDetails.hasPendingEdits()) itemDetails.apply();
        if (ctx.isDirty()) {
            int r = javax.swing.JOptionPane.showConfirmDialog(this, "Save changes to the DAMN menus before closing?",
                    "Unsaved changes", javax.swing.JOptionPane.YES_NO_CANCEL_OPTION);
            if (r == javax.swing.JOptionPane.CANCEL_OPTION || r == javax.swing.JOptionPane.CLOSED_OPTION) return;
            if (r == javax.swing.JOptionPane.YES_OPTION) {
                try {
                    ctx.menus().save(ctx.settings.backupDir(), it -> Images.placeholderIcon(it.linkName()));
                    if (ctx.hiddenDir() != null) {
                        HiddenLots.rebuild(ctx.menus().root().descendantsAndSelf(), ctx.index(), ctx.hiddenDir(),
                                HiddenLots.SaveFormat.valueOf(ctx.settings.get(Settings.HIDE_FORMAT, "ORIGINAL")));
                    }
                } catch (IOException | RuntimeException e) {
                    Ui.error(this, "Save", "Saving failed: " + e.getMessage());
                    return;
                }
            }
        }
        saveLayout();
        try {
            ctx.templates.save();
        } catch (IOException e) {
            Log.warn("Cannot save templates: " + e.getMessage());
        }
        ctx.settings.save();
        dispose();
        System.exit(0);
    }

    private void restoreLayout() {
        Settings s = ctx.settings;
        int w = s.getInt("ui.width", 1400), h = s.getInt("ui.height", 900);
        setSize(new Dimension(w, h));
        setLocationRelativeTo(null);
        if (s.getBool("ui.maximized", false)) setExtendedState(MAXIMIZED_BOTH);
        javax.swing.SwingUtilities.invokeLater(() -> {
            setDivider(vertical, "ui.div.v");
            setDivider(top, "ui.div.top");
            setDivider(bottom, "ui.div.bottom");
            setDivider(topRight, "ui.div.topRight");
            setDivider(bottomRight, "ui.div.bottomRight");
        });
    }

    private void setDivider(JSplitPane sp, String key) {
        int v = ctx.settings.getInt(key, -1);
        if (v > 0) sp.setDividerLocation(v);
    }

    private void saveLayout() {
        Settings s = ctx.settings;
        boolean max = (getExtendedState() & MAXIMIZED_BOTH) != 0;
        s.setBool("ui.maximized", max);
        if (!max) {
            s.setInt("ui.width", getWidth());
            s.setInt("ui.height", getHeight());
        }
        s.setInt("ui.div.v", vertical.getDividerLocation());
        s.setInt("ui.div.top", top.getDividerLocation());
        s.setInt("ui.div.bottom", bottom.getDividerLocation());
        s.setInt("ui.div.topRight", topRight.getDividerLocation());
        s.setInt("ui.div.bottomRight", bottomRight.getDividerLocation());
    }

    private void defaultLayout() {
        for (String k : List.of("ui.div.v", "ui.div.top", "ui.div.bottom", "ui.div.topRight", "ui.div.bottomRight")) {
            ctx.settings.set(k, null);
        }
        vertical.setDividerLocation(0.45);
        top.setDividerLocation(0.2);
        bottom.setDividerLocation(0.2);
        topRight.setDividerLocation(0.62);
        bottomRight.setDividerLocation(0.5);
    }

    private void itemApplied() {
        items.repaintList();
        tree.refreshLabels();
    }

    private MenuNode currentMenu() {
        return tree.selected();
    }

    MenuTreePanel tree() {
        return tree;
    }

    void refreshMenus() {
        tree.reload();
        items.refresh();
        updateTitle();
    }

    /** Installs DAMN using the given configuration (used by the installer dialog). */
    void runInstall(DamnInstaller.Config cfg, boolean defaultMenus) {
        Path damn = ctx.damnDir();
        Ui.runWithProgress(this, "Installing DAMN", p -> {
            Path backup = ctx.settings.backupDir().resolve("engine-" + System.currentTimeMillis());
            DamnInstaller.install(damn, cfg, DamnInstaller.Texts.english(),
                    defaultMenus ? DamnInstaller.defaultMenuStructure() : List.of(), backup);
            ctx.settings.setInt(Settings.MAX_ITEMS, cfg.maxItems());
            DamnMenus m = DamnMenus.load(damn);
            m.writeIndex();
            return m;
        }, m -> {
            try {
                ctx.loadMenus();
            } catch (IOException e) {
                Log.warn(e.getMessage());
            }
            loadAllMenus();
            Ui.info(this, "DAMN", "DAMN is installed and configured.\nIn game window: " + cfg.windowWidth() + " x "
                    + cfg.windowHeight() + " pixels, " + cfg.maxItems() + " entries per menu.");
        });
    }
}
