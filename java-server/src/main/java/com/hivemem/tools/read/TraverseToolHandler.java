package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@Order(6)
public class TraverseToolHandler implements ToolHandler {
    private static final int DEFAULT_MAX_DEPTH = 2;
    private static final int MAX_MAX_DEPTH = 100;

    private final ReadToolService readToolService;

    public TraverseToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "traverse";
    }

    @Override
    public String description() {
        return "Bidirectional cell-to-cell graph traversal.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredUuid("cell_id", "UUID of the starting cell")
                .optionalString("relation_filter", "Limit traversal to this relation type")
                .optionalInteger("max_depth", "Maximum traversal depth (default 2, max 100)")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        if (arguments == null || !arguments.hasNonNull("cell_id")) {
            throw new IllegalArgumentException("Missing cell_id");
        }

        String cellId = arguments.get("cell_id").asText();
        if (cellId.isBlank()) {
            throw new IllegalArgumentException("Missing cell_id");
        }

        String relationFilter = textValue(arguments, "relation_filter");
        int maxDepth = intValue(arguments, "max_depth");
        return readToolService.traverse(UUID.fromString(cellId), maxDepth, relationFilter);
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
            return DEFAULT_MAX_DEPTH;
        }
        JsonNode node = arguments.get(field);
        if (!node.canConvertToInt()) {
            throw new IllegalArgumentException("Invalid max_depth");
        }
        int value = node.intValue();
        if (value <= 0 || value > MAX_MAX_DEPTH) {
            throw new IllegalArgumentException("Invalid max_depth");
        }
        return value;
    }
}
