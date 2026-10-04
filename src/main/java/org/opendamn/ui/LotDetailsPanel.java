package org.opendamn.ui;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import org.opendamn.Settings;
import org.opendamn.dbpf.Property;
import org.opendamn.sc4.LotInfo;
import org.opendamn.util.Images;

/** "Lot details" panel with Main, Misc, Preview and Properties tabs. */
final class LotDetailsPanel extends JPanel {

    private final AppContext ctx;
    private final JTextField name = field(), exName = field(), size = field(), ploppable = field(), landmark = field(),
            reward = field(), plop = field(), monthly = field(), bulldoze = field();
    private final JTextField occupant = field(), dept = field(), purpose = field(), file = field(), tgi = field(),
            hidden = field(), lotTgi = field();
    private final ImageView preview = new ImageView();
    private final DefaultTableModel props = new DefaultTableModel(new String[] {"Property", "Name", "Value"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };

    LotDetailsPanel(AppContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Main", form(new String[] {"Name", "Exemplar name", "Size (w x d)", "Ploppable", "Landmark",
                "Reward", "Plop cost", "Monthly cost", "Bulldoze cost"},
                name, exName, size, ploppable, landmark, reward, plop, monthly, bulldoze));
        tabs.addTab("Misc", form(new String[] {"Occupant groups", "Budget department", "Budget purpose", "File",
                "Exemplar TGI", "Lot config TGI", "Hidden (no button)"},
                occupant, dept, purpose, file, tgi, lotTgi, hidden));
        tabs.addTab("Preview", new JScrollPane(preview));
        JTable t = new JTable(props);
        t.getColumnModel().getColumn(0).setPreferredWidth(80);
        t.getColumnModel().getColumn(1).setPreferredWidth(160);
        t.getColumnModel().getColumn(2).setPreferredWidth(220);
        tabs.addTab("Properties", new JScrollPane(t));
        add(tabs);
    }

    void show(LotInfo l) {
        if (l == null) {
            for (JTextField f : new JTextField[] {name, exName, size, ploppable, landmark, reward, plop, monthly, bulldoze,
                    occupant, dept, purpose, file, tgi, lotTgi, hidden}) f.setText("");
            preview.setImage(null, true);
            props.setRowCount(0);
            return;
        }
        name.setText(l.displayName());
        exName.setText(l.exemplarName());
        size.setText(l.sizeText());
        ploppable.setText(yes(l.ploppable()));
        landmark.setText(yes(l.landmark()));
        reward.setText(yes(l.reward()));
        plop.setText(Long.toString(l.plopCost()));
        monthly.setText(Long.toString(l.monthlyCost()));
        bulldoze.setText(Long.toString(l.bulldozeCost()));
        occupant.setText(describeGroups(l));
        dept.setText(l.budgetDepartment());
        purpose.setText(l.budgetPurpose());
        file.setText(l.file().toString());
        file.setCaretPosition(0);
        tgi.setText(l.exemplar().toString());
        lotTgi.setText(l.lotConfig() == null ? "" : l.lotConfig().toString());
        hidden.setText(yes(l.hidden()));
        byte[] png = ctx.catalog().png(l.previewTgi() != null ? l.previewTgi() : l.iconTgi());
        preview.setImage(Images.decode(png), ctx.settings.getBool(Settings.RESET_ZOOM, true));
        props.setRowCount(0);
        addProps("", l.properties());
        if (!l.lotProperties().isEmpty()) addProps("Lot: ", l.lotProperties());
    }

    private void addProps(String prefix, Map<Integer, Property> m) {
        for (Property p : m.values()) {
            String n = ctx.filters().propertyName(p.id());
            props.addRow(new Object[] {String.format("0x%08x", p.id()), prefix + (n == null ? "" : n), p.valueString()});
        }
    }

    private String describeGroups(LotInfo l) {
        Property og = l.properties().get(org.opendamn.dbpf.Exemplar.P_OCCUPANT_GROUPS);
        if (og == null) return "";
        StringBuilder sb = new StringBuilder();
        for (long v : og.values()) {
            if (sb.length() > 0) sb.append(", ");
            String n = ctx.filters().optionName(og.id(), v);
            sb.append(n != null ? n : String.format("0x%x", v));
        }
        return sb.toString();
    }

    private static String yes(boolean b) {
        return b ? "Yes" : "No";
    }

    static JTextField field() {
        JTextField f = new JTextField();
        f.setEditable(false);
        return f;
    }

    static JPanel form(String[] labels, JTextField... fields) {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        for (int k = 0; k < labels.length; k++) {
            c.gridx = 0;
            c.gridy = k;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            c.anchor = GridBagConstraints.LINE_END;
            p.add(new JLabel(labels[k] + ":"), c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            p.add(fields[k], c);
        }
        c.gridy = labels.length;
        c.weighty = 1;
        p.add(new JPanel(), c);
        return p;
    }
}
