package ua.medicine.schedule;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.Schedule;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static java.time.DayOfWeek.MONDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static ua.medicine.testutil.T.d;

class CurrentPeriodTest { // 2026-10-13 is Tuesday

    private final List<LocalTime> nine = List.of(LocalTime.of(9, 0));

    @Test
    void weekly() {
        assertThat(CurrentPeriod.of(new Schedule.Weekly(Set.of(MONDAY), nine), d("2026-10-13")))
                .isEqualTo(new DateRange(d("2026-10-12"), d("2026-10-18")));
    }

    @Test
    void monthly() {
        assertThat(CurrentPeriod.of(new Schedule.Monthly(1, nine), d("2026-02-10")))
                .isEqualTo(new DateRange(d("2026-02-01"), d("2026-02-28")));
    }

    @Test
    void daily() {
        assertThat(CurrentPeriod.of(new Schedule.Daily(nine), d("2026-10-13")))
                .isEqualTo(new DateRange(d("2026-10-13"), d("2026-10-13")));
    }
}
