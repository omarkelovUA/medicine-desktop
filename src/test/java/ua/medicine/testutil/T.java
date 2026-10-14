package ua.medicine.testutil;

import ua.medicine.domain.Course;
import ua.medicine.domain.Medicine;
import ua.medicine.domain.Period;
import ua.medicine.domain.Schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** Short constructors for test values. */
public final class T {
    private T() {
    }

    public static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    public static LocalTime t(String iso) {
        return LocalTime.parse(iso);
    }

    public static LocalDateTime dt(String iso) {
        return LocalDateTime.parse(iso);
    }

    public static Medicine med(LocalDate start, Course course, Schedule schedule) {
        return med(1, start, course, schedule);
    }

    public static Medicine med(long personId, LocalDate start, Course course, Schedule schedule) {
        return new Medicine(null, personId, "Амоксицилін", "500 мг", "#d5a23a", start, course, schedule, true);
    }

    public static Period p(String start, Course course) {
        return new Period(d(start), course);
    }

    public static Medicine med(List<Period> periods) {
        return new Medicine(null, 1, "Амоксицилін", "500 мг", "#d5a23a", periods,
                new Schedule.Daily(List.of(LocalTime.of(8, 0))), true);
    }

    public static Medicine med(LocalDate start, Course course) {
        return med(start, course, new Schedule.Daily(List.of(LocalTime.of(8, 0))));
    }
}
