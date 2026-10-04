package org.opendamn.dbpf;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * An exemplar or cohort resource. Supports the binary (EQZB/CQZB) and text (EQZT/CQZT) encodings.
 */
public final class Exemplar {

    public enum Format { BINARY, TEXT }

    // Frequently used property IDs.
    public static final int P_EXEMPLAR_TYPE = 0x00000010;
    public static final int P_EXEMPLAR_NAME = 0x00000020;
    public static final int P_EXEMPLAR_ID = 0x00000021;
    public static final int P_BULLDOZE_COST = 0x099AFACD;
    public static final int P_PLOP_COST = 0x49CAC341;
    public static final int P_ITEM_ICON = 0x8A2602B8;
    public static final int P_ITEM_ORDER = 0x8A2602B9;
    public static final int P_ITEM_BUTTON_ID = 0x8A2602BB;
    public static final int P_ITEM_SUBMENU_PARENT = 0x8A2602CA;
    public static final int P_ITEM_NAME = 0x899AFBAD;
    public static final int P_USER_VISIBLE_NAME_KEY = 0x8A416A99;
    public static final int P_ITEM_DESCRIPTION = 0x8A2602A9;
    public static final int P_ITEM_DESCRIPTION_KEY = 0xCA416AB5;
    public static final int P_DESCRIPTION_KEY = 0xCA416B2A;
    public static final int P_LOT_RESOURCE_KEY = 0xEA260589;
    public static final int P_OCCUPANT_GROUPS = 0xAA1DD396;
    public static final int P_BUILDING_SUBMENUS = 0xAA1DD399;
    public static final int P_CONDITIONAL_BUILDING = 0xEA3209F8;
    public static final int P_LOT_CONFIG_SIZE = 0x88EDC790;
    public static final int P_LOT_CONFIG_ZONE_TYPES = 0x88EDC793;
    public static final int P_LOT_CONFIG_OBJECT = 0x88EDC900;
    public static final int P_LOT_CONFIG_OBJECT_LAST = 0x88EDCDFF;
    public static final int P_BUDGET_DEPARTMENT = 0xEA54D283;
    public static final int P_BUDGET_PURPOSE = 0xEA54D285;
    public static final int P_BUDGET_COST = 0xEA54D286;

    // Exemplar type values (property 0x10).
    public static final int TYPE_BUILDING = 0x02;
    public static final int TYPE_ROAD = 0x06;
    public static final int TYPE_FLORA = 0x0F;
    public static final int TYPE_LOT_CONFIG = 0x10;
    public static final int TYPE_PROP = 0x1E;

    private final boolean cohort;
    private Format format;
    private Tgi parent;
    private final List<Property> properties;

    public Exemplar(boolean cohort, Format format, Tgi parent, List<Property> properties) {
        this.cohort = cohort;
        this.format = format;
        this.parent = parent == null ? new Tgi(0, 0, 0) : parent;
        this.properties = new ArrayList<>(properties);
    }

    public static boolean looksLikeExemplar(byte[] d) {
        return d.length >= 8 && (d[0] == 'E' || d[0] == 'C') && d[1] == 'Q' && d[2] == 'Z'
                && (d[3] == 'B' || d[3] == 'T');
    }

    /** Parses decompressed exemplar/cohort data. */
    public static Exemplar parse(byte[] d) {
        if (!looksLikeExemplar(d)) throw new DbpfException("Not an exemplar");
        boolean cohort = d[0] == 'C';
        try {
            return d[3] == 'B' ? parseBinary(d, cohort) : parseText(d, cohort);
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            throw new DbpfException("Malformed exemplar: " + e.getMessage(), e);
        }
    }

    private static Exemplar parseBinary(byte[] d, boolean cohort) {
        try {
            ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
            b.position(8);
            Tgi parent = new Tgi(b.getInt(), b.getInt(), b.getInt());
            int count = b.getInt();
            if (count < 0 || count > 100_000) throw new DbpfException("Bad property count " + count);
            List<Property> props = new ArrayList<>(count);
            for (int k = 0; k < count; k++) {
                int id = b.getInt();
                PropertyType type = PropertyType.forCode(b.getShort() & 0xFFFF);
                int keyType = b.getShort() & 0xFFFF;
                b.get(); // unused
                boolean multi = keyType == 0x80 || type == PropertyType.STRING;
                int reps = multi ? b.getInt() : 1;
                if (reps < 0 || (long) reps * type.width > b.remaining()) {
                    throw new DbpfException(String.format("Bad repetition count for property 0x%08x", id));
                }
                if (type == PropertyType.STRING) {
                    byte[] s = new byte[reps];
                    b.get(s);
                    props.add(new Property(id, type, true, null, decodeString(s)));
                } else {
                    long[] vals = new long[reps];
                    for (int r = 0; r < reps; r++) vals[r] = readValue(b, type);
                    props.add(new Property(id, type, multi, vals, null));
                }
            }
            return new Exemplar(cohort, Format.BINARY, parent, props);
        } catch (java.nio.BufferUnderflowException e) {
            throw new DbpfException("Truncated exemplar", e);
        }
    }

    private static long readValue(ByteBuffer b, PropertyType t) {
        return switch (t) {
            case UINT8, BOOL -> b.get() & 0xFFL;
            case UINT16 -> b.getShort() & 0xFFFFL;
            case UINT32 -> b.getInt() & 0xFFFFFFFFL;
            case SINT32 -> b.getInt();
            case FLOAT32 -> b.getInt() & 0xFFFFFFFFL;
            case SINT64 -> b.getLong();
            case STRING -> throw new IllegalStateException();
        };
    }

    private static void writeValue(ByteBuffer b, PropertyType t, long v) {
        switch (t) {
            case UINT8, BOOL -> b.put((byte) v);
            case UINT16 -> b.putShort((short) v);
            case UINT32, SINT32, FLOAT32 -> b.putInt((int) v);
            case SINT64 -> b.putLong(v);
            case STRING -> throw new IllegalStateException();
        }
    }

    /** Strings are kept as Latin-1 so that every byte round-trips unchanged. */
    private static String decodeString(byte[] s) {
        return new String(s, StandardCharsets.ISO_8859_1);
    }

    private static Exemplar parseText(byte[] d, boolean cohort) {
        String all = new String(d, StandardCharsets.ISO_8859_1);
        String[] lines = all.split("\r\n|\n|\r");
        Tgi parent = new Tgi(0, 0, 0);
        List<Property> props = new ArrayList<>();
        for (int k = 1; k < lines.length; k++) {
            String line = lines[k].trim();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("parentcohort")) {
                int o = line.indexOf('{');
                int c = line.indexOf('}', o);
                if (o >= 0 && c > o) {
                    String[] p = line.substring(o + 1, c).split(",");
                    if (p.length == 3) parent = new Tgi(Tgi.parseHex(p[0]), Tgi.parseHex(p[1]), Tgi.parseHex(p[2]));
                }
                continue;
            }
            if (lower.startsWith("propcount")) continue;
            props.add(parseTextProperty(line));
        }
        return new Exemplar(cohort, Format.TEXT, parent, props);
    }

    /** Parses one line of the form {@code 0xID:{"Name"}=Type:Reps:{values}}. */
    static Property parseTextProperty(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) throw new DbpfException("Bad text property: " + line);
        int id = Tgi.parseHex(line.substring(0, colon));
        int eq;
        int nameEnd = line.indexOf("\"}=", colon);
        if (nameEnd >= 0) {
            eq = nameEnd + 2;
        } else {
            eq = line.indexOf('=', colon);
        }
        if (eq < 0) throw new DbpfException("Bad text property: " + line);
        String rest = line.substring(eq + 1);
        int c1 = rest.indexOf(':');
        int c2 = rest.indexOf(':', c1 + 1);
        if (c1 < 0 || c2 < 0) throw new DbpfException("Bad text property: " + line);
        PropertyType type = PropertyType.forTextName(rest.substring(0, c1));
        int reps = Integer.decode(rest.substring(c1 + 1, c2).trim());
        String body = rest.substring(c2 + 1).trim();
        if (body.startsWith("{")) body = body.substring(1);
        if (body.endsWith("}")) body = body.substring(0, body.length() - 1);

        if (type == PropertyType.STRING) {
            String s = body;
            int q1 = s.indexOf('"');
            int q2 = s.lastIndexOf('"');
            if (q1 >= 0 && q2 > q1) s = s.substring(q1 + 1, q2);
            return Property.ofString(id, s);
        }
        List<Long> vals = new ArrayList<>();
        if (!body.isBlank()) {
            for (String v : body.split(",")) vals.add(parseTextNumber(type, v.trim()));
        }
        long[] arr = vals.stream().mapToLong(Long::longValue).toArray();
        return new Property(id, type, reps != 0 || arr.length != 1, arr, null);
    }

    private static long parseTextNumber(PropertyType type, String v) {
        if (type == PropertyType.BOOL) {
            return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("0x01") ? 1 : 0;
        }
        boolean hex = v.startsWith("0x") || v.startsWith("0X");
        String digits = hex ? v.substring(2) : v;
        if (type == PropertyType.FLOAT32) {
            if (hex) return Long.parseUnsignedLong(digits, 16) & 0xFFFFFFFFL;
            return Float.floatToRawIntBits(Float.parseFloat(v)) & 0xFFFFFFFFL;
        }
        // Reader-style text exemplars write integers as hex, with or without "0x".
        long r;
        if (hex || digits.matches(".*[a-fA-F].*") || digits.length() == type.width * 2) {
            r = Long.parseUnsignedLong(digits, 16);
        } else {
            r = Long.parseLong(digits);
        }
        return switch (type) {
            case SINT32 -> (int) r;
            case UINT8 -> r & 0xFF;
            case UINT16 -> r & 0xFFFF;
            case UINT32 -> r & 0xFFFFFFFFL;
            default -> r;
        };
    }

    // ---- accessors ----

    public boolean isCohort() { return cohort; }
    public Format format() { return format; }
    public void setFormat(Format f) { this.format = f; }
    public Tgi parent() { return parent; }
    public void setParent(Tgi p) { this.parent = p; }

    public List<Property> properties() {
        return Collections.unmodifiableList(properties);
    }

    public Property get(int id) {
        for (Property p : properties) if (p.id() == id) return p;
        return null;
    }

    public boolean has(int id) {
        return get(id) != null;
    }

    public long getLong(int id, long def) {
        Property p = get(id);
        return p == null ? def : p.getLong(0, def);
    }

    public String getString(int id) {
        Property p = get(id);
        return p != null && p.isString() ? p.text() : null;
    }

    /** The Exemplar Type (property 0x10), or -1 if missing. */
    public int exemplarType() {
        Property p = get(P_EXEMPLAR_TYPE);
        if (p != null) return (int) p.getLong(0, -1);
        // Lot configurations sometimes omit the type but always carry a lot size.
        return has(P_LOT_CONFIG_SIZE) ? TYPE_LOT_CONFIG : -1;
    }

    public void put(Property p) {
        for (int k = 0; k < properties.size(); k++) {
            if (properties.get(k).id() == p.id()) {
                properties.set(k, p);
                return;
            }
        }
        properties.add(p);
    }

    public Property remove(int id) {
        for (int k = 0; k < properties.size(); k++) {
            if (properties.get(k).id() == id) return properties.remove(k);
        }
        return null;
    }

    // ---- encoding ----

    public byte[] encode() {
        return format == Format.TEXT ? encodeText(null) : encodeBinary();
    }

    public byte[] encodeBinary() {
        int size = 24;
        List<byte[]> strings = new ArrayList<>();
        for (Property p : properties) {
            size += 9;
            if (p.multi()) size += 4;
            if (p.isString()) {
                byte[] s = p.text().getBytes(StandardCharsets.ISO_8859_1);
                strings.add(s);
                size += s.length;
            } else {
                size += p.values().length * p.type().width;
            }
        }
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.put((cohort ? "CQZB1###" : "EQZB1###").getBytes(StandardCharsets.US_ASCII));
        b.putInt(parent.type()).putInt(parent.group()).putInt(parent.instance());
        b.putInt(properties.size());
        int si = 0;
        for (Property p : properties) {
            b.putInt(p.id());
            b.putShort((short) p.type().code);
            b.putShort((short) (p.multi() ? 0x80 : 0x00));
            b.put((byte) 0);
            if (p.isString()) {
                byte[] s = strings.get(si++);
                b.putInt(s.length);
                b.put(s);
            } else {
                if (p.multi()) b.putInt(p.values().length);
                for (long v : p.values()) writeValue(b, p.type(), v);
            }
        }
        return b.array();
    }

    /** Text encoding. {@code names} supplies property names for readability (may be null). */
    public byte[] encodeText(IntFunction<String> names) {
        StringBuilder sb = new StringBuilder();
        sb.append(cohort ? "CQZT1###" : "EQZT1###").append("\r\n");
        sb.append(String.format("ParentCohort=Key:{0x%08x,0x%08x,0x%08x}\r\n",
                parent.type(), parent.group(), parent.instance()));
        sb.append(String.format("PropCount=0x%08x\r\n", properties.size()));
        for (Property p : properties) {
            String name = names == null ? null : names.apply(p.id());
            if (name == null || name.isEmpty()) name = "UNKNOWN";
            name = name.replace("\"", "'");
            sb.append(String.format("0x%08x:{\"%s\"}=%s:", p.id(), name, p.type().textName));
            if (p.isString()) {
                sb.append("1:{\"").append(p.text()).append("\"}");
            } else {
                sb.append(p.multi() ? p.values().length : 0).append(":{");
                for (int k = 0; k < p.values().length; k++) {
                    if (k > 0) sb.append(',');
                    long v = p.values()[k];
                    switch (p.type()) {
                        case BOOL -> sb.append(v != 0 ? "True" : "False");
                        case FLOAT32 -> sb.append(Float.intBitsToFloat((int) v));
                        default -> sb.append(String.format("0x%0" + (p.type().width * 2) + "x",
                                p.type() == PropertyType.SINT32 ? v & 0xFFFFFFFFL : v));
                    }
                }
                sb.append('}');
            }
            sb.append("\r\n");
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    public Exemplar copy() {
        return new Exemplar(cohort, format, parent, properties);
    }
}
