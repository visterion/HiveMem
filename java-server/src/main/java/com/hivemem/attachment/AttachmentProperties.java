package com.hivemem.attachment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "hivemem.attachment")
public class AttachmentProperties {

    private boolean enabled = false;
    private String s3Endpoint = "http://localhost:8333";
    private String s3Bucket = "hivemem-attachments";
    private String s3AccessKey = "hivemem";
    private String s3SecretKey = "hivemem_secret";

    private String krokiUrl = "";
    private int krokiTimeoutSeconds = 10;
    private Duration krokiBackfillInterval = Duration.ofHours(1);

    private String vistierieBaseUrl = "http://vistierie:8090";
    private String vistierieToken = "";
    private int visionTimeoutSeconds = 30;
    private double visionDailyBudgetUsd = 1.0;
    private Duration visionBackfillInterval = Duration.ofHours(1);
    private long visionMaxInputBytes = 5L * 1024 * 1024;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getS3Endpoint() { return s3Endpoint; }
    public void setS3Endpoint(String s3Endpoint) { this.s3Endpoint = s3Endpoint; }
    public String getS3Bucket() { return s3Bucket; }
    public void setS3Bucket(String s3Bucket) { this.s3Bucket = s3Bucket; }
    public String getS3AccessKey() { return s3AccessKey; }
    public void setS3AccessKey(String s3AccessKey) { this.s3AccessKey = s3AccessKey; }
    public String getS3SecretKey() { return s3SecretKey; }
    public void setS3SecretKey(String s3SecretKey) { this.s3SecretKey = s3SecretKey; }

    public String getKrokiUrl() { return krokiUrl; }
    public void setKrokiUrl(String v) { this.krokiUrl = v; }
    public int getKrokiTimeoutSeconds() { return krokiTimeoutSeconds; }
    public void setKrokiTimeoutSeconds(int v) { this.krokiTimeoutSeconds = v; }
    public Duration getKrokiBackfillInterval() { return krokiBackfillInterval; }
    public void setKrokiBackfillInterval(Duration v) { this.krokiBackfillInterval = v; }

    public String getVistierieBaseUrl() { return vistierieBaseUrl; }
    public void setVistierieBaseUrl(String v) { this.vistierieBaseUrl = v; }
    public String getVistierieToken() { return vistierieToken; }
    public void setVistierieToken(String v) { this.vistierieToken = v; }
    public int getVisionTimeoutSeconds() { return visionTimeoutSeconds; }
    public void setVisionTimeoutSeconds(int v) { this.visionTimeoutSeconds = v; }
    public double getVisionDailyBudgetUsd() { return visionDailyBudgetUsd; }
    public void setVisionDailyBudgetUsd(double v) { this.visionDailyBudgetUsd = v; }
    public Duration getVisionBackfillInterval() { return visionBackfillInterval; }
    public void setVisionBackfillInterval(Duration v) { this.visionBackfillInterval = v; }
    public long getVisionMaxInputBytes() { return visionMaxInputBytes; }
    public void setVisionMaxInputBytes(long v) { this.visionMaxInputBytes = v; }
}
