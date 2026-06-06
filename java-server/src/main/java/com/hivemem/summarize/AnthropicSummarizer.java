package com.hivemem.summarize;

import com.hivemem.extraction.ExtractionProfile;
import com.hivemem.extraction.FactSpec;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AnthropicSummarizer {

    private static final String BASE_SYSTEM = """
            You distill cells in HiveMem (a personal knowledge graph) and extract structured facts.
            Given the full content of a cell and a document-type-specific instruction, produce a
            structured summary plus a list of facts so the cell becomes searchable and queryable.

            Respond with ONLY this JSON, no surrounding prose:
            {
              "document_type": "invoice|contract|other",
              "summary": "1-2 sentences, ≤ 250 chars, capturing the cell's purpose",
              "key_points": ["3-5 bullets, each ≤ 80 chars"],
              "insight": "1 sentence ≤ 200 chars, the non-obvious takeaway, or null",
              "tags": ["up to 5 lowercase kebab-case tags"],
              "facts": [
                 {"predicate": "<from required/optional list>", "object": "<value>", "confidence": 0.0-1.0}
              ]
            }

            Rules:
            - Always include every required_facts entry as a fact (use confidence < 0.5 when unsure).
            - Multi-valued attributes (e.g. multiple parties on a contract) become multiple facts
              with the same predicate, one per value.
            - Object values are strings. Dates as ISO-8601 (YYYY-MM-DD). Amounts without currency.
            - If the document does not match the provided document type, return your best guess
              for document_type and adjust facts accordingly.
            - If content is too sparse, return facts: [] and key_points: [].
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client;
    private final String tenantToken;
    private final String agentName;
    private final String model;
    private final int maxInputChars;
    private final int maxOutputTokens;

    /**
     * Production constructor — called by SummarizerService via SummarizerProperties.
     */
    public AnthropicSummarizer(RestClient.Builder builder, SummarizerProperties props) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(props.getCallTimeoutSeconds() * 1000);
        rf.setReadTimeout(props.getCallTimeoutSeconds() * 1000);
        this.client = builder
                .baseUrl(props.getVistierieBaseUrl())
                .requestFactory(rf)
                .build();
        this.tenantToken = props.getVistierieToken();
        this.agentName = props.getAgentName();
        this.model = props.getModel();
        this.maxInputChars = props.getMaxInputChars();
        this.maxOutputTokens = props.getMaxOutputTokens();
    }

    /**
     * Package-private constructor for unit tests — takes a builder + base URL and forces
     * SimpleClientHttpRequestFactory (HTTP/1.1) so WireMock-standalone works correctly.
     */
    AnthropicSummarizer(RestClient.Builder builder, String baseUrl,
                        String tenantToken, String agentName, String model,
                        int maxInputChars, int maxOutputTokens) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(30_000);
        rf.setReadTimeout(30_000);
        this.client = builder.baseUrl(baseUrl).requestFactory(rf).build();
        this.tenantToken = tenantToken;
        this.agentName = agentName;
        this.model = model;
        this.maxInputChars = maxInputChars;
        this.maxOutputTokens = maxOutputTokens;
    }

    // Public method signature is unchanged — callers are unaffected.
    public SummaryResult summarize(String content, ExtractionProfile profile) {
        String input = (content.length() > maxInputChars)
                ? content.substring(0, maxInputChars) + "\n\n[truncated]"
                : content;

        String systemPrompt = BASE_SYSTEM + "\n\n[Document-Type-Profile: " + profile.type() + "]\n"
                + profile.prompt() + "\n"
                + "Required facts (always emit, even with low confidence): "
                + String.join(", ", profile.requiredFacts()) + "\n"
                + "Optional facts (emit if present): "
                + String.join(", ", profile.optionalFacts());

        // Vistierie /llm/complete contract: agent_name (a registered agent with an operational
        // budget) is required; the system prompt is a top-level field (Anthropic rejects a
        // role:"system" entry inside messages), so messages carries the user turn only.
        Map<String, Object> body = Map.of(
                "agent_name", agentName,
                "purpose", "summarize_cell",
                "realm", profile.type(),
                "model", model,
                "system", systemPrompt,
                "messages", List.of(
                        Map.of("role", "user", "content", input)
                ),
                "max_tokens", maxOutputTokens
        );

        JsonNode resp = client.post()
                .uri("/llm/complete")
                .header("Authorization", "Bearer " + tenantToken)
                .header("content-type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (resp == null) throw new IllegalStateException("Vistierie returned null body");

        // Vistierie envelope: { "text": "<llm output>", "usage": { "inputTokens": N, "outputTokens": M }, ... }
        String text = resp.path("text").asText();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Vistierie returned empty text");
        }

        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(stripJsonFences(text));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse summarizer JSON: " + text, e);
        }

        String summary = parsed.path("summary").asText(null);
        List<String> keyPoints = asStringList(parsed.path("key_points"));
        String insight = parsed.hasNonNull("insight") ? parsed.path("insight").asText() : null;
        List<String> tags = asStringList(parsed.path("tags"));
        String documentType = parsed.hasNonNull("document_type")
                ? parsed.path("document_type").asText() : null;
        List<FactSpec> facts = asFactList(parsed.path("facts"));

        // Vistierie usage fields are camelCase (inputTokens / outputTokens)
        int inputTokens = resp.path("usage").path("inputTokens").asInt(0);
        int outputTokens = resp.path("usage").path("outputTokens").asInt(0);

        return new SummaryResult(summary, keyPoints, insight, tags,
                documentType, facts, inputTokens, outputTokens);
    }

    /**
     * Tolerant: strip ```json / ``` fences and narrow to the {...} object, so a fenced or
     * prose-wrapped LLM response still parses. Mirrors PageGrouper's tolerant array parse.
     */
    static String stripJsonFences(String text) {
        String cleaned = text.strip();
        if (cleaned.startsWith("```")) {
            int firstNl = cleaned.indexOf('\n');
            if (firstNl >= 0) cleaned = cleaned.substring(firstNl + 1);
            if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
            cleaned = cleaned.strip();
        }
        int s = cleaned.indexOf('{');
        int e = cleaned.lastIndexOf('}');
        if (s >= 0 && e > s) cleaned = cleaned.substring(s, e + 1);
        return cleaned;
    }

    private static List<String> asStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) out.add(n.asText());
        }
        return out;
    }

    private static List<FactSpec> asFactList(JsonNode node) {
        List<FactSpec> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode n : node) {
            String predicate = n.path("predicate").asText(null);
            String object = n.path("object").asText(null);
            if (predicate == null || predicate.isBlank() || object == null) continue;
            double conf = n.path("confidence").asDouble(0.5);
            if (conf < 0.0) conf = 0.0;
            if (conf > 1.0) conf = 1.0;
            out.add(new FactSpec(predicate, object, conf));
        }
        return out;
    }
}
