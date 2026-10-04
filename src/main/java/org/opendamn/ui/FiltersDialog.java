package org.opendamn.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import org.opendamn.sc4.LotFilters;

/** Choose a lot filter from the property definition categories. */
final class FiltersDialog extends JDialog {

    private final JTree tree;
    private final JTextArea help = new JTextArea();
    private boolean accepted;
    private LotFilters.Category choice;

    FiltersDialog(Window owner, LotFilters filters, LotFilters.Category current) {
        super(owner, "Filters", ModalityType.APPLICATION_MODAL);
        DefaultMutableTreeNode root = build(filters.root());
        tree = new JTree(new DefaultTreeModel(root));
        tree.addTreeSelectionListener(e -> {
            LotFilters.Category c = selected();
            StringBuilder sb = new StringBuilder();
            if (c != null) {
                sb.append(c.path()).append("\n\n").append(c.help);
                if (!c.rules.isEmpty()) sb.append("\n\nRules:");
                for (LotFilters.Rule r : c.rules) {
                    String n = filters.propertyName(r.property());
                    sb.append("\n  ").append(r.needed() ? "has " : "has not ")
                            .append(n != null ? n : String.format("0x%08x", r.property()));
                    if (r.value() != null) sb.append(" = ").append(String.format("0x%x", r.value()));
                }
            }
            help.setText(sb.toString());
            help.setCaretPosition(0);
        });
        tree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && selected() != null) accept(selected());
            }
        });
        if (current != null) select(root, current);
        help.setEditable(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(tree), new JScrollPane(help));
        split.setResizeWeight(0.55);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(Ui.button("Disable", "Show all lots", e -> accept(null)));
        buttons.add(Ui.button("Filter", "Apply the selected filter", e -> {
            if (selected() != null) accept(selected());
        }));
        buttons.add(Ui.button("Close", null, e -> dispose()));
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        p.add(split);
        p.add(buttons, BorderLayout.SOUTH);
        setContentPane(p);
        setSize(new Dimension(720, 520));
        setLocationRelativeTo(owner);
    }

    private static DefaultMutableTreeNode build(LotFilters.Category c) {
        DefaultMutableTreeNode n = new DefaultMutableTreeNode(c);
        for (LotFilters.Category k : c.children) n.add(build(k));
        return n;
    }

    private void select(DefaultMutableTreeNode n, LotFilters.Category c) {
        if (n.getUserObject() == c) {
            TreePath tp = new TreePath(n.getPath());
            tree.setSelectionPath(tp);
            tree.scrollPathToVisible(tp);
            return;
        }
        for (int k = 0; k < n.getChildCount(); k++) select((DefaultMutableTreeNode) n.getChildAt(k), c);
    }

    private LotFilters.Category selected() {
        TreePath tp = tree.getSelectionPath();
        return tp == null ? null : (LotFilters.Category) ((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject();
    }

    private void accept(LotFilters.Category c) {
        accepted = true;
        choice = c != null && c.parent == null ? null : c;
        dispose();
    }

    boolean accepted() { return accepted; }
    LotFilters.Category choice() { return choice; }
}
