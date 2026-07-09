package com.hivemem.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResultSetStatsTest {

    @Test
    void spreadSetComputesMeanSigmaSize() {
        ResultSetStats stats = ResultSetStats.of(List.of(0.30, 0.40, 0.50));
        assertThat(stats.mean()).isCloseTo(0.40, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(stats.size()).isEqualTo(3);
        assertThat(stats.sigma()).isCloseTo(0.0816496581, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void emptySetIsZeroed() {
        ResultSetStats stats = ResultSetStats.of(List.of());
        assertThat(stats.mean()).isEqualTo(0.0);
        assertThat(stats.sigma()).isEqualTo(0.0);
        assertThat(stats.size()).isEqualTo(0);
    }

    @Test
    void identicalValuesHaveZeroSigma() {
        ResultSetStats stats = ResultSetStats.of(List.of(0.4, 0.4));
        assertThat(stats.mean()).isEqualTo(0.4);
        assertThat(stats.sigma()).isEqualTo(0.0);
        assertThat(stats.size()).isEqualTo(2);
    }
}
