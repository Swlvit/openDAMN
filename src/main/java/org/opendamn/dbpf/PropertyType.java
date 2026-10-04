package org.opendamn.dbpf;

/** Exemplar property data types. */
public enum PropertyType {
    UINT8(0x0100, "Uint8", 1),
    UINT16(0x0200, "Uint16", 2),
    UINT32(0x0300, "Uint32", 4),
    SINT32(0x0700, "Sint32", 4),
    SINT64(0x0800, "Sint64", 8),
    FLOAT32(0x0900, "Float32", 4),
    BOOL(0x0B00, "Bool", 1),
    STRING(0x0C00, "String", 1);

    public final int code;
    public final String textName;
    public final int width;

    PropertyType(int code, String textName, int width) {
        this.code = code;
        this.textName = textName;
        this.width = width;
    }

    public static PropertyType forCode(int code) {
        for (PropertyType t : values()) if (t.code == code) return t;
        throw new DbpfException(String.format("Unknown property type 0x%04x", code));
    }

    public static PropertyType forTextName(String name) {
        for (PropertyType t : values()) if (t.textName.equalsIgnoreCase(name.trim())) return t;
        throw new DbpfException("Unknown property type name: " + name);
    }

    public boolean isNumeric() {
        return this != STRING;
    }
}
