package ua.medicine.schedule;

import ua.medicine.domain.Medicine;
import ua.medicine.domain.Schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Turns a medicine's course and schedule into concrete dose times. Pure. */
public final class ScheduleExpander {

    private ScheduleExpander() {
    }

    /** All dose times on dates in [from, to] that fall within the course, sorted ascending. */
    public static List<LocalDateTime> expand(Medicine m, LocalDate from, LocalDate to) {
        LocalDate first = from.isAfter(m.startDate()) ? from : m.startDate();
        LocalDate last = m.endDate().filter(end -> end.isBefore(to)).orElse(to);
        List<LocalDateTime> result = new ArrayList<>();
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            if (matches(m.schedule(), date)) {
                for (var time : m.schedule().times()) {
                    result.add(date.atTime(time));
                }
            }
        }
        return result;
    }

    private static boolean matches(Schedule schedule, LocalDate date) {
        return switch (schedule) {
            case Schedule.Daily daily -> true;
            case Schedule.Weekly weekly -> weekly.days().contains(date.getDayOfWeek());
            case Schedule.Monthly monthly ->
                    date.getDayOfMonth() == Math.min(monthly.dayOfMonth(), date.lengthOfMonth());
        };
    }
}
