package org.opendamn.dbpf;

import java.util.Arrays;

/**
 * One exemplar property. Numeric values are kept as raw bits in a long array
 * (floats as {@link Float#floatToRawIntBits}) so that values round-trip exactly.
 *
 * @param id       property ID
 * @param type     data type
 * @param multi    whether it is stored with an explicit repetition count (key type 0x80)
 * @param values   numeric values (empty for strings)
 * @param text     string value (null for numerics)
 */
public record Property(int id, PropertyType type, boolean multi, long[] values, String text) {

    public Property {
        if (type == PropertyType.STRING) {
            if (text == null) text = "";
            values = new long[0];
            multi = true;
        } else {
            if (values == null) values = new long[0];
            if (values.length != 1) multi = true;
        }
    }

    public static Property ofUint32(int id, long... vals) {
        return new Property(id, PropertyType.UINT32, vals.length != 1, vals.clone(), null);
    }

    public static Property ofString(int id, String s) {
        return new Property(id, PropertyType.STRING, true, null, s);
    }

    public int count() {
        return type == PropertyType.STRING ? text.length() : values.length;
    }

    public boolean isString() {
        return type == PropertyType.STRING;
    }

    /** Value at index as an unsigned/signed long depending on type, or {@code def} if missing. */
    public long getLong(int index, long def) {
        if (type == PropertyType.STRING || index >= values.length) return def;
        if (type == PropertyType.FLOAT32) return (long) Float.intBitsToFloat((int) values[index]);
        return values[index];
    }

    public long getLong(int index) {
        return getLong(index, -1);
    }

    public float getFloat(int index) {
        if (type == PropertyType.STRING || index >= values.length) return Float.NaN;
        if (type == PropertyType.FLOAT32) return Float.intBitsToFloat((int) values[index]);
        return values[index];
    }

    /**
     * String value for display. Strings are stored byte-exact as Latin-1; many modern
     * plugins actually contain UTF-8, so re-decode when the bytes form valid UTF-8.
     */
    public String displayText() {
        if (text == null) return null;
        byte[] raw = text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        var dec = java.nio.charset.StandardCharsets.UTF_8.newDecoder();
        try {
            return dec.decode(java.nio.ByteBuffer.wrap(raw)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return text;
        }
    }

    /** Human-readable value list, e.g. "0x00001500, 0x00001504" or the string. */
    public String valueString() {
        if (type == PropertyType.STRING) return displayText();
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < values.length; k++) {
            if (k > 0) sb.append(", ");
            sb.append(formatValue(k));
        }
        return sb.toString();
    }

    public String formatValue(int k) {
        long v = values[k];
        return switch (type) {
            case BOOL -> v != 0 ? "True" : "False";
            case FLOAT32 -> Float.toString(Float.intBitsToFloat((int) v));
            case SINT32, SINT64 -> Long.toString(v);
            default -> String.format("0x%0" + (type.width * 2) + "x", v);
        };
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Property p && p.id == id && p.type == type && p.multi == multi
                && Arrays.equals(p.values, values) && java.util.Objects.equals(p.text, text);
    }

    @Override
    public int hashCode() {
        return id * 31 + Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return String.format("0x%08x=%s:%s", id, type.textName, valueString());
    }
}
