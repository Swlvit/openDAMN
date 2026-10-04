package org.opendamn.dbpf;

import java.util.Locale;

/**
 * Type / Group / Instance key identifying a resource inside a DBPF file.
 * Values are stored as Java ints but treated as unsigned 32-bit.
 */
public record Tgi(int type, int group, int instance) implements Comparable<Tgi> {

    // Well-known SimCity 4 resource types.
    public static final int TYPE_EXEMPLAR = 0x6534284A;
    public static final int TYPE_COHORT = 0x05342861;
    public static final int TYPE_PNG = 0x856DDBAC;
    public static final int TYPE_LTEXT = 0x2026960B;
    public static final int TYPE_LUA = 0xCA63E2A3;
    public static final int TYPE_RUL = 0x0A5BCF4B;
    public static final int TYPE_UI = 0x00000000;
    public static final int TYPE_DIR = 0xE86B1EEF;

    // Well-known groups.
    public static final int GROUP_PNG_MENU_ICON = 0x6A386D26;
    public static final int GROUP_LUA = 0x4A5E8EF6;
    public static final int GROUP_LTEXT_HTML = 0x6A231EA4;

    public static final Tgi DIRECTORY = new Tgi(TYPE_DIR, TYPE_DIR, 0x286B1F03);

    public static Tgi of(long t, long g, long i) {
        return new Tgi((int) t, (int) g, (int) i);
    }

    public long typeU() { return Integer.toUnsignedLong(type); }
    public long groupU() { return Integer.toUnsignedLong(group); }
    public long instanceU() { return Integer.toUnsignedLong(instance); }

    public Tgi withGroup(int g) { return new Tgi(type, g, instance); }
    public Tgi withInstance(int i) { return new Tgi(type, group, i); }

    public static String hex(int v) {
        return String.format(Locale.ROOT, "%08x", v);
    }

    public static String hexUpper(int v) {
        return String.format(Locale.ROOT, "%08X", v);
    }

    /** Parses an 8-digit hex value, with or without a 0x prefix. */
    public static int parseHex(String s) {
        s = s.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        return (int) Long.parseLong(s, 16);
    }

    /** Parses "tttttttt gggggggg iiiiiiii" (space, comma, dash or underscore separated). */
    public static Tgi parse(String s) {
        String[] p = s.trim().split("[\\s,_\\-]+");
        if (p.length != 3) throw new IllegalArgumentException("Not a TGI: " + s);
        return new Tgi(parseHex(p[0]), parseHex(p[1]), parseHex(p[2]));
    }

    @Override
    public String toString() {
        return hex(type) + " " + hex(group) + " " + hex(instance);
    }

    @Override
    public int compareTo(Tgi o) {
        int c = Integer.compareUnsigned(type, o.type);
        if (c != 0) return c;
        c = Integer.compareUnsigned(group, o.group);
        if (c != 0) return c;
        return Integer.compareUnsigned(instance, o.instance);
    }
}
