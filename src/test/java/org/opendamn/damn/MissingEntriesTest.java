package org.opendamn.damn;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MissingEntriesTest {

    static final MenuFileCodec.IconFallback NO_ICON = DamnMenusTest.NO_ICON;

    /** Instances 0x9xx stand for lots that are no longer installed. */
    static boolean missing(MenuItem it) {
        return (it.exemplarTgi().instance() & 0xF00) == 0x900;
    }

    @Test
    void archivesMissingEntriesAndRestoresThem(@TempDir Path tmp) throws Exception {
        Path damn = tmp.resolve("game/Plugins/DAMN");
        Path archiveParent = tmp.resolve("outside");
        Files.createDirectories(archiveParent);

        // Parks: one good, one missing. Gone/Inner: only missing entries, so both menus empty out.
        // Mixed: a missing entry but a sub-menu with a good lot, so Mixed itself is kept.
        DamnMenus m = DamnMenus.load(damn);
        m.ensureLoaded(m.root());
        MenuNode parks = m.createMenu(m.root(), "Parks");
        m.addItems(parks, List.of(DamnMenusTest.item("Good park", 7, 0x101), DamnMenusTest.item("Lost park", 7, 0x901)), true);
        MenuNode gone = m.createMenu(m.root(), "Gone");
        m.addItems(gone, List.of(DamnMenusTest.item("Lost A", 7, 0x902)), true);
        MenuNode inner = m.createMenu(gone, "Inner");
        m.addItems(inner, List.of(DamnMenusTest.item("Lost B", 7, 0x903)), true);
        MenuNode mixed = m.createMenu(m.root(), "Mixed");
        m.addItems(mixed, List.of(DamnMenusTest.item("Lost C", 7, 0x904)), true);
        MenuNode keep = m.createMenu(mixed, "Keep");
        m.addItems(keep, List.of(DamnMenusTest.item("Good D", 7, 0x105)), true);
        m.save(tmp.resolve("backups"), NO_ICON);

        // Start from disk, as the app does.
        m = DamnMenus.load(damn);
        MissingEntries.Plan plan = MissingEntries.plan(m, MissingEntriesTest::missing);
        assertEquals(4, plan.items());
        assertEquals(Set.of("Root/Gone", "Root/Gone/Inner"),
                Set.copyOf(plan.emptied().stream().map(MenuNode::displayPath).toList()));

        MissingEntries.Result r = MissingEntries.archive(m, plan, archiveParent, NO_ICON);
        assertTrue(r.folder().startsWith(archiveParent));
        assertEquals(4, r.items());
        assertTrue(Files.isRegularFile(r.folder().resolve("removed-entries.txt")));
        assertTrue(Files.isRegularFile(r.folder().resolve("restore-into-Root.zip")));
        String list = Files.readString(r.folder().resolve("removed-entries.txt"));
        for (String n : List.of("Lost park", "Lost A", "Lost B", "Lost C")) assertTrue(list.contains(n), n);

        // Archiving alone must not change the live menus.
        assertEquals(4, MissingEntries.plan(DamnMenus.load(damn), MissingEntriesTest::missing).items());

        MissingEntries.apply(m, plan);
        m.save(tmp.resolve("backups"), NO_ICON);

        DamnMenus after = DamnMenus.load(damn);
        assertEquals(0, MissingEntries.plan(after, MissingEntriesTest::missing).items());
        assertNull(after.root().child("Gone"), "a menu with nothing left is removed");
        MenuNode mixedAfter = after.root().child("Mixed");
        assertNotNull(mixedAfter, "a menu that still has a sub-menu with entries is kept");
        after.ensureLoaded(mixedAfter);
        assertTrue(mixedAfter.items().isEmpty());
        MenuNode parksAfter = after.root().child("Parks");
        after.ensureLoaded(parksAfter);
        assertEquals(List.of("Good park"), parksAfter.items().stream().map(MenuItem::linkName).toList());

        // Restoring the ZIP into Root, the same way the Import menu from ZIP action does, puts
        // every entry back without disturbing what was kept.
        Path zip = r.folder().resolve("restore-into-Root.zip");
        MenuZip.Contents c = MenuZip.inspect(zip);
        assertTrue(c.isRoot());
        MenuZip.extract(zip, after.rootDir(), true);
        DamnMenus.load(damn).save(tmp.resolve("backups"), NO_ICON);

        DamnMenus restored = DamnMenus.load(damn);
        assertEquals(4, MissingEntries.plan(restored, MissingEntriesTest::missing).items());
        MenuNode innerBack = restored.root().child("Gone").child("Inner");
        restored.ensureLoaded(innerBack);
        assertEquals(List.of("Lost B"), innerBack.items().stream().map(MenuItem::linkName).toList());
        MenuNode parksBack = restored.root().child("Parks");
        restored.ensureLoaded(parksBack);
        assertEquals(Set.of("Good park", "Lost park"),
                Set.copyOf(parksBack.items().stream().map(MenuItem::linkName).toList()));

        // Menu file names stay unique across the tree, which the in-game script relies on.
        try (Stream<Path> s = Files.walk(restored.rootDir())) {
            List<String> names = s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString().toLowerCase()).toList();
            assertEquals(names.size(), Set.copyOf(names).size(), names.toString());
        }
    }

    @Test
    void networksAreNeverRemoved(@TempDir Path tmp) throws Exception {
        DamnMenus m = DamnMenus.load(tmp.resolve("DAMN"));
        m.ensureLoaded(m.root());
        MenuNode roads = m.createMenu(m.root(), "Roads");
        MenuItem road = ItemTemplate.networkItem("Road", 0x901, new byte[] {1});
        m.addItems(roads, List.of(road), true);
        assertEquals(0, MissingEntries.plan(m, MissingEntriesTest::missing).items());
    }
}
