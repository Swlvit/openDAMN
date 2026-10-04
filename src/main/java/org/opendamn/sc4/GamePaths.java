package org.opendamn.sc4;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/** Locates SimCity 4 installations and plugin folders on Windows and in Wine/Proton prefixes. */
public final class GamePaths {

    /** A detected installation. {@code userPlugins} may be null if it does not exist yet. */
    public record Install(Path gameDir, Path userPlugins, String label) {
        public Path gamePlugins() {
            return gameDir.resolve("Plugins");
        }

        public Path damnDir() {
            return gamePlugins().resolve("DAMN");
        }
    }

    private static final String STEAM_APP_ID = "24780";

    private GamePaths() {}

    public static boolean isGameDir(Path p) {
        return p != null && Files.isRegularFile(p.resolve("SimCity_1.dat"));
    }

    /** All installations found, best guess first. */
    public static List<Install> detect() {
        Set<Path> prefixes = new LinkedHashSet<>();
        Set<Path> gameDirs = new LinkedHashSet<>();
        String home = System.getProperty("user.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

        if (windows) {
            for (String env : List.of("ProgramFiles(x86)", "ProgramFiles")) {
                String v = System.getenv(env);
                if (v != null) addWindowsCandidates(Path.of(v), gameDirs);
            }
            for (String drive : List.of("C:\\", "D:\\", "E:\\")) {
                gameDirs.add(Path.of(drive, "GOG Games", "SimCity 4 Deluxe Edition"));
                gameDirs.add(Path.of(drive, "Program Files (x86)", "Steam", "steamapps", "common", "SimCity 4 Deluxe"));
                gameDirs.add(Path.of(drive, "SteamLibrary", "steamapps", "common", "SimCity 4 Deluxe"));
            }
        } else {
            prefixes.add(Path.of(home, ".wine"));
            for (Path steam : List.of(Path.of(home, ".steam", "steam"), Path.of(home, ".local", "share", "Steam"),
                    Path.of(home, ".var", "app", "com.valvesoftware.Steam", ".local", "share", "Steam"))) {
                prefixes.add(steam.resolve("steamapps/compatdata/" + STEAM_APP_ID + "/pfx"));
                gameDirs.add(steam.resolve("steamapps/common/SimCity 4 Deluxe"));
                for (Path lib : steamLibraries(steam)) {
                    gameDirs.add(lib.resolve("steamapps/common/SimCity 4 Deluxe"));
                    prefixes.add(lib.resolve("steamapps/compatdata/" + STEAM_APP_ID + "/pfx"));
                }
            }
            // Lutris, Bottles, PlayOnLinux and hand-made prefixes.
            for (Path base : List.of(Path.of(home, "Games"), Path.of(home, ".local", "share", "bottles", "bottles"),
                    Path.of(home, ".var", "app", "com.usebottles.bottles", "data", "bottles", "bottles"),
                    Path.of(home, ".PlayOnLinux", "wineprefix"), Path.of(home, ".local", "share", "wineprefixes"))) {
                for (Path d : listDirs(base)) {
                    prefixes.add(d);
                    prefixes.add(d.resolve("pfx"));
                    prefixes.add(d.resolve("prefix"));
                }
            }
            for (Path pfx : prefixes) {
                Path c = pfx.resolve("drive_c");
                if (!Files.isDirectory(c)) continue;
                addWindowsCandidates(c.resolve("Program Files (x86)"), gameDirs);
                addWindowsCandidates(c.resolve("Program Files"), gameDirs);
                gameDirs.add(c.resolve("GOG Games/SimCity 4 Deluxe Edition"));
                addWindowsCandidates(c.resolve("Program Files (x86)/GOG Galaxy/Games"), gameDirs);
            }
        }

        List<Install> found = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (Path g : gameDirs) {
            if (!isGameDir(g)) continue;
            Path real;
            try {
                real = g.toRealPath();
            } catch (IOException e) {
                real = g;
            }
            if (!seen.add(real)) continue;
            found.add(new Install(g, findUserPlugins(g, windows ? null : prefixOf(g)), g.toString()));
        }
        // Prefer installs that already have DAMN or a populated user plugins folder.
        found.sort((a, b) -> Integer.compare(score(b), score(a)));
        return found;
    }

    private static int score(Install i) {
        int s = 0;
        if (Files.isDirectory(i.damnDir())) s += 2;
        if (i.userPlugins() != null && Files.isDirectory(i.userPlugins())) s += 1;
        return s;
    }

    private static void addWindowsCandidates(Path programFiles, Set<Path> out) {
        for (String sub : List.of("Maxis/SimCity 4 Deluxe Edition", "Maxis/SimCity 4 Deluxe", "Maxis/SimCity 4",
                "Steam/steamapps/common/SimCity 4 Deluxe", "SimCity 4 Deluxe Edition", "SimCity 4 Deluxe",
                "GOG Galaxy/Games/SimCity 4 Deluxe Edition")) {
            out.add(programFiles.resolve(sub));
        }
    }

    /** The Wine prefix (folder containing drive_c) of a path, or null. */
    public static Path prefixOf(Path p) {
        for (Path x = p.toAbsolutePath(); x != null; x = x.getParent()) {
            if (x.getFileName() != null && x.getFileName().toString().equals("drive_c")) return x.getParent();
        }
        return null;
    }

    /**
     * The path as the game sees it: unchanged on Windows; inside a Wine prefix
     * {@code .../drive_c/x/y} becomes {@code C:\x\y}. Returns null if it cannot be mapped.
     */
    public static String toWindowsPath(Path p) {
        if (p == null) return null;
        Path abs = p.toAbsolutePath().normalize();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return abs.toString();
        Path prefix = prefixOf(abs);
        if (prefix == null) return null;
        Path rel = prefix.resolve("drive_c").relativize(abs);
        StringBuilder sb = new StringBuilder("C:");
        for (Path seg : rel) sb.append('\\').append(seg);
        return sb.toString();
    }

    /** Finds "Documents/SimCity 4/Plugins" for an installation. */
    public static Path findUserPlugins(Path gameDir, Path prefix) {
        List<Path> candidates = new ArrayList<>();
        if (prefix != null) {
            for (Path user : listDirs(prefix.resolve("drive_c/users"))) {
                for (String docs : List.of("Documents", "My Documents")) {
                    candidates.add(user.resolve(docs).resolve("SimCity 4/Plugins"));
                }
            }
        } else {
            String home = System.getProperty("user.home");
            candidates.add(Path.of(home, "Documents", "SimCity 4", "Plugins"));
            candidates.add(Path.of(home, "OneDrive", "Documents", "SimCity 4", "Plugins"));
            candidates.add(Path.of(home, "My Documents", "SimCity 4", "Plugins"));
        }
        Path best = null;
        long bestCount = -1;
        for (Path c : candidates) {
            if (!Files.isDirectory(c)) continue;
            long n;
            try (Stream<Path> s = Files.list(c)) {
                n = s.count();
            } catch (IOException e) {
                n = 0;
            }
            if (n > bestCount) {
                best = c;
                bestCount = n;
            }
        }
        if (best == null && !candidates.isEmpty()) {
            for (Path c : candidates) if (Files.isDirectory(c.getParent())) return c;
        }
        return best;
    }

    /** Game data files in load order (base game, expansion, then the selected language). */
    public static List<Path> gameDataFiles(Path gameDir, Path languageDir) {
        List<Path> r = new ArrayList<>();
        for (int k = 1; k <= 5; k++) {
            Path p = gameDir.resolve("SimCity_" + k + ".dat");
            if (Files.isRegularFile(p)) r.add(p);
        }
        Path ep1 = gameDir.resolve("EP1.dat");
        if (Files.isRegularFile(ep1)) r.add(ep1);
        Path lang = languageDir != null ? languageDir : defaultLanguageDir(gameDir);
        if (lang != null) {
            for (Path f : listFiles(lang)) {
                String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.endsWith(".dat")) r.add(f);
            }
        }
        return r;
    }

    /** Language folders (those containing SimCityLocale.dat). */
    public static List<Path> languageDirs(Path gameDir) {
        List<Path> r = new ArrayList<>();
        for (Path d : listDirs(gameDir)) {
            for (Path f : listFiles(d)) {
                if (f.getFileName().toString().equalsIgnoreCase("SimCityLocale.dat")) {
                    r.add(d);
                    break;
                }
            }
        }
        return r;
    }

    public static Path defaultLanguageDir(Path gameDir) {
        List<Path> langs = languageDirs(gameDir);
        for (Path d : langs) if (d.getFileName().toString().equalsIgnoreCase("English")) return d;
        return langs.isEmpty() ? null : langs.get(0);
    }

    private static List<Path> steamLibraries(Path steam) {
        List<Path> r = new ArrayList<>();
        Path vdf = steam.resolve("steamapps/libraryfolders.vdf");
        if (!Files.isRegularFile(vdf)) return r;
        try {
            for (String line : Files.readAllLines(vdf)) {
                String t = line.trim();
                if (t.startsWith("\"path\"")) {
                    String[] parts = t.split("\"");
                    if (parts.length >= 4) r.add(Path.of(parts[3].replace("\\\\", "\\")));
                }
            }
        } catch (IOException | RuntimeException e) {
            // ignore unreadable library list
        }
        return r;
    }

    static List<Path> listDirs(Path p) {
        if (!Files.isDirectory(p)) return List.of();
        try (Stream<Path> s = Files.list(p)) {
            return s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    static List<Path> listFiles(Path p) {
        if (!Files.isDirectory(p)) return List.of();
        try (Stream<Path> s = Files.list(p)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
