package ua.medicine.domain;

import java.time.LocalDate;
import java.util.Optional;

public record Medicine(Long id, long personId, String name, String dosage, String color,
                       LocalDate startDate, Course course, Schedule schedule, boolean active) {

    public Optional<LocalDate> endDate() {
        return course.endDate(startDate);
    }

    public boolean isFinished(LocalDate today) {
        return endDate().map(end -> end.isBefore(today)).orElse(false);
    }

    public Medicine withId(long id) {
        return new Medicine(id, personId, name, dosage, color, startDate, course, schedule, active);
    }

    public Medicine withSchedule(Schedule schedule) {
        return new Medicine(id, personId, name, dosage, color, startDate, course, schedule, active);
    }

    public Medicine withActive(boolean active) {
        return new Medicine(id, personId, name, dosage, color, startDate, course, schedule, active);
    }
}
