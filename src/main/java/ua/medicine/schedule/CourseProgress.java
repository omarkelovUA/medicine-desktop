package ua.medicine.schedule;

import ua.medicine.domain.Course;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.PeriodUnit;

import java.time.LocalDate;
import java.util.Optional;

/** Position within a fixed course, e.g. day 4 of 10. */
public record CourseProgress(int current, int total, PeriodUnit unit, LocalDate endDate) {

    public static Optional<CourseProgress> of(Medicine m, LocalDate today) {
        if (!(m.course() instanceof Course.Fixed fixed)) {
            return Optional.empty();
        }
        int current = today.isBefore(m.startDate())
                ? 0
                : (int) Math.min(fixed.amount(), fixed.unit().chronoUnit().between(m.startDate(), today) + 1);
        return Optional.of(new CourseProgress(current, fixed.amount(), fixed.unit(), m.endDate().orElseThrow()));
    }

    public double fraction() {
        return (double) current / total;
    }
}
