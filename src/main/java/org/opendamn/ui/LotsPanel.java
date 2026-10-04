package org.opendamn.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.datatransfer.Transferable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DropMode;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import org.opendamn.Settings;
import org.opendamn.sc4.LotFilters;
import org.opendamn.sc4.LotInfo;

/** "Lots list" panel: lots found in the selected plugin folder. */
public final class LotsPanel extends JPanel {

    /** Callbacks to the main window. */
    public interface Actions {
        void addLots(List<LotInfo> lots);

        void copyLots(List<LotInfo> lots);

        void clearClipboard();

        void viewClipboard();

        boolean canAdd();
    }

    private final AppContext ctx;
    private final Actions actions;
    private final LotModel model = new LotModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<LotModel> sorter = new TableRowSorter<>(model);
    private final JTextField search = new JTextField(14);
    private final JLabel counts = new JLabel();
    private final JButton filterBtn;
    private final JButton addBtn;
    private final JScrollPane scroll;
    private LotFilters.Category activeFilter;
    private final Map<Object, Icon> icons = new HashMap<>();

    public LotsPanel(AppContext ctx, Actions actions, Consumer<LotInfo> onSelect) {
        super(new BorderLayout(2, 2));
        this.ctx = ctx;
        this.actions = actions;

        table.setRowSorter(sorter);
        table.setRowHeight(46);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setMaxWidth(50);
        table.getColumnModel().getColumn(0).setMinWidth(50);
        table.getColumnModel().getColumn(1).setPreferredWidth(220);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.getColumnModel().getColumn(3).setPreferredWidth(160);
        table.getColumnModel().getColumn(4).setPreferredWidth(50);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.setDefaultRenderer(Icon.class, new IconRenderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            updateCounts();
            List<LotInfo> sel = selected();
            onSelect.accept(sel.size() == 1 ? sel.get(0) : null);
        });
        table.setDragEnabled(true);
        table.setDropMode(DropMode.ON);
        table.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                return Transfers.of(Transfers.LOTS, new Transfers.Lots(selected()));
            }
        });
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && actions.canAdd()) actions.addLots(selected());
            }
        });

        JPopupMenu pm = new JPopupMenu();
        pm.add(Ui.action("Copy to clipboard", null, null, e -> actions.copyLots(selected())));
        pm.add(Ui.action("Clear clipboard", null, null, e -> actions.clearClipboard()));
        pm.add(Ui.action("View clipboard…", null, null, e -> actions.viewClipboard()));
        table.setComponentPopupMenu(pm);

        search.putClientProperty("JTextField.placeholderText", "Search lots");
        search.putClientProperty("JTextField.showClearButton", true);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override
            public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        top.add(search);
        filterBtn = Ui.button("Filter…", "Show only lots of a certain kind", e -> chooseFilter());
        top.add(filterBtn);
        top.add(Ui.button("Select all", "Select all lots in the list", e -> table.selectAll()));
        addBtn = Ui.button("Add ▶", "Add the selected lots to the selected DAMN menu", e -> actions.addLots(selected()));
        top.add(addBtn);

        scroll = new JScrollPane(table);
        add(top, BorderLayout.NORTH);
        add(scroll);
        add(counts, BorderLayout.SOUTH);
        updateCounts();
    }

    public void setLots(List<LotInfo> lots) {
        List<LotInfo> l = new ArrayList<>(lots);
        if (ctx.settings.getBool(Settings.IGNORE_NO_ICON, false)) l.removeIf(x -> !x.hasIcon());
        if (ctx.settings.getBool(Settings.USE_ITEM_ORDER, true)) {
            l.sort(Comparator.comparingLong(LotInfo::itemOrder));
        }
        icons.clear();
        model.set(l);
        updateCounts();
    }

    public List<LotInfo> selected() {
        List<LotInfo> r = new ArrayList<>();
        for (int v : table.getSelectedRows()) r.add(model.rows.get(table.convertRowIndexToModel(v)));
        return r;
    }

    public void updateAddEnabled() {
        addBtn.setEnabled(actions.canAdd() && table.getSelectedRowCount() > 0);
    }

    private void updateCounts() {
        counts.setText("Selected lots: " + table.getSelectedRowCount() + "    Lots in list: " + table.getRowCount()
                + (table.getRowCount() != model.getRowCount() ? " (of " + model.getRowCount() + ")" : ""));
        updateAddEnabled();
    }

    private void chooseFilter() {
        FiltersDialog d = new FiltersDialog(Ui.windowOf(this), ctx.filters(), activeFilter);
        d.setVisible(true);
        if (!d.accepted()) return;
        activeFilter = d.choice();
        applyFilter();
    }

    private void applyFilter() {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        LotFilters.Category cat = activeFilter;
        if (q.isEmpty() && cat == null) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(new RowFilter<>() {
                @Override
                public boolean include(Entry<? extends LotModel, ? extends Integer> e) {
                    LotInfo l = e.getModel().rows.get(e.getIdentifier());
                    if (cat != null && !cat.matches(l)) return false;
                    if (q.isEmpty()) return true;
                    return l.displayName().toLowerCase(Locale.ROOT).contains(q)
                            || l.exemplarName().toLowerCase(Locale.ROOT).contains(q)
                            || l.file().getFileName().toString().toLowerCase(Locale.ROOT).contains(q)
                            || l.exemplar().toString().contains(q);
                }
            });
        }
        filterBtn.setText(cat == null ? "Filter…" : "Filter: " + cat.name);
        scroll.setBorder(cat == null ? BorderFactory.createEmptyBorder()
                : BorderFactory.createLineBorder(Color.RED, 2));
        updateCounts();
    }

    Color colorFor(LotInfo l) {
        Settings s = ctx.settings;
        return switch (l.category()) {
            case REWARD -> s.getColor("color.lot.reward", new Color(0x8E44AD));
            case LANDMARK -> s.getColor("color.lot.landmark", new Color(0x1F6FB2));
            case PLOPPABLE -> s.getColor("color.lot.ploppable", new Color(0x1E7D32));
            case FLORA -> s.getColor("color.lot.flora", new Color(0x2E8B57));
            case GROWABLE, OTHER -> s.getColor("color.lot.growable", new Color(0x8A6D3B));
        };
    }

    private final class LotModel extends AbstractTableModel {
        final List<LotInfo> rows = new ArrayList<>();
        final String[] cols = {"", "Name", "File", "TGI", "Size", "Kind"};

        void set(List<LotInfo> l) {
            rows.clear();
            rows.addAll(l);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() { return rows.size(); }
        @Override
        public int getColumnCount() { return cols.length; }
        @Override
        public String getColumnName(int c) { return cols[c]; }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? Icon.class : String.class;
        }

        @Override
        public Object getValueAt(int r, int c) {
            LotInfo l = rows.get(r);
            return switch (c) {
                case 0 -> icons.computeIfAbsent(l.exemplar(), k -> Ui.icon(ctx.catalog().png(l.iconTgi())));
                case 1 -> l.displayName() + (l.hidden() ? "  (hidden)" : "");
                case 2 -> l.file().getFileName().toString();
                case 3 -> l.exemplar().toString();
                case 4 -> l.sizeText();
                default -> l.category().name().toLowerCase(Locale.ROOT);
            };
        }
    }

    private final class Renderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            if (!sel) {
                LotInfo l = model.rows.get(t.convertRowIndexToModel(row));
                c.setForeground(colorFor(l));
            }
            if (col == 2 || col == 3) setToolTipText(model.rows.get(t.convertRowIndexToModel(row)).file().toString());
            else setToolTipText(null);
            return c;
        }
    }

    private static final class IconRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, null, sel, focus, row, col);
            setIcon((Icon) v);
            setHorizontalAlignment(CENTER);
            return this;
        }
    }
}
