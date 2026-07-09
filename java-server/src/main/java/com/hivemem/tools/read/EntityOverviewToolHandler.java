package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Order(48)
public class EntityOverviewToolHandler implements ToolHandler {

    private final ReadToolService readToolService;

    public EntityOverviewToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "entity_overview";
    }

    @Override
    public String description() {
        return "Everything about an entity in one call: top-ranked cells, active facts (exact + "
                + "substring), and depth-1 tunnels of the best cell match. Replaces the search + "
                + "quick_facts/search_kg + traverse triple."
                + " Pass depth=quick for a fast facts-only lookup (replaces the former quick_facts tool).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("subject", "Entity name to build a 360-degree overview for")
                .optionalIntegerInRange("limit", "Per-section limit (default 5)", 1, 20)
                .optionalEnumString("depth", "quick = facts only (fast); full = cells + facts + tunnels (default)", "quick", "full")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String subject = WriteArgumentParser.requiredText(arguments, "subject");
        Integer limit = WriteArgumentParser.optionalInteger(arguments, "limit");
        boolean quick = "quick".equals(WriteArgumentParser.optionalText(arguments, "depth"));
        return readToolService.entityOverview(subject, limit == null ? 5 : limit, quick);
    }
}
