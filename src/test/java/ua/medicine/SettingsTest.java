package ua.medicine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
}
