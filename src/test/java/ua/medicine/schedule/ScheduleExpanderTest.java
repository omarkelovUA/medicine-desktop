package ua.medicine.schedule;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.Course;
import ua.medicine.domain.Schedule;

import java.util.List;
import java.util.Set;

import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.THURSDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static ua.medicine.domain.PeriodUnit.DAYS;
import static ua.medicine.domain.PeriodUnit.WEEKS;
import static ua.medicine.testutil.T.d;
import static ua.medicine.testutil.T.dt;
import static ua.medicine.testutil.T.med;
import static ua.medicine.testutil.T.t;

class ScheduleExpanderTest {

    @Test
    void dailyMultipleTimes() {
        var m = med(d("2026-10-13"), new Course.Fixed(2, DAYS), new Schedule.Daily(List.of(t("08:00"), t("20:00"))));
        assertThat(ScheduleExpander.expand(m, d("2026-10-01"), d("2026-10-31")))
                .containsExactly(dt("2026-10-13T08:00"), dt("2026-10-13T20:00"),
                        dt("2026-10-14T08:00"), dt("2026-10-14T20:00"));
    }

    @Test
    void weeklyOnChosenDays() { // 2026-10-13 is Tuesday
        var m = med(d("2026-10-13"), new Course.Ongoing(), new Schedule.Weekly(Set.of(MONDAY, THURSDAY), List.of(t("09:00"))));
        assertThat(ScheduleExpander.expand(m, d("2026-10-13"), d("2026-10-20")))
                .containsExactly(dt("2026-10-15T09:00"), dt("2026-10-19T09:00"));
    }

    @Test
    void monthly31stUsesLastDayOfShortMonths() {
        var m = med(d("2026-01-01"), new Course.Ongoing(), new Schedule.Monthly(31, List.of(t("09:00"))));
        assertThat(ScheduleExpander.expand(m, d("2026-01-01"), d("2026-04-30")))
                .containsExactly(dt("2026-01-31T09:00"), dt("2026-02-28T09:00"),
                        dt("2026-03-31T09:00"), dt("2026-04-30T09:00"));
    }

    @Test
    void monthly29thInLeapFebruary() {
        var m = med(d("2028-01-01"), new Course.Ongoing(), new Schedule.Monthly(30, List.of(t("09:00"))));
        assertThat(ScheduleExpander.expand(m, d("2028-02-01"), d("2028-02-29"))).containsExactly(dt("2028-02-29T09:00"));
    }

    @Test
    void ongoingLimitedOnlyByWindow() {
        var m = med(d("2026-01-01"), new Course.Ongoing(), new Schedule.Daily(List.of(t("08:00"))));
        assertThat(ScheduleExpander.expand(m, d("2026-10-13"), d("2026-10-27"))).hasSize(15);
    }

    @Test
    void nothingBeforeStartOrAfterEnd() {
        var m = med(d("2026-10-13"), new Course.Fixed(1, WEEKS), new Schedule.Daily(List.of(t("08:00"))));
        var r = ScheduleExpander.expand(m, d("2026-10-01"), d("2026-12-31"));
        assertThat(r).hasSize(7).first().isEqualTo(dt("2026-10-13T08:00"));
        assertThat(r).last().isEqualTo(dt("2026-10-19T08:00"));
    }

    @Test
    void emptyWhenFromAfterTo() {
        var m = med(d("2026-10-13"), new Course.Ongoing(), new Schedule.Daily(List.of(t("08:00"))));
        assertThat(ScheduleExpander.expand(m, d("2026-10-20"), d("2026-10-19"))).isEmpty();
    }
}
