package ua.medicine.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonTest {

    private final Person sofia = new Person(1L, "Софія", "#e08a1e", "o@g", "cal", 2L, "Ліки Софія");

    @Test
    void withNameAndColorKeepsHostLink() {
        var renamed = sofia.withNameAndColor("Соня", "#000");
        assertThat(renamed).isEqualTo(new Person(1L, "Соня", "#000", "o@g", "cal", 2L, "Ліки Софія"));
    }

    @Test
    void withGoogleClearsHostLink() {
        var own = sofia.withGoogle("s@g", "own");
        assertThat(own.hosted()).isFalse();
        assertThat(own.calendarName()).isNull();
        assertThat(own.googleCalendarId()).isEqualTo("own");
    }

    @Test
    void tokenOwnerIsHostForChildAndSelfOtherwise() {
        assertThat(sofia.tokenOwnerId()).isEqualTo(2L);
        assertThat(new Person(1L, "Марія", "#c0397b", null, null).tokenOwnerId()).isEqualTo(1L);
    }
}
