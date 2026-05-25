package com.hivemem.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Threshold values that map a numeric score_total (0–1) to a ConfidenceLevel.
 * Bound to hivemem.search.confidence.* in application.yml.
 */
@Configuration
@ConfigurationProperties(prefix = "hivemem.search.confidence")
public class ConfidenceThresholds {

    private double high = 0.80;
    private double medium = 0.65;
    private double low = 0.55;

    /** No-arg constructor required by Spring @ConfigurationProperties. */
    public ConfidenceThresholds() {}

    /** Convenience constructor used in unit tests — no Spring context needed. */
    public ConfidenceThresholds(double high, double medium, double low) {
        this.high = high;
        this.medium = medium;
        this.low = low;
    }

    public double getHigh()   { return high; }
    public double getMedium() { return medium; }
    public double getLow()    { return low; }

    public void setHigh(double v)   { this.high = v; }
    public void setMedium(double v) { this.medium = v; }
    public void setLow(double v)    { this.low = v; }
}
