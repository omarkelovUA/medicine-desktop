package ua.medicine.persistence;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.Course;
import ua.medicine.domain.DoseOccurrence;
import ua.medicine.domain.Person;
import ua.medicine.domain.Schedule;
import ua.medicine.domain.SyncState;
import ua.medicine.testutil.TestDb;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.medicine.domain.DoseOccurrence.planned;
import static ua.medicine.domain.SyncState.CLEAN;
import static ua.medicine.domain.SyncState.DELETE_PENDING;
import static ua.medicine.domain.SyncState.DIRTY;
import static ua.medicine.testutil.T.d;
import static ua.medicine.testutil.T.dt;
import static ua.medicine.testutil.T.med;
import static ua.medicine.testutil.T.t;

class DoseRepositoryTest {

    private final Database db = TestDb.create();
    private final DoseRepository doses = new DoseRepository(db);
    private final long pid = new PersonRepository(db).save(new Person(null, "Марія", "#c0397b", null, null)).id();
    private final long mid = new MedicineRepository(db).save(
            med(pid, d("2026-10-13"), new Course.Ongoing(), new Schedule.Daily(List.of(t("08:00"))))).id();

    private List<DoseOccurrence> day13() {
        return doses.findByMedicineBetween(mid, dt("2026-10-13T00:00"), dt("2026-10-14T00:00"));
    }

    @Test
    void insertAllIgnoresDuplicates() {
        doses.insertAll(List.of(planned(mid, dt("2026-10-13T08:00")), planned(mid, dt("2026-10-13T08:00"))));
        assertThat(day13()).hasSize(1);
        assertThat(day13().getFirst().id()).isNotNull();
        assertThat(day13().getFirst().syncState()).isEqualTo(DIRTY);
    }

    @Test
    void updateAndFindById() {
        doses.insertAll(List.of(planned(mid, dt("2026-10-13T08:00"))));
        var d = day13().getFirst();
        var updated = d.withTakenAt(dt("2026-10-13T08:05")).withCalendarEventId("ev").withSyncState(CLEAN);
        doses.update(updated);
        assertThat(doses.findById(d.id())).contains(updated);
    }

    @Test
    void deletePendingHiddenFromFindersButVisibleToSync() {
        doses.insertAll(List.of(planned(mid, dt("2026-10-13T08:00"))));
        var d = day13().getFirst();
        doses.update(d.withSyncState(DELETE_PENDING).withCalendarEventId("ev1"));
        assertThat(doses.findBetween(dt("2026-10-13T00:00"), dt("2026-10-14T00:00"))).isEmpty();
        assertThat(day13()).isEmpty();
        assertThat(doses.findNeedingSync(pid)).extracting(DoseOccurrence::calendarEventId).containsExactly("ev1");
    }

    @Test
    void unresolvedBefore() {
        doses.insertAll(List.of(planned(mid, dt("2026-10-10T08:00")), planned(mid, dt("2026-10-11T08:00")),
                planned(mid, dt("2026-10-12T08:00")), planned(mid, dt("2026-10-14T08:00"))));
        var all = doses.findBetween(dt("2026-10-01T00:00"), dt("2026-11-01T00:00"));
        doses.update(all.get(0).withTakenAt(dt("2026-10-10T08:01")));
        doses.update(all.get(1).withMissed(true));
        assertThat(doses.findUnresolvedBefore(dt("2026-10-13T00:00")))
                .extracting(DoseOccurrence::scheduledAt).containsExactly(dt("2026-10-12T08:00"));
    }

    @Test
    void futureUntaken() {
        doses.insertAll(List.of(planned(mid, dt("2026-10-13T08:00")), planned(mid, dt("2026-10-14T08:00")),
                planned(mid, dt("2026-10-15T08:00"))));
        var tomorrow = doses.findBetween(dt("2026-10-14T00:00"), dt("2026-10-15T00:00")).getFirst();
        doses.update(tomorrow.withTakenAt(dt("2026-10-13T12:00")));
        assertThat(doses.findFutureUntaken(mid, dt("2026-10-13T12:00")))
                .extracting(DoseOccurrence::scheduledAt).containsExactly(dt("2026-10-15T08:00"));
    }

    @Test
    void maxScheduledAt() {
        assertThat(doses.maxScheduledAt(mid)).isEmpty();
        doses.insertAll(List.of(planned(mid, dt("2026-10-13T08:00")), planned(mid, dt("2026-10-20T08:00"))));
        assertThat(doses.maxScheduledAt(mid)).contains(dt("2026-10-20T08:00"));
    }

    @Test
    void recordPushedKeepsTickMadeDuringPush() {
        doses.insertAll(List.of(planned(mid, dt("2026-10-13T08:00"))));
        var pushed = day13().getFirst(); // DIRTY, not taken
        doses.markTaken(pushed.id(), dt("2026-10-13T08:03"));
        doses.recordPushed(pushed.id(), "ev", pushed.takenAt(), pushed.syncState());
        var after = doses.findById(pushed.id()).orElseThrow();
        assertThat(after.takenAt()).isEqualTo(dt("2026-10-13T08:03"));
        assertThat(after.syncState()).isEqualTo(DIRTY);
        assertThat(after.calendarEventId()).isEqualTo("ev");
    }

    @Test
    void recordPushedMarksCleanWhenUnchanged() {
        doses.insertAll(List.of(planned(mid, dt("2026-10-13T08:00"))));
        var pushed = day13().getFirst();
        doses.recordPushed(pushed.id(), "ev", pushed.takenAt(), pushed.syncState());
        assertThat(doses.findById(pushed.id()).orElseThrow().syncState()).isEqualTo(CLEAN);
    }

    @Test
    void markTakenAndMissedKeepEventId() {
        doses.insertAll(List.of(planned(mid, dt("2026-10-13T08:00")), planned(mid, dt("2026-10-12T08:00"))));
        var today = day13().getFirst();
        doses.recordPushed(today.id(), "ev", null, DIRTY);
        doses.markTaken(today.id(), dt("2026-10-13T08:03"));
        assertThat(doses.findById(today.id()).orElseThrow().calendarEventId()).isEqualTo("ev");
        assertThat(doses.markMissedBefore(dt("2026-10-13T00:00"))).isEqualTo(1);
        doses.unmarkTaken(today.id());
        var after = doses.findById(today.id()).orElseThrow();
        assertThat(after.takenAt()).isNull();
        assertThat(after.calendarEventId()).isEqualTo("ev");
        assertThat(after.syncState()).isEqualTo(DIRTY);
    }

    @Test
    void markTakenDoesNotOverwriteEarlierTick() {
        doses.insertAll(List.of(planned(mid, dt("2026-10-13T08:00"))));
        var d = day13().getFirst();
        doses.markTaken(d.id(), dt("2026-10-13T08:03"));
        doses.markTaken(d.id(), dt("2026-10-13T09:00"));
        assertThat(doses.findById(d.id()).orElseThrow().takenAt()).isEqualTo(dt("2026-10-13T08:03"));
    }

    @Test
    void markDirtyForPersonAndDelete() {
        doses.insertAll(List.of(planned(mid, dt("2026-10-13T08:00"))));
        var d = day13().getFirst();
        doses.update(d.withSyncState(CLEAN));
        assertThat(doses.findNeedingSync(pid)).isEmpty();
        doses.markDirtyForPerson(pid);
        assertThat(doses.findNeedingSync(pid)).hasSize(1);
        doses.delete(d.id());
        assertThat(doses.findById(d.id())).isEmpty();
    }

    /** Inserts a dose at {@code at} and stores it with the given state. */
    private DoseOccurrence stored(long medicineId, String at, String takenAt, boolean missed, String eventId,
                                  SyncState state) {
        doses.insertAll(List.of(planned(medicineId, dt(at))));
        var d = doses.findByMedicineBetween(medicineId, dt(at), dt(at).plusMinutes(1)).getFirst();
        return doses.update(new DoseOccurrence(d.id(), medicineId, dt(at), takenAt == null ? null : dt(takenAt),
                missed, eventId, state));
    }

    @Test
    void findWithEventForPersonSkipsUnpushedDeletedAndOtherPersons() {
        long otherPid = new PersonRepository(db).save(new Person(null, "Олександр", "#3a7bd5", null, null)).id();
        long otherMid = new MedicineRepository(db).save(
                med(otherPid, d("2026-10-13"), new Course.Ongoing(), new Schedule.Daily(List.of(t("08:00"))))).id();
        var pushed = stored(mid, "2026-10-13T08:00", null, false, "ev1", CLEAN);
        stored(mid, "2026-10-13T09:00", null, false, null, DIRTY);
        stored(mid, "2026-10-13T10:00", null, false, "ev3", DELETE_PENDING);
        stored(mid, "2026-10-14T08:00", null, false, "ev4", CLEAN);
        stored(otherMid, "2026-10-13T08:00", null, false, "ev5", CLEAN);

        assertThat(doses.findWithEventForPerson(pid, dt("2026-10-13T00:00"), dt("2026-10-14T00:00")))
                .containsExactly(pushed);
    }

    @Test
    void markTakenFromPhoneOnCleanDose() {
        var d = stored(mid, "2026-10-13T08:00", null, true, "ev1", CLEAN);
        assertThat(doses.markTakenFromPhone(d.id(), dt("2026-10-13T09:10"))).isTrue();
        var after = doses.findById(d.id()).orElseThrow();
        assertThat(after.takenAt()).isEqualTo(dt("2026-10-13T09:10"));
        assertThat(after.missed()).isFalse();
        assertThat(after.syncState()).isEqualTo(DIRTY);
    }

    @Test
    void markTakenFromPhoneLeavesDirtyDose() {
        var d = stored(mid, "2026-10-13T08:00", null, false, "ev1", DIRTY);
        assertThat(doses.markTakenFromPhone(d.id(), dt("2026-10-13T09:10"))).isFalse();
        assertThat(doses.findById(d.id())).contains(d);
    }

    @Test
    void markTakenFromPhoneLeavesTakenDose() {
        var d = stored(mid, "2026-10-13T08:00", "2026-10-13T08:05", false, "ev1", CLEAN);
        assertThat(doses.markTakenFromPhone(d.id(), dt("2026-10-13T09:10"))).isFalse();
        assertThat(doses.findById(d.id())).contains(d);
    }

    @Test
    void unmarkTakenFromPhoneSetsMissed() {
        var d = stored(mid, "2026-10-13T08:00", "2026-10-13T08:05", false, "ev1", CLEAN);
        assertThat(doses.unmarkTakenFromPhone(d.id(), true)).isTrue();
        var after = doses.findById(d.id()).orElseThrow();
        assertThat(after.takenAt()).isNull();
        assertThat(after.missed()).isTrue();
        assertThat(after.syncState()).isEqualTo(DIRTY);

        var dirty = stored(mid, "2026-10-13T09:00", "2026-10-13T09:05", false, "ev2", DIRTY);
        assertThat(doses.unmarkTakenFromPhone(dirty.id(), true)).isFalse();
        assertThat(doses.findById(dirty.id())).contains(dirty);
    }

    @Test
    void markDirtyIfCleanOnlyTouchesClean() {
        var clean = stored(mid, "2026-10-13T08:00", null, false, "ev1", CLEAN);
        assertThat(doses.markDirtyIfClean(clean.id())).isTrue();
        assertThat(doses.findById(clean.id()).orElseThrow().syncState()).isEqualTo(DIRTY);

        var deleted = stored(mid, "2026-10-13T09:00", null, false, "ev2", DELETE_PENDING);
        assertThat(doses.markDirtyIfClean(deleted.id())).isFalse();
        assertThat(doses.findById(deleted.id()).orElseThrow().syncState()).isEqualTo(DELETE_PENDING);
    }
}
