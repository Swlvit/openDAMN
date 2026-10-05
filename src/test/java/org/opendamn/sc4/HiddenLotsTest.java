package org.opendamn.sc4;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opendamn.damn.DamnMenus;
import org.opendamn.damn.MenuItem;
import org.opendamn.damn.MenuNode;
import org.opendamn.dbpf.*;

class HiddenLotsTest {

    static void writeLot(Path f, int group, int inst) throws Exception {
        Exemplar e = new Exemplar(false, Exemplar.Format.BINARY, null, List.of(
                Property.ofUint32(Exemplar.P_EXEMPLAR_TYPE, Exemplar.TYPE_BUILDING),
                Property.ofString(Exemplar.P_EXEMPLAR_NAME, "Lot " + inst),
                Property.ofUint32(Exemplar.P_LOT_RESOURCE_KEY, inst),
                Property.ofUint32(Exemplar.P_PLOP_COST, 100)));
        new DbpfWriter().add(new Tgi(Tgi.TYPE_EXEMPLAR, group, inst), e.encode()).write(f);
    }

    @Test
    void hidesAndSurvivesDeletedPlugin(@TempDir Path tmp) throws Exception {
        Path plugins = tmp.resolve("Plugins");
        Path hidden = plugins.resolve("zzzzzz_DAMN_Hidden");
        Files.createDirectories(plugins);
        writeLot(plugins.resolve("a.SC4Lot"), 7, 0x111);
        writeLot(plugins.resolve("b.SC4Lot"), 7, 0x222);
        PluginIndex idx = PluginIndex.build(List.of(plugins), null, (d, n, p) -> {});

        DamnMenus m = DamnMenus.load(tmp.resolve("DAMN"));
        m.ensureLoaded(m.root());
        MenuNode menu = m.createMenu(m.root(), "Parks");
        for (int inst : new int[] {0x111, 0x222}) {
            MenuItem it = new MenuItem();
            it.setLinkName("Lot " + inst);
            it.setExemplar(7, inst);
            it.setHidden(true);
            m.addItems(menu, List.of(it), true);
        }

        // Plugin deleted after indexing (stale index) must not abort hiding.
        Files.delete(plugins.resolve("b.SC4Lot"));
        HiddenLots.Result r = HiddenLots.rebuild(m.root().descendantsAndSelf(), idx, hidden, HiddenLots.SaveFormat.ORIGINAL);
        assertEquals(1, r.hidden());
        assertEquals(1, r.problems().size(), r.problems().toString());

        // The copy loads last and has no menu button; everything else is unchanged.
        PluginIndex after = PluginIndex.build(List.of(plugins), null, (d, n, p) -> {});
        PluginIndex.Located eff = after.effective(new Tgi(Tgi.TYPE_EXEMPLAR, 7, 0x111));
        assertTrue(eff.file().path().startsWith(hidden));
        Exemplar e = PluginIndex.readExemplar(eff);
        assertFalse(e.has(Exemplar.P_LOT_RESOURCE_KEY));
        assertEquals(100, e.getLong(Exemplar.P_PLOP_COST, -1));
        assertTrue(HiddenLots.checkLoadOrder(after, hidden).isEmpty());

        // Unhiding removes the copy again.
        menu.items().get(0).setHidden(false);
        HiddenLots.Result r2 = HiddenLots.rebuild(m.root().descendantsAndSelf(), after, hidden, HiddenLots.SaveFormat.ORIGINAL);
        assertEquals(0, r2.hidden());
        assertEquals(1, r2.filesRemoved());
    }
}
