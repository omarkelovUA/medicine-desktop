package ua.medicine.domain;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** How often a medicine is taken. Times are always sorted, distinct and non-empty. */
public sealed interface Schedule permits Schedule.Daily, Schedule.Weekly, Schedule.Monthly {

    List<LocalTime> times();

    record Daily(List<LocalTime> times) implements Schedule {
        public Daily {
            times = normalize(times);
        }
    }

    record Weekly(Set<DayOfWeek> days, List<LocalTime> times) implements Schedule {
        public Weekly {
            if (days == null || days.isEmpty()) {
                throw new IllegalArgumentException("at least one weekday is required");
            }
            days = Collections.unmodifiableSet(EnumSet.copyOf(days));
            times = normalize(times);
        }
    }

    record Monthly(int dayOfMonth, List<LocalTime> times) implements Schedule {
        public Monthly {
            if (dayOfMonth < 1 || dayOfMonth > 31) {
                throw new IllegalArgumentException("dayOfMonth must be 1..31: " + dayOfMonth);
            }
            times = normalize(times);
        }
    }

    private static List<LocalTime> normalize(List<LocalTime> times) {
        if (times == null || times.isEmpty()) {
            throw new IllegalArgumentException("at least one time is required");
        }
        return times.stream().distinct().sorted().toList();
    }
}
