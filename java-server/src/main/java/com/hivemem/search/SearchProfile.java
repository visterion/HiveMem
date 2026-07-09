package com.hivemem.search;

/**
 * Named 6-signal weight presets for search. Each vector sums to 1.0.
 * BALANCED equals the historical default weights, so omitting profile is behavior-preserving.
 */
public enum SearchProfile {

    BALANCED (0.30, 0.15, 0.15, 0.15, 0.15, 0.10),
    SEMANTIC (0.55, 0.10, 0.05, 0.10, 0.10, 0.10),
    RECENT   (0.25, 0.10, 0.40, 0.10, 0.05, 0.10),
    IMPORTANT(0.25, 0.10, 0.10, 0.40, 0.05, 0.10),
    KEYWORD  (0.20, 0.45, 0.10, 0.10, 0.05, 0.10);

    public final double semantic;
    public final double keyword;
    public final double recency;
    public final double importance;
    public final double popularity;
    public final double graphProximity;

    SearchProfile(double semantic, double keyword, double recency,
                  double importance, double popularity, double graphProximity) {
        this.semantic = semantic;
        this.keyword = keyword;
        this.recency = recency;
        this.importance = importance;
        this.popularity = popularity;
        this.graphProximity = graphProximity;
    }

    /** Resolve a profile name; null/blank -> BALANCED; unknown -> IllegalArgumentException. */
    public static SearchProfile fromString(String value) {
        if (value == null || value.isBlank()) {
            return BALANCED;
        }
        try {
            return SearchProfile.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown profile: " + value + " (expected balanced|semantic|recent|important|keyword)");
        }
    }
}
