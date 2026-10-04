package org.opendamn.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import org.opendamn.Settings;
import org.opendamn.damn.DamnInstaller;
import org.opendamn.damn.DamnMenus;
import org.opendamn.damn.HtmlExport;
import org.opendamn.damn.ItemTemplate;
import org.opendamn.damn.MenuItem;
import org.opendamn.damn.MenuNode;
import org.opendamn.damn.MenuZip;
import org.opendamn.sc4.GamePaths;
import org.opendamn.sc4.HealthCheck;
import org.opendamn.sc4.HiddenLots;
import org.opendamn.sc4.LotInfo;
import org.opendamn.sc4.NetworkList;
import org.opendamn.util.Images;
import org.opendamn.util.Log;

/** The application's secondary windows. */
final class Dialogs {

    private Dialogs() {}

    // ------------------------------------------------------------------ paths

    /** Lets the user pick the game and plugin folders. Returns false if cancelled. */
    static boolean setupPaths(MainWindow w, AppContext ctx, boolean firstRun) {
        List<GamePaths.Install> found = GamePaths.detect();
        JTextField game = new JTextField(ctx.gameDir() == null ? "" : ctx.gameDir().toString(), 48);
        JTextField plugins = new JTextField(ctx.userPlugins() == null ? "" : ctx.userPlugins().toString(), 48);
        JTextField hidden = new JTextField(ctx.settings.get(Settings.HIDDEN_FOLDER, ""), 24);
        if (game.getText().isEmpty() && !found.isEmpty()) {
            game.setText(found.get(0).gameDir().toString());
            if (found.get(0).userPlugins() != null) plugins.setText(found.get(0).userPlugins().toString());
        }
        JComboBox<String> detected = new JComboBox<>();
        detected.addItem(found.isEmpty() ? "(no installation found automatically)" : "Detected installations…");
        for (GamePaths.Install i : found) detected.addItem(i.gameDir().toString());
        detected.addActionListener(e -> {
            int k = detected.getSelectedIndex() - 1;
            if (k < 0 || k >= found.size()) return;
            game.setText(found.get(k).gameDir().toString());
            plugins.setText(found.get(k).userPlugins() == null ? "" : found.get(k).userPlugins().toString());
            hidden.setText("");
        });

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = gbc();
        int y = 0;
        if (firstRun) {
            addRow(p, c, y++, null, new JLabel("<html>Welcome to OpenDAMN. Please check where SimCity 4 is installed.<br>"
                    + "Wine/Proton/Lutris/Bottles prefixes are searched automatically.</html>"));
        }
        addRow(p, c, y++, "", detected);
        addRow(p, c, y++, "Game folder (with SimCity_1.dat)", withBrowse(game, true));
        addRow(p, c, y++, "Your Plugins folder (Documents/SimCity 4/Plugins)", withBrowse(plugins, true));
        addRow(p, c, y++, "Hidden lots folder name (in your Plugins)", hidden);
        addRow(p, c, y, null, new JLabel("<html><small>Leave the hidden folder empty to find an existing one "
                + "(e.g. z_zDAMN_Hidden or zzzzzz_DAMN_Hidden).</small></html>"));

        while (true) {
            int r = JOptionPane.showConfirmDialog(w, p, "Game and plugin folders", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return false;
            Path g = Path.of(game.getText().trim());
            if (!GamePaths.isGameDir(g)) {
                Ui.error(w, "Game folder", "SimCity_1.dat was not found in\n" + g);
                continue;
            }
            Path pl = plugins.getText().isBlank() ? GamePaths.findUserPlugins(g, GamePaths.prefixOf(g))
                    : Path.of(plugins.getText().trim());
            String h = hidden.getText().trim();
            if (h.isEmpty()) h = detectHiddenFolder(pl);
            String err = org.opendamn.damn.MenuNames.validate(h);
            if (err != null) {
                Ui.error(w, "Hidden folder", err);
                continue;
            }
            boolean changed = !g.equals(ctx.gameDir()) || pl != null && !pl.equals(ctx.userPlugins());
            ctx.setPaths(g, pl);
            ctx.settings.set(Settings.HIDDEN_FOLDER, h);
            ctx.settings.save();
            if (changed && !firstRun) w.reloadAll();
            return true;
        }
    }

    /** Finds an existing hidden-lots folder, or proposes a name that loads last. */
    static String detectHiddenFolder(Path plugins) {
        if (plugins != null && Files.isDirectory(plugins)) {
            try (var s = Files.list(plugins)) {
                List<String> cand = s.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                        .filter(n -> n.toLowerCase(Locale.ROOT).contains("damn_hidden")).sorted().toList();
                if (!cand.isEmpty()) return cand.get(cand.size() - 1);
            } catch (IOException e) {
                // fall through
            }
        }
        return "zzzzzz_DAMN_Hidden";
    }

    // ------------------------------------------------------------------ settings

    static void settings(MainWindow w, AppContext ctx) {
        Settings s = ctx.settings;
        JCheckBox itemOrder = check("Sort lots by their Item Order property", s.getBool(Settings.USE_ITEM_ORDER, true));
        JCheckBox noIcon = check("Ignore lots without icon", s.getBool(Settings.IGNORE_NO_ICON, false));
        JCheckBox resetZoom = check("Reset preview zoom for each new lot", s.getBool(Settings.RESET_ZOOM, true));
        JCheckBox clearClip = check("Clear the clipboard after pasting", s.getBool(Settings.CLEAR_CLIPBOARD, false));
        JCheckBox lotErrors = check("Show a message when lots cannot be read (otherwise only logged)",
                s.getBool(Settings.SHOW_LOT_ERRORS, false));
        JCheckBox emptyUnfilled = check("Empty template patterns that have no value",
                s.getBool(Settings.EMPTY_UNFILLED, true));
        JComboBox<String> template = new JComboBox<>();
        for (ItemTemplate t : ctx.templates.all()) template.addItem(t.name);
        template.setSelectedItem(s.get(Settings.TEMPLATE, "Standard"));
        JComboBox<String> theme = new JComboBox<>(new String[] {"Light", "Dark", "System"});
        theme.setSelectedItem(s.get(Settings.THEME, "Light"));

        JCheckBox atEnd = check("Add new lots at the end of a menu (otherwise at the top)", s.getBool(Settings.ADD_AT_END, true));
        JCheckBox preview = check("Add the lot's preview image to new items", s.getBool(Settings.ADD_PREVIEW, false));
        JCheckBox cleanChars = check("Replace unusual characters in names with _ (not needed; for old setups)",
                s.getBool(Settings.CLEAN_CHARS, false));
        JSpinner maxItems = new JSpinner(new SpinnerNumberModel(ctx.maxItems(), 1, 500, 1));

        JCheckBox hideNew = check("Hide lots in the game's own menus when adding them", s.getBool(Settings.HIDE_NEW, false));
        JCheckBox markHidden = check("Show hidden lots in a different colour", s.getBool(Settings.MARK_HIDDEN, true));
        JComboBox<HiddenLots.SaveFormat> fmt = new JComboBox<>(HiddenLots.SaveFormat.values());
        fmt.setSelectedItem(HiddenLots.SaveFormat.valueOf(s.get(Settings.HIDE_FORMAT, "ORIGINAL")));
        JTextField hidden = new JTextField(s.get(Settings.HIDDEN_FOLDER, "zzzzzz_DAMN_Hidden"), 20);

        JPanel general = form();
        GridBagConstraints c = gbc();
        int y = 0;
        for (JComponent x : new JComponent[] {itemOrder, noIcon, resetZoom, clearClip, lotErrors, emptyUnfilled}) {
            addRow(general, c, y++, null, x);
        }
        addRow(general, c, y++, "Template for new items", template);
        addRow(general, c, y++, "Theme (restart to apply)", theme);

        JPanel menu = form();
        y = 0;
        for (JComponent x : new JComponent[] {atEnd, preview, cleanChars}) addRow(menu, c, y++, null, x);
        addRow(menu, c, y++, "Maximum entries per menu", maxItems);
        addRow(menu, c, y, null, new JLabel("<html><small>Should match the in-game window (DAMN &gt; Install / configure). "
                + "Menus with more entries are shown in red.</small></html>"));

        JPanel hiding = form();
        y = 0;
        addRow(hiding, c, y++, "Hidden lots folder (in your Plugins)", hidden);
        addRow(hiding, c, y++, null, hideNew);
        addRow(hiding, c, y++, null, markHidden);
        addRow(hiding, c, y++, "Save hidden exemplars as", fmt);
        addRow(hiding, c, y, null, new JLabel("<html><small>ORIGINAL keeps each exemplar exactly as its author made it, "
                + "only removing the menu button. This is the safest choice.</small></html>"));

        JPanel colours = form();
        y = 0;
        String[][] keys = {
            {"color.menu.empty", "Empty menu", "#808080"}, {"color.menu.submenus", "Menu with submenus only", "#000000"},
            {"color.menu.items", "Menu with lots", "#2e7d32"}, {"color.menu.overloaded", "Menu over the limit", "#c62828"},
            {"color.lot.ploppable", "Ploppable lot", "#1e7d32"}, {"color.lot.growable", "Growable lot", "#8a6d3b"},
            {"color.lot.landmark", "Landmark", "#1f6fb2"}, {"color.lot.reward", "Reward", "#8e44ad"},
            {"color.lot.flora", "Flora", "#2e8b57"}, {"color.item.hidden", "Hidden menu item", "#7b5ea7"}};
        List<JButton> colourButtons = new ArrayList<>();
        for (String[] k : keys) {
            JButton b = new JButton("      ");
            b.setBackground(s.getColor(k[0], Color.decode(k[2])));
            b.addActionListener(e -> {
                Color col = JColorChooser.showDialog(w, k[1], b.getBackground());
                if (col != null) b.setBackground(col);
            });
            colourButtons.add(b);
            addRow(colours, c, y++, k[1], b);
        }

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("General", general);
        tabs.addTab("DAMN menus", menu);
        tabs.addTab("Hiding", hiding);
        tabs.addTab("Colours", colours);
        if (JOptionPane.showConfirmDialog(w, tabs, "Settings", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;

        String err = org.opendamn.damn.MenuNames.validate(hidden.getText().trim());
        if (err != null) {
            Ui.error(w, "Hidden folder", err);
            return;
        }
        s.setBool(Settings.USE_ITEM_ORDER, itemOrder.isSelected());
        s.setBool(Settings.IGNORE_NO_ICON, noIcon.isSelected());
        s.setBool(Settings.RESET_ZOOM, resetZoom.isSelected());
        s.setBool(Settings.CLEAR_CLIPBOARD, clearClip.isSelected());
        s.setBool(Settings.SHOW_LOT_ERRORS, lotErrors.isSelected());
        s.setBool(Settings.EMPTY_UNFILLED, emptyUnfilled.isSelected());
        s.set(Settings.TEMPLATE, (String) template.getSelectedItem());
        s.set(Settings.THEME, (String) theme.getSelectedItem());
        s.setBool(Settings.ADD_AT_END, atEnd.isSelected());
        s.setBool(Settings.ADD_PREVIEW, preview.isSelected());
        s.setBool(Settings.CLEAN_CHARS, cleanChars.isSelected());
        s.setInt(Settings.MAX_ITEMS, (Integer) maxItems.getValue());
        s.set(Settings.HIDDEN_FOLDER, hidden.getText().trim());
        s.setBool(Settings.HIDE_NEW, hideNew.isSelected());
        s.setBool(Settings.MARK_HIDDEN, markHidden.isSelected());
        s.set(Settings.HIDE_FORMAT, ((HiddenLots.SaveFormat) fmt.getSelectedItem()).name());
        for (int k = 0; k < keys.length; k++) s.setColor(keys[k][0], colourButtons.get(k).getBackground());
        s.save();
        w.refreshMenus();
    }

    // ------------------------------------------------------------------ installer

    static void installer(MainWindow w, AppContext ctx) {
        Path damn = ctx.damnDir();
        if (damn == null) {
            Ui.error(w, "DAMN", "Choose the game folder first (File > Game and plugin folders).");
            return;
        }
        boolean installed = DamnInstaller.isInstalled(damn);
        DamnInstaller.Config cur = installed ? DamnInstaller.readConfig(damn) : DamnInstaller.Config.defaults();
        JRadioButton urgent = new JRadioButton("Urgent advice window", cur.urgentAdvice());
        JRadioButton news = new JRadioButton("News ticker window", !cur.urgentAdvice());
        ButtonGroup g = new ButtonGroup();
        g.add(urgent);
        g.add(news);
        JSpinner cols = new JSpinner(new SpinnerNumberModel(cur.columns(), 1, 6, 1));
        JComboBox<Integer> width = new JComboBox<>(new Integer[] {300, 350, 400, 450, 500});
        width.setEditable(true);
        width.setSelectedItem(cur.columnWidth());
        JComboBox<Integer> height = new JComboBox<>(new Integer[] {450, 700, 1000});
        height.setEditable(true);
        height.setSelectedItem(cur.newsHeight());
        JSpinner max = new JSpinner(new SpinnerNumberModel(cur.maxItems(), 1, 500, 1));
        JCheckBox reduce = check("Reduce the number of news messages (news filter)", cur.reduceNews());
        JCheckBox defaults = check("Create the standard menu structure (Civic, Commercial, Transport, …)", false);
        defaults.setEnabled(ctx.menus() == null || ctx.menus().root().children().isEmpty());
        JLabel size = new JLabel();
        Runnable upd = () -> {
            try {
                DamnInstaller.Config c = cfg(urgent, cols, width, height, max, reduce);
                size.setText("In-game window: " + c.windowWidth() + " x " + c.windowHeight() + " pixels");
                height.setEnabled(!c.urgentAdvice());
            } catch (RuntimeException e) {
                size.setText("Invalid value");
            }
        };
        for (var comp : new JComponent[] {urgent, news, width, height}) {
            if (comp instanceof JComboBox<?> cb) cb.addActionListener(e -> upd.run());
            if (comp instanceof JRadioButton rb) rb.addActionListener(e -> upd.run());
        }
        cols.addChangeListener(e -> upd.run());
        upd.run();

        JPanel p = form();
        GridBagConstraints c = gbc();
        int y = 0;
        addRow(p, c, y++, null, new JLabel(installed ? "DAMN is installed in " + damn : "DAMN will be installed in " + damn));
        addRow(p, c, y++, "Menu window", urgent);
        addRow(p, c, y++, "", news);
        addRow(p, c, y++, "Columns", cols);
        addRow(p, c, y++, "Column width (px)", width);
        addRow(p, c, y++, "Window height (news ticker, px)", height);
        addRow(p, c, y++, "Entries per menu (items + submenus)", max);
        addRow(p, c, y++, null, size);
        addRow(p, c, y++, null, reduce);
        addRow(p, c, y++, null, defaults);
        addRow(p, c, y, null, new JLabel("<html><small>Tip: columns × width + about 120 px must fit your screen width. "
                + "Your existing engine files are backed up first.</small></html>"));
        if (JOptionPane.showConfirmDialog(w, p, installed ? "Configure DAMN" : "Install DAMN", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        DamnInstaller.Config cfg;
        try {
            cfg = cfg(urgent, cols, width, height, max, reduce);
        } catch (RuntimeException e) {
            Ui.error(w, "DAMN", "Please enter valid numbers.");
            return;
        }
        w.runInstall(cfg, defaults.isSelected() && defaults.isEnabled());
    }

    private static DamnInstaller.Config cfg(JRadioButton urgent, JSpinner cols, JComboBox<Integer> width,
                                            JComboBox<Integer> height, JSpinner max, JCheckBox reduce) {
        return new DamnInstaller.Config(urgent.isSelected(), (Integer) cols.getValue(),
                Integer.parseInt(String.valueOf(width.getSelectedItem()).trim()),
                Integer.parseInt(String.valueOf(height.getSelectedItem()).trim()), (Integer) max.getValue(),
                reduce.isSelected());
    }

    // ------------------------------------------------------------------ Maxis lots

    static void maxisLots(MainWindow w, AppContext ctx, MenuNode target) {
        if (!requireMenu(w, target) || ctx.gameDir() == null) return;
        List<Path> data = GamePaths.gameDataFiles(ctx.gameDir(), ctx.settings.getPath(Settings.LANGUAGE_DIR));
        Ui.runWithProgress(w, "Reading SimCity lots", p -> {
            List<String> errors = new ArrayList<>();
            List<LotInfo> all = ctx.catalog().lotsIn(data, errors::add);
            all.removeIf(l -> !l.ploppable() || l.hidden() && l.kind() != MenuItem.Kind.FLORA);
            all.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
            return all;
        }, all -> {
            if (all.isEmpty()) {
                Ui.info(w, "SimCity lots", "No lots found. Has the plugin index been built (Tools > Rescan plugins)?");
                return;
            }
            List<LotInfo> chosen = chooseLots(w, ctx, "Original SimCity lots", all);
            if (!chosen.isEmpty()) w.addLots(chosen);
        });
    }

    private static List<LotInfo> chooseLots(Component parent, AppContext ctx, String title, List<LotInfo> all) {
        DefaultListModel<LotInfo> m = new DefaultListModel<>();
        all.forEach(m::addElement);
        JList<LotInfo> list = new JList<>(m);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                super.getListCellRendererComponent(l, null, i, s, f);
                LotInfo li = (LotInfo) v;
                setIcon(Ui.icon(ctx.catalog().png(li.iconTgi())));
                setText("<html><b>" + MenuItemsPanel.esc(li.displayName()) + "</b><br><small>"
                        + li.category().name().toLowerCase(Locale.ROOT) + " · " + li.sizeText() + "</small></html>");
                return this;
            }
        });
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(520, 520));
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(new JLabel(all.size() + " lots — select the ones to add:"), BorderLayout.NORTH);
        p.add(sp);
        if (JOptionPane.showConfirmDialog(parent, p, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return List.of();
        return list.getSelectedValuesList();
    }

    // ------------------------------------------------------------------ networks

    static void networks(MainWindow w, AppContext ctx, MenuNode target) {
        if (!requireMenu(w, target)) return;
        DefaultTableModel model = new DefaultTableModel(new Object[] {"Add", "Button ID", "Name", "Icon file"}, 0) {
            @Override
            public Class<?> getColumnClass(int c) {
                return c == 0 ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int r, int c) {
                return c != 3;
            }
        };
        JTable table = new JTable(model);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(260);
        JCheckBox onlyIcons = check("Only items with an icon", false);
        Path[] iconDir = {null};

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(Ui.button("Game networks", "Roads, rails, highways and other standard networks", e -> {
            try (var in = Dialogs.class.getResourceAsStream("/org/opendamn/networks/SimCity4Network.txt")) {
                fill(model, NetworkList.parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1), false), null);
                iconDir[0] = null;
            } catch (IOException | RuntimeException ex) {
                Ui.error(w, "Networks", ex.getMessage());
            }
        }));
        buttons.add(Ui.button("From installed NAM", "Read the network rules of the installed Network Addon Mod", e ->
                Ui.runWithProgress(w, "Reading RUL files", p -> NetworkList.fromInstalledRuls(ctx.index()), l -> {
                    fill(model, l, null);
                    iconDir[0] = null;
                    if (l.isEmpty()) Ui.info(w, "Networks", "No network RUL files found in the plugin index.");
                })));
        buttons.add(Ui.button("Load RUL/TXT file…", "Lines like: RotationRing = 1020 ;Name", e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Network lists (rul, txt)", "rul", "txt"));
            if (fc.showOpenDialog(w) != JFileChooser.APPROVE_OPTION) return;
            try {
                Path f = fc.getSelectedFile().toPath();
                iconDir[0] = f.getParent();
                fill(model, NetworkList.parseFile(f), iconDir[0]);
            } catch (IOException ex) {
                Ui.error(w, "Networks", ex.getMessage());
            }
        }));
        buttons.add(Ui.button("Add row", "Enter a button ID by hand", e -> model.addRow(new Object[] {true, "", "", ""})));

        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(buttons, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(640, 420));
        p.add(sp);
        p.add(onlyIcons, BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(w, p, "Network items", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        if (table.isEditing()) table.getCellEditor().stopCellEditing();

        List<MenuItem> items = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        for (int r = 0; r < model.getRowCount(); r++) {
            if (!Boolean.TRUE.equals(model.getValueAt(r, 0))) continue;
            String idText = String.valueOf(model.getValueAt(r, 1)).trim();
            String name = String.valueOf(model.getValueAt(r, 2)).trim();
            int id;
            try {
                id = org.opendamn.dbpf.Tgi.parseHex(idText);
            } catch (RuntimeException ex) {
                bad.add("Row " + (r + 1) + ": invalid button ID \"" + idText + "\"");
                continue;
            }
            if (name.isEmpty()) name = "Network " + idText;
            byte[] icon = networkIcon(ctx, id, String.valueOf(model.getValueAt(r, 3)));
            if (icon == null && onlyIcons.isSelected()) continue;
            items.add(ItemTemplate.networkItem(name, id, icon != null ? icon : Images.placeholderIcon(name)));
        }
        if (!bad.isEmpty()) Ui.showLines(w, "Network items", "Some rows were skipped:", bad);
        if (!items.isEmpty()) w.insertItems(target, -1, items);
    }

    private static void fill(DefaultTableModel m, List<NetworkList.Entry> l, Path iconDir) {
        m.setRowCount(0);
        for (NetworkList.Entry e : l) {
            String icon = "";
            if (iconDir != null) {
                for (String n : List.of(String.format("%08x.png", e.id()), String.format("%08X.png", e.id()))) {
                    if (Files.exists(iconDir.resolve(n))) icon = iconDir.resolve(n).toString();
                }
            }
            m.addRow(new Object[] {false, String.format("%08x", e.id()), e.name(), icon});
        }
    }

    private static byte[] networkIcon(AppContext ctx, int id, String file) {
        try {
            if (file != null && !file.isBlank() && Files.exists(Path.of(file))) return Images.loadIconFile(Path.of(file));
        } catch (IOException e) {
            Log.warn("Cannot read icon " + file + ": " + e.getMessage());
        }
        byte[] png = ctx.catalog().png(NetworkList.iconFor(ctx.index(), id));
        return png == null ? null : Images.normalizeIconPng(png);
    }

    // ------------------------------------------------------------------ templates

    static void templates(MainWindow w, AppContext ctx) {
        JComboBox<String> names = new JComboBox<>();
        ctx.templates.all().forEach(t -> names.addItem(t.name));
        JTextField link = new JTextField(40), i1 = new JTextField(40), i2 = new JTextField(40);
        JTextArea desc = new JTextArea(3, 40), info = new JTextArea(4, 40);
        JTextField repl = new JTextField(40);
        JCheckBox useRepl = check("If a name/description pattern is empty, use the first available of:", true);
        ItemTemplate[] cur = {null};
        Runnable store = () -> {
            if (cur[0] == null) return;
            cur[0].linkName = link.getText();
            cur[0].infoText1 = i1.getText();
            cur[0].infoText2 = i2.getText();
            cur[0].description = desc.getText();
            cur[0].info = info.getText();
            cur[0].useReplacements = useRepl.isSelected();
            cur[0].replacements = new ArrayList<>(List.of(repl.getText().replace(" ", "").split(",")));
        };
        Runnable load = () -> {
            cur[0] = ctx.templates.get((String) names.getSelectedItem());
            link.setText(cur[0].linkName);
            i1.setText(cur[0].infoText1);
            i2.setText(cur[0].infoText2);
            desc.setText(cur[0].description);
            info.setText(cur[0].info);
            useRepl.setSelected(cur[0].useReplacements);
            repl.setText(String.join(", ", cur[0].replacements));
        };
        names.addActionListener(e -> {
            store.run();
            load.run();
        });
        load.run();

        StringBuilder help = new StringBuilder("<html><b>Patterns</b> (right-click a field to insert):<br>");
        ItemTemplate.PATTERNS.forEach((k, v) -> help.append("${").append(k).append("} — ").append(v).append("<br>"));
        help.append("</html>");
        for (JComponent f : new JComponent[] {link, i1, i2, desc, info}) {
            javax.swing.text.JTextComponent t = (javax.swing.text.JTextComponent) f;
            javax.swing.JPopupMenu pm = new javax.swing.JPopupMenu();
            ItemTemplate.PATTERNS.forEach((k, v) -> pm.add(Ui.action("${" + k + "}  " + v, null, null,
                    e -> t.replaceSelection("${" + k + "}"))));
            t.setComponentPopupMenu(pm);
        }

        JPanel p = form();
        GridBagConstraints c = gbc();
        int y = 0;
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        top.add(names);
        top.add(Ui.button("New…", null, e -> {
            String n = JOptionPane.showInputDialog(w, "Name of the new template:");
            if (n == null || n.isBlank()) return;
            try {
                store.run();
                ItemTemplate t = ctx.templates.create(n.trim());
                names.addItem(t.name);
                names.setSelectedItem(t.name);
            } catch (IllegalArgumentException ex) {
                Ui.error(w, "Templates", ex.getMessage());
            }
        }));
        top.add(Ui.button("Delete", null, e -> {
            try {
                ctx.templates.delete(cur[0]);
                cur[0] = null;
                names.removeItem(names.getSelectedItem());
            } catch (IllegalArgumentException ex) {
                Ui.error(w, "Templates", ex.getMessage());
            }
        }));
        addRow(p, c, y++, "Template", top);
        addRow(p, c, y++, "Link name", link);
        addRow(p, c, y++, "Info text 1", i1);
        addRow(p, c, y++, "Info text 2", i2);
        addRow(p, c, y++, "Description", new JScrollPane(desc));
        addRow(p, c, y++, "Information", new JScrollPane(info));
        addRow(p, c, y++, null, useRepl);
        addRow(p, c, y++, "", repl);
        JLabel h = new JLabel(help.toString());
        JScrollPane hs = new JScrollPane(h);
        hs.setPreferredSize(new Dimension(560, 170));
        addRow(p, c, y, null, hs);
        JOptionPane.showMessageDialog(w, p, "Item templates", JOptionPane.PLAIN_MESSAGE);
        store.run();
        try {
            ctx.templates.save();
        } catch (IOException e) {
            Ui.error(w, "Templates", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ zip / html

    static void exportZip(MainWindow w, AppContext ctx, MenuNode n) {
        if (!requireMenu(w, n)) return;
        if (ctx.isDirty() && !Ui.confirm(w, "Export", "There are unsaved changes; the export contains the saved state. Continue?")) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File((n.isRoot() ? "Root" : n.name()) + ".zip"));
        if (fc.showSaveDialog(w) != JFileChooser.APPROVE_OPTION) return;
        try {
            int k = MenuZip.export(ctx.menus(), n, fc.getSelectedFile().toPath());
            Ui.info(w, "Export", "Exported " + k + " menu file(s).");
        } catch (IOException e) {
            Ui.error(w, "Export", e.getMessage());
        }
    }

    static void importZip(MainWindow w, AppContext ctx, MenuNode target) {
        if (!requireMenu(w, target)) return;
        if (ctx.isDirty()) {
            Ui.info(w, "Import", "Please save your changes before importing.");
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("ZIP archives", "zip"));
        if (fc.showOpenDialog(w) != JFileChooser.APPROVE_OPTION) return;
        Path zip = fc.getSelectedFile().toPath();
        try {
            MenuZip.Contents c = MenuZip.inspect(zip);
            Path folder = ctx.menus().rootDir();
            for (String s : target.segments()) folder = folder.resolve(s);
            boolean strip = c.isRoot() && target.isRoot();
            if (strip && !Ui.confirm(w, "Import", "This archive contains a whole Root menu. Its menus will be merged into "
                    + "your Root menu (existing files are kept). Continue?")) return;
            if (!strip && target.child(c.topName()) != null && !Ui.confirm(w, "Import", "A menu named \"" + c.topName()
                    + "\" already exists in " + target.displayPath() + ". Merge into it?")) return;
            int k = MenuZip.extract(zip, folder, strip);
            DamnMenus.load(ctx.menus().damnDir()).save(ctx.settings.backupDir(), it -> Images.placeholderIcon(it.linkName()));
            w.reloadAll();
            Ui.info(w, "Import", "Imported " + k + " menu file(s).");
        } catch (IOException e) {
            Ui.error(w, "Import", e.getMessage());
        }
    }

    static void exportHtml(MainWindow w, AppContext ctx, MenuNode n) {
        if (!requireMenu(w, n)) return;
        JTextField title = new JTextField("DAMN menu", 30);
        JTextField h1 = new JTextField("DAMN menu contents", 30);
        JTextField h2 = new JTextField(n.displayPath(), 30);
        JCheckBox pv = check("Preview images", true), r1 = check("Info text 1", true), r2 = check("Info text 2", true),
                inf = check("Information", true), de = check("Description", true), tg = check("TGI", false);
        JSpinner cols = new JSpinner(new SpinnerNumberModel(3, 1, 6, 1));
        JComboBox<Integer> cw = new JComboBox<>(new Integer[] {300, 350, 400});
        JPanel p = form();
        GridBagConstraints c = gbc();
        int y = 0;
        addRow(p, c, y++, "Page title", title);
        addRow(p, c, y++, "Header row 1", h1);
        addRow(p, c, y++, "Header row 2", h2);
        for (JCheckBox b : new JCheckBox[] {pv, r1, r2, inf, de, tg}) addRow(p, c, y++, null, b);
        addRow(p, c, y++, "Columns", cols);
        addRow(p, c, y, "Column width", cw);
        if (JOptionPane.showConfirmDialog(w, p, "Export as web page", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Folder for the web page");
        if (fc.showSaveDialog(w) != JFileChooser.APPROVE_OPTION) return;
        HtmlExport.Options o = new HtmlExport.Options(title.getText(), h1.getText(), h2.getText(), pv.isSelected(),
                r1.isSelected(), r2.isSelected(), inf.isSelected(), de.isSelected(), tg.isSelected(),
                (Integer) cols.getValue(), (Integer) cw.getSelectedItem());
        Path dir = fc.getSelectedFile().toPath();
        Ui.runWithProgress(w, "Exporting", pr -> HtmlExport.export(ctx.menus(), n, dir, o), out -> {
            try {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(out.toUri());
            } catch (IOException | RuntimeException e) {
                Ui.info(w, "Export", "Written to " + out);
            }
        });
    }

    // ------------------------------------------------------------------ info windows

    static void statistics(MainWindow w, AppContext ctx) {
        if (ctx.menus() == null) return;
        int menus = 0, items = 0, hidden = 0, over = 0, empty = 0;
        java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
        for (MenuNode n : ctx.menus().root().descendantsAndSelf()) {
            menus++;
            if (!n.itemsLoaded()) continue;
            items += n.items().size();
            if (n.items().isEmpty() && n.children().isEmpty()) empty++;
            if (n.entryCount() > ctx.maxItems()) over++;
            for (MenuItem it : n.items()) {
                if (it.hidden()) hidden++;
                kinds.merge(it.kind().luaName, 1, Integer::sum);
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("Menus: " + (menus - 1) + " (plus Root)");
        lines.add("Menu items: " + items);
        kinds.forEach((k, v) -> lines.add("   " + k + ": " + v));
        lines.add("Hidden in game menus: " + hidden);
        lines.add("Empty menus: " + empty);
        lines.add("Menus over the limit of " + ctx.maxItems() + ": " + over);
        lines.add("Plugin files indexed: " + ctx.index().files().size());
        Ui.showLines(w, "Statistics", "DAMN menu statistics", lines);
    }

    static void health(MainWindow w, AppContext ctx) {
        if (ctx.menus() == null) return;
        Ui.runWithProgress(w, "Checking", p -> HealthCheck.run(ctx.menus(), ctx.index(), ctx.hiddenDir(), ctx.maxItems()),
                issues -> {
                    List<String> lines = new ArrayList<>();
                    for (HealthCheck.Issue i : issues) {
                        lines.add("[" + i.severity() + "] " + i.title());
                        if (!i.detail().isBlank()) for (String l : i.detail().split("\n")) lines.add("      " + l);
                        for (Path f : i.files()) lines.add("      " + f);
                        lines.add("");
                    }
                    Ui.showLines(w, "Health check", "Results (nothing was changed):", lines);
                });
    }

    static void clipboard(MainWindow w, AppContext ctx) {
        DefaultListModel<String> m = new DefaultListModel<>();
        for (MenuItem it : ctx.clipboard) m.addElement(it.linkName() + "   [" + it.exemplarTgi() + "]");
        JList<String> l = new JList<>(m);
        JScrollPane sp = new JScrollPane(l);
        sp.setPreferredSize(new Dimension(480, 300));
        JOptionPane.showMessageDialog(w, sp, "Clipboard (" + ctx.clipboard.size() + ")", JOptionPane.PLAIN_MESSAGE);
    }

    static void log(MainWindow w) {
        JDialog d = new JDialog(w, "Log", false);
        JComboBox<Log.Level> level = new JComboBox<>(Log.Level.values());
        level.setSelectedItem(Log.Level.INFO);
        JTextArea t = new JTextArea();
        t.setEditable(false);
        t.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        Runnable fill = () -> {
            StringBuilder sb = new StringBuilder();
            Log.Level min = (Log.Level) level.getSelectedItem();
            for (Log.Entry e : Log.entries()) if (e.level().compareTo(min) >= 0) sb.append(e).append('\n');
            t.setText(sb.toString());
        };
        level.addActionListener(e -> fill.run());
        fill.run();
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Show from level:"));
        top.add(level);
        top.add(Ui.button("Refresh", null, e -> fill.run()));
        top.add(Ui.button("Save…", null, e -> {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new java.io.File("opendamn-log.txt"));
            if (fc.showSaveDialog(d) != JFileChooser.APPROVE_OPTION) return;
            try {
                Files.writeString(fc.getSelectedFile().toPath(), t.getText());
            } catch (IOException ex) {
                Ui.error(d, "Log", ex.getMessage());
            }
        }));
        d.add(top, BorderLayout.NORTH);
        d.add(new JScrollPane(t));
        d.setSize(900, 500);
        d.setLocationRelativeTo(w);
        d.setVisible(true);
    }

    static void about(MainWindow w) {
        String v = MainWindow.class.getPackage().getImplementationVersion();
        JOptionPane.showMessageDialog(w, "<html><h2>OpenDAMN " + (v == null ? "" : v) + "</h2>"
                + "A manager for DAMN (Daeley's Advanced Menu Navigator) menus in SimCity 4.<br><br>"
                + "Free software under the GNU General Public License, version 3 or later.<br>"
                + "Compatible with menus made by SSPTool (Stefan Wertich) and DAMN Manager (Yildun).<br>"
                + "The DAMN engine files are from the SSPTool DAMN plugin (GPL).<br>"
                + "Property definitions: new_properties.xml from the SC4 community (memo33/submenus-dll).</html>",
                "About OpenDAMN", JOptionPane.INFORMATION_MESSAGE);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean requireMenu(Component w, MenuNode n) {
        if (n == null) {
            Ui.info(w, "DAMN", "Select a DAMN menu first.");
            return false;
        }
        return true;
    }

    static JCheckBox check(String text, boolean value) {
        JCheckBox b = new JCheckBox(text, value);
        return b;
    }

    static JPanel form() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return p;
    }

    static GridBagConstraints gbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.LINE_START;
        return c;
    }

    static void addRow(JPanel p, GridBagConstraints c, int y, String label, JComponent comp) {
        c.gridy = y;
        if (label == null) {
            c.gridx = 0;
            c.gridwidth = 2;
            c.fill = GridBagConstraints.HORIZONTAL;
            p.add(comp, c);
            c.gridwidth = 1;
            return;
        }
        c.gridx = 0;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        p.add(new JLabel(label.isEmpty() ? "" : label + ":"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(comp, c);
    }

    private static JComponent withBrowse(JTextField f, boolean dirs) {
        JPanel p = new JPanel(new BorderLayout(4, 0));
        p.add(f);
        JButton b = new JButton("…");
        b.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(f.getText().isBlank() ? null : f.getText());
            if (dirs) fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(p) == JFileChooser.APPROVE_OPTION) f.setText(fc.getSelectedFile().toString());
        });
        p.add(b, BorderLayout.EAST);
        return p;
    }
}
