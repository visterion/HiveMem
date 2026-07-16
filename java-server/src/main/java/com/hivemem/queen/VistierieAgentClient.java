package com.hivemem.queen;

import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outbound client to Vistierie's agent CRUD. Idempotent {@link #upsertAgent}: creates
 * via POST when the agent is missing, otherwise replaces via PUT. Bearer tenant token.
 */
public class VistierieAgentClient {

    private final RestClient client;
    private final String tenantToken;

    public VistierieAgentClient(RestClient.Builder builder, QueenProperties props) {
        this(builder, props.getVistierieBaseUrl(), props.getVistierieToken(), props.getCallTimeoutSeconds());
    }

    VistierieAgentClient(RestClient.Builder builder, String baseUrl, String tenantToken, int timeoutSeconds) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(timeoutSeconds * 1000);
        rf.setReadTimeout(timeoutSeconds * 1000);
        this.client = builder.baseUrl(baseUrl).requestFactory(rf).build();
        this.tenantToken = tenantToken;
    }

    /**
     * Create or replace the named agent. A 409 on create is tolerated as idempotent success.
     * Throws {@link RestClientResponseException} on other error statuses (e.g. 5xx).
     */
    public void upsertAgent(String name, Map<String, Object> body) {
        if (agentExists(name)) {
            Map<String, Object> putBody = new LinkedHashMap<>(body);
            putBody.remove("name"); // PUT /agents/{name} body omits name
            client.put().uri("/agents/{name}", name)
                    .header("Authorization", "Bearer " + tenantToken)
                    .header("content-type", "application/json")
                    .body(putBody)
                    .retrieve()
                    .toBodilessEntity();
        } else {
            try {
                client.post().uri("/agents")
                        .header("Authorization", "Bearer " + tenantToken)
                        .header("content-type", "application/json")
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientResponseException e) {
                if (e.getStatusCode() != HttpStatus.CONFLICT) throw e;
                // 409 → agent already exists (raced/retried create); idempotent success.
            }
        }
    }

    /**
     * Trigger an on-demand run of the named agent (Vistierie {@code POST /agents/{name}/run},
     * trigger="manual"). Tolerant: a 409 (agent paused) or 403 (budget exceeded) is a normal
     * "not now" and is swallowed — the safety-net cron still covers those cells. Other error
     * statuses (e.g. 5xx) rethrow.
     */
    public void triggerRun(String name, Map<String, Object> payload) {
        try {
            client.post().uri("/agents/{name}/run", name)
                    .header("Authorization", "Bearer " + tenantToken)
                    .header("content-type", "application/json")
                    .body(Map.of("payload", payload == null ? Map.of() : payload))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            if (status == HttpStatus.CONFLICT || status == HttpStatus.FORBIDDEN) {
                return; // paused (409) or budget-exceeded (403) — safety-net cron will pick these up
            }
            throw e;
        }
    }

    private boolean agentExists(String name) {
        try {
            client.get().uri("/agents/{name}", name)
                    .header("Authorization", "Bearer " + tenantToken)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) return false;
            throw e;
        }
    }
}
