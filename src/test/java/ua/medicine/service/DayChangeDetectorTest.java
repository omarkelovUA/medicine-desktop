package ua.medicine.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.medicine.testutil.T.d;

class DayChangeDetectorTest {

    @Test
    void reportsEachNewDayOnce() {
        var detector = new DayChangeDetector(d("2026-10-13"));
        assertThat(detector.changed(d("2026-10-13"))).isFalse();
        assertThat(detector.changed(d("2026-10-14"))).isTrue();
        assertThat(detector.changed(d("2026-10-14"))).isFalse();
    }

    @Test
    void skippedDaysCountAsOneChange() { // laptop slept for two nights
        var detector = new DayChangeDetector(d("2026-10-13"));
        assertThat(detector.changed(d("2026-10-16"))).isTrue();
        assertThat(detector.changed(d("2026-10-16"))).isFalse();
    }
}
