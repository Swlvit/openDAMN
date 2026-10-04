package org.opendamn.damn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.opendamn.dbpf.DbpfFile;
import org.opendamn.dbpf.DbpfWriter;
import org.opendamn.dbpf.LText;
import org.opendamn.dbpf.Tgi;

/**
 * Reads and writes DAMN menu files ({@code DAMN-Menu_*.dat}).
 *
 * <p>Each menu folder below {@code Plugins/DAMN/Root} contains one such DBPF file holding:
 * a LUA script that registers the items with {@code DAMenu}, one LTEXT per item with the
 * HTML info page, and one PNG per item for the menu icon (plus optional preview images).
 * The format is compatible with SSPTool and DAMN Manager.
 */
public final class MenuFileCodec {

    public static final int LTEXT_GROUP = Tgi.GROUP_LTEXT_HTML;
    static final String NL = "\r\n";

    /** Source of fresh random-looking IDs. Implementations must avoid collisions. */
    public interface IdSource {
        int next();
    }

    private MenuFileCodec() {}

    // ------------------------------------------------------------------ reading

    /** Result of reading a menu file. Problems are reported instead of aborting. */
    public record ReadResult(List<MenuItem> items, List<String> warnings) {}

    public static ReadResult read(Path file) throws IOException {
        DbpfFile db = DbpfFile.open(file);
        List<String> warnings = new ArrayList<>();
        List<MenuItem> items = new ArrayList<>();
        List<DbpfFile.Entry> luas = new ArrayList<>();
        for (DbpfFile.Entry e : db.entries()) if (e.tgi().type() == Tgi.TYPE_LUA) luas.add(e);
        if (luas.isEmpty()) return new ReadResult(items, warnings); // empty menu: valid

        try (var rd = db.reader()) {
            Function<Tgi, byte[]> lookup = tgi -> {
                DbpfFile.Entry e = db.find(tgi);
                if (e == null) return null;
                try {
                    return rd.read(e);
                } catch (IOException ex) {
                    warnings.add("Cannot read " + tgi + ": " + ex.getMessage());
                    return null;
                }
            };
            for (DbpfFile.Entry lua : luas) {
                String script = new String(rd.read(lua), StandardCharsets.ISO_8859_1);
                items.addAll(parseLua(script, lookup, warnings));
            }
        }
        return new ReadResult(items, warnings);
    }

    private static final Pattern ITEM_START = Pattern.compile("^\\s*local\\s+(item\\d+)\\s*=\\s*DAMenu\\.newItem\\(\\)");
    private static final Pattern FIELD = Pattern.compile("^\\s*(item\\d+)\\.([a-zA-Z]+)(\\[(\\d)\\])?\\s*=\\s*(.*)$");
    private static final Pattern ADD = Pattern.compile("^\\s*DAMenu\\.addItem\\(\\s*menu\\s*,\\s*(item\\d+)\\s*\\)");
    private static final Pattern HTML_REF = Pattern.compile("sc4://html/([0-9a-f]{1,8})/([0-9a-f]{1,8})", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEXT_REF = Pattern.compile("text@([0-9a-f]{1,8})", Pattern.CASE_INSENSITIVE);

    static List<MenuItem> parseLua(String script, Function<Tgi, byte[]> lookup, List<String> warnings) {
        Map<String, MenuItem> open = new HashMap<>();
        Map<String, Integer> descIds = new HashMap<>();
        Map<String, Tgi> iconRefs = new HashMap<>();
        List<MenuItem> result = new ArrayList<>();
        for (String line : script.split("\r\n|\n|\r")) {
            Matcher m = ITEM_START.matcher(line);
            if (m.find()) {
                open.put(m.group(1), new MenuItem());
                continue;
            }
            m = ADD.matcher(line);
            if (m.find()) {
                String var = m.group(1);
                MenuItem it = open.remove(var);
                if (it == null) continue;
                finishItem(it, descIds.remove(var), iconRefs.remove(var), lookup, warnings);
                if (it.linkName().isEmpty()) {
                    warnings.add("Skipped item without name (" + it.key() + ")");
                } else {
                    result.add(it);
                }
                continue;
            }
            m = FIELD.matcher(line);
            if (!m.find()) continue;
            MenuItem it = open.get(m.group(1));
            if (it == null) continue;
            String field = m.group(2).toLowerCase(Locale.ROOT);
            String idx = m.group(4);
            String raw = m.group(5).trim();
            switch (field) {
                case "linkname" -> it.setLinkName(LuaText.parseStringLiteral(raw));
                case "info" -> {
                    if ("1".equals(idx)) it.setInfoText1(LuaText.parseStringLiteral(raw));
                    else if ("2".equals(idx)) it.setInfoText2(LuaText.parseStringLiteral(raw));
                }
                case "gid" -> it.setExemplar(parseHexLoose(LuaText.parseStringLiteral(raw)), it.exemplarInstance());
                case "iid" -> it.setExemplar(it.exemplarGroup(), parseHexLoose(LuaText.parseStringLiteral(raw)));
                case "hidden" -> it.setHidden(raw.toLowerCase(Locale.ROOT).startsWith("true"));
                case "type" -> it.setKind(MenuItem.Kind.fromLua(LuaText.parseStringLiteral(raw)));
                case "network" -> {
                    if (raw.toLowerCase(Locale.ROOT).startsWith("true")) it.setKind(MenuItem.Kind.NETWORK);
                }
                case "icon" -> {
                    Matcher h = HTML_REF.matcher(raw);
                    if (h.find()) {
                        iconRefs.put(m.group(1), new Tgi(Tgi.TYPE_PNG, parseHexLoose(h.group(1)), parseHexLoose(h.group(2))));
                    }
                }
                case "description" -> {
                    Matcher t = TEXT_REF.matcher(raw);
                    if (t.find()) descIds.put(m.group(1), parseHexLoose(t.group(1)));
                }
                default -> { /* ignore unknown fields */ }
            }
        }
        return result;
    }

    private static void finishItem(MenuItem it, Integer descId, Tgi iconRef, Function<Tgi, byte[]> lookup,
                                   List<String> warnings) {
        if (iconRef != null) {
            byte[] png = lookup.apply(iconRef);
            if (png != null) it.setIcon(png);
            else warnings.add("Icon " + iconRef + " missing for " + it.linkName());
        }
        if (descId == null) return;
        byte[] lt = lookup.apply(new Tgi(Tgi.TYPE_LTEXT, LTEXT_GROUP, descId));
        if (lt == null) {
            warnings.add("Info page text missing for " + it.linkName());
            return;
        }
        String html = LText.decode(lt);
        parseInfoPage(html, it);
        Matcher img = HTML_REF.matcher(tableSection(html));
        if (img.find()) {
            Tgi ref = new Tgi(Tgi.TYPE_PNG, parseHexLoose(img.group(1)), parseHexLoose(img.group(2)));
            if (ref.equals(iconRef)) {
                // Preview == icon means "no separate preview".
                it.setPreview(null);
                it.setPreviewRef(null);
            } else {
                byte[] embedded = lookup.apply(ref);
                if (embedded != null) it.setPreview(embedded);
                else it.setPreviewRef(ref);
            }
        }
    }

    private static String tableSection(String html) {
        int t = html.toLowerCase(Locale.ROOT).indexOf("</table>");
        return t < 0 ? html : html.substring(0, t);
    }

    private static final Pattern TD = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Extracts description (second table cell) and info (between the two &lt;hr&gt;). */
    static void parseInfoPage(String html, MenuItem it) {
        String table = tableSection(html);
        Matcher td = TD.matcher(table);
        List<String> cells = new ArrayList<>();
        while (td.find()) cells.add(td.group(1));
        if (cells.size() >= 2) it.setDescription(htmlToText(cells.get(1)));
        String lower = html.toLowerCase(Locale.ROOT);
        int h1 = lower.indexOf("<hr>");
        int h2 = h1 < 0 ? -1 : lower.indexOf("<hr>", h1 + 4);
        if (h1 >= 0 && h2 > h1) it.setInfo(htmlToText(html.substring(h1 + 4, h2)));
    }

    static String htmlToText(String s) {
        String t = s.replace("\r", "").replace("\n", "").trim();
        t = t.replaceAll("(?i)<br\\s*/?>", "\n");
        return t;
    }

    static String textToHtml(String s) {
        return s.replace("\r", "").replace("\n", "<br>");
    }

    private static int parseHexLoose(String s) {
        s = s.trim();
        if (s.isEmpty()) return 0;
        try {
            return Tgi.parseHex(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ writing

    /** Default 44x44 placeholder icon when an item has none. */
    public interface IconFallback {
        byte[] iconFor(MenuItem item);
    }

    /**
     * Writes a menu file. {@code fileName} must be globally unique inside the DAMN Root tree
     * because the in-game script looks menus up by file name only.
     */
    public static void write(Path file, List<MenuItem> items, IdSource ids, IconFallback fallback) throws IOException {
        build(file.getFileName().toString(), items, ids, fallback).write(file);
    }

    static DbpfWriter build(String fileName, List<MenuItem> items, IdSource ids, IconFallback fallback) {
        DbpfWriter w = new DbpfWriter();
        if (items.isEmpty()) return w; // folder placeholder, exactly like the original tools

        int iconGroup = ids.next();
        int imageGroup = ids.next();
        int luaInstance = ids.next();

        StringBuilder lua = new StringBuilder();
        lua.append("--#-package:").append(Tgi.hex(luaInstance)).append("# -- package signature").append(NL);
        lua.append("---------- autogenerated file by OpenDAMN -----").append(NL).append(NL);
        lua.append("if (DAMenu ~= nil) then").append(NL).append(NL);
        lua.append(" ---- menu ---- ").append(NL).append(NL);
        lua.append("local menu = DAMenu.items[").append(LuaText.quote(fileName)).append("] ").append(NL).append(NL);
        lua.append(" ---- items ").append(NL).append(NL);

        int nr = 0;
        for (MenuItem it : items) {
            nr++;
            int iid = ids.next();
            byte[] icon = it.icon() != null ? it.icon() : fallback.iconFor(it);
            Tgi iconTgi = new Tgi(Tgi.TYPE_PNG, iconGroup, iid);
            w.add(iconTgi, icon);

            Tgi previewTgi;
            if (it.preview() != null) {
                previewTgi = new Tgi(Tgi.TYPE_PNG, imageGroup, iid);
                w.add(previewTgi, it.preview());
            } else if (it.previewRef() != null) {
                previewTgi = it.previewRef();
            } else {
                previewTgi = iconTgi;
            }

            w.add(new Tgi(Tgi.TYPE_LTEXT, LTEXT_GROUP, iid), LText.encode(infoPage(it, previewTgi)));

            String v = "item" + nr;
            lua.append("local ").append(v).append(" = DAMenu.newItem()").append(NL);
            lua.append(v).append(".linkname = ").append(LuaText.quote(it.linkName())).append(NL);
            lua.append(v).append(".description = [[text@").append(Tgi.hex(iid)).append("]]").append(NL);
            lua.append(v).append(".gid = \"").append(Tgi.hex(it.exemplarGroup())).append('"').append(NL);
            lua.append(v).append(".iid = \"").append(Tgi.hex(it.exemplarInstance())).append('"').append(NL);
            lua.append(v).append(".info[1] = ").append(LuaText.quote(it.infoText1())).append(NL);
            lua.append(v).append(".info[2] = ").append(LuaText.quote(it.infoText2())).append(NL);
            lua.append(v).append(".icon = \"sc4://HTML/").append(Tgi.hex(iconGroup)).append('/')
                    .append(Tgi.hex(iid)).append('"').append(NL);
            lua.append(v).append(".hidden = ").append(it.hidden() && it.kind() != MenuItem.Kind.NETWORK).append(NL);
            lua.append(v).append(".type = \"").append(it.kind().luaName).append('"').append(NL);
            lua.append("DAMenu.addItem(menu,").append(v).append(')').append(NL).append(NL);
        }
        lua.append("end -- if (DAMenu ~= nil)").append(NL);
        w.add(new Tgi(Tgi.TYPE_LUA, Tgi.GROUP_LUA, luaInstance), lua.toString().getBytes(StandardCharsets.ISO_8859_1));
        return w;
    }

    /** The HTML info page shown when the player clicks the item's info icon. */
    static String infoPage(MenuItem it, Tgi preview) {
        return "<table>" + NL
                + "<tr>" + NL
                + "\t<td width=\"70\"> <img src=\"sc4://HTML/" + Tgi.hex(preview.group()) + "/" + Tgi.hex(preview.instance())
                + "\" align=\"top\" width=\"60\" height=\"60\"> </td>" + NL
                + "\t<td>" + textToHtml(it.description()) + "</td>" + NL
                + "</tr>" + NL
                + "</table>" + NL
                + "<hr>" + NL
                + textToHtml(it.info()) + NL
                + "<hr>" + NL
                + "<a href=\"#link_id#game.retire_advice();DAMenu.up()\"><img src=\"sc4://HTML/dae1e700/dae1e720\"></a>" + NL
                + "<a href=\"#link_id#game.retire_advice();DAMenu.root()\"><img src=\"sc4://HTML/dae1e700/dae1e721\"></a>" + NL
                + "<a href=\"#link_id#game.retire_advice();DAMenu.plop()\"><img src=\"sc4://HTML/dae1e700/dae1e723\"></a>" + NL
                + "<a href=\"#link_id#keyboardInput.trigger(DAMenu.searchAccept,DAMenu.searchTitle,DAMenu.searchBody)\">"
                + "<img src=\"sc4://HTML/dae1e700/dae1e722\"></a>" + NL;
    }
}
