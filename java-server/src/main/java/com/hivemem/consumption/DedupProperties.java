package com.hivemem.consumption;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "hivemem.consumption.dedup")
public class DedupProperties {
    /** Feature switch. false -> OCR behaves exactly as before (no dedup). */
    private boolean enabled = true;
    /** Stage-1 cosine similarity floor for HNSW candidate recall. */
    private double recallThreshold = 0.92;
    /** Stage-2 normalized-text Jaccard floor that confirms a true duplicate. */
    private double textThreshold = 0.85;
    /** Max HNSW candidates considered. */
    private int candidateK = 10;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public double getRecallThreshold() { return recallThreshold; }
    public void setRecallThreshold(double v) { this.recallThreshold = v; }
    public double getTextThreshold() { return textThreshold; }
    public void setTextThreshold(double v) { this.textThreshold = v; }
    public int getCandidateK() { return candidateK; }
    public void setCandidateK(int v) { this.candidateK = v; }
}
