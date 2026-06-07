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
@Order(11)
public class FacetCountToolHandler implements ToolHandler {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;

    private final ReadToolService readToolService;

    public FacetCountToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "facet_count";
    }

    @Override
    public String description() {
        return "Aggregate document counts grouped by a cell field (tag, status, realm, year, signal), " +
               "honoring realm/signal/topic/tags/status/query filters.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .optionalString("realm", "Restrict to this realm")
                .optionalString("signal", "Restrict to this signal")
                .optionalString("topic", "Restrict to this topic")
                .optionalStringList("tags", "Filter to cells that have ANY of the given tags")
                .optionalString("status", "Restrict to a status: committed | pending | rejected")
                .optionalString("query", "Optional full-text filter applied before counting")
                .requiredEnumStringList("fields", "Fields to facet on", "tag", "status", "realm", "year", "signal")
                .optionalInteger("limit", "Maximum buckets per facet (default 10, max 100)")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String realm = WriteArgumentParser.optionalText(arguments, "realm");
        String signal = WriteArgumentParser.optionalText(arguments, "signal");
        String topic = WriteArgumentParser.optionalText(arguments, "topic");
        String status = WriteArgumentParser.optionalText(arguments, "status");
        String query = WriteArgumentParser.optionalText(arguments, "query");

        List<String> tags = null;
        if (arguments != null && arguments.hasNonNull("tags") && arguments.get("tags").isArray()) {
            tags = new ArrayList<>();
            for (JsonNode element : arguments.get("tags")) {
                tags.add(element.asText());
            }
        }

        if (arguments == null || !arguments.hasNonNull("fields") || !arguments.get("fields").isArray()) {
            throw new IllegalArgumentException("Missing required field: fields");
        }
        List<String> fields = new ArrayList<>();
        for (JsonNode element : arguments.get("fields")) {
            fields.add(element.asText());
        }
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("fields must not be empty");
        }

        int limit = DEFAULT_LIMIT;
        Integer limitArg = WriteArgumentParser.optionalInteger(arguments, "limit");
        if (limitArg != null) {
            if (limitArg < 1 || limitArg > MAX_LIMIT) {
                throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
            }
            limit = limitArg;
        }

        return readToolService.facetCount(realm, signal, topic, tags, status, query, fields, limit);
    }
}
