package org.opendamn.sc4;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opendamn.dbpf.*;
import org.opendamn.damn.DamnMenus;
import org.opendamn.damn.MenuItem;
import org.opendamn.damn.MenuNode;

class SubmenuIndexTest {

    /** A sub-menu button, as the Submenus DLL expects to find one. */
    static void writeButton(Path f, int id, int parent, String name) throws Exception {
        Exemplar e = new Exemplar(false, Exemplar.Format.BINARY, null, List.of(
                Property.ofUint32(Exemplar.P_EXEMPLAR_TYPE, SubmenuIndex.EXT_SUBMENU_BUTTON),
                Property.ofString(Exemplar.P_EXEMPLAR_NAME, name),
                Property.ofUint32(Exemplar.P_ITEM_BUTTON_ID, id),
                Property.ofUint32(Exemplar.P_ITEM_SUBMENU_PARENT, parent),
                Property.ofUint32(Exemplar.P_ITEM_ORDER, 1)));
        new DbpfWriter().add(new Tgi(Tgi.TYPE_EXEMPLAR, SubmenuIndex.GROUP_SUBMENU_BUTTON, id), e.encode()).write(f);
    }

    /** A patch cohort putting targets into a sub-menu, without tagging the lot itself. */
    static void writePatch(Path f, int iid, int submenu, int... groupInstancePairs) throws Exception {
        long[] targets = new long[groupInstancePairs.length];
        for (int i = 0; i < groupInstancePairs.length; i++) targets[i] = groupInstancePairs[i];
        Exemplar e = new Exemplar(true, Exemplar.Format.BINARY, null, List.of(
                Property.ofUint32(SubmenuIndex.P_PATCH_TARGETS, targets),
                Property.ofUint32(Exemplar.P_BUILDING_SUBMENUS, submenu)));
        new DbpfWriter().add(new Tgi(Tgi.TYPE_COHORT, SubmenuIndex.GROUP_EXEMPLAR_PATCH, iid), e.encode()).write(f);
    }

    static Exemplar lot(int inst, int submenuTag) {
        List<Property> props = new java.util.ArrayList<>(List.of(
                Property.ofUint32(Exemplar.P_EXEMPLAR_TYPE, Exemplar.TYPE_BUILDING),
                Property.ofString(Exemplar.P_EXEMPLAR_NAME, "Lot " + Integer.toHexString(inst)),
                Property.ofUint32(Exemplar.P_LOT_RESOURCE_KEY, inst)));
        if (submenuTag != 0) props.add(Property.ofUint32(Exemplar.P_BUILDING_SUBMENUS, submenuTag));
        return new Exemplar(false, Exemplar.Format.BINARY, null, props);
    }

    /** Writes several lots into one package, keyed by instance. */
    static void writeLots(Path f, int group, int submenuTag, int... instances) throws Exception {
        DbpfWriter w = new DbpfWriter();
        for (int inst : instances) {
            w.add(new Tgi(Tgi.TYPE_EXEMPLAR, group, inst), lot(inst, submenuTag).encode());
        }
        w.write(f);
    }

    @Test
    void classifiesHiddenTaggedUntaggedAndDangling(@TempDir Path tmp) throws Exception {
        Path plugins = tmp.resolve("Plugins");
        Path hidden = plugins.resolve("zzzzzz_DAMN_Hidden");
        Files.createDirectories(plugins);
        Files.createDirectories(hidden);
        int menu = 0xABCD0001;

        writeButton(plugins.resolve("button.dat"), menu, 3, "Test sub-menu");

        // 0x111 is tagged and will be hidden, 0x222 is tagged and visible,
        // 0x333 exists untagged and is only reachable through the patch, 0x444 does not exist.
        DbpfWriter lots = new DbpfWriter();
        lots.add(new Tgi(Tgi.TYPE_EXEMPLAR, 7, 0x111), lot(0x111, menu).encode());
        lots.add(new Tgi(Tgi.TYPE_EXEMPLAR, 7, 0x222), lot(0x222, menu).encode());
        lots.add(new Tgi(Tgi.TYPE_EXEMPLAR, 7, 0x333), lot(0x333, 0).encode());
        lots.write(plugins.resolve("tagged.SC4Lot"));
        writePatch(plugins.resolve("patch.dat"), 0x900, menu, 7, 0x111, 7, 0x222, 7, 0x333, 7, 0x444);

        DamnMenus m = DamnMenus.load(tmp.resolve("DAMN"));
        m.ensureLoaded(m.root());
        MenuNode node = m.createMenu(m.root(), "Parks");
        for (int inst : new int[] {0x111, 0x333}) {
            MenuItem it = new MenuItem();
            it.setLinkName("Lot " + Integer.toHexString(inst));
            it.setExemplar(7, inst);
            it.setHidden(true);
            m.addItems(node, List.of(it), true);
        }

        // Hide 0x111 into the hidden folder; its sub-menu tag survives, as it does in practice.
        HiddenLots.Result hr = HiddenLots.rebuild(m.root().descendantsAndSelf(),
                PluginIndex.build(List.of(plugins), null, (d, n, p) -> {}), hidden, HiddenLots.SaveFormat.ORIGINAL);
        assertEquals(2, hr.hidden(), hr.problems().toString());

        PluginIndex idx = PluginIndex.build(List.of(plugins), null, (d, n, p) -> {});
        SubmenuIndex si = SubmenuIndex.build(idx, hidden, new PluginIndex.Progress() {
            public void update(int d, int n, Path cur) {}
        });

        assertEquals(1, si.buttons().size());
        assertTrue(si.buttons().containsKey(menu));
        assertEquals("Test sub-menu", si.buttons().get(menu).name());

        List<SubmenuIndex.Target> targets = si.members().get(menu);
        assertEquals(4, targets.size());
        assertEquals(SubmenuIndex.State.HIDDEN, state(targets, 0x111));
        assertEquals(SubmenuIndex.State.VISIBLE, state(targets, 0x222));
        // Reachable through the patch, and hidden even though it was never tagged.
        assertEquals(SubmenuIndex.State.HIDDEN, state(targets, 0x333));
        assertEquals(SubmenuIndex.State.DANGLING, state(targets, 0x444));

        SubmenuIndex.Report r = si.classify(m.root().descendantsAndSelf());

        // 0x111: hidden and tagged. It is in the hidden folder, so it is reported as tagged.
        assertEquals(1, r.hiddenWithSubmenu().size());
        assertEquals(0x111, r.hiddenWithSubmenu().get(0).exemplar().instance());
        assertEquals("Test sub-menu", r.hiddenWithSubmenu().get(0).submenu());

        // 0x333: hidden, reachable by the patch, but no tag of its own.
        assertEquals(1, r.hiddenUntagged().size());
        assertEquals(0x333, r.hiddenUntagged().get(0).exemplar().instance());

        // 0x222 is tagged and visible: in the sub-menu exactly as intended, so not reported.
        assertTrue(r.hiddenWithSubmenu().stream().noneMatch(x -> x.exemplar().instance() == 0x222));

        // The row must carry the whole exemplar TGI, because unhiding matches on
        // MenuItem.exemplarTgi(), which is type 0x6534284A with the building's group and instance.
        Tgi expected = new Tgi(Tgi.TYPE_EXEMPLAR, 7, 0x111);
        assertEquals(expected, r.hiddenWithSubmenu().get(0).exemplar());
        MenuItem inMenu = node.items().get(0);
        assertEquals(inMenu.exemplarTgi(), r.hiddenWithSubmenu().get(0).exemplar(),
                "the report's TGI must match the one the menu item reports");
        assertTrue(inMenu.hidden());

        // And unhiding through that identity reaches the item.
        inMenu.setHidden(false);
        assertFalse(node.items().get(0).hidden());

        // 0x444 does not exist; it is reported as a missing lot, not as a button problem.
        assertEquals(1, r.missingLots().size());
        assertEquals(0x444, r.missingLots().get(0).exemplar().instance());
        assertTrue(r.needsButton().isEmpty());

        assertEquals(1, r.allSubmenus().size());
        SubmenuIndex.SubmenuStatus st = r.allSubmenus().get(0);
        assertTrue(st.hasButton());
        assertEquals(4, st.members());
        assertEquals(1, st.dangling());
        assertEquals(2, st.hidden());
        assertEquals(1, st.visible());
        // One member is still visible, so the sub-menu does show in game.
        assertFalse(st.empty());
        assertTrue(r.brokenSubmenus().isEmpty());

        // The lot text is not a hex key when a menu name is known.
        assertEquals("Lot 111", r.hiddenWithSubmenu().get(0).lot());
    }

    @Test
    void reportsSubmenuWithNoUsableMembers(@TempDir Path tmp) throws Exception {
        Path plugins = tmp.resolve("Plugins");
        Path hidden = plugins.resolve("zzzzzz_DAMN_Hidden");
        Files.createDirectories(plugins);
        Files.createDirectories(hidden);
        int menu = 0xABCD0002;

        writeButton(plugins.resolve("button.dat"), menu, 3, "Empty sub-menu");
        // Every target is missing, so the game has nothing to show.
        writePatch(plugins.resolve("patch.dat"), 0x901, menu, 7, 0x555, 7, 0x666);

        DamnMenus m = DamnMenus.load(tmp.resolve("DAMN"));
        m.ensureLoaded(m.root());
        PluginIndex idx = PluginIndex.build(List.of(plugins), null, (d, n, p) -> {});
        SubmenuIndex si = SubmenuIndex.build(idx, hidden, new PluginIndex.Progress() {
            public void update(int d, int n, Path cur) {}
        });
        SubmenuIndex.Report r = si.classify(m.root().descendantsAndSelf());

        assertEquals(1, r.brokenSubmenus().size());
        SubmenuIndex.SubmenuStatus st = r.brokenSubmenus().get(0);
        assertEquals(2, st.dangling());
        assertEquals(0, st.visible());
        assertTrue(st.empty());
        assertEquals("all 2 member(s) are missing", st.reason());
    }

    @Test
    void submenuWithNoMembersIsNotReportedAsBroken(@TempDir Path tmp) throws Exception {
        Path plugins = tmp.resolve("Plugins");
        Path hidden = plugins.resolve("zzzzzz_DAMN_Hidden");
        Files.createDirectories(plugins);
        Files.createDirectories(hidden);

        // A curated button nobody has added anything to: unused, not broken.
        writeButton(plugins.resolve("button.dat"), 0xABCD0003, 3, "Unused sub-menu");

        DamnMenus m = DamnMenus.load(tmp.resolve("DAMN"));
        m.ensureLoaded(m.root());
        PluginIndex idx = PluginIndex.build(List.of(plugins), null, (d, n, p) -> {});
        SubmenuIndex si = SubmenuIndex.build(idx, hidden, new PluginIndex.Progress() {
            public void update(int d, int n, Path cur) {}
        });
        SubmenuIndex.Report r = si.classify(m.root().descendantsAndSelf());

        assertEquals(1, r.allSubmenus().size());
        assertEquals(0, r.allSubmenus().get(0).members());
        assertTrue(r.brokenSubmenus().isEmpty(), "a sub-menu with no members is unused, not broken");
        assertFalse(r.allSubmenus().get(0).empty());
    }

    private static SubmenuIndex.State state(List<SubmenuIndex.Target> targets, int instance) {
        for (SubmenuIndex.Target t : targets) {
            if (t.exemplar().instance() == instance) return t.state();
        }
        return fail("no target for instance " + Integer.toHexString(instance));
    }
}
