package ua.medicine.domain;

import java.time.LocalDate;
import java.util.Optional;

/** One taking period of a medicine: a start date and how long it lasts. */
public record Period(LocalDate start, Course course) {

    public Period {
        if (start == null || course == null) {
            throw new IllegalArgumentException("start and course are required");
        }
    }

    /** Inclusive last day, empty for ongoing. */
    public Optional<LocalDate> endDate() {
        return course.endDate(start);
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(start) && endDate().map(end -> !date.isAfter(end)).orElse(true);
    }

    public boolean isFinished(LocalDate today) {
        return endDate().map(end -> end.isBefore(today)).orElse(false);
    }

    public Period withCourse(Course course) {
        return new Period(start, course);
    }
}
