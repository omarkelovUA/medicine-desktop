package ua.medicine.i18n;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessagesTest {

    @Test
    void bundleHasNoAsciiApostrophes() {
        var b = Messages.bundle();
        assertThat(b.keySet()).isNotEmpty()
                .allSatisfy(k -> assertThat(b.getString(k)).as(k).doesNotContain("'"));
    }

    @Test
    void formatsArgs() {
        assertThat(Messages.get("google.connected", "a@b.c")).isEqualTo("G ✓ a@b.c");
    }

    @Test
    void plainValueWithoutArgs() {
        assertThat(Messages.get("tab.all")).isEqualTo("Усі");
    }

    @Test
    void childCalendarTexts() {
        assertThat(Messages.get("google.throughHost", "Олена", "o@g")).isEqualTo("Через календар Олена (o@g)");
        assertThat(Messages.get("google.connectedThrough", "Олена")).isEqualTo("G ✓ через Олена");
    }
}
