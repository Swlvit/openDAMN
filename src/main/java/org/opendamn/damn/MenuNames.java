package org.opendamn.damn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Rules for menu (folder) names. */
public final class MenuNames {

    private static final Pattern INVALID = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1F]");
    private static final Set<String> RESERVED = Set.of(
            "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private MenuNames() {}

    /** Returns an error message, or null if the name is acceptable as a folder name on all systems. */
    public static String validate(String name) {
        if (name == null || name.isBlank()) return "The name is empty.";
        if (!name.equals(name.strip())) return "The name must not start or end with spaces.";
        if (name.endsWith(".")) return "The name must not end with a dot.";
        if (INVALID.matcher(name).find()) return "The name must not contain any of \\ / : * ? \" < > |";
        if (RESERVED.contains(MenuNode.key(name).split("\\.")[0])) return "\"" + name + "\" is reserved on Windows.";
        if (!java.nio.charset.Charset.forName("windows-1252").newEncoder().canEncode(name)) {
            return "The name contains characters SimCity 4 cannot display.";
        }
        if (name.length() > 100) return "The name is too long.";
        if (MenuNode.key(name).endsWith(".dat")) return "Menu names must not end with .dat.";
        return null;
    }

    /** Lists all menu .dat file names below Root (used to keep names unique). */
    static Set<String> existingDatNames(Path root) throws IOException {
        Set<String> s = new HashSet<>();
        if (!Files.isDirectory(root)) return s;
        try (var w = Files.walk(root)) {
            for (Path p : (Iterable<Path>) w::iterator) {
                if (Files.isRegularFile(p) && isMenuFile(p)) s.add(MenuNode.key(p.getFileName().toString()));
            }
        }
        return s;
    }

    static boolean isMenuFile(Path p) {
        return MenuNode.key(p.getFileName().toString()).endsWith(".dat");
    }

    /** A new unique menu file name in the style of the original tools. */
    static String newDatName(Set<String> taken) {
        long t = System.currentTimeMillis();
        while (true) {
            String n = "DAMN-Menu_" + t + ".dat";
            if (taken.add(MenuNode.key(n))) return n;
            t++;
        }
    }

    static List<String> segmentsOf(Path root, Path folder) {
        List<String> s = new ArrayList<>();
        for (Path p : root.relativize(folder)) {
            String n = p.toString();
            if (!n.isEmpty()) s.add(n);
        }
        return s;
    }
}
