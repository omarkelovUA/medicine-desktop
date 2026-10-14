package ua.medicine.ui;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.Course;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.PeriodUnit;
import ua.medicine.domain.Schedule;
import ua.medicine.ui.MedicineForm.PeriodRow;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.medicine.testutil.T.d;
import static ua.medicine.testutil.T.med;
import static ua.medicine.testutil.T.p;
import static ua.medicine.testutil.T.t;

class MedicineFormTest {

    private MedicineForm valid() {
        return new MedicineForm(1L, "Магній B6", "1 таблетка", "#2f7d5b",
                List.of(new PeriodRow(d("2026-10-13"), false, 3, PeriodUnit.MONTHS)),
                "WEEKLY", Set.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), 1, List.of(t("09:00")));
    }

    private MedicineForm with(String name, boolean ongoing, Integer amount, String frequency,
                              Set<DayOfWeek> weekdays, List<LocalTime> times) {
        var v = valid();
        return new MedicineForm(v.personId(), name, v.dosage(), v.color(),
                List.of(new PeriodRow(d("2026-10-13"), ongoing, amount, PeriodUnit.MONTHS)),
                frequency, weekdays, v.dayOfMonth(), times);
    }

    private MedicineForm withPeriods(PeriodRow... rows) {
        var v = valid();
        return new MedicineForm(v.personId(), v.name(), v.dosage(), v.color(), List.of(rows), v.frequency(),
                v.weekdays(), v.dayOfMonth(), v.times());
    }

    @Test
    void validFormHasNoErrors() {
        assertThat(valid().validate()).isEmpty();
    }

    @Test
    void blankName() {
        assertThat(with("  ", false, 3, "WEEKLY", Set.of(DayOfWeek.MONDAY), List.of(t("09:00"))).validate())
                .containsEntry("name", "error.nameRequired");
    }

    @Test
    void emptyTimes() {
        assertThat(with("X", false, 3, "WEEKLY", Set.of(DayOfWeek.MONDAY), List.of()).validate())
                .containsEntry("times", "error.timeRequired");
    }

    @Test
    void zeroAmountWhenFixed() {
        assertThat(with("X", false, 0, "WEEKLY", Set.of(DayOfWeek.MONDAY), List.of(t("09:00"))).validate())
                .containsEntry("amount", "error.amount");
        assertThat(with("X", true, 0, "WEEKLY", Set.of(DayOfWeek.MONDAY), List.of(t("09:00"))).validate())
                .doesNotContainKey("amount");
    }

    @Test
    void weeklyNeedsWeekday() {
        assertThat(with("X", false, 3, "WEEKLY", Set.of(), List.of(t("09:00"))).validate())
                .containsEntry("weekdays", "error.weekdayRequired");
        assertThat(with("X", false, 3, "DAILY", Set.of(), List.of(t("09:00"))).validate()).isEmpty();
    }

    @Test
    void toMedicine() {
        var m = valid().toMedicine(null, true);
        assertThat(m.schedule()).isEqualTo(new Schedule.Weekly(Set.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
                List.of(t("09:00"))));
        assertThat(m.lastPeriod().course()).isEqualTo(new Course.Fixed(3, PeriodUnit.MONTHS));
        assertThat(m.name()).isEqualTo("Магній B6");
    }

    @Test
    void nameAndDosageAreTrimmed() {
        var m = with("  Вітамін D ", true, null, "DAILY", Set.of(), List.of(t("08:00"))).toMedicine(null, true);
        assertThat(m.name()).isEqualTo("Вітамін D");
        assertThat(m.lastPeriod().course()).isEqualTo(new Course.Ongoing());
    }

    @Test
    void roundTrip() {
        var monthly = new Medicine(7L, 1, "Б", null, "#000000", d("2026-10-13"), new Course.Ongoing(),
                new Schedule.Monthly(31, List.of(t("09:00"), t("21:00"))), true);
        assertThat(MedicineForm.from(monthly).toMedicine(monthly.id(), true)).isEqualTo(monthly);
        var weekly = valid().toMedicine(5L, true);
        assertThat(MedicineForm.from(weekly).toMedicine(5L, true)).isEqualTo(weekly);
    }

    @Test
    void fromAndBackKeepsAllPeriods() {
        var m = med(List.of(p("2026-01-01", new Course.Fixed(30, PeriodUnit.DAYS)), p("2026-08-01", new Course.Ongoing())))
                .withId(3);
        assertThat(MedicineForm.from(m).toMedicine(m.id(), true)).isEqualTo(m);
    }

    @Test
    void overlapError() {
        var form = withPeriods(new PeriodRow(d("2026-01-01"), false, 30, PeriodUnit.DAYS),
                new PeriodRow(d("2026-01-20"), false, 5, PeriodUnit.DAYS));
        assertThat(form.validate()).containsEntry("periods", "error.periodsOverlap");
    }

    @Test
    void unsortedRowsAreSortedNotErrors() {
        var p1 = new PeriodRow(d("2026-01-01"), false, 30, PeriodUnit.DAYS);
        var p2 = new PeriodRow(d("2026-08-01"), false, 30, PeriodUnit.DAYS);
        var form = withPeriods(p2, p1);
        assertThat(form.validate()).isEmpty();
        assertThat(form.toMedicine(null, true).periods()).containsExactly(p1.toPeriod(), p2.toPeriod());
    }

    @Test
    void ongoingNotLastError() {
        var form = withPeriods(new PeriodRow(d("2026-01-01"), true, null, PeriodUnit.DAYS),
                new PeriodRow(d("2026-08-01"), false, 30, PeriodUnit.DAYS));
        assertThat(form.validate()).containsEntry("periods", "error.ongoingNotLast");
    }

    @Test
    void nextPeriodStartsAfterLastEnd() {
        assertThat(MedicineForm.nextPeriod(List.of(new PeriodRow(d("2026-01-01"), false, 30, PeriodUnit.DAYS)),
                d("2026-10-14"))).isEqualTo(new PeriodRow(d("2026-01-31"), false, 30, PeriodUnit.DAYS));
    }

    @Test
    void nextPeriodWithNoRows() {
        assertThat(MedicineForm.nextPeriod(List.of(), d("2026-10-14")))
                .isEqualTo(new PeriodRow(d("2026-10-14"), false, 10, PeriodUnit.DAYS));
    }

    @Test
    void endTodayMakesFixedDays() {
        assertThat(MedicineForm.endToday(new PeriodRow(d("2026-10-01"), true, null, PeriodUnit.DAYS), d("2026-10-14")))
                .isEqualTo(new PeriodRow(d("2026-10-01"), false, 14, PeriodUnit.DAYS));
    }
}
