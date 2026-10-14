package ua.medicine.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.medicine.domain.PeriodUnit.DAYS;
import static ua.medicine.testutil.T.d;
import static ua.medicine.testutil.T.p;

class PeriodTest {

    private final Period january = p("2026-01-01", new Course.Fixed(30, DAYS));

    @Test
    void fixedEndDate() {
        assertThat(january.endDate()).contains(d("2026-01-30"));
    }

    @Test
    void ongoingHasNoEnd() {
        assertThat(p("2026-01-01", new Course.Ongoing()).endDate()).isEmpty();
    }

    @Test
    void containsIsInclusive() {
        assertThat(january.contains(d("2026-01-01"))).isTrue();
        assertThat(january.contains(d("2026-01-30"))).isTrue();
        assertThat(january.contains(d("2026-01-31"))).isFalse();
        assertThat(january.contains(d("2025-12-31"))).isFalse();
    }

    @Test
    void isFinishedAfterEnd() {
        assertThat(january.isFinished(d("2026-01-30"))).isFalse();
        assertThat(january.isFinished(d("2026-01-31"))).isTrue();
    }
}
