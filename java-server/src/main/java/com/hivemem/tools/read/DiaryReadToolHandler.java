package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Order(14)
public class DiaryReadToolHandler implements ToolHandler {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;

    private final ReadToolService readToolService;

    public DiaryReadToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "diary_read";
    }

    @Override
    public String description() {
        return "Recent diary entries for a registered agent.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("agent", "Agent name to read diary for")
                .optionalInteger("last_n", "Number of most recent entries to return (default 10, max 100)")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String agent = requiredText(arguments, "agent");
        int lastN = intValue(arguments, "last_n");
        return readToolService.diaryRead(agent, lastN);
    }

    private static String requiredText(JsonNode arguments, String field) {
        if (arguments == null || !arguments.hasNonNull(field)) {
            throw new IllegalArgumentException("Missing " + field);
        }
        JsonNode node = arguments.get(field);
        if (!node.isTextual()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        String value = node.asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return value;
    }

    private static int intValue(JsonNode arguments, String field) {
        if (arguments == null || !arguments.hasNonNull(field)) {
            return DEFAULT_LIMIT;
        }
        JsonNode node = arguments.get(field);
        if (!node.isIntegralNumber()) {
            throw new IllegalArgumentException("Invalid last_n");
        }
        int value = node.intValue();
        if (value <= 0 || value > MAX_LIMIT) {
            throw new IllegalArgumentException("Invalid last_n");
        }
        return value;
    }
}
