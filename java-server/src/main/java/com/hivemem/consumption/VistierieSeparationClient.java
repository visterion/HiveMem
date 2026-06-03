package com.hivemem.consumption;

import com.hivemem.queen.QueenProperties;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Dispatches a document-separation task to Vistierie. New direction: HiveMem -> Vistierie push. */
@Component
@ConditionalOnProperty(name = "hivemem.queen.enabled", havingValue = "true")
public class VistierieSeparationClient {

    private final RestClient client;
    private final String tenantToken;
    private final String agentName;
    private final String callbackBaseUrl;
    private final String callbackToken;

    public VistierieSeparationClient(RestClient.Builder builder, QueenProperties props) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(props.getCallTimeoutSeconds() * 1000);
        rf.setReadTimeout(props.getCallTimeoutSeconds() * 1000);
        this.client = builder.baseUrl(props.getVistierieBaseUrl()).requestFactory(rf).build();
        this.tenantToken = props.getVistierieToken();
        this.agentName = props.getDocumentSeparatorAgent();
        this.callbackBaseUrl = props.getHivememBaseUrl();
        this.callbackToken = props.getSeparationWebhookToken();
    }

    /**
     * Create a run on Vistierie's document-separator agent and return its run id.
     *
     * Matches Vistierie's real run-creation contract (RunController#trigger): POST /agents/{name}/run
     * with {payload, completion_webhook, completion_webhook_token}. The page digests and a
     * correlation id ride inside the free-form {@code payload}; Vistierie echoes neither back, so the
     * returned run id (RunCreatedResponse.run_id) is what HiveMem stores to correlate the callback.
     *
     * @return the Vistierie run id, or {@code null} if the response carried none.
     */
    public String dispatch(UUID correlationId, List<PageDigest> digests) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("correlation_id", correlationId.toString());
        payload.put("pages", digests);

        Map<String, Object> body = new HashMap<>();
        body.put("payload", payload);
        body.put("completion_webhook", callbackBaseUrl + "/vistierie/separation/done");
        body.put("completion_webhook_token", callbackToken);

        RunCreated created = client.post()
                .uri("/agents/{name}/run", agentName)
                .header("Authorization", "Bearer " + tenantToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(RunCreated.class);
        return created == null ? null : created.run_id();
    }

    /** Subset of Vistierie's RunCreatedResponse (202 Accepted). */
    record RunCreated(String run_id, String agent_name, int agent_version, String status) {}
}
