package ua.medicine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.medicine.schedule.TimeScale;
import ua.medicine.ui.DashboardView;
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
    private static final String VIEW = "dashboard.view";
    private static final String ZOOM = "timeline.zoom";

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
        return get(THEME, ThemeMode.SYSTEM);
    }

    public void setThemeMode(ThemeMode mode) {
        set(THEME, mode);
    }

    public DashboardView dashboardView() {
        return get(VIEW, DashboardView.CARDS);
    }

    public void setDashboardView(DashboardView view) {
        set(VIEW, view);
    }

    public TimeScale.Zoom timelineZoom() {
        return get(ZOOM, TimeScale.Zoom.MONTHS);
    }

    public void setTimelineZoom(TimeScale.Zoom zoom) {
        set(ZOOM, zoom);
    }

    /** The stored constant, or {@code fallback} when missing or unknown. */
    private <E extends Enum<E>> E get(String key, E fallback) {
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), props.getProperty(key, fallback.name()));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private void set(String key, Enum<?> value) {
        props.setProperty(key, value.name());
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
