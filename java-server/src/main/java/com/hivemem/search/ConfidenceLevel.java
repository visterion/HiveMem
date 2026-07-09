package com.hivemem.search;

/**
 * Human-readable retrieval confidence, classified relative to the result-set distribution
 * with an absolute floor. Computed once per result-set (mean + population sigma over score_total).
 *
 * NONE   -> score_total < floor, OR result-set has fewer than 2 elements (no distribution).
 * HIGH   -> score_total >= mean + sigma
 * LOW    -> score_total < mean
 * MEDIUM -> otherwise (mean <= score_total < mean + sigma)
 *
 * When sigma == 0 (all scores equal) every above-floor hit is MEDIUM.
 */
public enum ConfidenceLevel {

    HIGH, MEDIUM, LOW, NONE;

    public static ConfidenceLevel classify(double scoreTotal, ResultSetStats stats, ConfidenceThresholds thresholds) {
        if (scoreTotal < thresholds.getFloor()) return NONE;
        if (stats.size() < 2) return NONE;
        if (stats.sigma() == 0.0) return MEDIUM;
        if (scoreTotal >= stats.mean() + stats.sigma()) return HIGH;
        if (scoreTotal < stats.mean()) return LOW;
        return MEDIUM;
    }
}
