package ua.medicine.domain;

import java.time.LocalDate;
import java.util.Optional;

/** How long a medicine is taken: a fixed number of units, or ongoing. */
public sealed interface Course permits Course.Fixed, Course.Ongoing {

    /** Inclusive last day of the course, empty for ongoing. */
    Optional<LocalDate> endDate(LocalDate start);

    record Fixed(int amount, PeriodUnit unit) implements Course {
        public Fixed {
            if (amount < 1) {
                throw new IllegalArgumentException("amount must be >= 1: " + amount);
            }
            if (unit == null) {
                throw new IllegalArgumentException("unit is required");
            }
        }

        @Override
        public Optional<LocalDate> endDate(LocalDate start) {
            return Optional.of(start.plus(amount, unit.chronoUnit()).minusDays(1));
        }
    }

    record Ongoing() implements Course {
        @Override
        public Optional<LocalDate> endDate(LocalDate start) {
            return Optional.empty();
        }
    }
}
