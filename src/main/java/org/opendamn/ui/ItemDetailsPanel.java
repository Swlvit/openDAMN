package org.opendamn.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.JTextComponent;
import org.opendamn.damn.ItemTemplate;
import org.opendamn.damn.MenuItem;
import org.opendamn.damn.MenuNode;
import org.opendamn.sc4.LotInfo;
import org.opendamn.sc4.PluginIndex;
import org.opendamn.util.Images;
import org.opendamn.util.Log;

/** "Menu item details" editor. */
final class ItemDetailsPanel extends JPanel {

    private final AppContext ctx;
    private final Runnable onApplied;
    private final JLabel icon = new JLabel();
    private final JTextField linkName = new JTextField();
    private final JTextField info1 = new JTextField();
    private final JTextField info2 = new JTextField();
    private final JTextArea description = new JTextArea(4, 20);
    private final JTextArea info = new JTextArea(4, 20);
    private final ImageView preview = new ImageView();
    private final JComboBox<MenuItem.Kind> kind = new JComboBox<>(MenuItem.Kind.values());
    private final JCheckBox hidden = new JCheckBox("Hidden in the game's own menus");
    private final JLabel status = new JLabel(" ");
    private final JButton apply = new JButton("Apply");
    private final JButton revert = new JButton("Revert");

    private MenuNode node;
    private MenuItem item;
    private byte[] editIcon;
    private byte[] editPreview;
    private org.opendamn.dbpf.Tgi editPreviewRef;
    private boolean loading;
    private boolean dirty;

    ItemDetailsPanel(AppContext ctx, Runnable onApplied) {
        super(new BorderLayout(4, 4));
        this.ctx = ctx;
        this.onApplied = onApplied;

        icon.setPreferredSize(new Dimension(48, 48));
        icon.setBorder(BorderFactory.createEtchedBorder());
        icon.setToolTipText("Double-click to change the icon (44x44; larger images are scaled, 176-wide strips are cut)");
        icon.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) changeIcon();
            }
        });
        JPopupMenu ip = new JPopupMenu();
        ip.add(Ui.action("Change icon…", null, null, e -> changeIcon()));
        ip.add(Ui.action("Use icon from plugin", "Reload the icon from the lot's plugin file", null, e -> iconFromPlugin()));
        icon.setComponentPopupMenu(ip);

        preview.setToolTipText("Preview image shown on the item's info page. Double-click to change.");
        preview.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) changePreview();
            }
        });
        preview.popup().addSeparator();
        preview.popup().add(Ui.action("Add/change image…", null, null, e -> changePreview()));
        preview.popup().add(Ui.action("Use preview from plugin", null, null, e -> previewFromPlugin()));
        preview.popup().add(Ui.action("Remove image", null, null, e -> {
            editPreview = null;
            editPreviewRef = null;
            preview.setImage(null, true);
            markDirty();
        }));

        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        info.setLineWrap(true);
        info.setWrapStyleWord(true);
        for (JTextComponent t : new JTextComponent[] {linkName, info1, info2, description, info}) {
            t.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) { markDirty(); }
                @Override
                public void removeUpdate(DocumentEvent e) { markDirty(); }
                @Override
                public void changedUpdate(DocumentEvent e) { markDirty(); }
            });
            t.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) { maybePopup(e, t); }
                @Override
                public void mouseReleased(java.awt.event.MouseEvent e) { maybePopup(e, t); }
            });
        }
        kind.addActionListener(e -> markDirty());
        hidden.addActionListener(e -> markDirty());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 3, 2, 3);
        c.anchor = GridBagConstraints.FIRST_LINE_START;
        c.gridx = 0;
        c.gridy = 0;
        c.gridheight = 3;
        form.add(icon, c);
        c.gridheight = 1;
        row(form, c, 0, "Link name", linkName);
        row(form, c, 1, "Info text 1", info1);
        row(form, c, 2, "Info text 2", info2);
        c.gridx = 0;
        c.gridwidth = 1;
        c.gridy = 3;
        c.weightx = 0;
        form.add(new JLabel("Description:"), c);
        c.gridy = 4;
        c.gridwidth = 3;
        c.weightx = 1;
        c.weighty = 0.5;
        c.fill = GridBagConstraints.BOTH;
        form.add(new JScrollPane(description), c);
        c.gridy = 5;
        c.weighty = 0;
        c.fill = GridBagConstraints.NONE;
        form.add(new JLabel("Information:"), c);
        c.gridy = 6;
        c.weighty = 0.5;
        c.fill = GridBagConstraints.BOTH;
        form.add(new JScrollPane(info), c);

        JPanel side = new JPanel(new BorderLayout(2, 2));
        JScrollPane ps = new JScrollPane(preview);
        ps.setPreferredSize(new Dimension(130, 130));
        side.add(new JLabel("Preview image:"), BorderLayout.NORTH);
        side.add(ps);
        JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        opts.add(new JLabel("Type:"));
        opts.add(kind);
        side.add(opts, BorderLayout.SOUTH);

        JPanel south = new JPanel(new BorderLayout());
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        btns.add(Ui.button("Preview page…", "Show roughly how the item's info page looks in game", e -> showPagePreview()));
        btns.add(revert);
        btns.add(apply);
        south.add(hidden, BorderLayout.WEST);
        south.add(btns, BorderLayout.EAST);
        south.add(status, BorderLayout.SOUTH);
        apply.addActionListener(e -> apply());
        revert.addActionListener(e -> load(node, item));

        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.add(form);
        center.add(side, BorderLayout.EAST);
        add(center);
        add(south, BorderLayout.SOUTH);
        load(null, null);
    }

    private static void row(JPanel p, GridBagConstraints c, int y, String label, JTextField f) {
        c.gridy = y;
        c.gridx = 1;
        c.weightx = 0;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        p.add(new JLabel(label + ":"), c);
        c.gridx = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(f, c);
    }

    /** Shows an item. Pending edits of the previous item are applied, never silently lost. */
    void load(MenuNode n, MenuItem it) {
        if (dirty && item != null && it != item) apply();
        loading = true;
        node = n;
        item = it;
        boolean en = it != null;
        for (var comp : new javax.swing.JComponent[] {linkName, info1, info2, description, info, kind, hidden, apply, revert}) {
            comp.setEnabled(en);
        }
        if (it == null) {
            linkName.setText("");
            info1.setText("");
            info2.setText("");
            description.setText("");
            info.setText("");
            icon.setIcon(null);
            preview.setImage(null, true);
            editIcon = editPreview = null;
            editPreviewRef = null;
        } else {
            linkName.setText(it.linkName());
            linkName.setCaretPosition(0);
            info1.setText(it.infoText1());
            info2.setText(it.infoText2());
            description.setText(it.description());
            description.setCaretPosition(0);
            info.setText(it.info());
            info.setCaretPosition(0);
            kind.setSelectedItem(it.kind());
            hidden.setSelected(it.hidden());
            editIcon = it.icon();
            editPreview = it.preview();
            editPreviewRef = it.previewRef();
            icon.setIcon(Ui.icon(editIcon));
            preview.setImage(Images.decode(currentPreviewBytes()), ctx.settings.getBool(org.opendamn.Settings.RESET_ZOOM, true));
        }
        loading = false;
        dirty = false;
        updateStatus();
    }

    private byte[] currentPreviewBytes() {
        if (editPreview != null) return editPreview;
        if (editPreviewRef != null) return ctx.catalog().png(editPreviewRef);
        return null;
    }

    private void markDirty() {
        if (loading || item == null) return;
        dirty = true;
        updateStatus();
    }

    private void updateStatus() {
        apply.setEnabled(item != null && dirty);
        revert.setEnabled(item != null && dirty);
        status.setText(item == null ? " " : dirty ? "Unapplied changes" : item.sourceFile() != null
                ? "From " + item.sourceFile().getFileName() : " ");
    }

    boolean hasPendingEdits() {
        return dirty;
    }

    void apply() {
        if (item == null || !dirty) return;
        String name = linkName.getText().strip();
        if (name.isEmpty()) {
            Ui.error(this, "Menu item", "The link name must not be empty.");
            return;
        }
        item.setLinkName(name);
        item.setInfoText1(info1.getText().strip());
        item.setInfoText2(info2.getText().strip());
        item.setDescription(description.getText());
        item.setInfo(info.getText());
        item.setKind((MenuItem.Kind) kind.getSelectedItem());
        item.setHidden(hidden.isSelected() && item.kind() != MenuItem.Kind.NETWORK);
        item.setIcon(editIcon);
        if (editPreview != null) item.setPreview(editPreview);
        else {
            item.setPreview(null);
            item.setPreviewRef(editPreviewRef);
        }
        dirty = false;
        ctx.menus().markItemsChanged(node);
        ctx.fireDirty();
        updateStatus();
        onApplied.run();
    }

    // ---- images

    private Path chooseImage(String title) {
        JFileChooser fc = new JFileChooser(ctx.settings.get("ui.lastImageDir", null));
        fc.setDialogTitle(title);
        fc.setFileFilter(new FileNameExtensionFilter("Images (png, jpg, gif, bmp)", "png", "jpg", "jpeg", "gif", "bmp"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return null;
        ctx.settings.set("ui.lastImageDir", fc.getSelectedFile().getParent());
        return fc.getSelectedFile().toPath();
    }

    private void changeIcon() {
        if (item == null) return;
        Path p = chooseImage("Choose an icon");
        if (p == null) return;
        try {
            editIcon = Images.loadIconFile(p);
            icon.setIcon(Ui.icon(editIcon));
            markDirty();
        } catch (IOException e) {
            Ui.error(this, "Icon", e.getMessage());
        }
    }

    private void changePreview() {
        if (item == null) return;
        Path p = chooseImage("Choose a preview image");
        if (p == null) return;
        try {
            editPreview = Images.loadPreviewFile(p, 256);
            editPreviewRef = null;
            preview.setImage(Images.decode(editPreview), true);
            markDirty();
        } catch (IOException e) {
            Ui.error(this, "Preview", e.getMessage());
        }
    }

    private LotInfo lotOf(MenuItem it) {
        if (it == null || it.kind() == MenuItem.Kind.NETWORK) return null;
        PluginIndex.Located l = ctx.index().effective(it.exemplarTgi());
        if (l == null) return null;
        try {
            return ctx.catalog().describe(l);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void iconFromPlugin() {
        LotInfo l = lotOf(item);
        byte[] png = l == null ? null : ctx.catalog().png(l.iconTgi());
        if (png == null) {
            Ui.info(this, "Icon", "No icon found in the plugins (has the plugin index been built?).");
            return;
        }
        editIcon = Images.normalizeIconPng(png);
        icon.setIcon(Ui.icon(editIcon));
        markDirty();
    }

    private void previewFromPlugin() {
        LotInfo l = lotOf(item);
        if (l == null || l.previewTgi() == null) {
            Ui.info(this, "Preview", "No preview image found in the plugins.");
            return;
        }
        editPreview = ctx.catalog().png(l.previewTgi());
        editPreviewRef = null;
        preview.setImage(Images.decode(editPreview), true);
        markDirty();
    }

    // ---- insert lot values

    private void maybePopup(java.awt.event.MouseEvent e, JTextComponent target) {
        if (!e.isPopupTrigger() || item == null) return;
        JPopupMenu m = new JPopupMenu();
        LotInfo l = lotOf(item);
        if (l == null) {
            JMenuItem none = new JMenuItem("No lot data (plugin not indexed or not installed)");
            none.setEnabled(false);
            m.add(none);
        } else {
            JMenu ins = new JMenu("Insert lot value");
            Map<String, String> values = ItemTemplate.values(l, ctx.catalog());
            for (var en : values.entrySet()) {
                if (en.getValue() == null || en.getValue().isBlank()) continue;
                String shown = en.getValue().length() > 50 ? en.getValue().substring(0, 50) + "…" : en.getValue();
                JMenuItem mi = new JMenuItem(ItemTemplate.PATTERNS.getOrDefault(en.getKey(), en.getKey()) + ": " + shown);
                mi.addActionListener(a -> target.replaceSelection(en.getValue()));
                ins.add(mi);
            }
            m.add(ins);
        }
        m.addSeparator();
        m.add(Ui.action("Cut", null, null, a -> target.cut()));
        m.add(Ui.action("Copy", null, null, a -> target.copy()));
        m.add(Ui.action("Paste", null, null, a -> target.paste()));
        m.show(target, e.getX(), e.getY());
    }

    // ---- info page preview

    private void showPagePreview() {
        if (item == null) return;
        try {
            Path dir = Files.createTempDirectory("opendamn-preview");
            dir.toFile().deleteOnExit();
            Path ic = dir.resolve("icon.png");
            Files.write(ic, editIcon != null ? editIcon : Images.placeholderIcon(linkName.getText()));
            byte[] pv = currentPreviewBytes();
            Path pvp = dir.resolve("preview.png");
            Files.write(pvp, pv != null ? pv : Files.readAllBytes(ic));
            ic.toFile().deleteOnExit();
            pvp.toFile().deleteOnExit();
            String html = "<html><body style='font-family:sans-serif;background:#e1f4ff;padding:6px'>"
                    + "<h3>" + MenuItemsPanel.esc(linkName.getText()) + "</h3>"
                    + "<table><tr><td width='70' valign='top'><img src='" + pvp.toUri() + "' width='60' height='60'></td>"
                    + "<td>" + toHtml(description.getText()) + "</td></tr></table><hr>"
                    + toHtml(info.getText()) + "<hr>"
                    + "<p><img src='" + ic.toUri() + "'> <b>" + MenuItemsPanel.esc(linkName.getText()) + "</b><br>"
                    + "<small>&gt; " + MenuItemsPanel.esc(info1.getText()) + "<br>&gt; "
                    + MenuItemsPanel.esc(info2.getText()) + "</small></p></body></html>";
            javax.swing.JEditorPane ep = new javax.swing.JEditorPane("text/html", html);
            ep.setEditable(false);
            JScrollPane sp = new JScrollPane(ep);
            sp.setPreferredSize(new Dimension(460, 380));
            javax.swing.JOptionPane.showMessageDialog(this, sp, "Info page preview", javax.swing.JOptionPane.PLAIN_MESSAGE);
        } catch (IOException e) {
            Log.warn("Preview failed: " + e.getMessage());
        }
    }

    private static String toHtml(String s) {
        return MenuItemsPanel.esc(ItemTemplate.stripHashes(s)).replace("\n", "<br>");
    }
}
