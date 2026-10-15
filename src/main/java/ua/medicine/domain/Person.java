package ua.medicine.domain;

/**
 * @param calendarHostId the person whose Google account holds this person's calendar; null when linked with their
 *                       own account or not linked
 * @param calendarName   the name the hosted calendar had at the last link or sync; null unless hosted
 */
public record Person(Long id, String name, String color, String googleEmail, String googleCalendarId,
                     Long calendarHostId, String calendarName) {

    public Person(Long id, String name, String color, String googleEmail, String googleCalendarId) {
        this(id, name, color, googleEmail, googleCalendarId, null, null);
    }

    public boolean googleLinked() {
        return googleEmail != null && googleCalendarId != null;
    }

    public boolean hosted() {
        return calendarHostId != null;
    }

    /** The person whose Google token this person's calendar is reached with. */
    public long tokenOwnerId() {
        return hosted() ? calendarHostId : id;
    }

    public Person withId(long id) {
        return new Person(id, name, color, googleEmail, googleCalendarId, calendarHostId, calendarName);
    }

    public Person withNameAndColor(String name, String color) {
        return new Person(id, name, color, googleEmail, googleCalendarId, calendarHostId, calendarName);
    }

    /** Links with the person's own account; pass nulls to unlink. */
    public Person withGoogle(String email, String calendarId) {
        return new Person(id, name, color, email, calendarId, null, null);
    }

    /** Links to a calendar in the host's account. */
    public Person withHostedGoogle(String email, String calendarId, long hostId, String calendarName) {
        return new Person(id, name, color, email, calendarId, hostId, calendarName);
    }

    /** Same link, another calendar (re-created or renamed). */
    public Person withCalendar(String calendarId, String calendarName) {
        return new Person(id, name, color, googleEmail, calendarId, calendarHostId, calendarName);
    }
}
