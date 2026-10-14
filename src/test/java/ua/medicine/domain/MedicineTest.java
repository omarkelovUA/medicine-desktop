package ua.medicine.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ua.medicine.domain.PeriodUnit.DAYS;
import static ua.medicine.testutil.T.d;
import static ua.medicine.testutil.T.med;
import static ua.medicine.testutil.T.p;

class MedicineTest {

    private static final Period P1 = p("2026-01-01", new Course.Fixed(30, DAYS));
    private static final Period P2 = p("2026-08-01", new Course.Fixed(30, DAYS));
    private static final Period P3 = p("2026-09-15", new Course.Ongoing());

    @Test
    void rejectsEmptyPeriods() {
        assertThatThrownBy(() -> med(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsorted() {
        assertThatThrownBy(() -> med(List.of(P2, P1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverlap() {
        assertThatThrownBy(() -> med(List.of(P1, p("2026-01-30", new Course.Fixed(5, DAYS)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOngoingNotLast() {
        assertThatThrownBy(() -> med(List.of(p("2026-01-01", new Course.Ongoing()), P2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adjacentPeriodsAllowed() {
        assertThat(med(List.of(P1, p("2026-01-31", new Course.Fixed(5, DAYS)))).periods()).hasSize(2);
    }

    @Test
    void ongoingLastAllowed() {
        assertThat(med(List.of(P1, P2, P3)).endDate()).isEmpty();
    }

    @Test
    void relevantPeriod() {
        var m = med(List.of(P1, P2));
        assertThat(m.relevantPeriod(d("2026-01-10"))).isEqualTo(P1);
        assertThat(m.relevantPeriod(d("2026-05-01"))).isEqualTo(P2);
        assertThat(m.relevantPeriod(d("2026-12-01"))).isEqualTo(P2);
    }

    @Test
    void isFinishedUsesLastPeriod() {
        var m = med(List.of(P1, P2));
        assertThat(m.isFinished(d("2026-05-01"))).isFalse();
        assertThat(m.isFinished(d("2026-09-01"))).isTrue();
    }

    @Test
    void periodAt() {
        var m = med(List.of(P1, P2));
        assertThat(m.periodAt(d("2026-05-01"))).isEmpty();
        assertThat(m.periodAt(d("2026-08-05"))).contains(P2);
    }

    @Test
    void firstStartAndLastPeriod() {
        var m = med(List.of(P1, P2));
        assertThat(m.firstStart()).isEqualTo(d("2026-01-01"));
        assertThat(m.lastPeriod()).isEqualTo(P2);
    }

    @Test
    void isRestingOnlyBetweenPeriods() {
        var m = med(List.of(P1, P2));
        assertThat(m.isResting(d("2026-05-01"))).isTrue();
        assertThat(m.isResting(d("2026-01-10"))).isFalse(); // in a period
        assertThat(m.isResting(d("2025-12-01"))).isFalse(); // not started yet
        assertThat(m.isResting(d("2026-12-01"))).isFalse(); // finished
    }
}
