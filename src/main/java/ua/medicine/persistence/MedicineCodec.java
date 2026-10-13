package ua.medicine.persistence;

import ua.medicine.domain.Course;
import ua.medicine.domain.PeriodUnit;
import ua.medicine.domain.Schedule;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Maps Course and Schedule to and from their database columns. */
final class MedicineCodec {

    private MedicineCodec() {
    }

    static Integer courseAmount(Course course) {
        return course instanceof Course.Fixed f ? f.amount() : null;
    }

    static String courseUnit(Course course) {
        return course instanceof Course.Fixed f ? f.unit().name() : null;
    }

    static Course course(Integer amount, String unit) {
        return amount == null ? new Course.Ongoing() : new Course.Fixed(amount, PeriodUnit.valueOf(unit));
    }

    static String scheduleType(Schedule s) {
        return switch (s) {
            case Schedule.Daily d -> "DAILY";
            case Schedule.Weekly w -> "WEEKLY";
            case Schedule.Monthly m -> "MONTHLY";
        };
    }

    static String scheduleDays(Schedule s) {
        return switch (s) {
            case Schedule.Daily d -> null;
            case Schedule.Weekly w -> w.days().stream().sorted().map(day -> day.name().substring(0, 3))
                    .collect(Collectors.joining(","));
            case Schedule.Monthly m -> String.valueOf(m.dayOfMonth());
        };
    }

    static String scheduleTimes(Schedule s) {
        return s.times().stream().map(LocalTime::toString).collect(Collectors.joining(","));
    }

    static Schedule schedule(String type, String days, String times) {
        List<LocalTime> parsedTimes = Arrays.stream(times.split(",")).map(LocalTime::parse).toList();
        return switch (type) {
            case "DAILY" -> new Schedule.Daily(parsedTimes);
            case "WEEKLY" -> new Schedule.Weekly(parseDays(days), parsedTimes);
            case "MONTHLY" -> new Schedule.Monthly(Integer.parseInt(days), parsedTimes);
            default -> throw new IllegalStateException("Unknown schedule type " + type);
        };
    }

    private static Set<DayOfWeek> parseDays(String days) {
        return Arrays.stream(days.split(","))
                .map(abbr -> Arrays.stream(DayOfWeek.values()).filter(d -> d.name().startsWith(abbr)).findFirst().orElseThrow())
                .collect(Collectors.toSet());
    }
}
