package ua.medicine.schedule;

import org.junit.jupiter.api.Test;
import ua.medicine.schedule.TimeScale.Column;
import ua.medicine.schedule.TimeScale.Zoom;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TimeScaleTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 10, 13); // Tuesday

    @Test
    void daysShowsTheAnchorMonth() {
        var s = TimeScale.around(Zoom.DAYS, ANCHOR);
        assertThat(s.start()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(s.endExclusive()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(s.columns()).hasSize(31);
        assertThat(s.next().columns()).hasSize(30); // November
    }

    @Test
    void weeksStartOnMondayTwoWeeksBack() {
        var s = TimeScale.around(Zoom.WEEKS, ANCHOR);
        assertThat(s.start()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(s.endExclusive()).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(s.columns()).hasSize(13)
                .first().isEqualTo(new Column(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 5)));
    }

    @Test
    void monthsQuartersYears() {
        assertThat(TimeScale.around(Zoom.MONTHS, ANCHOR).start()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(TimeScale.around(Zoom.MONTHS, ANCHOR).columns()).hasSize(12);
        assertThat(TimeScale.around(Zoom.QUARTERS, ANCHOR).start()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(TimeScale.around(Zoom.QUARTERS, ANCHOR).endExclusive()).isEqualTo(LocalDate.of(2028, 7, 1));
        assertThat(TimeScale.around(Zoom.QUARTERS, ANCHOR).columns()).hasSize(8);
        assertThat(TimeScale.around(Zoom.YEARS, ANCHOR).start()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(TimeScale.around(Zoom.YEARS, ANCHOR).columns()).hasSize(5);
    }

    @Test
    void quarterOfJanuaryAnchorGoesToPreviousYear() {
        assertThat(TimeScale.around(Zoom.QUARTERS, LocalDate.of(2026, 1, 15)).start())
                .isEqualTo(LocalDate.of(2025, 10, 1));
    }

    @Test
    void nextAndPreviousRoundTrip() {
        for (Zoom z : Zoom.values()) {
            var s = TimeScale.around(z, ANCHOR);
            assertThat(s.next().start()).as(z.name()).isEqualTo(s.endExclusive());
            assertThat(s.next().previous().start()).as(z.name()).isEqualTo(s.start());
        }
    }

    @Test
    void fractionIsProportionalToDaysAndClamped() {
        var s = TimeScale.around(Zoom.DAYS, ANCHOR); // 31 days
        assertThat(s.fraction(LocalDate.of(2026, 10, 1))).isZero();
        assertThat(s.fraction(LocalDate.of(2026, 11, 1))).isEqualTo(1.0);
        assertThat(s.fraction(LocalDate.of(2026, 10, 2))).isCloseTo(1 / 31.0, within(1e-9));
        assertThat(s.fraction(LocalDate.of(2020, 1, 1))).isZero();
        assertThat(s.fraction(LocalDate.of(2030, 1, 1))).isEqualTo(1.0);
    }

    @Test
    void containsIsHalfOpen() {
        var s = TimeScale.around(Zoom.DAYS, ANCHOR);
        assertThat(s.contains(LocalDate.of(2026, 10, 1))).isTrue();
        assertThat(s.contains(LocalDate.of(2026, 11, 1))).isFalse();
    }
}
