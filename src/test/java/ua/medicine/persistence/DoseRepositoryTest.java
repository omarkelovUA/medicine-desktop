package ua.medicine.persistence;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.Course;
import ua.medicine.domain.DoseOccurrence;
import ua.medicine.domain.Person;
import ua.medicine.domain.Schedule;
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
}
