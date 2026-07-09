package com.hivemem.consumption;

import com.hivemem.queen.QueenProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Calls Vistierie's text completion endpoint (POST /llm/complete) for the reassembly's
 *  text-only mailing-assembly pass. Model selection stays with the routing rule (purpose). */
@Component
@ConditionalOnProperty(name = "hivemem.queen.enabled", havingValue = "true")
public class CompleteClient {

    private final RestClient client;
    private final String tenantToken;
    private final String agentName;
    private final ConsumptionProperties consumption;

    public CompleteClient(RestClient.Builder builder, QueenProperties props,
                          ConsumptionProperties consumption) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(props.getCallTimeoutSeconds() * 1000);
        rf.setReadTimeout(props.getCallTimeoutSeconds() * 1000);
        this.client = builder.baseUrl(props.getVistierieBaseUrl()).requestFactory(rf).build();
        this.tenantToken = props.getVistierieToken();
        this.agentName = props.getDocumentSeparatorAgent();
        this.consumption = consumption;
    }

    /** Send a text-only prompt; return the model's raw text (caller parses JSON). */
    public String complete(String realm, String prompt) {
        Map<String, Object> body = new HashMap<>();
        body.put("agent_name", agentName);
        body.put("purpose", consumption.getReassemblyPurpose());
        if (realm != null) body.put("realm", realm);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("max_tokens", consumption.getReassemblyMaxTokens());

        Resp r = client.post().uri("/llm/complete")
                .header("Authorization", "Bearer " + tenantToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(Resp.class);
        return r == null ? null : r.text();
    }

    /** Subset of Vistierie's LlmResponse. */
    record Resp(String text) {}
}
