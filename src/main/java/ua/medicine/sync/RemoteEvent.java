package ua.medicine.sync;

import java.time.Instant;

/** What the app reads back about one calendar event: its colour and when it was last changed. */
public record RemoteEvent(String id, String colorId, Instant updated) {
}
