package com.hivemem.ocr;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "hivemem.ocr")
public class OcrProperties {

    private boolean enabled = false;
    private String tesseractPath = "tesseract";
    private String languages = "deu+eng";
    private int scanDetectionThreshold = 50;
    private int renderDpi = 300;
    private int callTimeoutSeconds = 60;
    private Duration backfillInterval = Duration.ofHours(1);
    private int backfillBatchSize = 5;
    private int maxPages = 50;
    private boolean visionFallbackEnabled = false;
    private int visionFallbackMinCharsPerPage = 30;
    private int visionFallbackMaxPagesPerDoc = 20;
    private boolean dropBlankPages = true;
    private double blankWhiteFraction = 0.995;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getTesseractPath() { return tesseractPath; }
    public void setTesseractPath(String v) { this.tesseractPath = v; }
    public String getLanguages() { return languages; }
    public void setLanguages(String v) { this.languages = v; }
    public int getScanDetectionThreshold() { return scanDetectionThreshold; }
    public void setScanDetectionThreshold(int v) { this.scanDetectionThreshold = v; }
    public int getRenderDpi() { return renderDpi; }
    public void setRenderDpi(int v) { this.renderDpi = v; }
    public int getCallTimeoutSeconds() { return callTimeoutSeconds; }
    public void setCallTimeoutSeconds(int v) { this.callTimeoutSeconds = v; }
    public Duration getBackfillInterval() { return backfillInterval; }
    public void setBackfillInterval(Duration v) { this.backfillInterval = v; }
    public int getBackfillBatchSize() { return backfillBatchSize; }
    public void setBackfillBatchSize(int v) { this.backfillBatchSize = v; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int v) { this.maxPages = v; }
    public boolean isVisionFallbackEnabled() { return visionFallbackEnabled; }
    public void setVisionFallbackEnabled(boolean v) { this.visionFallbackEnabled = v; }
    public int getVisionFallbackMinCharsPerPage() { return visionFallbackMinCharsPerPage; }
    public void setVisionFallbackMinCharsPerPage(int v) { this.visionFallbackMinCharsPerPage = v; }
    public int getVisionFallbackMaxPagesPerDoc() { return visionFallbackMaxPagesPerDoc; }
    public void setVisionFallbackMaxPagesPerDoc(int v) { this.visionFallbackMaxPagesPerDoc = v; }
    public boolean isDropBlankPages() { return dropBlankPages; }
    public void setDropBlankPages(boolean v) { this.dropBlankPages = v; }
    public double getBlankWhiteFraction() { return blankWhiteFraction; }
    public void setBlankWhiteFraction(double v) { this.blankWhiteFraction = v; }
}
