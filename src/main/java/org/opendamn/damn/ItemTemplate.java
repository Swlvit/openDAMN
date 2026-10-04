package org.opendamn.damn;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.opendamn.dbpf.Exemplar;
import org.opendamn.dbpf.Property;
import org.opendamn.dbpf.Tgi;
import org.opendamn.sc4.LotCatalog;
import org.opendamn.sc4.LotInfo;

/**
 * Fills new menu items from lot data using user-editable format strings such as
 * {@code "Size: ${LOT_SIZE}"}. Compatible with the pattern names of DAMN Manager.
 */
public final class ItemTemplate {

    /** Pattern names with a short description, in display order. */
    public static final Map<String, String> PATTERNS = new LinkedHashMap<>();

    static {
        PATTERNS.put("NAME", "Best available in-game name");
        PATTERNS.put("DESCRIPTION", "Best available in-game description");
        PATTERNS.put("EX_EX_NAME_KEY", "Exemplar Name (0x00000020)");
        PATTERNS.put("EX_US_VIS_NAME", "User Visible Name Key text (0x8a416a99)");
        PATTERNS.put("EX_ITEM_DESCR_KEY", "Item Description Key text (0xca416ab5)");
        PATTERNS.put("EX_ITEM_DESCR", "Description Key text (0xca416b2a)");
        PATTERNS.put("EX_ITEM_DESCRIPTION", "Item Description string (0x8a2602a9)");
        PATTERNS.put("EX_ITEM_NAME", "Item Name (0x899afbad)");
        PATTERNS.put("EXLC_EX_NAME_KEY", "Lot configuration: Exemplar Name");
        PATTERNS.put("EXLC_US_VIS_NAME", "Lot configuration: User Visible Name Key text");
        PATTERNS.put("EXLC_ITEM_DESCR_KEY", "Lot configuration: Item Description Key text");
        PATTERNS.put("EXLC_ITEM_DESCR", "Lot configuration: Description Key text");
        PATTERNS.put("EXLC_ITEM_NAME", "Lot configuration: Item Name");
        PATTERNS.put("LOT_SIZE", "Lot size (width x depth)");
        PATTERNS.put("PLOP_COST", "Plop cost (0x49cac341)");
        PATTERNS.put("MONTHLY_COST", "Monthly cost (0xea54d286)");
        PATTERNS.put("BULLDOZE_COST", "Bulldoze cost (0x099afacd)");
        PATTERNS.put("OCCUPANCY_GROUP", "Occupant groups (0xaa1dd396)");
        PATTERNS.put("BUDGET_DEPARTMENT", "Budget department (0xea54d283)");
        PATTERNS.put("BUDGET_PURPOSE", "Budget purpose (0xea54d285)");
        PATTERNS.put("LOT_TGI", "Exemplar TGI (hex)");
        PATTERNS.put("FILE", "Plugin file name");
    }

    private static final Pattern REF = Pattern.compile("\\$\\{([A-Z_]+)}");

    public String name = "Standard";
    public String linkName = "${NAME}";
    public String infoText1 = "";
    public String infoText2 = "Size: ${LOT_SIZE}";
    public String description = "${DESCRIPTION}";
    public String info = "Plop cost: ${PLOP_COST} §\nMonthly cost: ${MONTHLY_COST} §\nBulldoze cost: ${BULLDOZE_COST} §";
    /** Patterns tried, in order, when a name/description pattern has no value. */
    public List<String> replacements = new ArrayList<>(List.of("EX_US_VIS_NAME", "EX_ITEM_NAME", "EXLC_US_VIS_NAME",
            "EXLC_ITEM_NAME", "EX_EX_NAME_KEY", "EXLC_EX_NAME_KEY"));
    public boolean useReplacements = true;

    public ItemTemplate copy(String newName) {
        ItemTemplate t = new ItemTemplate();
        t.name = newName;
        t.linkName = linkName;
        t.infoText1 = infoText1;
        t.infoText2 = infoText2;
        t.description = description;
        t.info = info;
        t.replacements = new ArrayList<>(replacements);
        t.useReplacements = useReplacements;
        return t;
    }

    /** Creates a menu item for a lot. */
    public MenuItem apply(LotInfo lot, LotCatalog catalog, boolean emptyUnfilled) {
        Map<String, String> values = values(lot, catalog);
        MenuItem m = new MenuItem();
        m.setKind(lot.kind());
        m.setExemplar(lot.exemplar().group(), lot.exemplar().instance());
        m.setSourceFile(lot.file());
        m.setLinkName(singleLine(fill(linkName, values, emptyUnfilled)));
        m.setInfoText1(singleLine(fill(infoText1, values, emptyUnfilled)));
        m.setInfoText2(singleLine(fill(infoText2, values, emptyUnfilled)));
        m.setDescription(fill(description, values, emptyUnfilled));
        m.setInfo(fill(info, values, emptyUnfilled));
        if (m.linkName().isBlank()) m.setLinkName(lot.displayName().isBlank() ? lot.key() : lot.displayName());
        return m;
    }

    String fill(String format, Map<String, String> values, boolean emptyUnfilled) {
        Matcher m = REF.matcher(format);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String v = values.get(key);
            if ((v == null || v.isBlank()) && useReplacements && isNameOrDescription(key)) {
                for (String alt : replacements) {
                    String av = values.get(alt);
                    if (av != null && !av.isBlank()) {
                        v = av;
                        break;
                    }
                }
            }
            if (v == null || v.isBlank()) v = emptyUnfilled || !PATTERNS.containsKey(key) ? "" : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString().strip();
    }

    private static boolean isNameOrDescription(String key) {
        return key.startsWith("EX_") || key.startsWith("EXLC_") || key.equals("NAME") || key.equals("DESCRIPTION");
    }

    /** All pattern values for a lot. */
    public static Map<String, String> values(LotInfo lot, LotCatalog catalog) {
        Map<String, String> v = new LinkedHashMap<>();
        Map<Integer, Property> p = lot.properties();
        Map<Integer, Property> lp = lot.lotProperties();
        Function<Long, String> money = ItemTemplate::money;
        v.put("NAME", lot.displayName());
        v.put("DESCRIPTION", cleanGameText(lot.description(), p));
        v.put("EX_EX_NAME_KEY", str(p, Exemplar.P_EXEMPLAR_NAME));
        v.put("EX_US_VIS_NAME", text(catalog, p, Exemplar.P_USER_VISIBLE_NAME_KEY));
        v.put("EX_ITEM_DESCR_KEY", cleanGameText(text(catalog, p, Exemplar.P_ITEM_DESCRIPTION_KEY), p));
        v.put("EX_ITEM_DESCR", cleanGameText(text(catalog, p, Exemplar.P_DESCRIPTION_KEY), p));
        v.put("EX_ITEM_DESCRIPTION", cleanGameText(str(p, Exemplar.P_ITEM_DESCRIPTION), p));
        v.put("EX_ITEM_NAME", str(p, Exemplar.P_ITEM_NAME));
        v.put("EXLC_EX_NAME_KEY", str(lp, Exemplar.P_EXEMPLAR_NAME));
        v.put("EXLC_US_VIS_NAME", text(catalog, lp, Exemplar.P_USER_VISIBLE_NAME_KEY));
        v.put("EXLC_ITEM_DESCR_KEY", cleanGameText(text(catalog, lp, Exemplar.P_ITEM_DESCRIPTION_KEY), lp));
        v.put("EXLC_ITEM_DESCR", cleanGameText(text(catalog, lp, Exemplar.P_DESCRIPTION_KEY), lp));
        v.put("EXLC_ITEM_NAME", str(lp, Exemplar.P_ITEM_NAME));
        v.put("LOT_SIZE", lot.sizeText());
        v.put("PLOP_COST", money.apply(lot.plopCost()));
        v.put("MONTHLY_COST", money.apply(lot.monthlyCost()));
        v.put("BULLDOZE_COST", money.apply(lot.bulldozeCost()));
        v.put("OCCUPANCY_GROUP", lot.occupantGroups());
        v.put("BUDGET_DEPARTMENT", lot.budgetDepartment());
        v.put("BUDGET_PURPOSE", lot.budgetPurpose());
        v.put("LOT_TGI", lot.exemplar().toString());
        v.put("FILE", lot.file() == null ? "" : lot.file().getFileName().toString());
        return v;
    }

    static String money(long v) {
        return v == LotInfo.MISSING ? "-99" : NumberFormat.getIntegerInstance(Locale.US).format(v);
    }

    private static String str(Map<Integer, Property> p, int id) {
        Property x = p.get(id);
        return x != null && x.isString() ? x.displayText().strip() : "";
    }

    private static String text(LotCatalog c, Map<Integer, Property> p, int id) {
        Property x = p.get(id);
        if (x == null) return "";
        if (x.isString()) return x.displayText().strip();
        String t = c == null ? null : c.textFromKey(x);
        return t == null ? "" : t.strip();
    }

    private static final Pattern GAME_TOKEN = Pattern.compile("#([md]):(?:0x)?([0-9a-fA-F]{1,8})#");

    /**
     * Makes game text safe for a DAMN info page: fills {@code #m:ID#} (money) and
     * {@code #d:ID#} (number) with the lot's property values and removes other {@code #...#}
     * expressions and stray {@code #}, which the game would try to evaluate as Lua.
     */
    public static String cleanGameText(String s, Map<Integer, Property> props) {
        if (s == null || s.isEmpty()) return "";
        Matcher m = GAME_TOKEN.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int id = (int) Long.parseLong(m.group(2), 16);
            Property p = props.get(id);
            String v;
            if (p == null || p.isString() || p.values().length == 0) {
                v = "?";
            } else if (m.group(1).equals("m")) {
                v = "§" + money(p.getLong(0));
            } else {
                v = NumberFormat.getIntegerInstance(Locale.US).format(p.getLong(0));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return stripHashes(sb.toString());
    }

    /** Removes {@code #expr#} sequences and lone {@code #} characters. */
    public static String stripHashes(String s) {
        String t = s.replaceAll("#[A-Za-z_][A-Za-z0-9_.:()]*#", "");
        return t.replace("#", "");
    }

    private static String singleLine(String s) {
        return s.replace("\r", " ").replace("\n", " ").strip();
    }

    /** Builds a network menu item (roads, rails, ...): plopped by its Item Button ID. */
    public static MenuItem networkItem(String name, int buttonId, byte[] icon) {
        MenuItem m = new MenuItem();
        m.setKind(MenuItem.Kind.NETWORK);
        m.setLinkName(name);
        m.setExemplar(0, buttonId);
        m.setIcon(icon);
        m.setInfoText2("Size: unknown");
        return m;
    }

    /** Exemplar TGI string helper for tests. */
    static String tgi(int g, int i) {
        return new Tgi(Tgi.TYPE_EXEMPLAR, g, i).toString();
    }
}
