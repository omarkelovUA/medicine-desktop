package ua.medicine.sync;

import ua.medicine.domain.DoseOccurrence;
import ua.medicine.domain.Medicine;
import ua.medicine.i18n.Formats;

/** Builds the calendar event that represents one dose. */
public final class CalendarEvents {

    static final int DURATION_MINUTES = 15;

    private CalendarEvents() {
    }

    public static CalendarEvent from(Medicine m, DoseOccurrence d) {
        boolean taken = d.takenAt() != null;
        String title = (taken ? "✅ " : "💊 ") + m.name();
        if (m.dosage() != null && !m.dosage().isBlank()) {
            title += " — " + m.dosage().strip();
        }
        return new CalendarEvent(title, Formats.scheduleSummary(m.schedule()), d.scheduledAt(), DURATION_MINUTES, taken);
    }
}
