package org.opendamn.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.datatransfer.Transferable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.DropMode;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.TransferHandler;
import org.opendamn.Settings;
import org.opendamn.damn.MenuItem;
import org.opendamn.damn.MenuNode;

/** "DAMN menu items" panel: the items of the selected menu. */
final class MenuItemsPanel extends JPanel {

    interface Actions {
        void copyItems(List<MenuItem> items);

        void cutItems(MenuNode from, List<MenuItem> items);

        void paste(MenuNode into);

        void clearClipboard();

        void viewClipboard();

        void dropLots(MenuNode target, int index, Transfers.Lots lots);
    }

    private final AppContext ctx;
    private final Actions actions;
    private final DefaultListModel<MenuItem> model = new DefaultListModel<>();
    private final JList<MenuItem> list = new JList<>(model);
    private final JLabel counts = new JLabel(" ");
    private MenuNode node;

    MenuItemsPanel(AppContext ctx, Actions actions, Consumer<MenuItem> onSelect) {
        super(new BorderLayout(2, 2));
        this.ctx = ctx;
        this.actions = actions;
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setCellRenderer(new Renderer());
        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            updateCounts();
            List<MenuItem> sel = list.getSelectedValuesList();
            onSelect.accept(sel.size() == 1 ? sel.get(0) : null);
        });
        list.setDragEnabled(true);
        list.setDropMode(DropMode.INSERT);
        list.setTransferHandler(new Dnd());

        JPopupMenu pm = new JPopupMenu();
        pm.add(Ui.action("Copy to clipboard", null, KeyStroke.getKeyStroke("ctrl C"), e -> actions.copyItems(selected())));
        pm.add(Ui.action("Cut to clipboard", null, KeyStroke.getKeyStroke("ctrl X"), e -> cut()));
        pm.add(Ui.action("Paste from clipboard", null, KeyStroke.getKeyStroke("ctrl V"), e -> {
            if (node != null) actions.paste(node);
        }));
        pm.add(Ui.action("Clear clipboard", null, null, e -> actions.clearClipboard()));
        pm.add(Ui.action("View clipboard…", null, null, e -> actions.viewClipboard()));
        pm.addSeparator();
        pm.add(Ui.action("Sort by name", "Sort all items in this menu alphabetically", null, e -> sortByName()));
        list.setComponentPopupMenu(pm);
        bind("ctrl C", () -> actions.copyItems(selected()));
        bind("ctrl X", this::cut);
        bind("ctrl V", () -> {
            if (node != null) actions.paste(node);
        });
        bind("DELETE", this::remove);
        bind("alt UP", () -> move(-1));
        bind("alt DOWN", () -> move(1));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        top.add(Ui.button("All", "Select all items", e -> list.setSelectionInterval(0, model.size() - 1)));
        top.add(Ui.button("Remove", "Remove the selected items from the menu", e -> remove()));
        top.add(Ui.button("Hide", "Toggle hiding the selected lots in the game's own menus", e -> toggleHidden()));
        top.add(Ui.button("⤒", "Move to top", e -> moveTo(true)));
        top.add(Ui.button("↑", "Move up (Alt+Up)", e -> move(-1)));
        top.add(Ui.button("↓", "Move down (Alt+Down)", e -> move(1)));
        top.add(Ui.button("⤓", "Move to bottom", e -> moveTo(false)));

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(list));
        add(counts, BorderLayout.SOUTH);
    }

    private void bind(String key, Runnable r) {
        list.getInputMap().put(KeyStroke.getKeyStroke(key), key);
        list.getActionMap().put(key, Ui.action(key, null, null, e -> r.run()));
    }

    void show(MenuNode n) {
        node = n;
        model.clear();
        if (n != null && n.itemsLoaded()) {
            for (MenuItem it : n.items()) model.addElement(it);
        }
        if (MenuTreePanel.foundItem != null) select(List.of(MenuTreePanel.foundItem));
        updateCounts();
    }

    MenuNode node() {
        return node;
    }

    List<MenuItem> selected() {
        return list.getSelectedValuesList();
    }

    void select(List<MenuItem> items) {
        list.clearSelection();
        for (MenuItem it : items) {
            int i = model.indexOf(it);
            if (i >= 0) list.addSelectionInterval(i, i);
        }
        int first = list.getMinSelectionIndex();
        if (first >= 0) list.ensureIndexIsVisible(first);
    }

    void refresh() {
        List<MenuItem> sel = selected();
        show(node);
        select(sel);
    }

    void repaintList() {
        list.repaint();
        updateCounts();
    }

    private void updateCounts() {
        if (node == null || !node.itemsLoaded()) {
            counts.setText(" ");
            return;
        }
        int total = node.entryCount();
        counts.setText("Selected: " + list.getSelectedIndices().length + "    Items in menu: " + node.items().size()
                + " (+" + node.children().size() + " submenus)" + (total > ctx.maxItems() ? "  — over the limit of "
                + ctx.maxItems() + "!" : ""));
        counts.setForeground(total > ctx.maxItems() ? new Color(0xC62828) : getForeground());
    }

    // ---- editing

    private void changed() {
        ctx.menus().markItemsChanged(node);
        ctx.fireDirty();
    }

    private void remove() {
        List<MenuItem> sel = selected();
        if (node == null || sel.isEmpty()) return;
        node.items().removeAll(identity(sel));
        changed();
        refresh();
    }

    private void cut() {
        List<MenuItem> sel = selected();
        if (node == null || sel.isEmpty()) return;
        actions.cutItems(node, sel);
        refresh();
    }

    private void toggleHidden() {
        List<MenuItem> sel = selected();
        if (node == null || sel.isEmpty()) return;
        boolean hide = sel.stream().anyMatch(i -> !i.hidden());
        for (MenuItem it : sel) {
            if (it.kind() != MenuItem.Kind.NETWORK) it.setHidden(hide);
        }
        changed();
        refresh();
    }

    private void sortByName() {
        if (node == null) return;
        node.items().sort((a, b) -> a.linkName().compareToIgnoreCase(b.linkName()));
        changed();
        refresh();
    }

    /** Moves the selection one step, keeping it selected. */
    private void move(int dir) {
        if (node == null) return;
        int[] idx = list.getSelectedIndices();
        if (idx.length == 0) return;
        List<MenuItem> items = node.items();
        if (dir < 0 && idx[0] == 0) return;
        if (dir > 0 && idx[idx.length - 1] == items.size() - 1) return;
        List<MenuItem> sel = selected();
        if (dir < 0) {
            for (int i : idx) Collections.swap(items, i, i - 1);
        } else {
            for (int k = idx.length - 1; k >= 0; k--) Collections.swap(items, idx[k], idx[k] + 1);
        }
        changed();
        show(node);
        select(sel);
    }

    private void moveTo(boolean top) {
        if (node == null) return;
        List<MenuItem> sel = selected();
        if (sel.isEmpty()) return;
        List<MenuItem> items = node.items();
        items.removeAll(identity(sel));
        if (top) items.addAll(0, sel);
        else items.addAll(sel);
        changed();
        show(node);
        select(sel);
    }

    /** Wraps items so removeAll works by identity (MenuItem has no equals). */
    private static List<MenuItem> identity(List<MenuItem> l) {
        Set<MenuItem> s = Collections.newSetFromMap(new IdentityHashMap<>());
        s.addAll(l);
        return new ArrayList<>(s);
    }

    // ---- rendering

    private final class Renderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object v, int index, boolean sel, boolean focus) {
            super.getListCellRendererComponent(l, null, index, sel, focus);
            MenuItem it = (MenuItem) v;
            setIcon(Ui.icon(it.icon()));
            String sub = String.join("  ·  ", nonEmpty(it.infoText1(), it.infoText2()));
            setText("<html><b>" + esc(it.linkName()) + "</b>" + (it.hidden() ? " <i>(hidden)</i>" : "")
                    + "<br><small>" + esc(sub) + "</small><br><small>" + it.kind().luaName + " · "
                    + it.exemplarTgi() + "</small></html>");
            if (!sel && it.hidden() && ctx.settings.getBool(Settings.MARK_HIDDEN, true)) {
                setForeground(ctx.settings.getColor("color.item.hidden", new Color(0x7B5EA7)));
            }
            setIconTextGap(8);
            return this;
        }
    }

    private static List<String> nonEmpty(String... s) {
        List<String> r = new ArrayList<>();
        for (String x : s) if (x != null && !x.isBlank()) r.add(x);
        return r;
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---- drag and drop: reorder inside the list, drop lots from the lots list

    private final class Dnd extends TransferHandler {
        @Override
        public int getSourceActions(JComponent c) {
            return COPY_OR_MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            return node == null ? null : Transfers.of(Transfers.ITEMS, new Transfers.Items(node, selected()));
        }

        @Override
        public boolean canImport(TransferSupport s) {
            if (node == null || !s.isDrop()) return false;
            return s.isDataFlavorSupported(Transfers.ITEMS) || s.isDataFlavorSupported(Transfers.LOTS);
        }

        @Override
        public boolean importData(TransferSupport s) {
            if (!canImport(s)) return false;
            int index = ((JList.DropLocation) s.getDropLocation()).getIndex();
            try {
                if (s.isDataFlavorSupported(Transfers.LOTS)) {
                    actions.dropLots(node, index, (Transfers.Lots) s.getTransferable().getTransferData(Transfers.LOTS));
                    return true;
                }
                Transfers.Items t = (Transfers.Items) s.getTransferable().getTransferData(Transfers.ITEMS);
                if (t.source() != node) return false; // between menus: drop on the tree instead
                List<MenuItem> items = node.items();
                Map<MenuItem, Boolean> moving = new IdentityHashMap<>();
                for (MenuItem it : t.items()) moving.put(it, true);
                int before = 0;
                for (int k = 0; k < index && k < items.size(); k++) if (moving.containsKey(items.get(k))) before++;
                items.removeIf(moving::containsKey);
                int at = Math.max(0, Math.min(items.size(), index - before));
                items.addAll(at, t.items());
                changed();
                show(node);
                select(t.items());
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
