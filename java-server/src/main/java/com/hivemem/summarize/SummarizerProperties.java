package com.hivemem.summarize;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "hivemem.summarize")
public class SummarizerProperties {

    private boolean enabled = false;
    private String vistierieBaseUrl = "http://vistierie:8090";
    private String vistierieToken = "";
    /**
     * Registered Vistierie agent used for summarize completions. /llm/complete requires a
     * known agent_name with an operational budget; defaults to the document-separator agent
     * the Queen bootstrap provisions (reused here — its model_purpose is ignored for
     * /llm/complete, which routes on the per-request purpose/model).
     */
    private String agentName = "document-separator";
    private String model = "claude-haiku-4-5";
    private double dailyBudgetUsd = 1.00;
    private Duration backfillInterval = Duration.ofMinutes(5);
    private int backfillBatchSize = 10;
    private int callTimeoutSeconds = 30;
    private int maxInputChars = 8000;
    /** Output token cap for the completion. Must fit summary + key_points + insight + all
     *  facts as JSON; too small truncates the response into invalid JSON. */
    private int maxOutputTokens = 4096;
    private int summaryThresholdChars = 500;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getVistierieBaseUrl() { return vistierieBaseUrl; }
    public void setVistierieBaseUrl(String v) { this.vistierieBaseUrl = v; }
    public String getVistierieToken() { return vistierieToken; }
    public void setVistierieToken(String v) { this.vistierieToken = v; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String v) { this.agentName = v; }
    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }
    public double getDailyBudgetUsd() { return dailyBudgetUsd; }
    public void setDailyBudgetUsd(double v) { this.dailyBudgetUsd = v; }
    public Duration getBackfillInterval() { return backfillInterval; }
    public void setBackfillInterval(Duration v) { this.backfillInterval = v; }
    public int getBackfillBatchSize() { return backfillBatchSize; }
    public void setBackfillBatchSize(int v) { this.backfillBatchSize = v; }
    public int getCallTimeoutSeconds() { return callTimeoutSeconds; }
    public void setCallTimeoutSeconds(int v) { this.callTimeoutSeconds = v; }
    public int getMaxInputChars() { return maxInputChars; }
    public void setMaxInputChars(int v) { this.maxInputChars = v; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int v) { this.maxOutputTokens = v; }
    public int getSummaryThresholdChars() { return summaryThresholdChars; }
    public void setSummaryThresholdChars(int v) { this.summaryThresholdChars = v; }
}
