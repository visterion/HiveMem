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
     * POST /agents/{name}/runs with the page digests + the callback HiveMem expects results on.
     *
     * NOTE: The endpoint path (/agents/{name}/runs) and request body shape (correlation_id, input.pages,
     * completion_webhook) are ASSUMED based on the existing VistierieAgentClient idiom and must be
     * reconciled with Vistierie's real run-creation API before production use.
     */
    public void dispatch(UUID correlationId, List<PageDigest> digests) {
        Map<String, Object> body = new HashMap<>();
        body.put("correlation_id", correlationId.toString());
        body.put("input", Map.of("pages", digests));
        body.put("completion_webhook", Map.of(
                "url", callbackBaseUrl + "/vistierie/separation/done",
                "token", callbackToken));
        client.post()
                .uri("/agents/{name}/runs", agentName)
                .header("Authorization", "Bearer " + tenantToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}
