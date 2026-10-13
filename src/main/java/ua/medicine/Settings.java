package ua.medicine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.medicine.ui.ThemeMode;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Small user preferences kept in a properties file. */
public final class Settings {

    private static final Logger log = LoggerFactory.getLogger(Settings.class);
    private static final String THEME = "theme";

    private final Path file;
    private final Properties props = new Properties();

    public Settings(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            try (Reader in = Files.newBufferedReader(file)) {
                props.load(in);
            } catch (IOException e) {
                log.warn("Cannot read {}", file, e);
            }
        }
    }

    public ThemeMode themeMode() {
        try {
            return ThemeMode.valueOf(props.getProperty(THEME, ThemeMode.SYSTEM.name()));
        } catch (IllegalArgumentException e) {
            return ThemeMode.SYSTEM;
        }
    }

    public void setThemeMode(ThemeMode mode) {
        props.setProperty(THEME, mode.name());
        save();
    }

    private void save() {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            try (Writer out = Files.newBufferedWriter(tmp)) {
                props.store(out, null);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Cannot save {}", file, e);
        }
    }
}
