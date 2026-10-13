package ua.medicine.schedule;

import ua.medicine.domain.DoseOccurrence;
import ua.medicine.domain.DoseStatus;

import java.time.LocalDateTime;

public final class DoseStatusResolver {

    private DoseStatusResolver() {
    }

    public static DoseStatus statusOf(DoseOccurrence dose, LocalDateTime now) {
        if (dose.takenAt() != null) {
            return DoseStatus.TAKEN;
        }
        if (dose.missed() || dose.scheduledAt().toLocalDate().isBefore(now.toLocalDate())) {
            return DoseStatus.MISSED;
        }
        return dose.scheduledAt().isAfter(now) ? DoseStatus.PENDING : DoseStatus.OVERDUE;
    }
}
