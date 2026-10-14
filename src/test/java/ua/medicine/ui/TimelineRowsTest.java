package ua.medicine.ui;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.Course;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.Period;
import ua.medicine.domain.PeriodUnit;
import ua.medicine.domain.Person;
import ua.medicine.domain.Schedule;
import ua.medicine.schedule.TimeScale;
import ua.medicine.schedule.TimeScale.Zoom;
import ua.medicine.ui.TimelineRows.MedicineRow;
import ua.medicine.ui.TimelineRows.PersonHeader;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineRowsTest {

    private static final TimeScale OCTOBER = TimeScale.around(Zoom.DAYS, LocalDate.of(2026, 10, 13));
    private static final Person A = new Person(1L, "Олександр", "#3a7bd5", null, null);
    private static final Person B = new Person(2L, "Марія", "#c0397b", null, null);
    private static final Person C = new Person(3L, "Софія", "#e08a1e", null, null);

    private static Medicine med(long personId, String name, LocalDate start, Course course) {
        return new Medicine((long) name.hashCode(), personId, name, "", "#3a7bd5", start, course,
                new Schedule.Daily(List.of(LocalTime.of(8, 0))), true);
    }

    private static Course days(int n) {
        return new Course.Fixed(n, PeriodUnit.DAYS);
    }

    private static List<String> names(List<TimelineRows.Row> rows) {
        return rows.stream().map(r -> switch (r) {
            case PersonHeader h -> "# " + h.person().name();
            case MedicineRow m -> m.medicine().name();
        }).toList();
    }

    @Test
    void endsOnRangeStartIsShown() {
        var m = med(1, "x", LocalDate.of(2026, 9, 22), days(10)); // last day 1 Oct
        assertThat(names(TimelineRows.build(List.of(A), List.of(m), false, OCTOBER))).containsExactly("x");
    }

    @Test
    void endsBeforeRangeIsHidden() {
        var m = med(1, "x", LocalDate.of(2026, 9, 21), days(10)); // last day 30 Sep
        assertThat(TimelineRows.build(List.of(A), List.of(m), false, OCTOBER)).isEmpty();
    }

    @Test
    void startsOnEndExclusiveIsHidden() {
        var m = med(1, "x", LocalDate.of(2026, 11, 1), days(5));
        assertThat(TimelineRows.build(List.of(A), List.of(m), false, OCTOBER)).isEmpty();
    }

    @Test
    void ongoingStartedYearsAgoIsShown() {
        var m = med(1, "x", LocalDate.of(2019, 3, 1), new Course.Ongoing());
        assertThat(names(TimelineRows.build(List.of(A), List.of(m), false, OCTOBER))).containsExactly("x");
    }

    @Test
    void sortedByStartThenName() {
        var late = med(1, "a-late", LocalDate.of(2026, 10, 20), days(3));
        var earlyB = med(1, "b-early", LocalDate.of(2026, 10, 2), days(3));
        var earlyA = med(1, "a-early", LocalDate.of(2026, 10, 2), days(3));
        assertThat(names(TimelineRows.build(List.of(A), List.of(late, earlyB, earlyA), false, OCTOBER)))
                .containsExactly("a-early", "b-early", "a-late");
    }

    @Test
    void groupedEmitsHeaderPerPersonAndSkipsEmptyPersons() {
        var a1 = med(1, "A1", LocalDate.of(2026, 10, 2), days(3));
        var a2 = med(1, "A2", LocalDate.of(2026, 10, 5), days(3));
        var bOld = med(2, "B-old", LocalDate.of(2025, 1, 1), days(3));
        var c1 = med(3, "C1", LocalDate.of(2026, 10, 1), days(3));
        assertThat(names(TimelineRows.build(List.of(A, B, C), List.of(c1, a2, bOld, a1), true, OCTOBER)))
                .containsExactly("# Олександр", "A1", "A2", "# Софія", "C1");
    }

    @Test
    void ungroupedHasNoHeaders() {
        var a1 = med(1, "A1", LocalDate.of(2026, 10, 2), days(3));
        var c1 = med(3, "C1", LocalDate.of(2026, 10, 1), days(3));
        assertThat(names(TimelineRows.build(List.of(A, C), List.of(a1, c1), false, OCTOBER)))
                .containsExactly("C1", "A1");
    }

    private static Medicine medPeriods(String name, Period... periods) {
        return med(1, name, periods[0].start(), periods[0].course()).withPeriods(List.of(periods));
    }

    @Test
    void visiblePeriodsClipsToRange() {
        var m = medPeriods("x", new Period(LocalDate.of(2026, 9, 1), days(10)),
                new Period(LocalDate.of(2026, 10, 5), days(3)), new Period(LocalDate.of(2026, 10, 20), days(30)));
        assertThat(TimelineRows.visiblePeriods(m, OCTOBER)).containsExactly(m.periods().get(1), m.periods().get(2));
    }

    @Test
    void periodsOnlyAroundRangeHidden() {
        var m = medPeriods("x", new Period(LocalDate.of(2026, 9, 1), days(10)),
                new Period(LocalDate.of(2026, 12, 1), days(5)));
        assertThat(TimelineRows.build(List.of(A), List.of(m), false, OCTOBER)).isEmpty();
    }

    @Test
    void orderByFirstVisibleStart() {
        var a = medPeriods("A", new Period(LocalDate.of(2026, 1, 1), days(10)),
                new Period(LocalDate.of(2026, 10, 20), days(5)));
        var b = medPeriods("B", new Period(LocalDate.of(2026, 10, 10), days(5)));
        assertThat(names(TimelineRows.build(List.of(A), List.of(a, b), false, OCTOBER))).containsExactly("B", "A");
    }
}
