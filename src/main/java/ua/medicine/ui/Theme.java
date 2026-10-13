package ua.medicine.ui;

import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.stage.Window;
import ua.medicine.Settings;

import java.util.List;

/** Puts the light or dark stylesheet on every window, following the chosen {@link ThemeMode}. */
public final class Theme {

    private static final String BASE = Theme.class.getResource("theme.css").toExternalForm();
    private static final String DARK = Theme.class.getResource("dark.css").toExternalForm();
    private static final String TRACKED = "medicine.theme.tracked";

    private final Settings settings;
    private final SystemColorScheme system;
    private ThemeMode mode;

    public Theme(Settings settings, SystemColorScheme system) {
        this.settings = settings;
        this.system = system;
        this.mode = settings.themeMode();
    }

    /** Styles every current and future window. Call on the FX thread before showing the first one. */
    public void install() {
        system.start();
        system.darkProperty().addListener(o -> {
            if (mode == ThemeMode.SYSTEM) {
                applyAll();
            }
        });
        Window.getWindows().addListener((ListChangeListener<Window>) c -> {
            while (c.next()) {
                c.getAddedSubList().forEach(this::track);
            }
        });
        Window.getWindows().forEach(this::track);
    }

    public void stop() {
        system.stop();
    }

    public ThemeMode mode() {
        return mode;
    }

    public void setMode(ThemeMode mode) {
        this.mode = mode;
        settings.setThemeMode(mode);
        applyAll();
    }

    public boolean isDark() {
        return mode == ThemeMode.DARK || mode == ThemeMode.SYSTEM && system.isDark();
    }

    private void track(Window window) {
        if (window.getProperties().putIfAbsent(TRACKED, true) == null) {
            window.sceneProperty().addListener((o, old, scene) -> apply(scene));
        }
        apply(window.getScene());
    }

    private void applyAll() {
        List.copyOf(Window.getWindows()).forEach(w -> apply(w.getScene()));
    }

    private void apply(Scene scene) {
        if (scene == null) {
            return;
        }
        var sheets = scene.getStylesheets();
        sheets.removeAll(BASE, DARK);
        sheets.addAll(0, isDark() ? List.of(BASE, DARK) : List.of(BASE));
    }
}
