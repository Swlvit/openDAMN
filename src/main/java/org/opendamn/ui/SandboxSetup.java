package org.opendamn.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import org.opendamn.util.Log;

/** Sets up a sandbox: copies the user's DAMN setup to /tmp so they can experiment safely. */
public final class SandboxSetup {

    private SandboxSetup() {}

    public static class Paths {
        public final Path sandboxRoot, gameDir, userPlugins, damnDir, hiddenDir, cacheFile;

        public Paths(Path sandboxRoot, Path gameDir, Path userPlugins, Path damnDir, Path hiddenDir, Path cacheFile) {
            this.sandboxRoot = sandboxRoot;
            this.gameDir = gameDir;
            this.userPlugins = userPlugins;
            this.damnDir = damnDir;
            this.hiddenDir = hiddenDir;
            this.cacheFile = cacheFile;
        }
    }

    /**
     * Shows a dialog asking whether to sandbox, then sets up a copy in /tmp. Returns null if
     * cancelled or the user chose to work directly on the real folders.
     */
    public static Paths offerSandbox(JFrame parent, Path realGameDir, Path realUserPlugins, String hiddenFolderName) {
        String msg = "<html><b>Sandbox mode (recommended for first use)</b><br><br>" +
                "OpenDAMN can work on a temporary copy of your DAMN menus in /tmp<br>" +
                "so nothing in your real install is changed until you say so.<br><br>" +
                "Real folders:<br>" +
                "  Game: " + realGameDir + "<br>" +
                "  Plugins: " + realUserPlugins + "<br><br>" +
                "Choose:</html>";
        String[] opts = {"Sandbox (safe)", "Real folders (careful!)", "Cancel"};
        int r = JOptionPane.showOptionDialog(parent, msg, "Sandbox mode", JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
        if (r == 2 || r == JOptionPane.CLOSED_OPTION) return null;
        if (r == 1) return null; // user chose real folders

        JDialog progress = new JDialog(parent, "Setting up sandbox…", true);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        JLabel status = new JLabel("Preparing…");
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        p.add(status, BorderLayout.NORTH);
        p.add(bar);
        progress.add(p);
        progress.setSize(480, 120);
        progress.setLocationRelativeTo(parent);
        progress.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        Paths[] result = {null};
        Throwable[] error = {null};
        SwingWorker<Paths, String> worker = new SwingWorker<>() {
            @Override
            protected Paths doInBackground() {
                try {
                    return create(realGameDir, realUserPlugins, hiddenFolderName, this::publish);
                } catch (IOException e) {
                    error[0] = e;
                    return null;
                }
            }

            @Override
            protected void process(List<String> chunks) {
                status.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                progress.dispose();
                try {
                    result[0] = get();
                } catch (Exception e) {
                    error[0] = e;
                }
            }
        };
        worker.execute();
        progress.setVisible(true);

        if (error[0] != null) {
            JOptionPane.showMessageDialog(parent, "Sandbox setup failed:\n" + error[0].getMessage(),
                    "Sandbox", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        if (result[0] != null) {
            JOptionPane.showMessageDialog(parent, "<html>Sandbox ready in:<br>" + result[0].sandboxRoot +
                    "<br><br>Your real folders are read-only. Changes go to the sandbox.<br>" +
                    "When you're happy, use File > Apply sandbox to copy changes back.</html>",
                    "Sandbox", JOptionPane.INFORMATION_MESSAGE);
        }
        return result[0];
    }

    private static Paths create(Path realGameDir, Path realUserPlugins, String hiddenFolderName,
                                java.util.function.Consumer<String> status) throws IOException {
        Path root = Files.createTempDirectory("opendamn-sandbox-");
        Log.info("Sandbox root: " + root);
        status.accept("Copying DAMN menus…");
        Path damnSrc = realGameDir.resolve("Plugins/DAMN");
        Path damnDst = root.resolve("damn");
        if (Files.isDirectory(damnSrc)) copyTree(damnSrc, damnDst);
        else {
            Files.createDirectories(damnDst);
            Log.warn("DAMN not installed yet in " + realGameDir);
        }

        status.accept("Copying hidden lots folder…");
        Path hiddenSrc = realUserPlugins.resolve(hiddenFolderName);
        Path hiddenDst = root.resolve("hidden");
        if (Files.isDirectory(hiddenSrc)) copyTree(hiddenSrc, hiddenDst);
        else Files.createDirectories(hiddenDst);

        status.accept("Linking game files (read-only)…");
        Path gameDst = root.resolve("game");
        Files.createDirectories(gameDst);
        try (var s = Files.list(realGameDir)) {
            for (Path f : s.toList()) {
                String n = f.getFileName().toString();
                if (n.endsWith(".dat") || n.endsWith(".DAT") || n.equals("Plugins")) {
                    Files.createSymbolicLink(gameDst.resolve(n), f);
                }
            }
        }

        status.accept("Linking user plugins (read-only)…");
        Path pluginsDst = root.resolve("plugins");
        Files.createDirectories(pluginsDst);
        try (var s = Files.list(realUserPlugins)) {
            for (Path f : s.toList()) {
                if (!f.getFileName().toString().equals(hiddenFolderName)) {
                    Files.createSymbolicLink(pluginsDst.resolve(f.getFileName()), f);
                }
            }
        }

        Files.writeString(root.resolve("README.txt"), """
                OpenDAMN sandbox
                
                Your real game and plugin folders are symlinked (read-only).
                DAMN menus and the hidden lots folder are copied here as working copies.
                Changes stay in this sandbox until you apply them (File > Apply sandbox).
                
                Real folders:
                  Game: %s
                  Plugins: %s
                  Hidden: %s
                
                Sandbox folders:
                  DAMN menus: %s
                  Hidden: %s
                """.formatted(realGameDir, realUserPlugins, hiddenSrc, damnDst, hiddenDst));

        return new Paths(root, gameDst, pluginsDst, damnDst, hiddenDst, root.resolve("cache.bin"));
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Copies the sandbox's DAMN menus and hidden folder back to the real install. */
    public static void applyToReal(JFrame parent, Paths sandbox, Path realGameDir, Path realUserPlugins,
                                   String hiddenFolderName) {
        String msg = "<html><b>Apply sandbox changes to your real install?</b><br><br>" +
                "This will overwrite:<br>" +
                "  " + realGameDir.resolve("Plugins/DAMN") + "<br>" +
                "  " + realUserPlugins.resolve(hiddenFolderName) + "<br><br>" +
                "Your current versions will be backed up first.</html>";
        if (JOptionPane.showConfirmDialog(parent, msg, "Apply sandbox", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;

        try {
            Path realDamn = realGameDir.resolve("Plugins/DAMN");
            Path realHidden = realUserPlugins.resolve(hiddenFolderName);
            if (Files.exists(realDamn)) {
                Path bak = realDamn.getParent().resolve("DAMN.backup." + System.currentTimeMillis());
                Files.move(realDamn, bak);
                Log.info("Backed up DAMN to " + bak);
            }
            if (Files.exists(realHidden)) {
                Path bak = realHidden.getParent().resolve(hiddenFolderName + ".backup." + System.currentTimeMillis());
                Files.move(realHidden, bak);
                Log.info("Backed up hidden folder to " + bak);
            }
            copyTree(sandbox.damnDir, realDamn);
            copyTree(sandbox.hiddenDir, realHidden);
            JOptionPane.showMessageDialog(parent, "Sandbox applied successfully.", "Apply sandbox",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parent, "Failed to apply sandbox:\n" + e.getMessage(), "Apply sandbox",
                    JOptionPane.ERROR_MESSAGE);
        }
    }
}
