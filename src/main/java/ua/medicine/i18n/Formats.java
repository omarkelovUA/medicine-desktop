package ua.medicine.i18n;

import ua.medicine.domain.Period;
import ua.medicine.domain.PeriodUnit;
import ua.medicine.domain.Schedule;
import ua.medicine.schedule.CourseProgress;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.stream.Collectors;

/** Ukrainian formatting of dates, times, amounts and schedules. */
public final class Formats {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Messages.LOCALE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", Messages.LOCALE);
    private static final DateTimeFormatter DAY_MONTH_NUMERIC = DateTimeFormatter.ofPattern("dd.MM", Messages.LOCALE);
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", Messages.LOCALE);
    private static final DateTimeFormatter DAY_HEADER = DateTimeFormatter.ofPattern("EEEE, d MMMM", Messages.LOCALE);

    private Formats() {
    }

    public static String time(LocalTime time) {
        return TIME.format(time);
    }

    public static String date(LocalDate date) {
        return DATE.format(date);
    }

    /** {@code 01.01–30.01.2026}, {@code 15.12.2025–14.01.2026}, or {@code 01.08.2026–…} for ongoing. */
    public static String periodRange(Period p) {
        return p.endDate()
                .map(end -> (end.getYear() == p.start().getYear() ? DAY_MONTH_NUMERIC.format(p.start()) : date(p.start()))
                        + "–" + date(end))
                .orElseGet(() -> date(p.start()) + "–…");
    }

    public static String periodRanges(List<Period> periods) {
        return periods.stream().map(Formats::periodRange).collect(Collectors.joining(" · "));
    }

    public static String dayMonth(LocalDate date) {
        return DAY_MONTH.format(date);
    }

    public static String dayHeader(LocalDate date) {
        return DAY_HEADER.format(date);
    }

    public static String amount(int n, PeriodUnit unit) {
        return UkrainianPlural.count(n, "plural." + unit.name());
    }

    public static String progress(CourseProgress p) {
        return Messages.get("progress." + p.unit().name(), String.valueOf(p.current()), String.valueOf(p.total()));
    }

    public static String scheduleSummary(Schedule schedule) {
        return switch (schedule) {
            case Schedule.Daily daily -> daily.times().size() == 1
                    ? Messages.get("format.daily.once")
                    : Messages.get("format.daily.times", String.valueOf(daily.times().size()));
            case Schedule.Weekly weekly -> weekly.days().stream()
                    .sorted()
                    .map(d -> d.getDisplayName(TextStyle.SHORT, Messages.LOCALE).toLowerCase(Messages.LOCALE))
                    .collect(Collectors.joining(", "));
            case Schedule.Monthly monthly -> Messages.get("format.monthly", String.valueOf(monthly.dayOfMonth()));
        };
    }
}
