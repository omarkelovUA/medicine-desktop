package ua.medicine.i18n;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.Course;
import ua.medicine.domain.PeriodUnit;
import ua.medicine.domain.Schedule;
import ua.medicine.schedule.CourseProgress;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.THURSDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static ua.medicine.testutil.T.p;

class FormatsTest {

    @Test
    void dayHeader() {
        assertThat(Formats.dayHeader(LocalDate.of(2026, 10, 13))).isEqualTo("вівторок, 13 жовтня");
    }

    @Test
    void dayMonth() {
        assertThat(Formats.dayMonth(LocalDate.of(2026, 10, 22))).isEqualTo("22 жовтня");
    }

    @Test
    void dateAndTime() {
        assertThat(Formats.date(LocalDate.of(2026, 10, 13))).isEqualTo("13.10.2026");
        assertThat(Formats.time(LocalTime.of(8, 0))).isEqualTo("08:00");
    }

    @Test
    void amount() {
        assertThat(Formats.amount(3, PeriodUnit.MONTHS)).isEqualTo("3 місяці");
        assertThat(Formats.amount(1, PeriodUnit.WEEKS)).isEqualTo("1 тиждень");
        assertThat(Formats.amount(5, PeriodUnit.YEARS)).isEqualTo("5 років");
        assertThat(Formats.amount(10, PeriodUnit.DAYS)).isEqualTo("10 днів");
    }

    @Test
    void progress() {
        var end = LocalDate.of(2026, 10, 22);
        assertThat(Formats.progress(new CourseProgress(4, 10, PeriodUnit.DAYS, end))).isEqualTo("День 4 з 10");
        assertThat(Formats.progress(new CourseProgress(3, 4, PeriodUnit.WEEKS, end))).isEqualTo("Тиждень 3 з 4");
        assertThat(Formats.progress(new CourseProgress(1, 3, PeriodUnit.MONTHS, end))).isEqualTo("Місяць 1 з 3");
        assertThat(Formats.progress(new CourseProgress(1, 2, PeriodUnit.YEARS, end))).isEqualTo("Рік 1 з 2");
    }

    @Test
    void weeklySummary() {
        assertThat(Formats.scheduleSummary(new Schedule.Weekly(Set.of(THURSDAY, MONDAY), List.of(LocalTime.NOON))))
                .isEqualTo("пн, чт");
    }

    @Test
    void dailySummary() {
        assertThat(Formats.scheduleSummary(new Schedule.Daily(
                List.of(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(20, 0))))).isEqualTo("3×/день");
        assertThat(Formats.scheduleSummary(new Schedule.Daily(List.of(LocalTime.of(8, 0))))).isEqualTo("щодня");
    }

    @Test
    void monthlySummary() {
        assertThat(Formats.scheduleSummary(new Schedule.Monthly(1, List.of(LocalTime.NOON)))).isEqualTo("щомісяця 1-го");
    }

    @Test
    void periodRangeSameYear() {
        assertThat(Formats.periodRange(p("2026-01-01", new Course.Fixed(30, PeriodUnit.DAYS)))).isEqualTo("01.01–30.01.2026");
    }

    @Test
    void periodRangeAcrossYears() {
        assertThat(Formats.periodRange(p("2025-12-15", new Course.Fixed(31, PeriodUnit.DAYS))))
                .isEqualTo("15.12.2025–14.01.2026");
    }

    @Test
    void periodRangeOngoing() {
        assertThat(Formats.periodRange(p("2026-08-01", new Course.Ongoing()))).isEqualTo("01.08.2026–…");
    }

    @Test
    void periodRangesJoined() {
        assertThat(Formats.periodRanges(List.of(p("2026-01-01", new Course.Fixed(30, PeriodUnit.DAYS)),
                p("2026-08-01", new Course.Ongoing())))).isEqualTo("01.01–30.01.2026 · 01.08.2026–…");
    }
}
