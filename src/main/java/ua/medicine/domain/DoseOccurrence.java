package ua.medicine.domain;

import java.time.LocalDateTime;

/** One concrete scheduled dose (one checkbox). */
public record DoseOccurrence(Long id, long medicineId, LocalDateTime scheduledAt, LocalDateTime takenAt,
                             boolean missed, String calendarEventId, SyncState syncState) {

    public static DoseOccurrence planned(long medicineId, LocalDateTime at) {
        return new DoseOccurrence(null, medicineId, at, null, false, null, SyncState.DIRTY);
    }

    public DoseOccurrence withId(long id) {
        return new DoseOccurrence(id, medicineId, scheduledAt, takenAt, missed, calendarEventId, syncState);
    }

    public DoseOccurrence withTakenAt(LocalDateTime takenAt) {
        return new DoseOccurrence(id, medicineId, scheduledAt, takenAt, missed, calendarEventId, syncState);
    }

    public DoseOccurrence withMissed(boolean missed) {
        return new DoseOccurrence(id, medicineId, scheduledAt, takenAt, missed, calendarEventId, syncState);
    }

    public DoseOccurrence withCalendarEventId(String calendarEventId) {
        return new DoseOccurrence(id, medicineId, scheduledAt, takenAt, missed, calendarEventId, syncState);
    }

    public DoseOccurrence withSyncState(SyncState syncState) {
        return new DoseOccurrence(id, medicineId, scheduledAt, takenAt, missed, calendarEventId, syncState);
    }
}
