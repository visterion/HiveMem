package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Order(3)
public class SearchKgToolHandler implements ToolHandler {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 100;

    private final ReadToolService readToolService;

    public SearchKgToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "search_kg";
    }

    @Override
    public String description() {
        return "Search active facts. ILIKE filters by default; pass query for semantic search over facts.embedding (falls back to ILIKE filters if unavailable).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .optionalString("query", "Semantic search over facts (embeds the query; falls back to ILIKE filters if unavailable)")
                .optionalString("subject", "Filter by subject (ILIKE pattern)")
                .optionalString("predicate", "Filter by predicate (ILIKE pattern)")
                .optionalString("object_", "Filter by object (ILIKE pattern)")
                .optionalInteger("limit", "Maximum number of results (default 100, max 100)")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String query = textValue(arguments, "query");
        String subject = textValue(arguments, "subject");
        String predicate = textValue(arguments, "predicate");
        String object_ = textValue(arguments, "object_");
        int limit = intValue(arguments, "limit");
        return readToolService.searchKg(query, subject, predicate, object_, limit);
    }

    private static String textValue(JsonNode arguments, String field) {
        if (arguments == null || !arguments.hasNonNull(field)) {
            return null;
        }
        String value = arguments.get(field).asText();
        return value.isBlank() ? null : value;
    }

    private static int intValue(JsonNode arguments, String field) {
        if (arguments == null || !arguments.hasNonNull(field)) {
            return DEFAULT_LIMIT;
        }
        JsonNode node = arguments.get(field);
        if (!node.canConvertToInt()) {
            throw new IllegalArgumentException("Invalid limit");
        }
        int value = node.intValue();
        if (value <= 0 || value > MAX_LIMIT) {
            throw new IllegalArgumentException("Invalid limit");
        }
        return value;
    }
}
