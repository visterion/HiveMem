package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Order(2)
public class SearchToolHandler implements ToolHandler {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;
    private static final String[] INCLUDE_FIELDS =
            CellFieldSelection.searchIncludeFields().toArray(new String[0]);

    private final ReadToolService readToolService;

    public SearchToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "search";
    }

    @Override
    public String description() {
        return "5-signal ranked search over committed cells with metadata by default; use include to request extra fields such as content.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("query", "Full-text search query")
                .optionalInteger("limit", "Maximum number of results (default 10, max 100)")
                .optionalString("realm", "Restrict search to this realm")
                .optionalString("signal", "Restrict search to this signal")
                .optionalString("topic", "Restrict search to this topic")
                .optionalEnumStringList("include", "Optional fields to return. Defaults to summary, tags, importance, created_at.", INCLUDE_FIELDS)
                .optionalNumber("weight_semantic", "Semantic similarity weight (default 0.30)")
                .optionalNumber("weight_keyword", "Keyword match weight (default 0.15)")
                .optionalNumber("weight_recency", "Recency weight (default 0.15)")
                .optionalNumber("weight_importance", "Importance weight (default 0.15)")
                .optionalNumber("weight_popularity", "Popularity weight (default 0.15)")
                .optionalNumber("weight_graph_proximity", "Graph proximity weight (default 0.10)")
                .optionalStringList("tags", "Filter to cells that have ANY of the given tags (array overlap)")
                .optionalString("status", "Restrict to a status: committed | pending | rejected (default committed)")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String query = WriteArgumentParser.requiredText(arguments, "query");
        int limit = boundedLimit(arguments, "limit", DEFAULT_LIMIT, MAX_LIMIT);
        String realm = WriteArgumentParser.optionalText(arguments, "realm");
        String signal = WriteArgumentParser.optionalText(arguments, "signal");
        String topic = WriteArgumentParser.optionalText(arguments, "topic");
        CellFieldSelection selection = CellFieldSelection.forSearch(CellFieldSelection.parseInclude(arguments));
        double weightSemantic = optionalWeight(arguments, "weight_semantic", 0.30d);
        double weightKeyword = optionalWeight(arguments, "weight_keyword", 0.15d);
        double weightRecency = optionalWeight(arguments, "weight_recency", 0.15d);
        double weightImportance = optionalWeight(arguments, "weight_importance", 0.15d);
        double weightPopularity = optionalWeight(arguments, "weight_popularity", 0.15d);
        double weightGraphProximity = optionalWeight(arguments, "weight_graph_proximity", 0.10d);
        String status = WriteArgumentParser.optionalText(arguments, "status");
        List<String> tags = null;
        if (arguments != null && arguments.hasNonNull("tags") && arguments.get("tags").isArray()) {
            tags = new ArrayList<>();
            for (JsonNode element : arguments.get("tags")) {
                tags.add(element.asText());
            }
        }
        return readToolService.search(
                query,
                limit,
                realm,
                signal,
                topic,
                selection,
                weightSemantic,
                weightKeyword,
                weightRecency,
                weightImportance,
                weightPopularity,
                weightGraphProximity,
                tags,
                status
        );
    }

    private static int boundedLimit(JsonNode arguments, String field, int defaultValue, int max) {
        Integer value = WriteArgumentParser.optionalInteger(arguments, field);
        if (value == null) {
            return defaultValue;
        }
        if (value < 1 || value > max) {
            throw new IllegalArgumentException("Invalid limit");
        }
        return value;
    }

    private static double optionalWeight(JsonNode arguments, String field, double defaultValue) {
        if (arguments == null || !arguments.has(field) || arguments.get(field).isNull()) {
            return defaultValue;
        }
        JsonNode node = arguments.get(field);
        if (!node.isNumber()) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        double value = node.asDouble();
        if (!Double.isFinite(value) || value < 0.0d) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value;
    }
}
