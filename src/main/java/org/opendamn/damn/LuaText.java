package org.opendamn.damn;

/** Lua string literal helpers (SimCity 4 embeds Lua 5.0). */
final class LuaText {

    private LuaText() {}

    /** Produces a double-quoted Lua string literal that is always syntactically valid. */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\0' -> sb.append("\\000");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\%03d", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * Parses the value side of {@code x = "..."}. Understands escapes written by this tool and
     * the unescaped strings written by older tools (where the last quote on the line ends it).
     */
    static String parseStringLiteral(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) return "";
        char q = s.charAt(0);
        if (q != '"' && q != '\'') {
            int c = s.indexOf("--");
            return (c >= 0 ? s.substring(0, c) : s).trim();
        }
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        int k = 1;
        for (; k < s.length(); k++) {
            char c = s.charAt(k);
            if (escaped) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> {
                        if (Character.isDigit(c)) {
                            int end = k;
                            while (end < s.length() && end < k + 3 && Character.isDigit(s.charAt(end))) end++;
                            sb.append((char) Integer.parseInt(s.substring(k, end)));
                            k = end - 1;
                        } else {
                            sb.append(c);
                        }
                    }
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == q) {
                // Old tools never escaped quotes: treat a quote as closing only if nothing but
                // whitespace/comments follows it.
                String rest = s.substring(k + 1).trim();
                if (rest.isEmpty() || rest.startsWith("--")) return sb.toString();
                sb.append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
