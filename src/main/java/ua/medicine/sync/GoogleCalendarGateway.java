package ua.medicine.sync;

import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.EventReminder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Google Calendar API v3 implementation of {@link CalendarGateway}. */
public final class GoogleCalendarGateway implements CalendarGateway {

    private static final String TAKEN_COLOR_ID = "10"; // green ("Basil")

    @FunctionalInterface
    private interface ApiCall<T> {
        T call() throws IOException;
    }

    private final Calendar client;
    private final ZoneId zone = ZoneId.systemDefault();

    public GoogleCalendarGateway(Calendar client) {
        this.client = client;
    }

    /** The account's email (id of its primary calendar). */
    public String primaryEmail() {
        return call(null, null, () -> client.calendarList().get("primary").execute().getId());
    }

    @Override
    public String ensureCalendar(String name) {
        return call(null, null, () -> {
            var items = client.calendarList().list().execute().getItems();
            if (items != null) {
                for (var entry : items) {
                    if (name.equals(entry.getSummary())) {
                        return entry.getId();
                    }
                }
            }
            var calendar = new com.google.api.services.calendar.model.Calendar()
                    .setSummary(name)
                    .setTimeZone(zone.getId());
            return client.calendars().insert(calendar).execute().getId();
        });
    }

    @Override
    public void renameCalendar(String calendarId, String name) {
        call(null, calendarId, () -> client.calendars()
                .patch(calendarId, new com.google.api.services.calendar.model.Calendar().setSummary(name))
                .execute());
    }

    @Override
    public String insertEvent(String calendarId, CalendarEvent event) {
        return call(null, calendarId, () -> client.events().insert(calendarId, toEvent(event)).execute().getId());
    }

    @Override
    public void patchEvent(String calendarId, String eventId, CalendarEvent event) {
        // full update so that removing the "taken" colour also works
        call(eventId, calendarId, () -> client.events().update(calendarId, eventId, toEvent(event)).execute());
    }

    @Override
    public void deleteEvent(String calendarId, String eventId) {
        call(eventId, calendarId, () -> client.events().delete(calendarId, eventId).execute());
    }

    @Override
    public List<RemoteEvent> listEvents(String calendarId, LocalDateTime from, LocalDateTime toExclusive) {
        return call(null, calendarId, () -> {
            List<RemoteEvent> result = new ArrayList<>();
            String pageToken = null;
            do {
                var page = client.events().list(calendarId)
                        .setTimeMin(dateTime(from))
                        .setTimeMax(dateTime(toExclusive))
                        .setShowDeleted(false)
                        .setSingleEvents(true)
                        .setPageToken(pageToken)
                        .execute();
                if (page.getItems() != null) {
                    for (Event e : page.getItems()) {
                        result.add(new RemoteEvent(e.getId(), e.getColorId(),
                                Instant.ofEpochMilli(e.getUpdated().getValue())));
                    }
                }
                pageToken = page.getNextPageToken();
            } while (pageToken != null);
            return result;
        });
    }

    private Event toEvent(CalendarEvent e) {
        return new Event()
                .setSummary(e.summary())
                .setDescription(e.description())
                .setStart(at(e.start()))
                .setEnd(at(e.start().plusMinutes(e.durationMinutes())))
                .setColorId(e.taken() ? TAKEN_COLOR_ID : null)
                .setReminders(new Event.Reminders()
                        .setUseDefault(false)
                        .setOverrides(List.of(new EventReminder().setMethod("popup").setMinutes(0))));
    }

    private EventDateTime at(LocalDateTime time) {
        return new EventDateTime()
                .setDateTime(dateTime(time))
                .setTimeZone(zone.getId());
    }

    private DateTime dateTime(LocalDateTime time) {
        return new DateTime(time.atZone(zone).toInstant().toEpochMilli());
    }

    /** Maps API errors; a 404 means the event is gone, or — for calls on no event — the calendar is gone. */
    private static <T> T call(String eventId, String calendarId, ApiCall<T> apiCall) {
        try {
            return apiCall.call();
        } catch (GoogleJsonResponseException e) {
            int code = e.getStatusCode();
            if ((code == 404 || code == 410) && eventId != null) {
                throw new EventNotFoundException(eventId);
            }
            if (code == 404 && calendarId != null) {
                throw new CalendarNotFoundException(calendarId);
            }
            if (code == 401) {
                throw new AuthRequiredException(e.getMessage(), e);
            }
            throw new UncheckedIOException(e);
        } catch (TokenResponseException e) {
            throw new AuthRequiredException(e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
