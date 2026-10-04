package org.opendamn.damn;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/** Loads and saves item templates as one properties file per template. */
public final class TemplateStore {

    public static final String STANDARD = "Standard";

    private final Path dir;
    private final List<ItemTemplate> templates = new ArrayList<>();

    public TemplateStore(Path dir) {
        this.dir = dir;
        load();
    }

    public List<ItemTemplate> all() {
        return templates;
    }

    public ItemTemplate get(String name) {
        for (ItemTemplate t : templates) if (t.name.equals(name)) return t;
        return templates.get(0);
    }

    public ItemTemplate create(String name) {
        if (name == null || name.isBlank() || exists(name)) throw new IllegalArgumentException("Name in use or empty");
        ItemTemplate t = new ItemTemplate().copy(name);
        templates.add(t);
        return t;
    }

    public boolean exists(String name) {
        return templates.stream().anyMatch(t -> t.name.equalsIgnoreCase(name));
    }

    public void delete(ItemTemplate t) {
        if (t.name.equals(STANDARD)) throw new IllegalArgumentException("The standard template cannot be deleted.");
        templates.remove(t);
        try {
            Files.deleteIfExists(fileOf(t));
        } catch (IOException e) {
            // ignore
        }
    }

    private void load() {
        templates.clear();
        templates.add(new ItemTemplate());
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".template")).sorted().toList()) {
                Properties pr = new Properties();
                try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    pr.load(r);
                }
                ItemTemplate t = new ItemTemplate();
                t.name = pr.getProperty("name", p.getFileName().toString().replace(".template", ""));
                t.linkName = pr.getProperty("linkName", t.linkName);
                t.infoText1 = pr.getProperty("infoText1", t.infoText1);
                t.infoText2 = pr.getProperty("infoText2", t.infoText2);
                t.description = pr.getProperty("description", t.description);
                t.info = pr.getProperty("info", t.info);
                t.useReplacements = Boolean.parseBoolean(pr.getProperty("useReplacements", "true"));
                String rep = pr.getProperty("replacements");
                if (rep != null) t.replacements = new ArrayList<>(Arrays.stream(rep.split(",")).map(String::trim)
                        .filter(x -> !x.isEmpty()).toList());
                if (t.name.equals(STANDARD)) templates.set(0, t);
                else templates.add(t);
            }
        } catch (IOException e) {
            // keep what was loaded
        }
    }

    public void save() throws IOException {
        Files.createDirectories(dir);
        for (ItemTemplate t : templates) {
            Properties pr = new Properties();
            pr.setProperty("name", t.name);
            pr.setProperty("linkName", t.linkName);
            pr.setProperty("infoText1", t.infoText1);
            pr.setProperty("infoText2", t.infoText2);
            pr.setProperty("description", t.description);
            pr.setProperty("info", t.info);
            pr.setProperty("useReplacements", Boolean.toString(t.useReplacements));
            pr.setProperty("replacements", String.join(",", t.replacements));
            try (Writer w = Files.newBufferedWriter(fileOf(t), StandardCharsets.UTF_8)) {
                pr.store(w, "OpenDAMN item template");
            }
        }
    }

    private Path fileOf(ItemTemplate t) {
        return dir.resolve(t.name.replaceAll("[^A-Za-z0-9 _.-]", "_") + ".template");
    }
}
