package ua.medicine.service;

import ua.medicine.domain.DoseOccurrence;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.SyncState;
import ua.medicine.persistence.DoseRepository;
import ua.medicine.persistence.MedicineRepository;
import ua.medicine.schedule.ScheduleExpander;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Medicine CRUD plus keeping doses generated up to today + {@value #WINDOW_DAYS} days. */
public final class MedicineService {

    public static final int WINDOW_DAYS = 14;

    private final MedicineRepository medicines;
    private final DoseRepository doses;
    private final Clock clock;
    private final SyncTrigger syncTrigger;

    public MedicineService(MedicineRepository medicines, DoseRepository doses, Clock clock, SyncTrigger syncTrigger) {
        this.medicines = medicines;
        this.doses = doses;
        this.clock = clock;
        this.syncTrigger = syncTrigger;
    }

    public Medicine create(Medicine medicine) {
        Medicine saved = medicines.save(medicine);
        LocalDate today = LocalDate.now(clock);
        insert(saved, timesBetween(saved, today, today.atStartOfDay().minusNanos(1)));
        syncTrigger.requestSync();
        return saved;
    }

    /** Saves the medicine and regenerates its future, not-taken doses. Taken and past doses are kept. */
    public Medicine update(Medicine medicine) {
        Medicine saved = medicines.save(medicine);
        LocalDateTime now = LocalDateTime.now(clock);
        List<LocalDateTime> wanted = timesBetween(saved, now.toLocalDate(), now);
        Set<LocalDateTime> wantedSet = new HashSet<>(wanted);
        for (DoseOccurrence existing : doses.findFutureUntaken(saved.id(), now)) {
            if (wantedSet.contains(existing.scheduledAt())) {
                doses.update(existing.withSyncState(SyncState.DIRTY));
            } else if (existing.syncState() != SyncState.DELETE_PENDING) {
                remove(existing);
            }
        }
        insert(saved, wanted);
        syncTrigger.requestSync();
        return saved;
    }

    /** Deactivates the medicine and removes its future, not-taken doses; history stays. */
    public void delete(long medicineId) {
        medicines.findById(medicineId).ifPresent(m -> {
            medicines.save(m.withActive(false));
            doses.findFutureUntaken(medicineId, LocalDateTime.now(clock)).stream()
                    .filter(d -> d.syncState() != SyncState.DELETE_PENDING)
                    .forEach(this::remove);
        });
        syncTrigger.requestSync();
    }

    /** Generates doses up to the window end, filling any gap since the last generated dose. */
    public void extendWindows() {
        LocalDate today = LocalDate.now(clock);
        for (Medicine m : medicines.findActive()) {
            var last = doses.maxScheduledAt(m.id());
            if (last.isPresent()) {
                insert(m, timesBetween(m, last.get().toLocalDate(), last.get()));
            } else {
                insert(m, timesBetween(m, today, today.atStartOfDay().minusNanos(1)));
            }
        }
    }

    /** Dose times from {@code fromDate} through the window end that are strictly after {@code after}. */
    private List<LocalDateTime> timesBetween(Medicine m, LocalDate fromDate, LocalDateTime after) {
        LocalDate windowEnd = LocalDate.now(clock).plusDays(WINDOW_DAYS);
        return ScheduleExpander.expand(m, fromDate, windowEnd).stream()
                .filter(t -> t.isAfter(after))
                .toList();
    }

    private void insert(Medicine m, List<LocalDateTime> times) {
        if (!times.isEmpty()) {
            doses.insertAll(times.stream().map(t -> DoseOccurrence.planned(m.id(), t)).toList());
        }
    }

    private void remove(DoseOccurrence dose) {
        if (dose.calendarEventId() != null) {
            doses.update(dose.withSyncState(SyncState.DELETE_PENDING));
        } else {
            doses.delete(dose.id());
        }
    }
}
