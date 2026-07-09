package com.hivemem.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfidenceLevelTest {

    private static final ConfidenceThresholds T = new ConfidenceThresholds(0.20);

    // Spread set: mean 0.40, population sigma ~= 0.0816496581
    private static final ResultSetStats SPREAD = ResultSetStats.of(List.of(0.30, 0.40, 0.50));

    @Test
    void belowFloorIsNone() {
        assertThat(ConfidenceLevel.classify(0.10, SPREAD, T)).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void singleElementSetIsNoneEvenAboveFloor() {
        ResultSetStats single = ResultSetStats.of(List.of(0.5));
        assertThat(ConfidenceLevel.classify(0.5, single, T)).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void emptySetIsNone() {
        ResultSetStats empty = ResultSetStats.of(List.of());
        assertThat(ConfidenceLevel.classify(0.5, empty, T)).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void spreadSetClassifiesRelativeToDistribution() {
        // mean 0.40, sigma ~= 0.081649 -> mean + sigma ~= 0.481649
        assertThat(ConfidenceLevel.classify(0.50, SPREAD, T)).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(ConfidenceLevel.classify(0.40, SPREAD, T)).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(ConfidenceLevel.classify(0.30, SPREAD, T)).isEqualTo(ConfidenceLevel.LOW);
    }

    @Test
    void zeroSigmaSetIsMedium() {
        ResultSetStats flat = ResultSetStats.of(List.of(0.40, 0.40));
        assertThat(ConfidenceLevel.classify(0.40, flat, T)).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    @Test
    void floorGuardBeatsDistribution() {
        // Below floor wins over any distribution-relative classification.
        assertThat(ConfidenceLevel.classify(0.05, SPREAD, T)).isEqualTo(ConfidenceLevel.NONE);
    }
}
