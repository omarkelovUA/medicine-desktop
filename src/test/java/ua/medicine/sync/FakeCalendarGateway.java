package ua.medicine.sync;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory calendar for one account. */
class FakeCalendarGateway implements CalendarGateway {

    final Map<String, CalendarEvent> events = new LinkedHashMap<>();
    final Map<String, String> colors = new HashMap<>();
    final Map<String, Instant> updated = new HashMap<>();
    /** eventId → calendarId */
    final Map<String, String> eventCalendar = new HashMap<>();
    /** calendarId → new name */
    final Map<String, String> renamed = new HashMap<>();
    int renames;
    boolean failRename;
    /** The time Google records as "updated" for the app's own inserts and patches. */
    Instant now = Instant.EPOCH;
    boolean failList;
    int inserts;
    int deletes;
    boolean failAuth;
    boolean failIo;
    boolean failUnexpected;
    boolean calendarDeleted;
    int calendarsCreated;
    Runnable onInsert = () -> {
    };
    /** Runs while a rename or a calendar creation is "on the network". */
    Runnable onCalendarCall = () -> {
    };
    private int nextId;

    @Override
    public String ensureCalendar(String name) {
        check();
        onCalendarCall.run();
        if (calendarDeleted) {
            calendarDeleted = false;
            calendarsCreated++;
        }
        return "cal-" + name;
    }

    @Override
    public String insertEvent(String calendarId, CalendarEvent e) {
        check();
        if (calendarDeleted) {
            throw new CalendarNotFoundException(calendarId);
        }
        onInsert.run();
        inserts++;
        String id = "ev" + (++nextId);
        store(id, e);
        eventCalendar.put(id, calendarId);
        return id;
    }

    @Override
    public void patchEvent(String calendarId, String eventId, CalendarEvent e) {
        check();
        if (calendarDeleted) {
            throw new CalendarNotFoundException(calendarId);
        }
        if (!events.containsKey(eventId)) {
            throw new EventNotFoundException(eventId);
        }
        store(eventId, e);
    }

    @Override
    public void deleteEvent(String calendarId, String eventId) {
        check();
        deletes++;
        if (events.remove(eventId) == null) {
            throw new EventNotFoundException(eventId);
        }
    }

    @Override
    public List<RemoteEvent> listEvents(String calendarId, LocalDateTime from, LocalDateTime toExclusive) {
        check();
        if (failList) {
            throw new UncheckedIOException(new IOException("offline"));
        }
        if (calendarDeleted) {
            throw new CalendarNotFoundException(calendarId);
        }
        return events.entrySet().stream()
                .filter(e -> calendarId.equals(eventCalendar.get(e.getKey())))
                .filter(e -> !e.getValue().start().isBefore(from) && e.getValue().start().isBefore(toExclusive))
                .map(e -> new RemoteEvent(e.getKey(), colors.get(e.getKey()), updated.get(e.getKey())))
                .toList();
    }

    @Override
    public void renameCalendar(String calendarId, String name) {
        check();
        if (failRename) {
            throw new UncheckedIOException(new IOException("offline"));
        }
        if (calendarDeleted) {
            throw new CalendarNotFoundException(calendarId);
        }
        onCalendarCall.run();
        renames++;
        renamed.put(calendarId, name);
    }

    /** A colour change made in Google Calendar on the phone. */
    void phoneEdit(String eventId, String colorId, Instant at) {
        colors.put(eventId, colorId);
        updated.put(eventId, at);
    }

    private void store(String id, CalendarEvent e) {
        events.put(id, e);
        colors.put(id, e.taken() ? "10" : null);
        updated.put(id, now);
    }

    private void check() {
        if (failAuth) {
            throw new AuthRequiredException("revoked");
        }
        if (failUnexpected) {
            throw new IllegalStateException("boom");
        }
        if (failIo) {
            throw new UncheckedIOException(new IOException("offline"));
        }
    }
}
