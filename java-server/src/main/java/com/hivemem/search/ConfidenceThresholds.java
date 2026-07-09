package com.hivemem.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Tuning for the relative confidence classifier. Bound to hivemem.search.confidence.* in application.yml.
 * floor: a score_total below this is always NONE, regardless of the result-set distribution.
 */
@Configuration
@ConfigurationProperties(prefix = "hivemem.search.confidence")
public class ConfidenceThresholds {

    private double floor = 0.20;

    /** No-arg constructor required by Spring @ConfigurationProperties. */
    public ConfidenceThresholds() {}

    /** Convenience constructor used in unit tests — no Spring context needed. */
    public ConfidenceThresholds(double floor) {
        this.floor = floor;
    }

    public double getFloor() { return floor; }

    public void setFloor(double v) { this.floor = v; }
}
