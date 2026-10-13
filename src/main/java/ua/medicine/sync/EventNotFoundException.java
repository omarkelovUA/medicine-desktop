package ua.medicine.sync;

public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(String eventId) {
        super("Calendar event not found: " + eventId);
    }
}
