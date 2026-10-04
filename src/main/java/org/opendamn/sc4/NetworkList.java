package org.opendamn.sc4;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.opendamn.dbpf.Exemplar;
import org.opendamn.dbpf.Property;
import org.opendamn.dbpf.Tgi;

/** Network tools (roads, rails, NAM pieces ...) that can be plopped from DAMN by button ID. */
public final class NetworkList {

    public record Entry(int id, String name) {}

    private NetworkList() {}

    /**
     * Parses RUL files and simple lists. Lines like {@code RotationRing = 1020, 11020 ; name}
     * or {@code AddTypes = ...; name} define one entry; the comment is the name. In RUL files
     * short IDs are NAM pieces and get the 0x6A47 prefix.
     */
    public static List<Entry> parse(String text, boolean rulFile) {
        Map<Integer, Entry> out = new LinkedHashMap<>();
        for (String raw : text.split("\r\n|\n|\r")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#") || line.startsWith("!")) continue;
            int eq = line.indexOf('=');
            int sc = line.indexOf(';');
            if (eq < 0 || sc < eq) continue;
            String key = line.substring(0, eq).strip().toLowerCase(Locale.ROOT);
            if (!key.equals("rotationring") && !key.equals("addtypes")) continue;
            String name = line.substring(sc + 1).strip();
            if (name.isEmpty()) continue;
            String first = line.substring(eq + 1, sc).split(",")[0].strip();
            if (first.startsWith("0x") || first.startsWith("0X")) first = first.substring(2);
            if (first.isEmpty() || !first.matches("[0-9a-fA-F]{1,8}")) continue;
            long v = Long.parseLong(first, 16);
            if (rulFile && first.length() <= 4) v = 0x6A470000L | v;
            int id = (int) v;
            out.putIfAbsent(id, new Entry(id, name));
        }
        return new ArrayList<>(out.values());
    }

    public static List<Entry> parseFile(Path p) throws IOException {
        String t = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
        return parse(t, p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".rul"));
    }

    /** Network RUL files inside installed plugins (e.g. the NAM), as one combined list. */
    public static List<Entry> fromInstalledRuls(PluginIndex index) throws IOException {
        Map<Integer, Entry> out = new LinkedHashMap<>();
        for (IndexedFile f : index.files()) {
            for (IndexedFile.Res r : f.resources()) {
                if (r.tgi().type() != Tgi.TYPE_RUL) continue;
                byte[] d = PluginIndex.read(new PluginIndex.Located(f, r));
                for (Entry e : parse(new String(d, StandardCharsets.ISO_8859_1), true)) out.putIfAbsent(e.id(), e);
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Icon of a network tool: the PNG of an exemplar whose Item Button ID matches. */
    public static Tgi iconFor(PluginIndex index, int buttonId) {
        Tgi direct = new Tgi(Tgi.TYPE_PNG, Tgi.GROUP_PNG_MENU_ICON, buttonId);
        if (index.effective(direct) != null) return direct;
        for (IndexedFile f : index.files()) {
            for (IndexedFile.Res r : f.resources()) {
                if (r.tgi().type() != Tgi.TYPE_EXEMPLAR || !r.has(IndexedFile.Res.F_HAS_ITEM_BUTTON_ID)) continue;
                if (r.exType() == Exemplar.TYPE_BUILDING || r.exType() == Exemplar.TYPE_FLORA
                        || r.exType() == Exemplar.TYPE_LOT_CONFIG) continue;
                try {
                    Exemplar e = PluginIndex.readExemplar(new PluginIndex.Located(f, r));
                    Property b = e.get(Exemplar.P_ITEM_BUTTON_ID);
                    if (b == null || (int) b.getLong(0) != buttonId) continue;
                    long icon = e.getLong(Exemplar.P_ITEM_ICON, -1);
                    if (icon >= 0) {
                        Tgi t = new Tgi(Tgi.TYPE_PNG, Tgi.GROUP_PNG_MENU_ICON, (int) icon);
                        if (index.effective(t) != null) return t;
                    }
                } catch (IOException | RuntimeException ex) {
                    // skip unreadable exemplars
                }
            }
        }
        return null;
    }
}
