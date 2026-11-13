package com.hivemem.search;

public record SearchWeights(
        double semantic,
        double keyword,
        double recency,
        double importance,
        double popularity,
        double graphProximity
) {
    public static SearchWeights defaults() {
        return new SearchWeights(0.20, 0.25, 0.15, 0.15, 0.15, 0.10);
    }
}
