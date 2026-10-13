package ua.medicine.sync;

/**
 * One Google account's calendar. Any call may throw {@link AuthRequiredException};
 * network problems surface as {@link java.io.UncheckedIOException}.
 */
public interface CalendarGateway {

    /** Finds or creates the calendar with this name and returns its id. */
    String ensureCalendar(String name);

    /** @throws CalendarNotFoundException if the calendar no longer exists */
    String insertEvent(String calendarId, CalendarEvent event);

    /** @throws EventNotFoundException if the event no longer exists */
    void patchEvent(String calendarId, String eventId, CalendarEvent event);

    /** @throws EventNotFoundException if the event no longer exists */
    void deleteEvent(String calendarId, String eventId);
}
