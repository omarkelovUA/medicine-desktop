package ua.medicine.schedule;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.DoseOccurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.medicine.domain.DoseStatus.MISSED;
import static ua.medicine.domain.DoseStatus.OVERDUE;
import static ua.medicine.domain.DoseStatus.PENDING;
import static ua.medicine.domain.DoseStatus.TAKEN;
import static ua.medicine.schedule.DoseStatusResolver.statusOf;
import static ua.medicine.testutil.T.dt;

class DoseStatusResolverTest {

    private final DoseOccurrence dose = DoseOccurrence.planned(1, dt("2026-10-13T09:00"));

    @Test
    void pendingBeforeTime() {
        assertThat(statusOf(dose, dt("2026-10-13T08:59"))).isEqualTo(PENDING);
    }

    @Test
    void overdueAfterTimeSameDay() {
        assertThat(statusOf(dose, dt("2026-10-13T09:00"))).isEqualTo(OVERDUE);
    }

    @Test
    void overdueBecomesMissedAfterMidnight() {
        assertThat(statusOf(dose, dt("2026-10-14T00:00"))).isEqualTo(MISSED);
    }

    @Test
    void takenWins() {
        assertThat(statusOf(dose.withTakenAt(dt("2026-10-13T09:05")), dt("2026-10-20T00:00"))).isEqualTo(TAKEN);
    }

    @Test
    void persistedMissed() {
        assertThat(statusOf(dose.withMissed(true), dt("2026-10-13T10:00"))).isEqualTo(MISSED);
    }
}
