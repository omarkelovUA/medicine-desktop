package ua.medicine.sync;

/** The person's "Ліки" calendar no longer exists (deleted in Google). */
public class CalendarNotFoundException extends RuntimeException {

    public CalendarNotFoundException(String calendarId) {
        super("Calendar not found: " + calendarId);
    }
}
