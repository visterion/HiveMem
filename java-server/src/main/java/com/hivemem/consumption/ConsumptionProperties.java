package com.hivemem.consumption;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "hivemem.consumption")
public class ConsumptionProperties {
    private boolean enabled = false;
    private String dir = "/data/consumption";
    private String realm = "documents";
    private Duration pollInterval = Duration.ofSeconds(10);
    private int stableSeconds = 5;
    private int maxPages = 200;
    private double confidenceThreshold = 0.80;   // used in M2
    private int maxDispatchRetries = 3;          // used in M2
    private int workerThreads = 2;               // bounded executor size for off-thread ingest

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getDir() { return dir; }
    public void setDir(String v) { this.dir = v; }
    public String getRealm() { return realm; }
    public void setRealm(String v) { this.realm = v; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration v) { this.pollInterval = v; }
    public int getStableSeconds() { return stableSeconds; }
    public void setStableSeconds(int v) { this.stableSeconds = v; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int v) { this.maxPages = v; }
    public double getConfidenceThreshold() { return confidenceThreshold; }
    public void setConfidenceThreshold(double v) { this.confidenceThreshold = v; }
    public int getMaxDispatchRetries() { return maxDispatchRetries; }
    public void setMaxDispatchRetries(int v) { this.maxDispatchRetries = v; }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int v) { this.workerThreads = v; }
}
