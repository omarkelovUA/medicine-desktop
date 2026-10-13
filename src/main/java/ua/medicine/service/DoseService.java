package ua.medicine.service;

import ua.medicine.domain.DoseOccurrence;
import ua.medicine.domain.DoseStatus;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.SyncState;
import ua.medicine.persistence.DoseRepository;
import ua.medicine.persistence.MedicineRepository;
import ua.medicine.schedule.CurrentPeriod;
import ua.medicine.schedule.DoseStatusResolver;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Ticking doses off and working out their statuses. */
public final class DoseService {

    private final DoseRepository doses;
    private final MedicineRepository medicines;
    private final Clock clock;
    private final SyncTrigger syncTrigger;

    public DoseService(DoseRepository doses, MedicineRepository medicines, Clock clock, SyncTrigger syncTrigger) {
        this.doses = doses;
        this.medicines = medicines;
        this.clock = clock;
        this.syncTrigger = syncTrigger;
    }

    public DoseOccurrence markTaken(long doseId) {
        DoseOccurrence dose = find(doseId);
        if (dose.takenAt() != null) {
            return dose;
        }
        DoseOccurrence taken = doses.update(
                dose.withTakenAt(LocalDateTime.now(clock)).withMissed(false).withSyncState(SyncState.DIRTY));
        syncTrigger.requestSync();
        return taken;
    }

    /** Clears "taken"; allowed only on the dose's own day. */
    public DoseOccurrence unmarkTaken(long doseId) {
        DoseOccurrence dose = find(doseId);
        if (!dose.scheduledAt().toLocalDate().equals(LocalDate.now(clock))) {
            throw new IllegalStateException("A dose can be unticked only on its own day");
        }
        DoseOccurrence untaken = doses.update(dose.withTakenAt(null).withSyncState(SyncState.DIRTY));
        syncTrigger.requestSync();
        return untaken;
    }

    /** Persists MISSED for not-taken doses of past days. */
    public int resolveMissed() {
        List<DoseOccurrence> unresolved = doses.findUnresolvedBefore(LocalDate.now(clock).atStartOfDay());
        unresolved.forEach(d -> doses.update(d.withMissed(true)));
        return unresolved.size();
    }

    /** Doses a dashboard card shows: today, this week or this month depending on the schedule. */
    public List<DoseView> currentPeriodDoses(Medicine m) {
        LocalDateTime now = LocalDateTime.now(clock);
        var period = CurrentPeriod.of(m.schedule(), now.toLocalDate());
        return doses.findByMedicineBetween(m.id(), period.from().atStartOfDay(), period.to().plusDays(1).atStartOfDay())
                .stream()
                .map(d -> new DoseView(d, DoseStatusResolver.statusOf(d, now)))
                .toList();
    }

    public int overdueCount(long personId) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        int count = 0;
        for (Medicine m : medicines.findActiveByPerson(personId)) {
            count += (int) doses.findByMedicineBetween(m.id(), today.atStartOfDay(), today.plusDays(1).atStartOfDay())
                    .stream()
                    .filter(d -> DoseStatusResolver.statusOf(d, now) == DoseStatus.OVERDUE)
                    .count();
        }
        return count;
    }

    private DoseOccurrence find(long doseId) {
        return doses.findById(doseId).orElseThrow(() -> new IllegalArgumentException("No dose " + doseId));
    }
}
