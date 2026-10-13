package ua.medicine.i18n;

import ua.medicine.domain.PeriodUnit;
import ua.medicine.domain.Schedule;
import ua.medicine.schedule.CourseProgress;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.stream.Collectors;

/** Ukrainian formatting of dates, times, amounts and schedules. */
public final class Formats {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Messages.LOCALE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", Messages.LOCALE);
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

    public static String dayMonth(LocalDate date) {
        return DAY_MONTH.format(date);
    }

    public static String dayHeader(LocalDate date) {
        return DAY_HEADER.format(date);
    }

    public static String amount(int n, PeriodUnit unit) {
        String word = switch (unit) {
            case DAYS -> UkrainianPlural.choose(n, "день", "дні", "днів");
            case WEEKS -> UkrainianPlural.choose(n, "тиждень", "тижні", "тижнів");
            case MONTHS -> UkrainianPlural.choose(n, "місяць", "місяці", "місяців");
            case YEARS -> UkrainianPlural.choose(n, "рік", "роки", "років");
        };
        return n + " " + word;
    }

    public static String progress(CourseProgress p) {
        String unit = switch (p.unit()) {
            case DAYS -> "День";
            case WEEKS -> "Тиждень";
            case MONTHS -> "Місяць";
            case YEARS -> "Рік";
        };
        return unit + " " + p.current() + " з " + p.total();
    }

    public static String scheduleSummary(Schedule schedule) {
        return switch (schedule) {
            case Schedule.Daily daily -> daily.times().size() == 1 ? "щодня" : daily.times().size() + "×/день";
            case Schedule.Weekly weekly -> weekly.days().stream()
                    .sorted()
                    .map(d -> d.getDisplayName(TextStyle.SHORT, Messages.LOCALE).toLowerCase(Messages.LOCALE))
                    .collect(Collectors.joining(", "));
            case Schedule.Monthly monthly -> "щомісяця " + monthly.dayOfMonth() + "-го";
        };
    }
}
