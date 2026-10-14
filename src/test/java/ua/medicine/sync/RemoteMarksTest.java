package ua.medicine.sync;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.DoseOccurrence;
import ua.medicine.domain.SyncState;
import ua.medicine.sync.RemoteMarks.None;
import ua.medicine.sync.RemoteMarks.Restore;
import ua.medicine.sync.RemoteMarks.Take;
import ua.medicine.sync.RemoteMarks.Untake;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.medicine.testutil.T.dt;

class RemoteMarksTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static DoseOccurrence dose(boolean taken, SyncState state) {
        return new DoseOccurrence(1L, 1, dt("2026-10-13T08:00"), taken ? dt("2026-10-13T08:05") : null,
                false, "ev1", state);
    }

    private static RemoteEvent edited(String colorId, String isoLocal) {
        return new RemoteEvent("ev1", colorId, at(isoLocal));
    }

    private static Instant at(String isoLocal) {
        return dt(isoLocal).atZone(ZONE).toInstant();
    }

    private static RemoteMarks.Decision decide(DoseOccurrence dose, RemoteEvent event) {
        return RemoteMarks.decide(dose, event, ZONE);
    }

    @Test
    void dirtyDoseIsNeverChanged() {
        assertThat(decide(dose(false, SyncState.DIRTY), edited("10", "2026-10-13T09:00"))).isEqualTo(new None());
    }

    @Test
    void notTakenAndNotGreenIsNone() {
        assertThat(decide(dose(false, SyncState.CLEAN), edited(null, "2026-10-13T09:00"))).isEqualTo(new None());
        assertThat(decide(dose(false, SyncState.CLEAN), edited("5", "2026-10-14T09:00"))).isEqualTo(new None());
    }

    @Test
    void takenAndGreenIsNone() {
        assertThat(decide(dose(true, SyncState.CLEAN), edited("10", "2026-10-14T09:00"))).isEqualTo(new None());
        assertThat(decide(dose(true, SyncState.CLEAN), edited("2", "2026-10-13T09:00"))).isEqualTo(new None());
    }

    @Test
    void basilOnOwnDayTakes() {
        assertThat(decide(dose(false, SyncState.CLEAN), edited("10", "2026-10-13T09:15")))
                .isEqualTo(new Take(dt("2026-10-13T09:15")));
    }

    @Test
    void sageOnOwnDayTakes() {
        assertThat(decide(dose(false, SyncState.CLEAN), edited("2", "2026-10-13T09:15")))
                .isEqualTo(new Take(dt("2026-10-13T09:15")));
    }

    @Test
    void greenRemovedOnOwnDayUntakes() {
        assertThat(decide(dose(true, SyncState.CLEAN), edited(null, "2026-10-13T10:00"))).isEqualTo(new Untake());
        assertThat(decide(dose(true, SyncState.CLEAN), edited("5", "2026-10-13T10:00"))).isEqualTo(new Untake());
    }

    @Test
    void greenOnLaterDayRestores() {
        assertThat(decide(dose(false, SyncState.CLEAN), edited("10", "2026-10-14T07:00"))).isEqualTo(new Restore());
    }

    @Test
    void greenRemovedOnLaterDayRestores() {
        assertThat(decide(dose(true, SyncState.CLEAN), edited(null, "2026-10-14T07:00"))).isEqualTo(new Restore());
    }

    @Test
    void editAtOneMinuteToMidnightCounts() {
        assertThat(decide(dose(false, SyncState.CLEAN), edited("10", "2026-10-13T23:59")))
                .isEqualTo(new Take(dt("2026-10-13T23:59")));
    }

    @Test
    void editOneMinuteAfterMidnightRestores() {
        assertThat(decide(dose(false, SyncState.CLEAN), edited("10", "2026-10-14T00:01"))).isEqualTo(new Restore());
    }
}
