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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opendamn.dbpf.DbpfFile;
import org.opendamn.dbpf.Tgi;

/**
 * Runs the generated DAMN engine script and menu scripts in a real Lua interpreter with
 * the game API stubbed, and checks the menu tree the game would build.
 */
class EngineSimulationTest {

    static final String LUA = findLua();

    static String findLua() {
        for (String c : List.of("/usr/bin/lua5.1", "/usr/bin/lua")) if (Files.isExecutable(Path.of(c))) return c;
        return null;
    }

    static final String STUBS = """
            advice_moods = { NEUTRAL = 0, GREAT_JOB = 1 }
            game_events = { kTEST1 = 1 }
            reward_state = { HIDDEN = 1 }
            tuning_constants = { ADVICE_TIMEOUT_LONG = 1, ADVICE_PRIORITY_URGENT = 1 }
            plopped = {}
            hiddenIds = {}
            function create_advice_cityplanning(id) return {} end
            function create_reward_cityplanning(id) hiddenIds[id] = true; return {} end
            game = {}
            function game.trigger_event(e) end
            function game.retire_advice() end
            function game.tool_plop_building(id) plopped[#plopped+1] = "building:" .. id end
            function game.tool_plop_flora(id) plopped[#plopped+1] = "flora:" .. id end
            function game.tool_plop_network(id) plopped[#plopped+1] = "network:" .. id end
            function game.tool_plop_zone(id) plopped[#plopped+1] = "zone:" .. id end
            """;

    static final String DUMP = """
            local function dump(m, path)
              for i = 0, m.size - 1 do
                local it = m.items[i]
                if it.menu then
                  print("M\\t" .. path .. "/" .. it.linkname)
                  dump(it, path .. "/" .. it.linkname)
                else
                  print("I\\t" .. path .. "\\t" .. it.linkname .. "\\t" .. it.type .. "\\t" .. it.iid .. "\\t" .. tostring(it.hidden))
                end
              end
            end
            dump(DAMenu, "Root")
            -- plop the first item of the root's first menu with items, like a click would
            """;

    record Result(Map<String, Integer> itemsPerMenu, List<String> menus, String stderr) {}

    static Result simulate(Path damn, Path workDir, boolean disableIo) throws Exception {
        Path scripts = workDir.resolve("scripts");
        Files.createDirectories(scripts);
        StringBuilder main = new StringBuilder(STUBS);
        if (disableIo) main.append("io = nil\n");
        main.append("dofile(").append(lq(scripts.resolve("control.lua"))).append(")\n");
        DbpfFile control = DbpfFile.open(damn.resolve("Support/damncontrol.dat"));
        Files.write(scripts.resolve("control.lua"), control.read(DamnInstaller.CONTROL_LUA));
        int k = 0;
        List<Path> menuFiles = new ArrayList<>();
        try (var s = Files.walk(damn.resolve("Root"))) {
            s.filter(p -> p.toString().endsWith(".dat")).sorted().forEach(menuFiles::add);
        }
        for (Path f : menuFiles) {
            DbpfFile d = DbpfFile.open(f);
            for (DbpfFile.Entry e : d.findByType(Tgi.TYPE_LUA)) {
                Path out = scripts.resolve("m" + (k++) + ".lua");
                Files.write(out, d.read(e));
                main.append("dofile(").append(lq(out)).append(")\n");
            }
        }
        main.append(DUMP);
        Path mainFile = scripts.resolve("main.lua");
        Files.writeString(mainFile, main.toString());

        Process p = new ProcessBuilder(LUA, mainFile.toString()).directory(workDir.toFile()).start();
        byte[] out = p.getInputStream().readAllBytes();
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), "lua failed: " + err);
        Map<String, Integer> items = new TreeMap<>();
        List<String> menus = new ArrayList<>();
        for (String line : new String(out, DamnInstaller.LUA_CHARSET).split("\n")) {
            String[] f = line.split("\t");
            if (f[0].equals("M")) menus.add(f[1]);
            else if (f[0].equals("I")) items.merge(f[1], 1, Integer::sum);
        }
        return new Result(items, menus, err);
    }

    static String lq(Path p) {
        return "[[" + p.toAbsolutePath() + "]]";
    }

    static Map<String, Integer> expected(DamnMenus m) throws IOException {
        Map<String, Integer> r = new TreeMap<>();
        for (MenuNode n : m.root().descendantsAndSelf()) {
            m.ensureLoaded(n);
            if (!n.items().isEmpty()) r.put(n.displayPath(), n.items().size());
        }
        return r;
    }

    @Test
    void syntheticMenusWorkWithAndWithoutIo(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(LUA != null, "no lua interpreter");
        Path damn = tmp.resolve("game/Plugins/DAMN");
        DamnInstaller.install(damn, DamnInstaller.Config.defaults(), DamnInstaller.Texts.english(), List.of(), null);
        DamnMenus m = DamnMenus.load(damn);
        MenuNode a = m.createMenu(m.root(), "Parks & [[Plazas]] it's");
        MenuNode b = m.createMenu(a, "Sub-menu (Root)");
        m.createMenu(m.root(), "Empty one");
        MenuItem x = DamnMenusTest.item("Weird \"name\" \\ here", 0x1, 0x10);
        x.setHidden(true);
        m.addItems(a, List.of(x, DamnMenusTest.item("Second", 0x1, 0x11)), true);
        m.addItems(b, List.of(DamnMenusTest.item("Deep", 0x2, 0x12)), true);
        m.save(tmp.resolve("bk"), DamnMenusTest.NO_ICON);

        for (boolean noIo : new boolean[] {false, true}) {
            Result r = simulate(damn, tmp.resolve("game"), noIo);
            assertEquals(expected(m), r.itemsPerMenu(), "io disabled=" + noIo + " " + r.stderr());
            assertTrue(r.menus().contains("Root/Empty one"), r.menus().toString());
        }
    }

    /** Full real-data run: OPENDAMN_DAMN_SAMPLE must point to a DAMN folder (copied, never modified). */
    @Test
    void realMenusInEngine(@TempDir Path tmp) throws Exception {
        String src = System.getenv().getOrDefault("OPENDAMN_DAMN_SAMPLE", "");
        Assumptions.assumeTrue(LUA != null && !src.isEmpty() && Files.isDirectory(Path.of(src)), "no sample");
        Path damn = tmp.resolve("game/Plugins/DAMN");
        DamnMenus.copyTree(Path.of(src), damn);

        DamnInstaller.Config cfg = DamnInstaller.readConfig(damn);
        System.out.println("config read back: " + cfg);
        DamnInstaller.install(damn, cfg, DamnInstaller.Texts.english(), List.of(), tmp.resolve("bk0"));
        assertEquals(cfg, DamnInstaller.readConfig(damn), "config must survive a reinstall");

        DamnMenus m = DamnMenus.load(damn);
        for (MenuNode n : m.root().descendantsAndSelf()) {
            m.ensureLoaded(n);
            m.markItemsChanged(n);
        }
        m.save(tmp.resolve("bk"), DamnMenusTest.NO_ICON);
        Map<String, Integer> exp = expected(DamnMenus.load(damn));

        for (boolean noIo : new boolean[] {false, true}) {
            Result r = simulate(damn, tmp.resolve("game"), noIo);
            int total = r.itemsPerMenu().values().stream().mapToInt(Integer::intValue).sum();
            System.out.println("io disabled=" + noIo + ": menus " + r.menus().size() + ", items " + total);
            assertEquals(exp, r.itemsPerMenu());
        }
    }
}
