package ua.medicine.sync;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One Google account's calendar. Any call may throw {@link AuthRequiredException};
 * network problems surface as {@link java.io.UncheckedIOException}.
 */
public interface CalendarGateway {

    /** Finds or creates the calendar with this name and returns its id. */
    String ensureCalendar(String name);

    /** Creates a new calendar, even if one with this name exists, and returns its id. */
    String createCalendar(String name);

    /** Whether the calendar exists and is this account's own (not deleted, not just shared with it). */
    boolean ownsCalendar(String calendarId);

    /** @throws CalendarNotFoundException if the calendar no longer exists */
    void renameCalendar(String calendarId, String name);

    /** @throws CalendarNotFoundException if the calendar no longer exists */
    String insertEvent(String calendarId, CalendarEvent event);

    /** @throws EventNotFoundException if the event no longer exists */
    void patchEvent(String calendarId, String eventId, CalendarEvent event);

    /** @throws EventNotFoundException if the event no longer exists */
    void deleteEvent(String calendarId, String eventId);

    /**
     * Events of the calendar starting in [from, toExclusive); deleted events are not returned.
     *
     * @throws CalendarNotFoundException if the calendar no longer exists
     */
    List<RemoteEvent> listEvents(String calendarId, LocalDateTime from, LocalDateTime toExclusive);
}
