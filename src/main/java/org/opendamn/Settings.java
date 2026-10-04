package org.opendamn;

import java.awt.Color;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/** Application settings, stored as a properties file in the user's config folder. */
public final class Settings {

    private final Path file;
    private final Properties p = new Properties();

    private Settings(Path file) {
        this.file = file;
    }

    public static Settings load() {
        Settings s = new Settings(configDir().resolve("settings.properties"));
        if (Files.exists(s.file)) {
            try (Reader r = Files.newBufferedReader(s.file, StandardCharsets.UTF_8)) {
                s.p.load(r);
            } catch (IOException e) {
                // fall back to defaults
            }
        }
        return s;
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("settings.properties.tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                p.store(w, "OpenDAMN settings");
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Cannot save settings: " + e.getMessage());
        }
    }

    /** Per-user configuration folder for this application. */
    public static Path configDir() {
        String override = System.getProperty("opendamn.config");
        if (override != null) return Path.of(override);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            return Path.of(appdata != null ? appdata : home, "OpenDAMN");
        }
        if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "OpenDAMN");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return Path.of(xdg != null && !xdg.isBlank() ? xdg : home + "/.config", "opendamn");
    }

    public Path backupDir() {
        return configDir().resolve("backups");
    }

    public Path cacheFile() {
        return configDir().resolve("plugin-index.cache");
    }

    // ---- generic accessors

    public String get(String k, String def) {
        return p.getProperty(k, def);
    }

    public void set(String k, String v) {
        if (v == null) p.remove(k);
        else p.setProperty(k, v);
    }

    public boolean getBool(String k, boolean def) {
        String v = p.getProperty(k);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    public void setBool(String k, boolean v) {
        p.setProperty(k, Boolean.toString(v));
    }

    public int getInt(String k, int def) {
        try {
            return Integer.parseInt(p.getProperty(k, Integer.toString(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void setInt(String k, int v) {
        p.setProperty(k, Integer.toString(v));
    }

    public Path getPath(String k) {
        String v = p.getProperty(k);
        return v == null || v.isBlank() ? null : Path.of(v);
    }

    public void setPath(String k, Path v) {
        set(k, v == null ? null : v.toString());
    }

    public Color getColor(String k, Color def) {
        String v = p.getProperty(k);
        if (v == null) return def;
        try {
            return new Color(Integer.parseInt(v.replace("#", ""), 16));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void setColor(String k, Color c) {
        p.setProperty(k, String.format("#%06x", c.getRGB() & 0xFFFFFF));
    }

    // ---- keys
    public static final String GAME_DIR = "paths.game";
    public static final String PLUGINS_DIR = "paths.userPlugins";
    public static final String BROWSE_DIR = "paths.browse";
    public static final String LANGUAGE_DIR = "paths.gameLanguage";
    public static final String HIDDEN_FOLDER = "hiding.folder";
    public static final String HIDE_FORMAT = "hiding.format";
    public static final String HIDE_NEW = "hiding.hideNewItems";
    public static final String MARK_HIDDEN = "hiding.markHidden";
    public static final String MAX_ITEMS = "damn.maxItems";
    public static final String ADD_AT_END = "damn.addAtEnd";
    public static final String ADD_PREVIEW = "damn.addPreview";
    public static final String CLEAN_CHARS = "damn.removeNotAllowedChars";
    public static final String USE_ITEM_ORDER = "general.useItemOrder";
    public static final String IGNORE_NO_ICON = "general.ignoreLotsWithoutIcon";
    public static final String RESET_ZOOM = "general.resetZoom";
    public static final String CLEAR_CLIPBOARD = "general.clearClipboardAfterPaste";
    public static final String SHOW_LOT_ERRORS = "general.showLotErrors";
    public static final String EMPTY_UNFILLED = "general.emptyUnfilledPatterns";
    public static final String TEMPLATE = "general.template";
    public static final String THEME = "ui.theme";
    public static final String CURRENCY = "general.currency";
}
