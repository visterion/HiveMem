package com.hivemem.queen;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "hivemem.queen")
public class QueenProperties {

    /** Master switch. When false, no bootstrap runs and inbound webhooks reject (treated as disabled). */
    private boolean enabled = false;
    /** Vistierie base URL for outbound agent registration. */
    private String vistierieBaseUrl = "http://vistierie:8090";
    /** HiveMem tenant bearer token in Vistierie (may reuse HIVEMEM_VISTIERIE_TOKEN). */
    private String vistierieToken = "";
    /** Optional Vistierie ADMIN token. When set, queen_runs uses GET /admin/runs to include cost metrics. */
    private String vistierieAdminToken = "";
    /** HiveMem's own externally-reachable base URL, used to build tool webhook_url + completion_webhook. */
    private String hivememBaseUrl = "http://hivemem:8080";
    /** Token Vistierie presents to HiveMem on every tool webhook call (agent.webhook_token). */
    private String webhookToken = "";
    /** Token Vistierie presents to HiveMem on the completion webhook. */
    private String completionWebhookToken = "";
    /** Spring 6-field cron for the Queen. */
    private String schedule = "0 0 3 * * *";
    /** Max isolated cells the Queen processes per run. */
    private int isolatedBatchLimit = 20;
    /** Connect/read timeout for outbound registration calls. */
    private int callTimeoutSeconds = 30;
    /** Token HiveMem expects Vistierie to present on the separation webhook callback. */
    private String separationWebhookToken = "";
    /** Vistierie agent name that handles document separation tasks. */
    private String documentSeparatorAgent = "document-separator";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getVistierieBaseUrl() { return vistierieBaseUrl; }
    public void setVistierieBaseUrl(String v) { this.vistierieBaseUrl = v; }
    public String getVistierieToken() { return vistierieToken; }
    public void setVistierieToken(String v) { this.vistierieToken = v; }
    public String getVistierieAdminToken() { return vistierieAdminToken; }
    public void setVistierieAdminToken(String v) { this.vistierieAdminToken = v; }
    public String getHivememBaseUrl() { return hivememBaseUrl; }
    public void setHivememBaseUrl(String v) { this.hivememBaseUrl = v; }
    public String getWebhookToken() { return webhookToken; }
    public void setWebhookToken(String v) { this.webhookToken = v; }
    public String getCompletionWebhookToken() { return completionWebhookToken; }
    public void setCompletionWebhookToken(String v) { this.completionWebhookToken = v; }
    public String getSchedule() { return schedule; }
    public void setSchedule(String v) { this.schedule = v; }
    public int getIsolatedBatchLimit() { return isolatedBatchLimit; }
    public void setIsolatedBatchLimit(int v) { this.isolatedBatchLimit = v; }
    public int getCallTimeoutSeconds() { return callTimeoutSeconds; }
    public void setCallTimeoutSeconds(int v) { this.callTimeoutSeconds = v; }
    public String getSeparationWebhookToken() { return separationWebhookToken; }
    public void setSeparationWebhookToken(String v) { this.separationWebhookToken = v; }
    public String getDocumentSeparatorAgent() { return documentSeparatorAgent; }
    public void setDocumentSeparatorAgent(String v) { this.documentSeparatorAgent = v; }
}
