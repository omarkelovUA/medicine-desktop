package ua.medicine.schedule;

import ua.medicine.domain.Schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/** The period whose doses a dashboard card shows: today, this week (Mon–Sun) or this month. */
public final class CurrentPeriod {

    private CurrentPeriod() {
    }

    public static DateRange of(Schedule schedule, LocalDate today) {
        return switch (schedule) {
            case Schedule.Daily daily -> new DateRange(today, today);
            case Schedule.Weekly weekly -> new DateRange(
                    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                    today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)));
            case Schedule.Monthly monthly -> new DateRange(
                    today.withDayOfMonth(1), today.with(TemporalAdjusters.lastDayOfMonth()));
        };
    }
}
