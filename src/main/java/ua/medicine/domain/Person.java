package ua.medicine.domain;

public record Person(Long id, String name, String color, String googleEmail, String googleCalendarId) {

    public boolean googleLinked() {
        return googleEmail != null && googleCalendarId != null;
    }

    public Person withId(long id) {
        return new Person(id, name, color, googleEmail, googleCalendarId);
    }

    /** Pass nulls to unlink. */
    public Person withGoogle(String email, String calendarId) {
        return new Person(id, name, color, email, calendarId);
    }
}
