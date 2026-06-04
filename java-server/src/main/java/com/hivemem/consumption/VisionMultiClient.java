package com.hivemem.consumption;

import com.hivemem.queen.QueenProperties;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Calls Vistierie's generic multi-image vision endpoint (POST /llm/vision-multi). */
@Component
@ConditionalOnProperty(name = "hivemem.queen.enabled", havingValue = "true")
public class VisionMultiClient {

    /** One image to send: media type + base64 payload. */
    public record Image(String mediaType, String base64) {}

    private final RestClient client;
    private final String tenantToken;
    private final String agentName;
    private final ConsumptionProperties consumption;

    public VisionMultiClient(RestClient.Builder builder, QueenProperties props,
                             ConsumptionProperties consumption) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(props.getCallTimeoutSeconds() * 1000);
        rf.setReadTimeout(props.getCallTimeoutSeconds() * 1000);
        this.client = builder.baseUrl(props.getVistierieBaseUrl()).requestFactory(rf).build();
        this.tenantToken = props.getVistierieToken();
        this.agentName = props.getDocumentSeparatorAgent();
        this.consumption = consumption;
    }

    /** Send N images + a prompt; return the model's raw text (caller parses JSON). */
    public String group(String realm, String prompt, List<Image> images) {
        List<Map<String, Object>> imgs = new ArrayList<>();
        for (Image img : images) {
            imgs.add(Map.of("type", "base64", "media_type", img.mediaType(), "data", img.base64()));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("agent_name", agentName);
        body.put("purpose", consumption.getReassemblyPurpose());
        if (realm != null) body.put("realm", realm);
        body.put("images", imgs);
        body.put("prompt", prompt);
        body.put("max_tokens", consumption.getReassemblyMaxTokens());

        Resp r = client.post().uri("/llm/vision-multi")
                .header("Authorization", "Bearer " + tenantToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(Resp.class);
        return r == null ? null : r.text();
    }

    /** Subset of Vistierie's LlmResponse. */
    record Resp(String text) {}
}
