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
    private boolean reassemblyEnabled = false;
    private int blockSize = 15;                       // <= Bedrock 20-images/request limit
    private double reassemblyConfidenceThreshold = 0.5; // aggressive: most groups commit
    private int reassemblyRenderDpi = 150;            // downscale pages for the vision payload
    private String reassemblyPurpose = "separator";   // Vistierie routing purpose
    private int reassemblyMaxTokens = 4096;

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
    public boolean isReassemblyEnabled() { return reassemblyEnabled; }
    public void setReassemblyEnabled(boolean v) { this.reassemblyEnabled = v; }
    public int getBlockSize() { return blockSize; }
    public void setBlockSize(int v) { this.blockSize = v; }
    public double getReassemblyConfidenceThreshold() { return reassemblyConfidenceThreshold; }
    public void setReassemblyConfidenceThreshold(double v) { this.reassemblyConfidenceThreshold = v; }
    public int getReassemblyRenderDpi() { return reassemblyRenderDpi; }
    public void setReassemblyRenderDpi(int v) { this.reassemblyRenderDpi = v; }
    public String getReassemblyPurpose() { return reassemblyPurpose; }
    public void setReassemblyPurpose(String v) { this.reassemblyPurpose = v; }
    public int getReassemblyMaxTokens() { return reassemblyMaxTokens; }
    public void setReassemblyMaxTokens(int v) { this.reassemblyMaxTokens = v; }
}
