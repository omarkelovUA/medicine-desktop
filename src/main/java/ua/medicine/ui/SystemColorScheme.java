package ua.medicine.ui;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Whether the desktop prefers dark apps (GNOME settings), followed live while the app runs. */
public final class SystemColorScheme {

    private static final Logger log = LoggerFactory.getLogger(SystemColorScheme.class);
    private static final String SCHEMA = "org.gnome.desktop.interface";

    private final ReadOnlyBooleanWrapper dark = new ReadOnlyBooleanWrapper(false);
    private Process monitor;

    public ReadOnlyBooleanProperty darkProperty() {
        return dark.getReadOnlyProperty();
    }

    public boolean isDark() {
        return dark.get();
    }

    /** Reads the current scheme and starts watching for changes. Call on the FX thread. */
    public void start() {
        dark.set(readDark());
        try {
            // The shell kills the monitor once our end of its stdin closes, so it never outlives the app.
            monitor = new ProcessBuilder("sh", "-c",
                    "command -v gsettings >/dev/null || exit 0; gsettings monitor " + SCHEMA + " & read _; kill $!")
                    .redirectErrorStream(true).start();
        } catch (IOException e) {
            log.info("No shell; system theme is treated as light");
            return;
        }
        Process p = monitor;
        var reader = new Thread(() -> {
            try (var in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                while (in.readLine() != null) {
                    boolean now = readDark();
                    Platform.runLater(() -> dark.set(now));
                }
            } catch (IOException e) {
                log.debug("Colour scheme monitor ended", e);
            }
        }, "color-scheme");
        reader.setDaemon(true);
        reader.start();
    }

    public void stop() {
        if (monitor != null) {
            try {
                monitor.getOutputStream().close(); // ends the shell's read, which kills the monitor
            } catch (IOException e) {
                monitor.destroy();
            }
        }
    }

    /** GNOME 42+ color-scheme wins; with 'default' a "-dark" GTK theme (e.g. Yaru-dark) means dark. */
    static boolean isDark(String colorScheme, String gtkTheme) {
        if (colorScheme != null && colorScheme.contains("prefer-dark")) {
            return true;
        }
        if (colorScheme != null && colorScheme.contains("prefer-light")) {
            return false;
        }
        return gtkTheme != null && gtkTheme.toLowerCase().contains("dark");
    }

    private static boolean readDark() {
        return isDark(get("color-scheme"), get("gtk-theme"));
    }

    private static String get(String key) {
        try {
            Process p = new ProcessBuilder("gsettings", "get", SCHEMA, key).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.waitFor() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
