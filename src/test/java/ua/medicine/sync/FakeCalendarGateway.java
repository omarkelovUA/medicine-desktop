package ua.medicine.sync;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** In-memory calendar for one account. */
class FakeCalendarGateway implements CalendarGateway {

    final Map<String, CalendarEvent> events = new LinkedHashMap<>();
    int inserts;
    int deletes;
    boolean failAuth;
    boolean failIo;
    private int nextId;

    @Override
    public String ensureCalendar(String name) {
        check();
        return "cal-" + name;
    }

    @Override
    public String insertEvent(String calendarId, CalendarEvent e) {
        check();
        inserts++;
        String id = "ev" + (++nextId);
        events.put(id, e);
        return id;
    }

    @Override
    public void patchEvent(String calendarId, String eventId, CalendarEvent e) {
        check();
        if (!events.containsKey(eventId)) {
            throw new EventNotFoundException(eventId);
        }
        events.put(eventId, e);
    }

    @Override
    public void deleteEvent(String calendarId, String eventId) {
        check();
        deletes++;
        if (events.remove(eventId) == null) {
            throw new EventNotFoundException(eventId);
        }
    }

    private void check() {
        if (failAuth) {
            throw new AuthRequiredException("revoked");
        }
        if (failIo) {
            throw new UncheckedIOException(new IOException("offline"));
        }
    }
}
