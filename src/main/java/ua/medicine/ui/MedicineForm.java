package ua.medicine.ui;

import ua.medicine.domain.Course;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.Period;
import ua.medicine.domain.PeriodUnit;
import ua.medicine.domain.Schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Values of the medicine dialog, independent of JavaFX. Frequency is DAILY, WEEKLY or MONTHLY. */
public record MedicineForm(Long personId, String name, String dosage, String color, LocalDate start,
                           boolean ongoing, Integer amount, PeriodUnit unit, String frequency,
                           Set<DayOfWeek> weekdays, Integer dayOfMonth, List<LocalTime> times) {

    public static final String DAILY = "DAILY";
    public static final String WEEKLY = "WEEKLY";
    public static final String MONTHLY = "MONTHLY";

    /** Field name → message key of the first problem with that field. */
    public Map<String, String> validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (name == null || name.isBlank()) {
            errors.put("name", "error.nameRequired");
        }
        if (!ongoing && (amount == null || amount < 1)) {
            errors.put("amount", "error.amount");
        }
        if (WEEKLY.equals(frequency) && (weekdays == null || weekdays.isEmpty())) {
            errors.put("weekdays", "error.weekdayRequired");
        }
        if (times == null || times.isEmpty()) {
            errors.put("times", "error.timeRequired");
        }
        return errors;
    }

    public Medicine toMedicine(Long id, boolean active) {
        Course course = ongoing ? new Course.Ongoing() : new Course.Fixed(amount, unit);
        Schedule schedule = switch (frequency) {
            case WEEKLY -> new Schedule.Weekly(weekdays, times);
            case MONTHLY -> new Schedule.Monthly(dayOfMonth, times);
            default -> new Schedule.Daily(times);
        };
        String cleanDosage = dosage == null || dosage.isBlank() ? null : dosage.strip();
        return new Medicine(id, personId, name.strip(), cleanDosage, color, start, course, schedule, active);
    }

    public static MedicineForm from(Medicine m) {
        Period last = m.lastPeriod();
        boolean ongoing = last.course() instanceof Course.Ongoing;
        Integer amount = last.course() instanceof Course.Fixed f ? f.amount() : null;
        PeriodUnit unit = last.course() instanceof Course.Fixed f ? f.unit() : PeriodUnit.DAYS;
        return switch (m.schedule()) {
            case Schedule.Daily d -> new MedicineForm(m.personId(), m.name(), m.dosage(), m.color(), last.start(),
                    ongoing, amount, unit, DAILY, EnumSet.noneOf(DayOfWeek.class), 1, d.times());
            case Schedule.Weekly w -> new MedicineForm(m.personId(), m.name(), m.dosage(), m.color(), last.start(),
                    ongoing, amount, unit, WEEKLY, w.days(), 1, w.times());
            case Schedule.Monthly mo -> new MedicineForm(m.personId(), m.name(), m.dosage(), m.color(),
                    last.start(), ongoing, amount, unit, MONTHLY, EnumSet.noneOf(DayOfWeek.class),
                    mo.dayOfMonth(), mo.times());
        };
    }
}
