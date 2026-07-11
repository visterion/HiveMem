package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.search.CellSelector;
import com.hivemem.search.CellSelectorSchemas;
import com.hivemem.search.SearchProfile;
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
    private static final String[] INCLUDE_FIELDS = includeFieldsWithScores();

    private static String[] includeFieldsWithScores() {
        List<String> fields = new ArrayList<>(CellFieldSelection.searchIncludeFields());
        fields.add("scores");
        return fields.toArray(new String[0]);
    }

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
        return "6-signal ranked search over committed cells; returns metadata plus score_total and confidence_level by default. "
                + "Use include to request extra fields such as content, or include \"scores\" for the five per-signal sub-scores. "
                + "Use profile to pick a weight preset (balanced|semantic|recent|important|keyword). "
                + "Use a where object (realm | realm_in | signal | topic | tags | status) to filter; "
                + "pass where.realm=\"none\" to match cells with no realm assigned.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .optionalString("query", "Full-text search query. Optional if a realm or tags filter is present "
                        + "(via 'where' or the soft-deprecated flat params) — in that case, results are cells "
                        + "matching the filter, newest first, with no ranking scores. If both query and a filter "
                        + "are absent, the call fails with 'Missing query'.")
                .optionalInteger("limit", "Maximum number of results (default 10, max 100)")
                .optionalEnumStringList("include", "Optional fields to return. Defaults to summary, tags, importance, created_at.", INCLUDE_FIELDS)
                .optionalString("profile", "Weight preset: balanced (default) | semantic | recent | important | keyword")
                .optionalObject("where", "Filter object: realm | realm_in | signal | topic | tags | status. "
                        + "'query' is not allowed here.",
                        CellSelectorSchemas.where())
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String query = rawQueryText(arguments);
        int limit = boundedLimit(arguments, "limit", DEFAULT_LIMIT, MAX_LIMIT);
        // Soft-deprecated params: 'realm/signal/topic/tags/status' (use 'where' instead) and the
        // 'weight_*' knobs (use 'profile' instead) are no longer advertised in inputSchema(), but are
        // still parsed here so existing callers keep working. McpController does not validate args
        // against the schema, so hidden params are honored.
        String realm = WriteArgumentParser.optionalText(arguments, "realm");
        String signal = WriteArgumentParser.optionalText(arguments, "signal");
        String topic = WriteArgumentParser.optionalText(arguments, "topic");
        List<String> rawInclude = CellFieldSelection.parseInclude(arguments); // may be null
        boolean includeScores = rawInclude != null && rawInclude.contains("scores");
        List<String> cellInclude = rawInclude == null ? null
                : rawInclude.stream().filter(f -> !"scores".equals(f)).toList();
        CellFieldSelection selection = CellFieldSelection.forSearch(cellInclude);
        List<String> realmIn = null;
        SearchProfile profile = SearchProfile.fromString(WriteArgumentParser.optionalText(arguments, "profile"));
        double weightSemantic = optionalWeight(arguments, "weight_semantic", profile.semantic);
        double weightKeyword = optionalWeight(arguments, "weight_keyword", profile.keyword);
        double weightRecency = optionalWeight(arguments, "weight_recency", profile.recency);
        double weightImportance = optionalWeight(arguments, "weight_importance", profile.importance);
        double weightPopularity = optionalWeight(arguments, "weight_popularity", profile.popularity);
        double weightGraphProximity = optionalWeight(arguments, "weight_graph_proximity", profile.graphProximity);
        String status = WriteArgumentParser.optionalText(arguments, "status");
        List<String> tags = null;
        if (arguments != null && arguments.hasNonNull("tags") && arguments.get("tags").isArray()) {
            tags = new ArrayList<>();
            for (JsonNode element : arguments.get("tags")) {
                tags.add(element.asText());
            }
        }
        JsonNode whereNode = arguments == null ? null : arguments.get("where");
        if (whereNode != null && !whereNode.isNull()) {
            if (realm != null || signal != null || topic != null || tags != null || status != null) {
                throw new IllegalArgumentException("where is mutually exclusive with flat filter params");
            }
            CellSelector sel = CellSelector.fromJson(whereNode);
            if (sel.query() != null) {
                throw new IllegalArgumentException("where.query is not supported on search; use the top-level query");
            }
            realm = sel.realm();
            realmIn = sel.realmIn();
            signal = sel.signal();
            topic = sel.topic();
            tags = sel.tags() == null ? null : new ArrayList<>(sel.tags());
            status = sel.status();
        }
        boolean hasFilter = realm != null || realmIn != null || signal != null || topic != null
                || (tags != null && !tags.isEmpty());
        if (query == null || query.isBlank()) {
            if (!hasFilter) {
                // Full-table-dump-as-default is intentionally not supported: a blank query
                // with no realm/tags/signal/topic filter still fails, exactly like before
                // 'query' became optional.
                throw new IllegalArgumentException("Missing query");
            }
            return readToolService.searchBrowse(limit, realm, signal, topic, selection, tags, status, realmIn);
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
                status,
                realmIn,
                includeScores
        );
    }

    /**
     * Unlike {@link WriteArgumentParser#optionalText}, this allows an explicitly blank/empty
     * string (the UI's realm-drilldown sends {@code query: ""} alongside a realm filter) —
     * blank means "no query", not "invalid argument".
     */
    private static String rawQueryText(JsonNode arguments) {
        if (arguments == null || !arguments.has("query") || arguments.get("query").isNull()) {
            return null;
        }
        JsonNode node = arguments.get("query");
        if (!node.isTextual()) {
            throw new IllegalArgumentException("Invalid query");
        }
        return node.asText();
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
