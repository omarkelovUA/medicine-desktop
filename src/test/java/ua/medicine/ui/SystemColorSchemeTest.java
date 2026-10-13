package ua.medicine.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemColorSchemeTest {

    @Test
    void colorSchemeDecides() {
        assertThat(SystemColorScheme.isDark("'prefer-dark'", "'Adwaita'")).isTrue();
        assertThat(SystemColorScheme.isDark("'prefer-light'", "'Yaru-dark'")).isFalse();
    }

    @Test
    void gtkThemeDecidesWhenSchemeIsDefault() {
        assertThat(SystemColorScheme.isDark("'default'", "'Yaru-dark'")).isTrue();
        assertThat(SystemColorScheme.isDark("'default'", "'Yaru'")).isFalse();
    }

    @Test
    void unknownDesktopIsLight() {
        assertThat(SystemColorScheme.isDark(null, null)).isFalse();
    }
}
