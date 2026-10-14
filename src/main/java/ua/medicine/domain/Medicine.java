package ua.medicine.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** A medicine taken in one or more periods; periods are sorted, do not overlap, and only the last may be ongoing. */
public record Medicine(Long id, long personId, String name, String dosage, String color,
                       List<Period> periods, Schedule schedule, boolean active) {

    public Medicine {
        periods = List.copyOf(periods);
        checkPeriods(periods);
    }

    /** A medicine with a single period. */
    public Medicine(Long id, long personId, String name, String dosage, String color,
                    LocalDate start, Course course, Schedule schedule, boolean active) {
        this(id, personId, name, dosage, color, List.of(new Period(start, course)), schedule, active);
    }

    public static void checkPeriods(List<Period> periods) {
        if (periods.isEmpty()) {
            throw new IllegalArgumentException("at least one period is required");
        }
        for (int i = 1; i < periods.size(); i++) {
            Optional<LocalDate> previousEnd = periods.get(i - 1).endDate();
            if (previousEnd.isEmpty()) {
                throw new IllegalArgumentException("only the last period may be ongoing");
            }
            if (!periods.get(i).start().isAfter(previousEnd.get())) {
                throw new IllegalArgumentException("periods must be sorted and must not overlap");
            }
        }
    }

    public LocalDate firstStart() {
        return periods.getFirst().start();
    }

    public Period lastPeriod() {
        return periods.getLast();
    }

    /** Inclusive last day of the last period, empty for ongoing. */
    public Optional<LocalDate> endDate() {
        return lastPeriod().endDate();
    }

    public boolean isFinished(LocalDate today) {
        return lastPeriod().isFinished(today);
    }

    public Optional<Period> periodAt(LocalDate date) {
        return periods.stream().filter(p -> p.contains(date)).findFirst();
    }

    /** The period containing today, else the next one to start, else the last one. */
    public Period relevantPeriod(LocalDate today) {
        return periodAt(today)
                .or(() -> periods.stream().filter(p -> p.start().isAfter(today)).findFirst())
                .orElse(lastPeriod());
    }

    public Medicine withId(long id) {
        return new Medicine(id, personId, name, dosage, color, periods, schedule, active);
    }

    public Medicine withSchedule(Schedule schedule) {
        return new Medicine(id, personId, name, dosage, color, periods, schedule, active);
    }

    public Medicine withActive(boolean active) {
        return new Medicine(id, personId, name, dosage, color, periods, schedule, active);
    }

    public Medicine withPeriods(List<Period> periods) {
        return new Medicine(id, personId, name, dosage, color, periods, schedule, active);
    }
}
