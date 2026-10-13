package ua.medicine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ua.medicine.schedule.TimeScale.Zoom;
import ua.medicine.ui.DashboardView;
import ua.medicine.ui.ThemeMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsTest {

    @TempDir
    Path dir;

    @Test
    void themeDefaultsToSystem() {
        assertThat(new Settings(dir.resolve("settings.properties")).themeMode()).isEqualTo(ThemeMode.SYSTEM);
    }

    @Test
    void themeSurvivesRestart() {
        Path file = dir.resolve("settings.properties");
        new Settings(file).setThemeMode(ThemeMode.DARK);
        assertThat(new Settings(file).themeMode()).isEqualTo(ThemeMode.DARK);
    }

    @Test
    void unknownThemeFallsBackToSystem() throws IOException {
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "theme=PURPLE\n");
        assertThat(new Settings(file).themeMode()).isEqualTo(ThemeMode.SYSTEM);
    }

    @Test
    void viewAndZoomDefault() {
        var settings = new Settings(dir.resolve("settings.properties"));
        assertThat(settings.dashboardView()).isEqualTo(DashboardView.CARDS);
        assertThat(settings.timelineZoom()).isEqualTo(Zoom.MONTHS);
    }

    @Test
    void viewAndZoomSurviveRestart() {
        Path file = dir.resolve("settings.properties");
        var settings = new Settings(file);
        settings.setDashboardView(DashboardView.TIMELINE);
        settings.setTimelineZoom(Zoom.QUARTERS);
        assertThat(new Settings(file).dashboardView()).isEqualTo(DashboardView.TIMELINE);
        assertThat(new Settings(file).timelineZoom()).isEqualTo(Zoom.QUARTERS);
    }

    @Test
    void unknownViewAndZoomFallBack() throws IOException {
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "dashboard.view=GRID\ntimeline.zoom=DECADES\n");
        assertThat(new Settings(file).dashboardView()).isEqualTo(DashboardView.CARDS);
        assertThat(new Settings(file).timelineZoom()).isEqualTo(Zoom.MONTHS);
    }
}
