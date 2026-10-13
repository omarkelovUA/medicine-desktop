package ua.medicine.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourseTest {

    @Test
    void fixedEndDateIsInclusive() {
        assertThat(new Course.Fixed(10, PeriodUnit.DAYS).endDate(LocalDate.of(2026, 10, 13)))
                .contains(LocalDate.of(2026, 10, 22));
        assertThat(new Course.Fixed(1, PeriodUnit.MONTHS).endDate(LocalDate.of(2026, 1, 31)))
                .contains(LocalDate.of(2026, 2, 27));
        assertThat(new Course.Fixed(2, PeriodUnit.WEEKS).endDate(LocalDate.of(2026, 10, 13)))
                .contains(LocalDate.of(2026, 10, 26));
    }

    @Test
    void ongoingHasNoEnd() {
        assertThat(new Course.Ongoing().endDate(LocalDate.of(2026, 10, 13))).isEmpty();
    }

    @Test
    void fixedRejectsZeroAmount() {
        assertThatThrownBy(() -> new Course.Fixed(0, PeriodUnit.DAYS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
