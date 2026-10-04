package org.opendamn.damn;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Exports a menu (with submenus) to a ZIP and imports one. Archives use the layout of DAMN
 * Manager: {@code <MenuName>/.../DAMN-Menu_*.dat}, or {@code Root/...} for a whole tree.
 */
public final class MenuZip {

    private MenuZip() {}

    /** Writes the saved state of a menu folder to a ZIP. Save first to include pending edits. */
    public static int export(DamnMenus menus, MenuNode node, Path zip) throws IOException {
        Path folder = menus.rootDir();
        for (String s : node.segments()) folder = folder.resolve(s);
        String top = node.isRoot() ? "Root" : node.name();
        int n = 0;
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            Path base = folder;
            List<Path> all = new ArrayList<>();
            try (var w = Files.walk(base)) {
                w.sorted().forEach(all::add);
            }
            for (Path p : all) {
                String rel = base.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith(".")) continue;
                String name = rel.isEmpty() ? top + "/" : top + "/" + rel + (Files.isDirectory(p) ? "/" : "");
                out.putNextEntry(new ZipEntry(name));
                if (Files.isRegularFile(p)) {
                    Files.copy(p, out);
                    n++;
                }
                out.closeEntry();
            }
        }
        return n;
    }

    /** What an archive contains. */
    public record Contents(String topName, boolean isRoot, int menuFiles) {}

    public static Contents inspect(Path zip) throws IOException {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            String top = null;
            int files = 0;
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                ZipEntry en = e.nextElement();
                String n = clean(en.getName());
                if (n.isEmpty()) continue;
                String first = n.contains("/") ? n.substring(0, n.indexOf('/')) : n;
                if (top == null) top = first;
                else if (!top.equals(first)) top = "";
                if (!en.isDirectory() && n.toLowerCase(Locale.ROOT).endsWith(".dat")) files++;
            }
            if (top == null || top.isEmpty()) throw new IOException("The archive does not contain a single menu folder.");
            return new Contents(top, top.equalsIgnoreCase("Root"), files);
        }
    }

    /**
     * Extracts an archive into {@code targetFolder}. Only folders and .dat files are taken;
     * paths are sanitised so nothing can be written outside the target ("zip slip").
     *
     * @param stripTop drop the archive's top folder (used when importing Root into Root)
     */
    public static int extract(Path zip, Path targetFolder, boolean stripTop) throws IOException {
        int n = 0;
        Path base = targetFolder.toAbsolutePath().normalize();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                ZipEntry en = e.nextElement();
                String name = clean(en.getName());
                if (stripTop) {
                    int i = name.indexOf('/');
                    name = i < 0 ? "" : name.substring(i + 1);
                }
                if (name.isEmpty()) continue;
                for (String seg : name.split("/")) {
                    if (seg.equals("..") || MenuNames.validate(seg) != null && !seg.toLowerCase(Locale.ROOT).endsWith(".dat")) {
                        throw new IOException("Unsafe or invalid name in archive: " + en.getName());
                    }
                }
                Path out = base.resolve(name).normalize();
                if (!out.startsWith(base)) throw new IOException("Unsafe path in archive: " + en.getName());
                if (en.isDirectory()) {
                    Files.createDirectories(out);
                } else if (name.toLowerCase(Locale.ROOT).endsWith(".dat")) {
                    Files.createDirectories(out.getParent());
                    try (InputStream in = z.getInputStream(en); OutputStream o = Files.newOutputStream(out)) {
                        in.transferTo(o);
                    }
                    n++;
                } else {
                    Files.createDirectories(out.getParent()); // placeholder files: keep the folder only
                }
            }
        }
        return n;
    }

    private static String clean(String s) {
        String n = s.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        return n;
    }
}
