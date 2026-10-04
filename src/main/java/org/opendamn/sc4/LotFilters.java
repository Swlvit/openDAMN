package org.opendamn.sc4;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.opendamn.dbpf.Property;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Lot filters built from the category tree of {@code new_properties.xml} (the SC4 PIM-X
 * property definitions). A lot matches a category when it satisfies the category's own
 * rules and those of all its parent categories.
 */
public final class LotFilters {

    /** One rule: property must exist / have a value ({@code needed}), or must not. */
    public record Rule(boolean needed, int property, Long value) {
        boolean test(Map<Integer, Property> props) {
            Property p = props.get(property);
            boolean present;
            if (p == null) present = false;
            else if (value == null) present = true;
            else {
                present = false;
                if (!p.isString()) for (long v : p.values()) if (v == value) present = true;
            }
            return needed == present;
        }
    }

    public static final class Category {
        public final String name;
        public final String help;
        public final List<Rule> rules = new ArrayList<>();
        public final List<Category> children = new ArrayList<>();
        public Category parent;

        Category(String name, String help) {
            this.name = name;
            this.help = help;
        }

        public boolean matches(LotInfo lot) {
            for (Category c = this; c != null; c = c.parent) {
                for (Rule r : c.rules) {
                    // Lot-related properties may live in the lot configuration.
                    if (!r.test(lot.properties()) && !r.test(lot.lotProperties())) return false;
                }
            }
            return true;
        }

        public String path() {
            return parent == null ? name : parent.path() + " / " + name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final Category root;
    private final Map<Integer, String> propertyNames;
    private final Map<Integer, Map<Long, String>> optionNames;

    private LotFilters(Category root, Map<Integer, String> names, Map<Integer, Map<Long, String>> options) {
        this.root = root;
        this.propertyNames = names;
        this.optionNames = options;
    }

    public Category root() { return root; }

    /** Property name from the XML, or null. */
    public String propertyName(int id) {
        return propertyNames.get(id);
    }

    /** Named option for a property value (e.g. occupant group names), or null. */
    public String optionName(int id, long value) {
        Map<Long, String> m = optionNames.get(id);
        return m == null ? null : m.get(value);
    }

    public static LotFilters loadDefault() {
        try (InputStream in = LotFilters.class.getResourceAsStream("/org/opendamn/new_properties.xml")) {
            if (in == null) return new LotFilters(new Category("All", ""), Map.of(), Map.of());
            return load(in);
        } catch (IOException e) {
            return new LotFilters(new Category("All", ""), Map.of(), Map.of());
        }
    }

    public static LotFilters load(InputStream in) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = f.newDocumentBuilder().parse(in);
            Map<Integer, String> names = new HashMap<>();
            Map<Integer, Map<Long, String>> options = new HashMap<>();
            NodeList props = doc.getElementsByTagName("PROPERTY");
            for (int k = 0; k < props.getLength(); k++) {
                Element e = (Element) props.item(k);
                if (!e.hasAttribute("Name")) continue;
                Long id = parseNum(e.getAttribute("ID"));
                if (id == null) continue;
                names.put(id.intValue(), e.getAttribute("Name"));
                NodeList opts = e.getElementsByTagName("OPTION");
                for (int o = 0; o < opts.getLength(); o++) {
                    Element oe = (Element) opts.item(o);
                    Long v = parseNum(oe.getAttribute("Value"));
                    if (v != null) options.computeIfAbsent(id.intValue(), x -> new HashMap<>()).put(v, oe.getAttribute("Name"));
                }
            }

            Map<String, Category> byId = new HashMap<>();
            Map<String, String> parentOf = new HashMap<>();
            List<String> order = new ArrayList<>();
            NodeList cats = doc.getElementsByTagName("CATEGORY");
            for (int k = 0; k < cats.getLength(); k++) {
                Element e = (Element) cats.item(k);
                String id = e.getAttribute("ID").toLowerCase();
                Category c = new Category(e.getAttribute("Name"), text(e, "HELP"));
                for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (!(n instanceof Element fe) || !fe.getTagName().equals("FILTERS")) continue;
                    for (Node r = fe.getFirstChild(); r != null; r = r.getNextSibling()) {
                        if (!(r instanceof Element re)) continue;
                        Long pid = parseNum(re.getAttribute("ID"));
                        if (pid == null) continue;
                        Long val = re.hasAttribute("Value") ? parseNum(re.getAttribute("Value")) : null;
                        if (re.getTagName().equals("NEEDED")) c.rules.add(new Rule(true, pid.intValue(), val));
                        else if (re.getTagName().equals("NOT")) c.rules.add(new Rule(false, pid.intValue(), val));
                    }
                }
                byId.put(id, c);
                parentOf.put(id, e.getAttribute("ParentID").toLowerCase());
                order.add(id);
            }
            Category root = new Category("All", "All lots");
            for (String id : order) {
                Category c = byId.get(id);
                Category p = byId.get(parentOf.get(id));
                if (p == null || p == c) {
                    if (c.name.equals("All")) {
                        root = c;
                        continue;
                    }
                    p = root;
                }
                c.parent = p;
                p.children.add(c);
            }
            // Re-parent top-level categories that were attached before "All" was seen.
            for (String id : order) {
                Category c = byId.get(id);
                if (c != root && c.parent != null && c.parent.parent == null && c.parent != root
                        && c.parent.name.equals("All")) {
                    c.parent.children.remove(c);
                    c.parent = root;
                    root.children.add(c);
                }
            }
            return new LotFilters(root, names, options);
        } catch (Exception e) {
            throw new IOException("Cannot read property definitions: " + e.getMessage(), e);
        }
    }

    private static String text(Element e, String tag) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && c.getTagName().equals(tag)) return c.getTextContent().strip();
        }
        return "";
    }

    static Long parseNum(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) return Long.parseUnsignedLong(s.substring(2), 16);
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
