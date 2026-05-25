package com.hivemem.search;

/**
 * Human-readable retrieval confidence derived from score_total.
 *
 * HIGH   → score_total ≥ thresholds.high   (default 0.80)
 * MEDIUM → score_total ≥ thresholds.medium (default 0.65)
 * LOW    → score_total ≥ thresholds.low    (default 0.55)
 * NONE   → score_total < thresholds.low    (too weak to trust)
 */
public enum ConfidenceLevel {

    HIGH, MEDIUM, LOW, NONE;

    public static ConfidenceLevel from(double scoreTotal, ConfidenceThresholds thresholds) {
        if (scoreTotal >= thresholds.getHigh())   return HIGH;
        if (scoreTotal >= thresholds.getMedium()) return MEDIUM;
        if (scoreTotal >= thresholds.getLow())    return LOW;
        return NONE;
    }
}
