package org.opendamn.sc4;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.opendamn.damn.DamnInstaller;
import org.opendamn.damn.DamnMenus;
import org.opendamn.damn.MenuItem;
import org.opendamn.damn.MenuNode;
import org.opendamn.dbpf.Tgi;

/** Finds common problems with a DAMN setup. Read-only. */
public final class HealthCheck {

    public enum Severity { ERROR, WARNING, INFO }

    public record Issue(Severity severity, String title, String detail, List<Path> files) {}

    private HealthCheck() {}

    public static List<Issue> run(DamnMenus menus, PluginIndex index, Path hiddenDir, int maxItems) throws IOException {
        List<Issue> out = new ArrayList<>();
        Path damn = menus.damnDir();
        Path root = menus.rootDir().toAbsolutePath().normalize();

        if (!DamnInstaller.isInstalled(damn)) {
            out.add(new Issue(Severity.ERROR, "DAMN engine missing",
                    "Support/damncontrol.dat or damndesign.dat is missing; menus will not appear in game.", List.of()));
        }

        // Menu scripts outside DAMN/Root: the game runs them but their menu is not in the index.
        List<Path> stray = new ArrayList<>();
        List<Path> dupEngine = new ArrayList<>();
        for (IndexedFile f : index.files()) {
            Path p = f.path().toAbsolutePath().normalize();
            if (p.startsWith(root)) continue;
            for (IndexedFile.Res r : f.resources()) {
                if (r.tgi().type() != Tgi.TYPE_LUA || r.tgi().group() != Tgi.GROUP_LUA) continue;
                if (r.tgi().instance() == 0x0DAE102C && !p.startsWith(damn.toAbsolutePath().normalize())) {
                    dupEngine.add(p);
                    break;
                }
                if (isMenuScript(f, r)) {
                    stray.add(p);
                    break;
                }
            }
        }
        if (!stray.isEmpty()) {
            out.add(new Issue(Severity.ERROR, "Menu files outside DAMN/Root",
                    "These DAMN menu files are loaded by the game but are not part of the menu tree. "
                            + "They cause Lua errors in game and should be removed from the Plugins folders "
                            + "(or imported into a menu first).", stray));
        }
        if (!dupEngine.isEmpty()) {
            out.add(new Issue(Severity.ERROR, "Second copy of the DAMN engine",
                    "Another damncontrol script is installed; only one may exist.", dupEngine));
        }

        // Duplicate menu file names: the game can only map one of them.
        Map<String, List<Path>> names = new HashMap<>();
        if (Files.isDirectory(root)) {
            try (var s = Files.walk(root)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dat"))
                        .forEach(p -> names.computeIfAbsent(p.getFileName().toString().toLowerCase(Locale.ROOT),
                                k -> new ArrayList<>()).add(p));
            }
        }
        List<Path> dups = new ArrayList<>();
        for (List<Path> l : names.values()) if (l.size() > 1) dups.addAll(l);
        if (!dups.isEmpty()) {
            out.add(new Issue(Severity.WARNING, "Duplicate menu file names",
                    "Several menus use the same file name, so only one of them shows its items in game. "
                            + "Saving in OpenDAMN renames the duplicates.", dups));
        }

        if (!menus.indexIsCurrent()) {
            out.add(new Issue(Severity.WARNING, "DAMN-index.txt is out of date",
                    "Some menus are missing from the index. Use DAMN > Rebuild index or save.", List.of()));
        }

        // Overloaded menus and items whose lots are no longer installed.
        List<String> over = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (MenuNode n : menus.root().descendantsAndSelf()) {
            menus.ensureLoaded(n);
            if (n.entryCount() > maxItems) over.add(n.displayPath() + " (" + n.entryCount() + ")");
            for (MenuItem it : n.items()) {
                if (it.kind() == MenuItem.Kind.NETWORK || it.kind() == MenuItem.Kind.ZONE) continue;
                checked++;
                if (index.files().isEmpty()) continue;
                if (index.effective(it.exemplarTgi()) == null && index.exemplarsWithInstance(it.exemplarInstance()).isEmpty()) {
                    missing.add(n.displayPath() + ": " + it.linkName() + " (" + it.exemplarTgi() + ")");
                }
            }
        }
        if (!over.isEmpty()) {
            out.add(new Issue(Severity.WARNING, over.size() + " menu(s) over the in-game limit of " + maxItems,
                    String.join("\n", over), List.of()));
        }
        if (!missing.isEmpty()) {
            out.add(new Issue(Severity.WARNING, missing.size() + " of " + checked + " menu item(s) refer to lots that are not installed",
                    "Plopping them does nothing in game.\n" + String.join("\n", missing.subList(0, Math.min(200, missing.size())))
                            + (missing.size() > 200 ? "\n…" : ""), List.of()));
        }

        if (hiddenDir != null && Files.isDirectory(hiddenDir)) {
            List<String> order = HiddenLots.checkLoadOrder(index, hiddenDir);
            if (!order.isEmpty()) {
                out.add(new Issue(Severity.WARNING, "Hidden lots folder does not load last",
                        String.join("\n", order.subList(0, Math.min(50, order.size()))), List.of()));
            }
        }
        if (out.isEmpty()) out.add(new Issue(Severity.INFO, "No problems found", "", List.of()));
        return out;
    }

    /** A DAMN menu script registers items with {@code DAMenu.addItem(menu, ...)}. */
    static boolean isMenuScript(IndexedFile f, IndexedFile.Res r) {
        try {
            byte[] d = PluginIndex.read(new PluginIndex.Located(f, r));
            String s = new String(d, 0, Math.min(d.length, 800), StandardCharsets.ISO_8859_1);
            return s.contains("DAMenu.items[") && s.contains("if (DAMenu ~= nil)");
        } catch (IOException e) {
            return false;
        }
    }

    /** Files with menu scripts outside Root, as a set (for the importer). */
    public static Set<Path> strayMenuFiles(List<Issue> issues) {
        Set<Path> s = new HashSet<>();
        for (Issue i : issues) if (i.title().startsWith("Menu files outside")) s.addAll(i.files());
        return s;
    }
}
