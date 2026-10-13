package ua.medicine.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LabelPlacementTest {

    private static final double PAD = 6;

    @Test
    void insideWhenTheBarIsWideEnough() {
        assertThat(LabelPlacement.of(100, 200, 80, 1000, PAD)).isEqualTo(new LabelPlacement(106, 80, true));
    }

    @Test
    void rightOfTheBarWhenItIsTooShort() {
        assertThat(LabelPlacement.of(100, 20, 80, 1000, PAD)).isEqualTo(new LabelPlacement(126, 80, false));
    }

    @Test
    void leftOfTheBarWhenTheRightSideRunsOff() {
        assertThat(LabelPlacement.of(900, 20, 80, 1000, PAD)).isEqualTo(new LabelPlacement(814, 80, false));
    }

    @Test
    void cutInsideTheBarWhenNeitherSideFits() {
        // narrow track, bar near the left edge: the label stays inside, in the bar's white style, and is cut
        assertThat(LabelPlacement.of(10, 60, 200, 150, PAD)).isEqualTo(new LabelPlacement(16, 48, true));
    }

    @Test
    void neverNegativeWidthOnATinyBar() {
        assertThat(LabelPlacement.of(10, 4, 200, 100, PAD).width()).isZero();
    }
}
