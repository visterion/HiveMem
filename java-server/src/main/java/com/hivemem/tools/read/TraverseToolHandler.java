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
    /**
     * The recursive CTE dedupes on rows including depth, so a cyclic graph re-qualifies
     * every edge at every depth level; a high max_depth on a densely-connected graph can pin
     * a DB connection for a long time. Lowered from 100 (see B3/M14 fix).
     */
    private static final int MAX_MAX_DEPTH = 10;
    private static final int DEFAULT_MAX_NODES = 200;
    private static final int MIN_MAX_NODES = 1;
    private static final int MAX_MAX_NODES = 1000;

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
        return "Bidirectional cell-to-cell graph traversal. Returns {edges, node_count, truncated}; "
                + "truncated=true when max_nodes (default 200) or the internal edge backstop was hit.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredUuid("cell_id", "UUID of the starting cell")
                .optionalString("relation_filter", "Limit traversal to this relation type")
                .optionalInteger("max_depth", "Maximum traversal depth (default 2, max 10)")
                .optionalIntegerInRange("max_nodes", "Cap on distinct cells in the result (default 200)", 1, 1000)
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
        int maxNodes = maxNodesValue(arguments);
        return readToolService.traverse(UUID.fromString(cellId), maxDepth, relationFilter, maxNodes);
    }

    private static String textValue(JsonNode arguments, String field) {
        if (arguments == null || !arguments.hasNonNull(field)) {
            return null;
        }
        String value = arguments.get(field).asText();
        return value.isBlank() ? null : value;
    }

    private static int maxNodesValue(JsonNode arguments) {
        if (arguments == null || !arguments.hasNonNull("max_nodes")) {
            return DEFAULT_MAX_NODES;
        }
        JsonNode node = arguments.get("max_nodes");
        if (!node.canConvertToInt()) {
            throw new IllegalArgumentException("Invalid max_nodes");
        }
        int value = node.intValue();
        if (value < MIN_MAX_NODES || value > MAX_MAX_NODES) {
            throw new IllegalArgumentException("Invalid max_nodes");
        }
        return value;
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
