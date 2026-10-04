package org.opendamn.ui;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import org.opendamn.sc4.PluginIndex;
import org.opendamn.util.Log;

/** "Plugins (folder)" panel: browse plugin folders and pick where to look for lots. */
public final class PluginFolderPanel extends JPanel {

    /** Selected folder and whether subfolders are included. */
    public record Selection(Path folder, boolean subfolders) {
        public List<Path> files() throws IOException {
            List<Path> out = new ArrayList<>();
            if (subfolders) {
                PluginIndex.collectInLoadOrder(folder, out);
            } else {
                try (Stream<Path> s = Files.list(folder)) {
                    s.filter(Files::isRegularFile).filter(PluginIndex::isPluginFile).sorted().forEach(out::add);
                }
            }
            return out;
        }
    }

    private final AppContext ctx;
    private final JTree tree = new JTree();
    private final JCheckBox subfolders = new JCheckBox("Scan with subfolders");
    private final Consumer<Selection> onSelect;

    public PluginFolderPanel(AppContext ctx, Consumer<Selection> onSelect) {
        super(new BorderLayout(2, 2));
        this.ctx = ctx;
        this.onSelect = onSelect;
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeSelectionListener(e -> fire());
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent e) {
                loadChildren((DefaultMutableTreeNode) e.getPath().getLastPathComponent());
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent e) {}
        });
        subfolders.setToolTipText("Also look for lots in all folders below the selected one");
        subfolders.addActionListener(e -> fire());
        add(new JScrollPane(tree));
        add(subfolders, BorderLayout.SOUTH);
        tree.setComponentPopupMenu(popup());
        reload();
    }

    private JPopupMenu popup() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.action("Open folder", null, null, e -> openFolder()));
        m.add(Ui.action("Refresh", null, null, e -> reload()));
        m.add(Ui.action("Scan with subfolders", null, null, e -> {
            subfolders.setSelected(true);
            fire();
        }));
        m.addSeparator();
        m.add(Ui.action("Change plugins folder…", "Browse lots in another folder", null, e -> changeFolder()));
        m.add(Ui.action("Restore default folder", null, null, e -> {
            ctx.setBrowseRoot(null);
            reload();
        }));
        return m;
    }

    public void reload() {
        Path root = ctx.browseRoot();
        DefaultMutableTreeNode r = new DefaultMutableTreeNode(root == null ? "(no plugins folder)" : new FolderNode(root, true));
        if (root != null) loadChildren(r);
        tree.setModel(new DefaultTreeModel(r));
    }

    private void loadChildren(DefaultMutableTreeNode n) {
        if (!(n.getUserObject() instanceof FolderNode f) || f.loaded) return;
        f.loaded = true;
        n.removeAllChildren();
        try (Stream<Path> s = Files.list(f.path)) {
            // Hidden and "system" folders are included on purpose: the game loads them too.
            for (Path p : s.filter(Files::isDirectory)
                    .sorted((a, b) -> a.getFileName().toString().toLowerCase(Locale.ROOT)
                            .compareTo(b.getFileName().toString().toLowerCase(Locale.ROOT))).toList()) {
                DefaultMutableTreeNode c = new DefaultMutableTreeNode(new FolderNode(p, false));
                if (hasSubdirs(p)) c.add(new DefaultMutableTreeNode("…"));
                n.add(c);
            }
        } catch (IOException e) {
            Log.warn("Cannot list " + f.path + ": " + e.getMessage());
        }
        if (tree.getModel() instanceof DefaultTreeModel m) m.nodeStructureChanged(n);
    }

    private static boolean hasSubdirs(Path p) {
        try (Stream<Path> s = Files.list(p)) {
            return s.anyMatch(Files::isDirectory);
        } catch (IOException e) {
            return false;
        }
    }

    public Path selectedFolder() {
        TreePath tp = tree.getSelectionPath();
        if (tp == null) return null;
        Object o = ((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject();
        return o instanceof FolderNode f ? f.path : null;
    }

    public void refreshSelection() {
        fire();
    }

    private void fire() {
        Path p = selectedFolder();
        if (p != null) onSelect.accept(new Selection(p, subfolders.isSelected()));
    }

    private void openFolder() {
        Path p = selectedFolder() != null ? selectedFolder() : ctx.browseRoot();
        openInFileManager(p);
    }

    static void openInFileManager(Path p) {
        if (p == null) return;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(p.toFile());
            } else {
                new ProcessBuilder("xdg-open", p.toString()).start();
            }
        } catch (IOException | RuntimeException e) {
            Log.warn("Cannot open " + p + ": " + e.getMessage());
        }
    }

    private void changeFolder() {
        JFileChooser fc = new JFileChooser(ctx.browseRoot() == null ? null : ctx.browseRoot().toFile());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Folder with plugins to browse");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        ctx.setBrowseRoot(fc.getSelectedFile().toPath());
        reload();
    }

    private static final class FolderNode {
        final Path path;
        final boolean root;
        boolean loaded;

        FolderNode(Path path, boolean root) {
            this.path = path;
            this.root = root;
        }

        @Override
        public String toString() {
            return root ? path.toString() : path.getFileName().toString();
        }
    }
}
