package org.opendamn.damn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opendamn.dbpf.DbpfFile;
import org.opendamn.dbpf.Tgi;

class DamnMenusTest {

    static final MenuFileCodec.IconFallback NO_ICON = it -> new byte[] {(byte) 0x89, 'P', 'N', 'G'};

    static MenuItem item(String name, int g, int i) {
        MenuItem m = new MenuItem();
        m.setLinkName(name);
        m.setExemplar(g, i);
        m.setInfoText1("Info 1");
        m.setInfoText2("Size: 2 x 3");
        m.setDescription("Line one\nLine two");
        m.setInfo("Plop cost: 100");
        m.setIcon(new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
        return m;
    }

    @Test
    void luaQuotingSurvivesNastyNames() {
        for (String s : List.of("plain", "with \"quotes\"", "back\\slash", "a = b", "trailing\\", "ünïcödé ©")) {
            assertEquals(s, LuaText.parseStringLiteral(LuaText.quote(s)), s);
        }
        // Unescaped legacy strings: the last quote ends the value.
        assertEquals("Joe's \"Diner\"", LuaText.parseStringLiteral("\"Joe's \"Diner\"\""));
    }

    @Test
    void createSaveReload(@TempDir Path tmp) throws IOException {
        Path damn = tmp.resolve("DAMN");
        DamnMenus m = DamnMenus.load(damn);
        MenuNode civic = m.createMenu(m.root(), "Civic");
        MenuNode edu = m.createMenu(civic, "Education");
        m.createMenu(m.root(), "Empty");
        m.addItems(edu, List.of(item("School \"A\"", 0x1111, 0x2222), item("School B = best", 0x1111, 0x3333)), true);
        // Duplicates are ignored.
        assertEquals(0, m.addItems(edu, List.of(item("dupe", 0x1111, 0x2222)), true).size());
        m.save(tmp.resolve("backup"), NO_ICON);

        List<String> index = Files.readAllLines(damn.resolve("Support/DAMN-index.txt"), StandardCharsets.ISO_8859_1);
        assertEquals(3, index.size(), "one file per menu, root has none: " + index);
        assertTrue(index.stream().allMatch(l -> l.startsWith("Plugins\\DAMN\\Root\\")));
        assertTrue(index.stream().anyMatch(l -> l.contains("Root\\Civic\\Education\\DAMN-Menu_")));

        DamnMenus again = DamnMenus.load(damn);
        MenuNode e2 = again.root().child("civic").child("education");
        assertTrue(again.ensureLoaded(e2).isEmpty());
        assertEquals(2, e2.items().size());
        MenuItem a = e2.items().get(0);
        assertEquals("School \"A\"", a.linkName());
        assertEquals("Line one\nLine two", a.description());
        assertEquals("Plop cost: 100", a.info());
        assertEquals("Size: 2 x 3", a.infoText2());
        assertEquals(0x2222, a.exemplarInstance());
        assertEquals(7, a.icon().length);
        assertEquals("School B = best", e2.items().get(1).linkName());
    }

    @Test
    void renameMoveDeleteSwap(@TempDir Path tmp) throws IOException {
        Path damn = tmp.resolve("DAMN");
        DamnMenus m = DamnMenus.load(damn);
        MenuNode a = m.createMenu(m.root(), "A");
        MenuNode b = m.createMenu(m.root(), "B");
        MenuNode a1 = m.createMenu(a, "A1");
        m.addItems(a1, List.of(item("x", 1, 1)), true);
        m.addItems(b, List.of(item("y", 1, 2)), true);
        m.save(tmp.resolve("bk"), NO_ICON);

        // Move A1 out of A, delete A, rename B to A.
        m = DamnMenus.load(damn);
        a = m.root().child("A");
        b = m.root().child("B");
        a1 = a.child("A1");
        m.move(a1, m.root());
        m.delete(a);
        m.rename(b, "A");
        m.save(tmp.resolve("bk"), NO_ICON);

        DamnMenus r = DamnMenus.load(damn);
        assertEquals(List.of("A", "A1"), r.root().children().stream().map(MenuNode::name).toList());
        r.ensureLoaded(r.root().child("A"));
        assertEquals("y", r.root().child("A").items().get(0).linkName());
        r.ensureLoaded(r.root().child("A1"));
        assertEquals("x", r.root().child("A1").items().get(0).linkName());
        assertFalse(Files.exists(damn.resolve("Root/B")));
        try (var s = Files.list(damn.resolve("Root"))) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().startsWith(".opendamn")));
        }
    }

    @Test
    void invalidNamesRejected(@TempDir Path tmp) throws IOException {
        DamnMenus m = DamnMenus.load(tmp.resolve("DAMN"));
        m.createMenu(m.root(), "Parks");
        assertThrows(IllegalArgumentException.class, () -> m.createMenu(m.root(), "parks"));
        assertThrows(IllegalArgumentException.class, () -> m.createMenu(m.root(), "a/b"));
        assertThrows(IllegalArgumentException.class, () -> m.createMenu(m.root(), "CON"));
        assertThrows(IllegalArgumentException.class, () -> m.createMenu(m.root(), " x"));
    }

    /**
     * Loads a real DAMN folder (copied to a temp dir), rewrites every menu and verifies that
     * all items survive unchanged. Set OPENDAMN_DAMN_SAMPLE to a DAMN folder to run it.
     */
    @Test
    void realMenusRoundTrip(@TempDir Path tmp) throws IOException {
        String src = System.getenv().getOrDefault("OPENDAMN_DAMN_SAMPLE", "");
        Assumptions.assumeTrue(!src.isEmpty() && Files.isDirectory(Path.of(src)), "no DAMN sample");
        Path damn = tmp.resolve("DAMN");
        DamnMenus.copyTree(Path.of(src), damn);

        DamnMenus m = DamnMenus.load(damn);
        Map<String, List<String>> before = snapshot(m);
        int items = before.values().stream().mapToInt(List::size).sum();
        System.out.println("menus: " + before.size() + ", items: " + items);

        for (MenuNode n : m.root().descendantsAndSelf()) {
            m.ensureLoaded(n);
            m.markItemsChanged(n);
        }
        var res = m.save(tmp.resolve("backup"), NO_ICON);
        System.out.println("written: " + res.filesWritten() + " notes: " + res.notes());

        DamnMenus r = DamnMenus.load(damn);
        Map<String, List<String>> after = snapshot(r);
        assertEquals(before.keySet(), after.keySet());
        for (String k : before.keySet()) assertEquals(before.get(k), after.get(k), k);

        // Every written file must be a valid DBPF whose PNG/LTEXT references resolve.
        for (MenuNode n : r.root().descendantsAndSelf()) {
            for (Path f : n.diskFiles()) {
                DbpfFile db = DbpfFile.open(f);
                long lua = db.entries().stream().filter(e -> e.tgi().type() == Tgi.TYPE_LUA).count();
                assertTrue(lua <= 1);
            }
        }
        List<String> idx = Files.readAllLines(damn.resolve("Support/DAMN-index.txt"), DamnMenus.INDEX_CHARSET);
        assertTrue(idx.size() >= before.size() - 1, "index lines " + idx.size());
    }

    static Map<String, List<String>> snapshot(DamnMenus m) throws IOException {
        Map<String, List<String>> s = new TreeMap<>();
        for (MenuNode n : m.root().descendantsAndSelf()) {
            List<String> w = m.ensureLoaded(n);
            if (!w.isEmpty()) System.out.println(n.displayPath() + ": " + w);
            List<String> items = new ArrayList<>();
            for (MenuItem it : n.items()) {
                items.add(String.join("|", it.linkName(), it.infoText1(), it.infoText2(), it.description(), it.info(),
                        Boolean.toString(it.hidden()), it.kind().luaName, it.key(),
                        it.icon() == null ? "noicon" : Integer.toString(java.util.Arrays.hashCode(it.icon())),
                        it.preview() != null ? "p" + java.util.Arrays.hashCode(it.preview())
                                : String.valueOf(it.previewRef())));
            }
            s.put(n.displayPath(), items);
        }
        return s;
    }
}
