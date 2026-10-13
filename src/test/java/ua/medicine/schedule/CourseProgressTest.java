package ua.medicine.schedule;

import org.junit.jupiter.api.Test;
import ua.medicine.domain.Course;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.medicine.domain.PeriodUnit.DAYS;
import static ua.medicine.domain.PeriodUnit.WEEKS;
import static ua.medicine.testutil.T.d;
import static ua.medicine.testutil.T.med;

class CourseProgressTest {

    @Test
    void dayFourOfTen() {
        var p = CourseProgress.of(med(d("2026-10-10"), new Course.Fixed(10, DAYS)), d("2026-10-13")).orElseThrow();
        assertThat(p.current()).isEqualTo(4);
        assertThat(p.total()).isEqualTo(10);
        assertThat(p.endDate()).isEqualTo(d("2026-10-19"));
        assertThat(p.fraction()).isEqualTo(0.4);
    }

    @Test
    void weekThreeOfFour() {
        var p = CourseProgress.of(med(d("2026-09-28"), new Course.Fixed(4, WEEKS)), d("2026-10-13")).orElseThrow();
        assertThat(p.current()).isEqualTo(3);
    }

    @Test
    void clampedAfterEnd() {
        assertThat(CourseProgress.of(med(d("2026-01-01"), new Course.Fixed(3, DAYS)), d("2026-10-13"))
                .orElseThrow().current()).isEqualTo(3);
    }

    @Test
    void zeroBeforeStart() {
        assertThat(CourseProgress.of(med(d("2026-11-01"), new Course.Fixed(3, DAYS)), d("2026-10-13"))
                .orElseThrow().current()).isZero();
    }

    @Test
    void ongoingHasNone() {
        assertThat(CourseProgress.of(med(d("2026-01-01"), new Course.Ongoing()), d("2026-10-13"))).isEmpty();
    }
}
