package org.opendamn.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.datatransfer.Transferable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.DropMode;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.TransferHandler;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import org.opendamn.damn.DamnMenus;
import org.opendamn.damn.MenuItem;
import org.opendamn.damn.MenuNode;
import org.opendamn.util.Log;

/** "DAMN Menu (folder)" panel: the menu tree. */
final class MenuTreePanel extends JPanel {

    /** Operations the tree needs from the main window. */
    interface Actions {
        void dropLots(MenuNode target, Transfers.Lots lots);

        void dropItems(MenuNode target, Transfers.Items items, boolean move);

        void hideAll(MenuNode n, boolean hide);

        void openFolder(MenuNode n);
    }

    private final AppContext ctx;
    private final Actions actions;
    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode("(DAMN not found)")));
    private final Consumer<MenuNode> onSelect;
    private String lastFind = "";
    private final List<MenuNode> findOrder = new ArrayList<>();

    MenuTreePanel(AppContext ctx, Actions actions, Consumer<MenuNode> onSelect) {
        super(new BorderLayout());
        this.ctx = ctx;
        this.actions = actions;
        this.onSelect = onSelect;
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new Renderer());
        tree.addTreeSelectionListener(e -> onSelect.accept(selected()));
        tree.setDragEnabled(true);
        tree.setDropMode(DropMode.ON);
        tree.setTransferHandler(new Dnd());
        tree.setComponentPopupMenu(popup());
        tree.setToggleClickCount(0);
        bindKey("F2", e -> rename());
        bindKey("DELETE", e -> delete());
        bindKey("ctrl F", e -> find(false));
        bindKey("F3", e -> find(true));
        add(new JScrollPane(tree));
    }

    private void bindKey(String key, java.awt.event.ActionListener l) {
        tree.getInputMap().put(KeyStroke.getKeyStroke(key), key);
        tree.getActionMap().put(key, Ui.action(key, null, null, l));
    }

    private JPopupMenu popup() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.action("New menu…", null, null, e -> newMenu()));
        m.add(Ui.action("Rename…", null, KeyStroke.getKeyStroke("F2"), e -> rename()));
        m.add(Ui.action("Delete", null, KeyStroke.getKeyStroke("DELETE"), e -> delete()));
        m.addSeparator();
        m.add(Ui.action("Open folder", null, null, e -> {
            if (selected() != null) actions.openFolder(selected());
        }));
        m.add(Ui.action("Refresh", "Reload the menu structure from disk", null, e -> reloadFromDisk()));
        m.addSeparator();
        m.add(Ui.action("Hide lots (with submenus)", "Hide every lot in this menu and its submenus from the game menus",
                null, e -> {
                    if (selected() != null) actions.hideAll(selected(), true);
                }));
        m.add(Ui.action("Unhide lots (with submenus)", null, null, e -> {
            if (selected() != null) actions.hideAll(selected(), false);
        }));
        m.addSeparator();
        m.add(Ui.action("Find…", "Find menu items by name", KeyStroke.getKeyStroke("ctrl F"), e -> find(false)));
        m.add(Ui.action("Find next", null, KeyStroke.getKeyStroke("F3"), e -> find(true)));
        return m;
    }

    void reload() {
        DamnMenus menus = ctx.menus();
        MenuNode sel = selected();
        DefaultMutableTreeNode root = menus == null ? new DefaultMutableTreeNode("(DAMN not installed)") : build(menus.root());
        tree.setModel(new DefaultTreeModel(root));
        if (menus != null) {
            tree.expandRow(0);
            select(sel != null ? sel : menus.root());
        }
    }

    /** Repaints labels (counts/colours) without rebuilding. */
    void refreshLabels() {
        tree.repaint();
    }

    private DefaultMutableTreeNode build(MenuNode n) {
        DefaultMutableTreeNode t = new DefaultMutableTreeNode(n);
        for (MenuNode c : n.children()) t.add(build(c));
        return t;
    }

    MenuNode selected() {
        TreePath tp = tree.getSelectionPath();
        if (tp == null) return null;
        Object o = ((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject();
        return o instanceof MenuNode m ? m : null;
    }

    void select(MenuNode n) {
        DefaultMutableTreeNode t = find((DefaultMutableTreeNode) tree.getModel().getRoot(), n);
        if (t == null) return;
        TreePath tp = new TreePath(t.getPath());
        tree.setSelectionPath(tp);
        tree.scrollPathToVisible(tp);
    }

    private static DefaultMutableTreeNode find(DefaultMutableTreeNode t, MenuNode n) {
        if (t.getUserObject() == n) return t;
        for (Enumeration<?> e = t.children(); e.hasMoreElements();) {
            DefaultMutableTreeNode r = find((DefaultMutableTreeNode) e.nextElement(), n);
            if (r != null) return r;
        }
        return null;
    }

    // ---- structure editing

    private void newMenu() {
        MenuNode parent = selected();
        if (parent == null || ctx.menus() == null) return;
        String name = JOptionPane.showInputDialog(this, "Name of the new menu in " + parent.displayPath() + ":", "New menu",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null) return;
        try {
            MenuNode c = ctx.menus().createMenu(parent, name.strip());
            reload();
            select(c);
            ctx.fireDirty();
        } catch (IllegalArgumentException ex) {
            Ui.error(this, "New menu", ex.getMessage());
        }
    }

    private void rename() {
        MenuNode n = selected();
        if (n == null || n.isRoot()) return;
        String name = (String) JOptionPane.showInputDialog(this, "New name:", "Rename menu", JOptionPane.PLAIN_MESSAGE,
                null, null, n.name());
        if (name == null || name.strip().equals(n.name())) return;
        try {
            ctx.menus().rename(n, name.strip());
            reload();
            select(n);
            ctx.fireDirty();
        } catch (IllegalArgumentException ex) {
            Ui.error(this, "Rename menu", ex.getMessage());
        }
    }

    private void delete() {
        MenuNode n = selected();
        if (n == null || n.isRoot()) return;
        int subs = n.descendantsAndSelf().size() - 1;
        String msg = "Delete the menu \"" + n.name() + "\"" + (subs > 0 ? " and its " + subs + " submenu(s)" : "") + "?\n"
                + "The files are moved to a backup folder when you save.";
        if (!Ui.confirm(this, "Delete menu", msg)) return;
        MenuNode parent = n.parent();
        ctx.menus().delete(n);
        reload();
        select(parent);
        ctx.fireDirty();
    }

    private void reloadFromDisk() {
        if (ctx.isDirty() && !Ui.confirm(this, "Refresh", "Discard unsaved changes and reload the menus from disk?")) return;
        try {
            ctx.loadMenus();
        } catch (IOException e) {
            Ui.error(this, "Refresh", e.getMessage());
        }
        reload();
        ctx.fireDirty();
    }

    // ---- find

    private void find(boolean next) {
        DamnMenus menus = ctx.menus();
        if (menus == null) return;
        if (!next || lastFind.isEmpty()) {
            String q = (String) JOptionPane.showInputDialog(this, "Find menu items whose name contains:", "Find",
                    JOptionPane.PLAIN_MESSAGE, null, null, lastFind);
            if (q == null || q.isBlank()) return;
            lastFind = q.toLowerCase(Locale.ROOT);
        }
        findOrder.clear();
        findOrder.addAll(menus.root().descendantsAndSelf());
        MenuNode cur = selected();
        int start = cur == null ? 0 : findOrder.indexOf(cur) + 1;
        Ui.runWithProgress(this, "Find", p -> {
            for (MenuNode n : findOrder) menus.ensureLoaded(n);
            return null;
        }, r -> {
            for (int k = 0; k < findOrder.size(); k++) {
                MenuNode n = findOrder.get((start + k) % findOrder.size());
                for (MenuItem it : n.items()) {
                    if (it.linkName().toLowerCase(Locale.ROOT).contains(lastFind)) {
                        select(n);
                        foundItem = it;
                        onSelect.accept(n);
                        foundItem = null;
                        return;
                    }
                }
            }
            Ui.info(this, "Find", "No menu item contains \"" + lastFind + "\".");
        });
    }

    /** Set while a find result is being shown, so the items panel can select it. */
    static MenuItem foundItem;

    // ---- rendering

    private final class Renderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel, boolean exp, boolean leaf,
                                                      int row, boolean focus) {
            super.getTreeCellRendererComponent(t, value, sel, exp, leaf, row, focus);
            Object o = ((DefaultMutableTreeNode) value).getUserObject();
            if (o instanceof MenuNode n) {
                int items = n.itemCount();
                String count = items < 0 ? (n.hasDiskFiles() ? "…" : "0") : Integer.toString(items);
                setText(n.name() + "  (" + count + ")" + (n.itemsDirty() ? " *" : ""));
                if (!sel) setForeground(colorFor(n));
                setToolTipText(n.displayPath() + " — " + n.children().size() + " submenu(s)");
            }
            return this;
        }
    }

    Color colorFor(MenuNode n) {
        var s = ctx.settings;
        boolean hasItems = n.itemCount() > 0 || (n.itemCount() < 0 && n.hasDiskFiles());
        if (n.itemCount() >= 0 && n.entryCount() > ctx.maxItems()) return s.getColor("color.menu.overloaded", new Color(0xC62828));
        if (hasItems) return s.getColor("color.menu.items", new Color(0x2E7D32));
        if (!n.children().isEmpty()) return s.getColor("color.menu.submenus", getForeground());
        return s.getColor("color.menu.empty", Color.GRAY);
    }

    // ---- drag and drop

    private final class Dnd extends TransferHandler {
        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            MenuNode n = selected();
            return n == null || n.isRoot() ? null : Transfers.of(Transfers.MENU, new Transfers.Menu(n));
        }

        @Override
        public boolean canImport(TransferSupport s) {
            if (!s.isDrop()) return false;
            JTree.DropLocation dl = (JTree.DropLocation) s.getDropLocation();
            if (dl.getPath() == null) return false;
            Object o = ((DefaultMutableTreeNode) dl.getPath().getLastPathComponent()).getUserObject();
            if (!(o instanceof MenuNode target)) return false;
            if (s.isDataFlavorSupported(Transfers.MENU)) {
                s.setDropAction(MOVE);
                return true;
            }
            if (s.isDataFlavorSupported(Transfers.ITEMS)) {
                boolean copy = (s.getUserDropAction() & COPY) != 0 && s.getUserDropAction() != MOVE;
                s.setDropAction(copy ? COPY : MOVE);
                return true;
            }
            if (s.isDataFlavorSupported(Transfers.LOTS)) {
                s.setDropAction(COPY);
                return target != null;
            }
            return false;
        }

        @Override
        public boolean importData(TransferSupport s) {
            if (!canImport(s)) return false;
            JTree.DropLocation dl = (JTree.DropLocation) s.getDropLocation();
            MenuNode target = (MenuNode) ((DefaultMutableTreeNode) dl.getPath().getLastPathComponent()).getUserObject();
            try {
                Transferable t = s.getTransferable();
                if (s.isDataFlavorSupported(Transfers.MENU)) {
                    MenuNode n = ((Transfers.Menu) t.getTransferData(Transfers.MENU)).node();
                    ctx.menus().move(n, target);
                    reload();
                    select(n);
                    ctx.fireDirty();
                } else if (s.isDataFlavorSupported(Transfers.ITEMS)) {
                    actions.dropItems(target, (Transfers.Items) t.getTransferData(Transfers.ITEMS),
                            s.getDropAction() == MOVE);
                } else {
                    actions.dropLots(target, (Transfers.Lots) t.getTransferData(Transfers.LOTS));
                }
                return true;
            } catch (IllegalArgumentException e) {
                Ui.error(MenuTreePanel.this, "Move menu", e.getMessage());
            } catch (Exception e) {
                Log.severe("Drop failed", e);
            }
            return false;
        }
    }
}
