package ua.medicine.ui;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.Course;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.PeriodUnit;
import ua.medicine.domain.Schedule;
import ua.medicine.schedule.TimeScale;
import ua.medicine.schedule.TimeScale.Column;
import ua.medicine.schedule.TimeScale.Zoom;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimeLabelsTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 10, 13);

    private static Column col(LocalDate start, LocalDate endExclusive) {
        return new Column(start, endExclusive);
    }

    private static Medicine medicine(String name, String dosage, Course course) {
        return new Medicine(1L, 1, name, dosage, "#3a7bd5", ANCHOR, course,
                new Schedule.Daily(List.of(LocalTime.of(8, 0))), true);
    }

    @Test
    void dayColumns() {
        var c = col(ANCHOR, ANCHOR.plusDays(1));
        assertThat(TimeLabels.column(Zoom.DAYS, c)).isEqualTo("13");
        assertThat(TimeLabels.columnSub(Zoom.DAYS, c, false)).isEqualTo("вт");
    }

    @Test
    void weekColumns() {
        assertThat(TimeLabels.column(Zoom.WEEKS, col(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 13))))
                .isEqualTo("6–12 жовт");
        assertThat(TimeLabels.column(Zoom.WEEKS, col(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 5))))
                .isEqualTo("28 вер – 4 жовт");
        assertThat(TimeLabels.column(Zoom.WEEKS, col(LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 5))))
                .isEqualTo("29 груд – 4 січ");
    }

    @Test
    void monthColumnsShowYearOnJanuaryAndFirst() {
        assertThat(TimeLabels.column(Zoom.MONTHS, col(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))))
                .isEqualTo("жовт");
        assertThat(TimeLabels.columnSub(Zoom.MONTHS, col(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 2, 1)), false))
                .isEqualTo("2027");
        assertThat(TimeLabels.columnSub(Zoom.MONTHS, col(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1)), false))
                .isEmpty();
        assertThat(TimeLabels.columnSub(Zoom.MONTHS, col(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1)), true))
                .isEqualTo("2026");
    }

    @Test
    void quarterAndYearColumns() {
        assertThat(TimeLabels.column(Zoom.QUARTERS, col(LocalDate.of(2026, 10, 1), LocalDate.of(2027, 1, 1))))
                .isEqualTo("IV кв. 2026");
        assertThat(TimeLabels.column(Zoom.YEARS, col(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1))))
                .isEqualTo("2026");
    }

    @Test
    void ranges() {
        assertThat(TimeLabels.range(TimeScale.around(Zoom.DAYS, ANCHOR))).isEqualTo("жовтень 2026");
        assertThat(TimeLabels.range(TimeScale.around(Zoom.WEEKS, ANCHOR))).isEqualTo("28 вер – 27 груд 2026");
        assertThat(TimeLabels.range(TimeScale.around(Zoom.MONTHS, ANCHOR))).isEqualTo("серп 2026 – лип 2027");
        assertThat(TimeLabels.range(TimeScale.around(Zoom.QUARTERS, ANCHOR))).isEqualTo("III кв. 2026 – II кв. 2028");
        assertThat(TimeLabels.range(TimeScale.around(Zoom.YEARS, ANCHOR))).isEqualTo("2025 – 2029");
    }

    @Test
    void weekRangeAcrossYearsShowsBothYears() {
        assertThat(TimeLabels.range(TimeScale.around(Zoom.WEEKS, LocalDate.of(2026, 12, 29))))
                .isEqualTo("14 груд 2026 – 14 бер 2027");
    }

    @Test
    void barLabelSkipsBlankDosage() {
        assertThat(TimeLabels.barLabel(medicine("Амоксицилін", "500 мг", new Course.Ongoing())))
                .isEqualTo("Амоксицилін · 500 мг");
        assertThat(TimeLabels.barLabel(medicine("Амоксицилін", "", new Course.Ongoing())))
                .isEqualTo("Амоксицилін");
    }

    @Test
    void tooltips() {
        assertThat(TimeLabels.tooltip(medicine("Амоксицилін", "500 мг", new Course.Fixed(10, PeriodUnit.DAYS))))
                .isEqualTo("Амоксицилін — 500 мг\n13.10.2026 – 22.10.2026");
        assertThat(TimeLabels.tooltip(medicine("Вітамін D", "2000 МО", new Course.Ongoing())))
                .isEqualTo("Вітамін D — 2000 МО\nз 13.10.2026 · Постійно");
        assertThat(TimeLabels.tooltip(medicine("Амоксицилін", " ", new Course.Ongoing())))
                .startsWith("Амоксицилін\n");
    }

    @Test
    void compactWeekColumnIsTheMondayDate() {
        assertThat(TimeLabels.columnCompact(Zoom.WEEKS, col(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 5))))
                .isEqualTo("28.09");
        assertThat(TimeLabels.columnCompact(Zoom.MONTHS, col(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))))
                .isEqualTo("жовт");
    }
}
