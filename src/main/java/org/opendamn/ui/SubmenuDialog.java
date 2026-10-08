package org.opendamn.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import org.opendamn.dbpf.Tgi;
import org.opendamn.sc4.SubmenuIndex;
import org.opendamn.util.Log;

/**
 * Shows the lots the Submenus DLL would put in a sub-menu, and how hiding interacts with them.
 *
 * <p>OpenDAMN's hidden copies keep their sub-menu tag, so hiding a lot takes it out of the game's
 * own menus but leaves it in its sub-menu. The first tab lists those; the second lists lots a
 * patch puts in a sub-menu without a tag of their own; the third lists lots whose sub-menu button
 * is missing; the fourth explains sub-menus that never show up in game.
 */
final class SubmenuDialog {

    private SubmenuDialog() {}

    /** Column indices, so the tabs agree with the unhide handler. */
    private static final int COL_TICK = 0;
    private static final int COL_LOT = 1;

    static void show(MainWindow w, AppContext ctx) {
        if (ctx.menus() == null) {
            Ui.info(w, "Sub-menus", "No DAMN menus are loaded.");
            return;
        }
        Ui.runWithProgress(w, "Reading sub-menus", p -> {
            SubmenuIndex si = ctx.submenuIndex();
            if (si == null) {
                p.message("Scanning plugins for sub-menu buttons…");
                si = SubmenuIndex.build(ctx.index(), ctx.hiddenDir(), new PluginIndexProgress(p));
                ctx.setSubmenuIndex(si);
            }
            p.message("Loading DAMN menus…");
            List<org.opendamn.damn.MenuNode> all = new ArrayList<>();
            int done = 0;
            for (var n : ctx.menus().root().descendantsAndSelf()) all.add(n);
            for (var n : all) {
                p.update(++done, all.size(), n.displayPath());
                ctx.menus().ensureLoaded(n);
            }
            p.message("Matching lots against sub-menus…");
            return si.classify(all);
        }, report -> present(w, ctx, report));
    }

    /** Adapts the shared progress dialog to the plugin index's own progress interface. */
    private record PluginIndexProgress(ProgressDialog.Handle handle) implements org.opendamn.sc4.PluginIndex.Progress {
        @Override
        public void update(int done, int total, java.nio.file.Path current) {
            handle.update(done, total, current == null ? "" : current.toString());
        }

        @Override
        public boolean cancelled() {
            return handle.cancelled();
        }
    }

    private static void present(MainWindow w, AppContext ctx, SubmenuIndex.Report r) {
        JTabbedPane tabs = new JTabbedPane();
        Bucket hiddenTagged = bucket(w, ctx, "Hidden, with a sub-menu",
                "These lots are hidden in DAMN but still tagged into a sub-menu, so they keep occupying "
                        + "it in game. Unhiding brings them back to the game's own menus too.",
                r.hiddenWithSubmenu(), true, true);
        Bucket hiddenUntagged = bucket(w, ctx, "Hidden, no tag of their own",
                "A patch cohort puts these in a sub-menu, but their own exemplar carries no sub-menu "
                        + "tag. Hiding removed them from the game's menus; the sub-menu still lists them.",
                r.hiddenUntagged(), true, false);
        Bucket missing = bucket(w, ctx, "Missing lots",
                "A patch cohort points at these lots, but no plugin provides them any more, so the "
                        + "sub-menu has one fewer entry than its author intended. Nothing to undo here.",
                r.missingLots(), false, false);
        Bucket noButton = bucket(w, ctx, "No sub-menu button",
                "These belong to a sub-menu whose button no plugin provides, so the sub-menu never "
                        + "appears at all. Creating the button is not supported yet.",
                r.needsButton(), false, false);
        Bucket broken = brokenBucket(w, r);

        tabs.addTab("Hidden with sub-menu (" + r.hiddenWithSubmenu().size() + ")", hiddenTagged.panel());
        tabs.addTab("Hidden, untagged (" + r.hiddenUntagged().size() + ")", hiddenUntagged.panel());
        tabs.addTab("Missing lots (" + r.missingLots().size() + ")", missing.panel());
        tabs.addTab("No button (" + r.needsButton().size() + ")", noButton.panel());
        tabs.addTab("Sub-menus not shown (" + r.brokenSubmenus().size() + ")", broken.panel());

        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(tabs);
        tabs.setPreferredSize(new Dimension(860, 480));
        if (JOptionPane.showConfirmDialog(w, p, "Lots with sub-menus", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            for (Bucket b : new Bucket[] {hiddenTagged, hiddenUntagged}) {
                if (b.table() != null && b.table().isEditing()) b.table().getCellEditor().stopCellEditing();
            }
            Set<Tgi> chosen = new LinkedHashSet<>();
            chosen.addAll(hiddenTagged.ticked());
            chosen.addAll(hiddenUntagged.ticked());
            if (!chosen.isEmpty()) w.unhideLots(chosen);
        }
    }

    /** One tab: a tick-list of lots, or (for the last tab) a read-only table of sub-menus. */
    private record Bucket(JPanel panel, DefaultTableModel model, JTable table) {

        /** Lots ticked for unhiding. */
        List<Tgi> ticked() {
            List<Tgi> out = new ArrayList<>();
            if (model == null) return out;
            for (int row = 0; row < model.getRowCount(); row++) {
                if (!Boolean.TRUE.equals(model.getValueAt(row, COL_TICK))) continue;
                out.add((Tgi) model.getValueAt(row, LOT_KEY_COLUMN));
            }
            return out;
        }
    }

    /** Hidden column holding the Tgi, never displayed. */
    private static final int LOT_KEY_COLUMN = 5;

    private static Bucket bucket(MainWindow w, AppContext ctx, String title, String help,
                                 List<SubmenuIndex.Row> rows, boolean tickable, boolean exportOnly) {
        DefaultTableModel model = new DefaultTableModel(
                new Object[] {"Unhide", "Lot", "Sub-menu", "Members", "Note", "key"}, 0) {
            @Override
            public Class<?> getColumnClass(int c) {
                return c == COL_TICK ? (tickable ? Boolean.class : String.class) : String.class;
            }

            @Override
            public boolean isCellEditable(int r, int c) {
                return tickable && c == COL_TICK;
            }
        };
        JTable table = new JTable(model);
        table.getColumnModel().getColumn(COL_TICK).setMaxWidth(tickable ? 60 : 0);
        table.getColumnModel().getColumn(COL_TICK).setMinWidth(tickable ? 60 : 0);
        table.getColumnModel().getColumn(2).setPreferredWidth(220);
        table.getColumnModel().getColumn(4).setPreferredWidth(260);
        table.getColumnModel().getColumn(LOT_KEY_COLUMN).setMinWidth(0);
        table.getColumnModel().getColumn(LOT_KEY_COLUMN).setMaxWidth(0);
        table.setAutoCreateRowSorter(true);

        for (SubmenuIndex.Row row : rows) {
            model.addRow(new Object[] {tickable ? Boolean.FALSE : "", row.lot(), row.submenu(),
                    String.valueOf(row.submenuSize()), row.note(), row.exemplar()});
        }

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        if (tickable) {
            buttons.add(Ui.button("Select all", null, e -> tick(model, true)));
            buttons.add(Ui.button("Select none", null, e -> tick(model, false)));
        }
        buttons.add(Ui.button("Copy / save list…", "Copy this list to the clipboard or save it as text", e ->
                Ui.showLines(w, "Lots with sub-menus", header(title, help),
                        rows.isEmpty() ? List.of("(none)") : SubmenuIndex.describe(rows, "Lot"))));

        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(buttons, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(840, 400));
        p.add(sp);
        return new Bucket(p, model, table);
    }

    private static Bucket brokenBucket(MainWindow w, SubmenuIndex.Report r) {
        DefaultTableModel model = new DefaultTableModel(
                new Object[] {"", "Sub-menu", "ID", "Members", "Missing", "Hidden", "Visible", "Why"}, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
        };
        JTable table = new JTable(model);
        table.getColumnModel().getColumn(0).setMaxWidth(0);
        table.getColumnModel().getColumn(0).setMinWidth(0);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        table.getColumnModel().getColumn(7).setPreferredWidth(240);
        table.setAutoCreateRowSorter(true);
        for (SubmenuIndex.SubmenuStatus s : r.brokenSubmenus()) {
            model.addRow(new Object[] {"", s.name(), s.hex(), String.valueOf(s.members()),
                    String.valueOf(s.dangling()), String.valueOf(s.hidden()), String.valueOf(s.visible()),
                    s.reason()});
        }

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(Ui.button("Copy / save list…", null, e ->
                Ui.showLines(w, "Sub-menus not shown in game",
                        "<html>Sub-menus with members that the game cannot display.<br>"
                                + "A sub-menu with no members at all is just unused and is not listed here.</html>",
                        SubmenuIndex.describe(r.brokenSubmenus()))));
        buttons.add(Ui.button("All sub-menus…", "Every sub-menu found, with member counts", e ->
                Ui.showLines(w, "All sub-menus", "Every sub-menu found in the plugins and its members:",
                        SubmenuIndex.describe(r.allSubmenus()))));

        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(buttons, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(840, 400));
        p.add(sp);
        return new Bucket(p, model, table);
    }

    private static void tick(DefaultTableModel m, boolean value) {
        for (int r = 0; r < m.getRowCount(); r++) m.setValueAt(value, r, COL_TICK);
    }

    private static String header(String title, String help) {
        return "<html><b>" + title + "</b><br>" + help + "</html>";
    }
}
