package org.opendamn;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.opendamn.ui.AppContext;
import org.opendamn.ui.MainWindow;
import org.opendamn.util.Log;

/** Entry point. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        Settings settings = Settings.load();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            Log.severe("Unexpected error in " + t.getName(), e);
            e.printStackTrace();
        });
        SwingUtilities.invokeLater(() -> {
            String theme = settings.get(Settings.THEME, "Light");
            try {
                if (theme.equals("Dark")) FlatDarkLaf.setup();
                else if (theme.equals("System")) UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                else FlatLightLaf.setup();
            } catch (Exception e) {
                FlatLightLaf.setup();
            }
            MainWindow w = new MainWindow(new AppContext(settings));
            w.setVisible(true);
            SwingUtilities.invokeLater(w::start);
        });
    }
}
