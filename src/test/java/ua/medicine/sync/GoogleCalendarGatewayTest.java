package ua.medicine.sync;

import com.google.api.services.calendar.model.CalendarListEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleCalendarGatewayTest {

    private static CalendarListEntry entry(String id, String name, String role) {
        return new CalendarListEntry().setId(id).setSummary(name).setAccessRole(role);
    }

    @Test
    void calendarsSharedWithTheAccountAreNotReused() {
        var items = List.of(entry("shared", "Ліки Софія", "writer"), entry("ro", "Ліки Софія", "reader"),
                entry("own", "Ліки Софія", "owner"));
        assertThat(GoogleCalendarGateway.ownedCalendar(items, "Ліки Софія")).contains("own");
        assertThat(GoogleCalendarGateway.ownedCalendar(items.subList(0, 2), "Ліки Софія")).isEmpty();
        assertThat(GoogleCalendarGateway.ownedCalendar(null, "Ліки")).isEmpty();
    }
}
