package ua.medicine.ui;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.Course;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.PeriodUnit;
import ua.medicine.domain.Schedule;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.medicine.testutil.T.d;
import static ua.medicine.testutil.T.t;

class MedicineFormTest {

    private MedicineForm valid() {
        return new MedicineForm(1L, "Магній B6", "1 таблетка", "#2f7d5b", d("2026-10-13"), false, 3,
                PeriodUnit.MONTHS, "WEEKLY", Set.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), 1, List.of(t("09:00")));
    }

    private MedicineForm with(String name, boolean ongoing, Integer amount, String frequency,
                              Set<DayOfWeek> weekdays, List<LocalTime> times) {
        var v = valid();
        return new MedicineForm(v.personId(), name, v.dosage(), v.color(), v.start(), ongoing, amount, v.unit(),
                frequency, weekdays, v.dayOfMonth(), times);
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
        assertThat(m.course()).isEqualTo(new Course.Fixed(3, PeriodUnit.MONTHS));
        assertThat(m.name()).isEqualTo("Магній B6");
    }

    @Test
    void nameAndDosageAreTrimmed() {
        var m = with("  Вітамін D ", true, null, "DAILY", Set.of(), List.of(t("08:00"))).toMedicine(null, true);
        assertThat(m.name()).isEqualTo("Вітамін D");
        assertThat(m.course()).isEqualTo(new Course.Ongoing());
    }

    @Test
    void roundTrip() {
        var monthly = new Medicine(7L, 1, "Б", null, "#000000", d("2026-10-13"), new Course.Ongoing(),
                new Schedule.Monthly(31, List.of(t("09:00"), t("21:00"))), true);
        assertThat(MedicineForm.from(monthly).toMedicine(monthly.id(), true)).isEqualTo(monthly);
        var weekly = valid().toMedicine(5L, true);
        assertThat(MedicineForm.from(weekly).toMedicine(5L, true)).isEqualTo(weekly);
    }
}
