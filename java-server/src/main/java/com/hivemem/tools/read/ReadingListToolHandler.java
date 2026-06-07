package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Order(12)
public class ReadingListToolHandler implements ToolHandler {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final ReadToolService readToolService;

    public ReadingListToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "reading_list";
    }

    @Override
    public String description() {
        return "Unread and in-progress references with cell linkage counts.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .optionalString("ref_type", "Filter by reference type")
                .optionalInteger("limit", "Maximum number of results (default 20, max 100)")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String refType = optionalText(arguments, "ref_type");
        int limit = intValue(arguments, "limit");
        return readToolService.readingList(refType, limit);
    }

    private static String optionalText(JsonNode arguments, String field) {
        if (arguments == null || !arguments.hasNonNull(field)) {
            return null;
        }
        JsonNode node = arguments.get(field);
        if (!node.isTextual()) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    private static int intValue(JsonNode arguments, String field) {
        if (arguments == null || !arguments.hasNonNull(field)) {
            return DEFAULT_LIMIT;
        }
        JsonNode node = arguments.get(field);
        if (!node.isIntegralNumber()) {
            throw new IllegalArgumentException("Invalid limit");
        }
        int value = node.intValue();
        if (value <= 0 || value > MAX_LIMIT) {
            throw new IllegalArgumentException("Invalid limit");
        }
        return value;
    }
}
