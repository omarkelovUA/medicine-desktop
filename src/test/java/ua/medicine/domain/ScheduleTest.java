package ua.medicine.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleTest {

    @Test
    void timesAreSortedAndDistinct() {
        var s = new Schedule.Daily(List.of(LocalTime.of(20, 0), LocalTime.of(8, 0), LocalTime.of(20, 0)));
        assertThat(s.times()).containsExactly(LocalTime.of(8, 0), LocalTime.of(20, 0));
    }

    @Test
    void emptyTimesRejected() {
        assertThatThrownBy(() -> new Schedule.Daily(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void weeklyNeedsDays() {
        assertThatThrownBy(() -> new Schedule.Weekly(Set.of(), List.of(LocalTime.NOON)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void monthlyDayRange() {
        assertThatThrownBy(() -> new Schedule.Monthly(32, List.of(LocalTime.NOON)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
