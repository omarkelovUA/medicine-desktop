package ua.medicine.sync;

import ua.medicine.domain.DoseOccurrence;
import ua.medicine.domain.SyncState;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Decides what a dose's event colour, changed on the phone, means for the dose. A green event is a taken dose.
 * A {@code CLEAN} dose's event holds what the app last pushed, so any difference was made on the phone; it counts
 * only if made on the dose's own day, otherwise the event is restored.
 */
final class RemoteMarks {

    private static final String BASIL = "10";
    private static final String SAGE = "2";

    sealed interface Decision permits None, Take, Untake, Restore {
    }

    record None() implements Decision {
    }

    record Take(LocalDateTime at) implements Decision {
    }

    record Untake() implements Decision {
    }

    record Restore() implements Decision {
    }

    private RemoteMarks() {
    }

    static boolean isGreen(String colorId) {
        return BASIL.equals(colorId) || SAGE.equals(colorId);
    }

    static Decision decide(DoseOccurrence dose, RemoteEvent event, ZoneId zone) {
        boolean taken = dose.takenAt() != null;
        if (dose.syncState() != SyncState.CLEAN || taken == isGreen(event.colorId())) {
            return new None();
        }
        LocalDateTime editedAt = LocalDateTime.ofInstant(event.updated(), zone);
        if (!editedAt.toLocalDate().equals(dose.scheduledAt().toLocalDate())) {
            return new Restore();
        }
        return taken ? new Untake() : new Take(editedAt);
    }
}
