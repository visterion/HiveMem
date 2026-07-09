package com.hivemem.search;

import java.util.List;

/** Aggregate stats over a result-set's score_total values, computed once per search. */
public record ResultSetStats(double mean, double sigma, int size) {

    /** Population mean + standard deviation over the given score_total values. Empty -> (0,0,0). */
    public static ResultSetStats of(List<Double> scoreTotals) {
        int n = scoreTotals == null ? 0 : scoreTotals.size();
        if (n == 0) {
            return new ResultSetStats(0.0, 0.0, 0);
        }
        double sum = 0.0;
        for (double v : scoreTotals) sum += v;
        double mean = sum / n;
        double variance = 0.0;
        for (double v : scoreTotals) {
            double d = v - mean;
            variance += d * d;
        }
        variance /= n; // population variance
        return new ResultSetStats(mean, Math.sqrt(variance), n);
    }
}
