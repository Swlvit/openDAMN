package org.opendamn.damn;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.opendamn.dbpf.DbpfFile;
import org.opendamn.dbpf.DbpfWriter;
import org.opendamn.dbpf.LText;
import org.opendamn.dbpf.Tgi;

/**
 * Installs and configures the DAMN plugin engine ({@code Plugins/DAMN/Support}).
 *
 * <p>The engine files come from the GPL SSPTool DAMN plugin. Window geometry is applied to
 * {@code damnDesign.dat}; the control script in {@code damnControl.dat} is regenerated with a
 * robust index lookup and an embedded copy of the menu index (see {@link #controlScript}).
 */
public final class DamnInstaller {

    public static final String CONTROL = "damncontrol.dat";
    public static final String DESIGN = "damndesign.dat";
    public static final String NEWS_FILTER = "news_filter.dat";
    static final Charset LUA_CHARSET = Charset.forName("windows-1252");

    static final Tgi CONTROL_LUA = new Tgi(Tgi.TYPE_LUA, Tgi.GROUP_LUA, 0x0DAE102C);
    static final Tgi UI_URGENT = new Tgi(Tgi.TYPE_UI, 0x96A006B0, 0x4A5A89D5);
    static final Tgi UI_NEWS = new Tgi(Tgi.TYPE_UI, 0x96A006B0, 0x2A2AED99);
    static final Tgi LT_TITLE = new Tgi(Tgi.TYPE_LTEXT, Tgi.GROUP_LTEXT_HTML, 0xDAE1E773);
    static final Tgi LT_GRID = new Tgi(Tgi.TYPE_LTEXT, Tgi.GROUP_LTEXT_HTML, 0xDAE1E774);
    static final Tgi LT_OPEN = new Tgi(Tgi.TYPE_LTEXT, Tgi.GROUP_LTEXT_HTML, 0xDAE1E775);
    static final Tgi LT_MORE = new Tgi(Tgi.TYPE_LTEXT, Tgi.GROUP_LTEXT_HTML, 0xDAE1E776);

    /** In-game window configuration. */
    public record Config(boolean urgentAdvice, int columns, int columnWidth, int newsHeight, int maxItems,
                         boolean reduceNews) {
        public Config {
            columns = clamp(columns, 1, 6);
            columnWidth = clamp(columnWidth, 200, 600);
            newsHeight = clamp(newsHeight, 300, 1400);
            maxItems = clamp(maxItems, 1, 500);
        }

        public static Config defaults() {
            return new Config(true, 2, 400, 450, 50, true);
        }

        /** Window width in pixels as written to the game UI. */
        public int windowWidth() {
            return columns * columnWidth + (urgentAdvice ? 114 : 80);
        }

        public int windowHeight() {
            return urgentAdvice ? 450 : newsHeight;
        }
    }

    /** Text shown inside the game. */
    public record Texts(String title, String open, String more, String home, String search, String searchHelp,
                        String found) {
        public static Texts english() {
            return new Texts("Menu Browser - ", "Open DAMN menu", "Additional submenus available >", "Home", "Search",
                    "Enter a search term (not case sensitive), then press [OK] to view the results. "
                            + "A maximum of ${C5} results can be displayed.", "items found");
        }
    }

    private DamnInstaller() {}

    public static boolean isInstalled(Path damnDir) {
        return damnDir != null && Files.isRegularFile(damnDir.resolve("Support").resolve(CONTROL))
                && Files.isRegularFile(damnDir.resolve("Support").resolve(DESIGN));
    }

    /** Case-insensitive lookup of a file in Support (old installs use mixed case). */
    static Path supportFile(Path damnDir, String name) throws IOException {
        Path sup = damnDir.resolve("Support");
        if (Files.isDirectory(sup)) {
            try (Stream<Path> s = Files.list(sup)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (p.getFileName().toString().equalsIgnoreCase(name)) return p;
                }
            }
        }
        return sup.resolve(name);
    }

    /**
     * Installs or reconfigures the engine. Existing files are backed up into {@code backup}.
     *
     * @param defaultMenus folder names to create below Root on a fresh install (may be empty)
     */
    public static void install(Path damnDir, Config cfg, Texts texts, List<String> defaultMenus, Path backup)
            throws IOException {
        Path support = damnDir.resolve("Support");
        Files.createDirectories(support);
        Files.createDirectories(damnDir.resolve("Root"));

        Path control = supportFile(damnDir, CONTROL);
        Path design = supportFile(damnDir, DESIGN);
        backup(control, backup);
        backup(design, backup);

        byte[] baseControl = Files.exists(control) ? Files.readAllBytes(control) : resource("damnControl.dat");
        byte[] baseDesign = Files.exists(design) ? Files.readAllBytes(design) : resource("damnDesign.dat");

        writeDesign(baseDesign, design, cfg, texts);
        // The control script embeds the index; it is completed by writeIndex in DamnMenus.
        writeControl(baseControl, control, cfg, texts, List.of(), null);

        Path news = supportFile(damnDir, NEWS_FILTER);
        if (cfg.reduceNews()) {
            if (!Files.exists(news)) Files.write(news, resource("News_Filter.dat"));
        } else if (Files.exists(news)) {
            backup(news, backup);
            Files.delete(news);
        }
        for (String m : defaultMenus) {
            Path p = damnDir.resolve("Root");
            for (String seg : m.split("/")) p = p.resolve(seg);
            Files.createDirectories(p);
        }
    }

    /** Reads the current configuration back from installed engine files (best effort). */
    public static Config readConfig(Path damnDir) {
        Config d = Config.defaults();
        try {
            Path design = supportFile(damnDir, DESIGN);
            Path control = supportFile(damnDir, CONTROL);
            if (!Files.exists(design) || !Files.exists(control)) return d;
            DbpfFile ds = DbpfFile.open(design);
            String grid = LText.decode(ds.read(LT_GRID));
            String news = new String(ds.read(UI_NEWS), LUA_CHARSET);
            String lua = new String(DbpfFile.open(control).read(CONTROL_LUA), LUA_CHARSET);

            int cells = count(grid, "DAMenu.plop(");
            String firstRow = grid.contains("</tr>") ? grid.substring(0, grid.indexOf("</tr>")) : grid;
            int cols = Math.max(1, count(firstRow, "DAMenu.plop("));
            Matcher w = Pattern.compile("<td width=\"(\\d+)\">#DAMenu\\.getLinkText").matcher(grid);
            int cw = w.find() ? Integer.parseInt(w.group(1)) + 94 : d.columnWidth();
            Matcher h = Pattern.compile("id=0xaa231508[^>]*?area=\\(\\d+,\\d+,\\d+,(\\d+)\\)").matcher(news);
            int height = h.find() ? Integer.parseInt(h.group(1)) - 7 : d.newsHeight();
            Matcher p = Pattern.compile("create_advice_cityplanning\\('0DAE102C'\\).*?a\\.priority\\s*=\\s*(\\d+)",
                    Pattern.DOTALL).matcher(lua);
            boolean urgent = !p.find() || !p.group(1).equals("0");
            boolean reduce = Files.exists(supportFile(damnDir, NEWS_FILTER));
            return new Config(urgent, cols, cw, height, cells > 0 ? cells : d.maxItems(), reduce);
        } catch (IOException | RuntimeException e) {
            return d;
        }
    }

    // ------------------------------------------------------------------ design

    static void writeDesign(byte[] base, Path target, Config cfg, Texts t) throws IOException {
        Map<Tgi, byte[]> entries = entries(base, target);
        Map<String, String> templates = templates("Template_damnDesign.txt");
        int b = cfg.columns() * cfg.columnWidth() + 114;
        String urgent = fill(templates.get("UI " + UI_URGENT), "X", 1,
                b, b - 102, b - 90, b - 9, b - 91, b - 6, b - 90, b - 68);
        int n = cfg.columns() * cfg.columnWidth() + 80;
        int hh = cfg.newsHeight();
        String news = fill(fill(templates.get("UI " + UI_NEWS), "W", 0,
                n, n + 204, n - 32, n - 48, n - 29, n - 7, n - 14, n - 145, n - 45), "H", 0, hh, hh + 7, hh - 14);
        entries.put(UI_URGENT, urgent.getBytes(LUA_CHARSET));
        entries.put(UI_NEWS, news.getBytes(LUA_CHARSET));
        entries.put(LT_TITLE, LText.encode(templates.get("LTEXT " + LT_TITLE).replace("${T1}", t.title())));
        entries.put(LT_GRID, LText.encode(grid(templates.get("LTEXT " + LT_GRID), cfg)));
        entries.put(LT_OPEN, LText.encode(templates.get("LTEXT " + LT_OPEN).replace("${T2}", t.open())));
        entries.put(LT_MORE, LText.encode(templates.get("LTEXT " + LT_MORE).replace("${T3}", t.more())));
        write(entries, target);
    }

    /** The item grid: one cell (icon, text, info icon) per menu entry, {@code columns} per row. */
    static String grid(String cellTemplate, Config cfg) {
        int width = cfg.columnWidth() - 94;
        int cols = cfg.columns();
        int total = (cfg.maxItems() + cols - 1) / cols * cols;
        StringBuilder sb = new StringBuilder("<table border=\"0\">\r\n");
        for (int k = 0; k < total; k++) {
            if (k % cols == 0) {
                if (k != 0) sb.append("</tr>\r\n");
                sb.append("<tr>\r\n");
            }
            if (k < cfg.maxItems()) {
                sb.append(cellTemplate.replace("${NR}", Integer.toString(k)).replace("${WIDTH}", Integer.toString(width)));
            } else {
                sb.append("<td></td>\r\n<td></td>\r\n<td></td>\r\n");
            }
        }
        sb.append("</tr>\r\n</table>");
        return sb.toString();
    }

    // ------------------------------------------------------------------ control

    /**
     * Rewrites the control script with the current menu index embedded. Only the main control
     * script is replaced; other entries (e.g. the keyboard input script) are kept.
     *
     * @param gameDirWindows the game folder as Windows path (e.g. {@code C:\...}) or null
     */
    public static void updateControl(Path damnDir, List<String> indexLines, String gameDirWindows, Path backup)
            throws IOException {
        Path control = supportFile(damnDir, CONTROL);
        if (!Files.exists(control)) return; // engine not installed: nothing to update
        Config cfg = readConfig(damnDir);
        Texts texts = readTexts(control);
        if (backup != null) backup(control, backup);
        writeControl(Files.readAllBytes(control), control, cfg, texts, indexLines, gameDirWindows);
    }

    static void writeControl(byte[] base, Path target, Config cfg, Texts t, List<String> index, String gameDirWin)
            throws IOException {
        Map<Tgi, byte[]> entries = entries(base, target);
        entries.put(CONTROL_LUA, controlScript(cfg, t, index, gameDirWin).getBytes(LUA_CHARSET));
        write(entries, target);
    }

    /** The control script: the DAMN engine template with a robust menu index loader. */
    static String controlScript(Config cfg, Texts t, List<String> index, String gameDirWin) {
        String tpl = templates("Template_damnControl.txt").get("LUA " + CONTROL_LUA);
        String max = Integer.toString(cfg.maxItems());
        String s = tpl.replace("${C0}", luaInner(t.home()))
                .replace("${C1}", cfg.urgentAdvice() ? "100" : "0")
                .replace("${C2}", luaInner(t.search()))
                .replace("${C3}", luaInner(t.searchHelp()))
                .replace("${C4}", luaInner(t.found()))
                .replace("${C5}", max);
        String original = " local luainput = io.open([[Plugins/DAMN/Support/DAMN-index.txt]])\r\n"
                + " local line = 1 \r\n"
                + " while (line) do\r\n"
                + "  line = luainput:read(\"*l\")\r\n"
                + "  if (line) then\r\n"
                + "   DAMenu.interpret(line)\r\n"
                + "  end\r\n"
                + " end\r\n";
        if (!s.contains(original)) throw new IllegalStateException("Unexpected control template");
        return s.replace(original, indexLoader(index, gameDirWin));
    }

    static String indexLoader(List<String> index, String gameDirWin) {
        StringBuilder sb = new StringBuilder();
        sb.append(" -- OpenDAMN: read the menu index from the first location that works. If the file\r\n");
        sb.append(" -- cannot be opened (game started from another folder, Lua io disabled, ...)\r\n");
        sb.append(" -- use the copy embedded below, written together with the index file.\r\n");
        sb.append(" local luainput = nil\r\n");
        sb.append(" if (io ~= nil) and (io.open ~= nil) then\r\n");
        sb.append("  local places = {}\r\n");
        sb.append("  places[1] = [[Plugins\\DAMN\\Support\\DAMN-index.txt]]\r\n");
        sb.append("  places[2] = [[..\\Plugins\\DAMN\\Support\\DAMN-index.txt]]\r\n");
        if (gameDirWin != null && !gameDirWin.contains("]]")) {
            sb.append("  places[3] = [[").append(gameDirWin).append("\\Plugins\\DAMN\\Support\\DAMN-index.txt]]\r\n");
        }
        sb.append("  local p = 1\r\n");
        sb.append("  while (luainput == nil) and (places[p] ~= nil) do\r\n");
        sb.append("   local ok, f = pcall(io.open, places[p])\r\n");
        sb.append("   if (ok) then luainput = f end\r\n");
        sb.append("   p = p + 1\r\n");
        sb.append("  end\r\n");
        sb.append(" end\r\n");
        sb.append(" if (luainput) then\r\n");
        sb.append("  local line = 1\r\n");
        sb.append("  while (line) do\r\n");
        sb.append("   line = luainput:read(\"*l\")\r\n");
        sb.append("   if (line) then\r\n");
        sb.append("    DAMenu.interpret(line)\r\n");
        sb.append("   end\r\n");
        sb.append("  end\r\n");
        sb.append("  luainput:close()\r\n");
        sb.append(" else\r\n");
        sb.append("  local idx = {}\r\n");
        int k = 1;
        for (String l : index) {
            sb.append("  idx[").append(k++).append("] = ").append(LuaText.quote(l)).append("\r\n");
        }
        sb.append("  local i = 1\r\n");
        sb.append("  while (idx[i] ~= nil) do\r\n");
        sb.append("   DAMenu.interpret(idx[i])\r\n");
        sb.append("   i = i + 1\r\n");
        sb.append("  end\r\n");
        sb.append(" end\r\n");
        sb.append(" luainput = nil\r\n");
        return sb.toString();
    }

    /** Recovers the in-game texts from an installed control script so updates keep them. */
    static Texts readTexts(Path control) {
        Texts d = Texts.english();
        try {
            String lua = new String(DbpfFile.open(control).read(CONTROL_LUA), LUA_CHARSET);
            String home = group(lua, "DAMenu\\.linkname = \"((?:[^\"\\\\]|\\\\.)*)\"", d.home());
            String search = group(lua, "DAMenu\\.searchTitle = \"((?:[^\"\\\\]|\\\\.)*)\"", d.search());
            String help = group(lua, "DAMenu\\.searchBody = \"((?:[^\"\\\\]|\\\\.)*)\"", d.searchHelp());
            String found = group(lua, "searchMenu\\.size \\.\\. \" ((?:[^\"\\\\]|\\\\.)*)\"", d.found());
            help = help.replaceAll("maximum of \\d+ results", "maximum of \\${C5} results");
            return new Texts(d.title(), d.open(), d.more(), unLua(home), unLua(search), unLua(help), unLua(found));
        } catch (IOException | RuntimeException e) {
            return d;
        }
    }

    private static String group(String s, String re, String def) {
        Matcher m = Pattern.compile(re).matcher(s);
        return m.find() ? m.group(1) : def;
    }

    private static String unLua(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /** Escapes text for use inside an existing pair of double quotes in the template. */
    private static String luaInner(String s) {
        String q = LuaText.quote(s);
        return q.substring(1, q.length() - 1);
    }

    // ------------------------------------------------------------------ helpers

    /** All entries of a DBPF exactly as stored (compressed entries stay compressed), keeping order. */
    private static Map<Tgi, byte[]> entries(byte[] dbpf, Path where) throws IOException {
        Path tmp = Files.createTempFile("opendamn", ".dat");
        try {
            Files.write(tmp, dbpf);
            DbpfFile f = DbpfFile.open(tmp);
            Map<Tgi, byte[]> m = new LinkedHashMap<>();
            try (var r = f.reader()) {
                for (DbpfFile.Entry e : f.entries()) {
                    if (e.tgi().type() == Tgi.TYPE_DIR) continue;
                    m.put(e.tgi(), r.readRaw(e));
                }
            }
            return m;
        } catch (RuntimeException e) {
            throw new IOException("Cannot read " + where + ": " + e.getMessage(), e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Writes entries; replaced entries are stored uncompressed, untouched ones byte for byte. */
    private static void write(Map<Tgi, byte[]> entries, Path target) throws IOException {
        DbpfWriter w = new DbpfWriter();
        for (var e : entries.entrySet()) w.add(e.getKey(), e.getValue(), false);
        w.write(target);
    }

    /** Parses a template file into "KIND tgi" -> body (CRLF line endings). */
    static Map<String, String> templates(String name) {
        String text;
        try {
            text = new String(resource(name), LUA_CHARSET);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Map<String, String> m = new LinkedHashMap<>();
        String[] lines = text.split("\r\n|\n");
        for (int k = 0; k < lines.length; k++) {
            if (!lines[k].equals("[TEMPLATE]")) continue;
            String kind = lines[++k].trim();
            Tgi tgi = new Tgi(Tgi.parseHex(lines[++k]), Tgi.parseHex(lines[++k]), Tgi.parseHex(lines[++k]));
            StringBuilder body = new StringBuilder();
            while (++k < lines.length && !lines[k].equals("[/TEMPLATE]")) {
                if (lines[k].startsWith("#")) continue;
                body.append(lines[k]).append("\r\n");
            }
            m.put(kind + " " + tgi, body.toString());
        }
        return m;
    }

    private static String fill(String s, String prefix, int first, int... values) {
        for (int k = 0; k < values.length; k++) {
            s = s.replace("${" + prefix + (k + first) + "}", Integer.toString(values[k]));
        }
        return s;
    }

    static byte[] resource(String name) throws IOException {
        try (InputStream in = DamnInstaller.class.getResourceAsStream("/org/opendamn/engine/" + name)) {
            if (in == null) throw new IOException("Missing bundled resource " + name);
            return in.readAllBytes();
        }
    }

    private static void backup(Path p, Path backup) throws IOException {
        if (backup == null || !Files.exists(p)) return;
        Files.createDirectories(backup);
        Files.copy(p, backup.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    }

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) n++;
        return n;
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** The standard menu structure offered on a fresh install. */
    public static List<String> defaultMenuStructure() {
        List<String> l = new ArrayList<>();
        String[] menus = {
            "Civic/Education", "Civic/Energy management/Power lines", "Civic/Energy management/Power plants",
            "Civic/Fire and rescue", "Civic/Healthcare", "Civic/Law enforcement", "Civic/Other",
            "Civic/Waste management", "Civic/Water management", "Commercial/Shops malls etc",
            "Commercial/Skyscrapers", "Culture/Museum", "Culture/Religion", "Eye candy/Canals",
            "Eye candy/Fauna and flora", "Eye candy/Other", "Eye candy/Parks", "Eye candy/Rural",
            "Eye candy/Technical infrastructure/Other", "Eye candy/Technical infrastructure/Road signs",
            "Eye candy/Technical infrastructure/Sound walls", "Eye candy/Technical infrastructure/Walls and seawalls",
            "Eye candy/Water beaches terrain", "Industry/Agriculture", "Landmarks/Skyscrapers", "Military", "Other",
            "Residential/Skyscrapers", "Rewards", "Sport and recreation", "Transport/Airports",
            "Transport/Ports docks and shipyards", "Transport/Rail/Stations", "Transport/Roads and highways/Bus stops",
            "Transport/Roads and highways/Parking and garages"};
        for (String m : menus) l.add(m);
        return l;
    }
}
