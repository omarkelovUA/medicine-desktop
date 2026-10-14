package ua.medicine.ui;

import ua.medicine.domain.Course;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.Period;
import ua.medicine.domain.PeriodUnit;
import ua.medicine.domain.Schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Values of the medicine dialog, independent of JavaFX. Frequency is DAILY, WEEKLY or MONTHLY. */
public record MedicineForm(Long personId, String name, String dosage, String color, List<PeriodRow> periods,
                           String frequency, Set<DayOfWeek> weekdays, Integer dayOfMonth, List<LocalTime> times) {

    /** One row of the periods section; {@code amount} and {@code unit} are ignored when ongoing. */
    public record PeriodRow(LocalDate start, boolean ongoing, Integer amount, PeriodUnit unit) {

        public Period toPeriod() {
            return new Period(start, ongoing ? new Course.Ongoing() : new Course.Fixed(amount, unit));
        }

        static PeriodRow of(Period p) {
            return p.course() instanceof Course.Fixed f
                    ? new PeriodRow(p.start(), false, f.amount(), f.unit())
                    : new PeriodRow(p.start(), true, null, PeriodUnit.DAYS);
        }
    }

    public static final String DAILY = "DAILY";
    public static final String WEEKLY = "WEEKLY";
    public static final String MONTHLY = "MONTHLY";

    /** Field name → message key of the first problem with that field. */
    public Map<String, String> validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (name == null || name.isBlank()) {
            errors.put("name", "error.nameRequired");
        }
        if (periods.stream().anyMatch(r -> !r.ongoing() && (r.amount() == null || r.amount() < 1))) {
            errors.put("amount", "error.amount");
        } else {
            periodsProblem().ifPresent(key -> errors.put("periods", key));
        }
        if (WEEKLY.equals(frequency) && (weekdays == null || weekdays.isEmpty())) {
            errors.put("weekdays", "error.weekdayRequired");
        }
        if (times == null || times.isEmpty()) {
            errors.put("times", "error.timeRequired");
        }
        return errors;
    }

    /** Same rules as {@link Medicine#checkPeriods}, as message keys; rows are taken in start order. */
    private Optional<String> periodsProblem() {
        List<Period> sorted = sortedPeriods();
        for (int i = 0; i < sorted.size() - 1; i++) {
            if (sorted.get(i).endDate().isEmpty()) {
                return Optional.of("error.ongoingNotLast");
            }
        }
        for (int i = 1; i < sorted.size(); i++) {
            if (!sorted.get(i).start().isAfter(sorted.get(i - 1).endDate().orElseThrow())) {
                return Optional.of("error.periodsOverlap");
            }
        }
        return Optional.empty();
    }

    private List<Period> sortedPeriods() {
        return periods.stream().map(PeriodRow::toPeriod).sorted(Comparator.comparing(Period::start)).toList();
    }

    /** A new row starting the day after the last row ends, with the same duration; the last row must not be ongoing. */
    public static PeriodRow nextPeriod(List<PeriodRow> rows, LocalDate today) {
        if (rows.isEmpty()) {
            return new PeriodRow(today, false, 10, PeriodUnit.DAYS);
        }
        PeriodRow last = rows.stream().max(Comparator.comparing(PeriodRow::start)).orElseThrow();
        return new PeriodRow(last.toPeriod().endDate().orElseThrow().plusDays(1), false, last.amount(), last.unit());
    }

    /** An ongoing row turned into a fixed number of days ending today. */
    public static PeriodRow endToday(PeriodRow ongoing, LocalDate today) {
        long days = ChronoUnit.DAYS.between(ongoing.start(), today) + 1;
        return new PeriodRow(ongoing.start(), false, (int) Math.max(1, days), PeriodUnit.DAYS);
    }

    public Medicine toMedicine(Long id, boolean active) {
        Schedule schedule = switch (frequency) {
            case WEEKLY -> new Schedule.Weekly(weekdays, times);
            case MONTHLY -> new Schedule.Monthly(dayOfMonth, times);
            default -> new Schedule.Daily(times);
        };
        String cleanDosage = dosage == null || dosage.isBlank() ? null : dosage.strip();
        return new Medicine(id, personId, name.strip(), cleanDosage, color, sortedPeriods(), schedule, active);
    }

    public static MedicineForm from(Medicine m) {
        List<PeriodRow> rows = m.periods().stream().map(PeriodRow::of).toList();
        return switch (m.schedule()) {
            case Schedule.Daily d -> new MedicineForm(m.personId(), m.name(), m.dosage(), m.color(), rows, DAILY,
                    EnumSet.noneOf(DayOfWeek.class), 1, d.times());
            case Schedule.Weekly w -> new MedicineForm(m.personId(), m.name(), m.dosage(), m.color(), rows, WEEKLY,
                    w.days(), 1, w.times());
            case Schedule.Monthly mo -> new MedicineForm(m.personId(), m.name(), m.dosage(), m.color(), rows, MONTHLY,
                    EnumSet.noneOf(DayOfWeek.class), mo.dayOfMonth(), mo.times());
        };
    }
}
