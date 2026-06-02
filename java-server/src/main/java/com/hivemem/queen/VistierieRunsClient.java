package com.hivemem.queen;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Read-only proxy to Vistierie's runs API. When an admin token is configured the list
 * call uses {@code GET /admin/runs} (includes cost); otherwise it falls back to the
 * tenant-scoped {@code GET /runs} (no cost). Detail + events are always tenant-scoped.
 * Any transport/5xx failure is rethrown as {@link VistierieUnavailableException}.
 */
public class VistierieRunsClient {

    private final RestClient client;
    private final String tenantToken;
    private final String adminToken;

    public VistierieRunsClient(RestClient.Builder builder, QueenProperties props) {
        this(builder, props.getVistierieBaseUrl(), props.getVistierieToken(),
                props.getVistierieAdminToken(), props.getCallTimeoutSeconds());
    }

    VistierieRunsClient(RestClient.Builder builder, String baseUrl, String tenantToken,
                        String adminToken, int timeoutSeconds) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(timeoutSeconds * 1000);
        rf.setReadTimeout(timeoutSeconds * 1000);
        this.client = builder.baseUrl(baseUrl).requestFactory(rf).build();
        this.tenantToken = tenantToken;
        this.adminToken = adminToken == null ? "" : adminToken;
    }

    /** True when the admin path (with cost) is available. */
    public boolean costAvailable() {
        return !adminToken.isBlank();
    }

    public JsonNode listRuns(int limit, int offset) {
        try {
            if (costAvailable()) {
                return client.get()
                        .uri(uri -> uri.path("/admin/runs")
                                .queryParam("tenant", "hivemem")
                                .queryParam("agent", AgentDefinitions.QUEEN_NAME)
                                .queryParam("agent", AgentDefinitions.BEE_NAME)
                                .queryParam("limit", limit)
                                .queryParam("offset", offset)
                                .build())
                        .header("Authorization", "Bearer " + adminToken)
                        .retrieve()
                        .body(JsonNode.class);
            }
            return client.get()
                    .uri(uri -> uri.path("/runs")
                            .queryParam("limit", limit)
                            .queryParam("offset", offset)
                            .build())
                    .header("Authorization", "Bearer " + tenantToken)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new VistierieUnavailableException("Vistierie runs list failed", e);
        }
    }

    public JsonNode getRun(String runId) {
        try {
            return client.get().uri("/runs/{id}", runId)
                    .header("Authorization", "Bearer " + tenantToken)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new VistierieUnavailableException("Vistierie run detail failed", e);
        }
    }

    public JsonNode getRunEvents(String runId) {
        try {
            return client.get().uri("/runs/{id}/events", runId)
                    .header("Authorization", "Bearer " + tenantToken)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new VistierieUnavailableException("Vistierie run events failed", e);
        }
    }
}
