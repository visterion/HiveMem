package com.hivemem.search;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ConfidenceLevelTest {

    private static final ConfidenceThresholds T = new ConfidenceThresholds(0.80, 0.65, 0.55);

    @Test
    void highAtOrAbove080() {
        assertThat(ConfidenceLevel.from(1.00, T)).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(ConfidenceLevel.from(0.80, T)).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    void mediumBetween065And080() {
        assertThat(ConfidenceLevel.from(0.79, T)).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(ConfidenceLevel.from(0.65, T)).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    @Test
    void lowBetween055And065() {
        assertThat(ConfidenceLevel.from(0.64, T)).isEqualTo(ConfidenceLevel.LOW);
        assertThat(ConfidenceLevel.from(0.55, T)).isEqualTo(ConfidenceLevel.LOW);
    }

    @Test
    void noneBelow055() {
        assertThat(ConfidenceLevel.from(0.54, T)).isEqualTo(ConfidenceLevel.NONE);
        assertThat(ConfidenceLevel.from(0.00, T)).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void boundaryExactlyAtThreshold() {
        // Boundary: value == threshold must resolve to the higher level
        assertThat(ConfidenceLevel.from(0.80, T)).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(ConfidenceLevel.from(0.65, T)).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(ConfidenceLevel.from(0.55, T)).isEqualTo(ConfidenceLevel.LOW);
    }
}
