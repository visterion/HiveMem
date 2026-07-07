package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Order(51)
public class DataQualityReportToolHandler implements ToolHandler {

    private static final Set<String> VALID_SECTIONS = Set.of("unclassified", "disconnected", "duplicate_clusters");
    private static final double MIN_THRESHOLD = 0.5;
    private static final double MAX_THRESHOLD = 1.0;
    private static final double DEFAULT_THRESHOLD = 0.90;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 50;

    private final ReadToolService readToolService;

    public DataQualityReportToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "data_quality_report";
    }

    @Override
    public String description() {
        return "Report memory health: cells missing realm/signal/topic, cells with no tunnels and no facts "
             + "(disconnected), and near-duplicate cell pairs by embedding cosine similarity. "
             + "Sections default to all three; pass include to compute only a subset.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .optionalEnumStringList("include", "Sections to compute (default: all)",
                        new String[]{"unclassified", "disconnected", "duplicate_clusters"})
                .optionalNumber("threshold", "Cosine similarity threshold for duplicate_clusters (default 0.90, range 0.5-1.0)")
                .optionalInteger("limit", "Max duplicate pairs (default 50, max 200)")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        List<String> include = parseInclude(arguments);
        double threshold = parseThreshold(arguments);
        int limit = parseLimit(arguments);
        return readToolService.dataQualityReport(include, threshold, limit);
    }

    private static List<String> parseInclude(JsonNode arguments) {
        if (arguments == null || !arguments.hasNonNull("include")) {
            return List.of("unclassified", "disconnected", "duplicate_clusters");
        }
        JsonNode node = arguments.get("include");
        if (!node.isArray()) {
            throw new IllegalArgumentException("include must be an array");
        }
        List<String> include = new ArrayList<>();
        for (JsonNode element : node) {
            String value = element.asText();
            if (!VALID_SECTIONS.contains(value)) {
                throw new IllegalArgumentException("Invalid include value: " + value);
            }
            include.add(value);
        }
        if (include.isEmpty()) {
            throw new IllegalArgumentException("include must not be empty");
        }
        return include;
    }

    private static double parseThreshold(JsonNode arguments) {
        if (arguments == null || !arguments.hasNonNull("threshold")) {
            return DEFAULT_THRESHOLD;
        }
        JsonNode node = arguments.get("threshold");
        if (!node.isNumber()) {
            throw new IllegalArgumentException("threshold must be a number");
        }
        double value = node.asDouble();
        if (!Double.isFinite(value) || value < MIN_THRESHOLD || value > MAX_THRESHOLD) {
            throw new IllegalArgumentException("threshold must be between " + MIN_THRESHOLD + " and " + MAX_THRESHOLD);
        }
        return value;
    }

    private static int parseLimit(JsonNode arguments) {
        if (arguments == null || !arguments.hasNonNull("limit")) {
            return DEFAULT_LIMIT;
        }
        JsonNode node = arguments.get("limit");
        if (!node.isIntegralNumber()) {
            throw new IllegalArgumentException("limit must be an integer");
        }
        int value = node.asInt();
        if (value < MIN_LIMIT || value > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between " + MIN_LIMIT + " and " + MAX_LIMIT);
        }
        return value;
    }
}
