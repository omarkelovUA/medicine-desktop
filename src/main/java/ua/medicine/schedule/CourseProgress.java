package ua.medicine.schedule;

import ua.medicine.domain.Course;
import ua.medicine.domain.Period;
import ua.medicine.domain.PeriodUnit;

import java.time.LocalDate;
import java.util.Optional;

/** Position within a fixed period, e.g. day 4 of 10. */
public record CourseProgress(int current, int total, PeriodUnit unit, LocalDate endDate) {

    public static Optional<CourseProgress> of(Period period, LocalDate today) {
        if (!(period.course() instanceof Course.Fixed fixed)) {
            return Optional.empty();
        }
        int current = today.isBefore(period.start())
                ? 0
                : (int) Math.min(fixed.amount(), fixed.unit().chronoUnit().between(period.start(), today) + 1);
        return Optional.of(new CourseProgress(current, fixed.amount(), fixed.unit(), period.endDate().orElseThrow()));
    }

    public double fraction() {
        return (double) current / total;
    }
}
