package com.hivemem.tools.read;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.queen.QueenRunsService;
import com.hivemem.queen.VistierieUnavailableException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

@Component
@Order(41)
public class QueenRunsToolHandler implements ToolHandler {

    private final QueenRunsService service;

    public QueenRunsToolHandler(QueenRunsService service) {
        this.service = service;
    }

    @Override
    public String name() { return "queen_runs"; }

    @Override
    public String description() {
        return "Recent Queen/Bee agent runs from Vistierie (admin-only). Cost fields present only when an admin token is configured.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .optionalIntegerInRange("limit", "Max runs to return (default 50).", 1, 200)
                .optionalIntegerInRange("offset", "Pagination offset (default 0).", 0, 100000)
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        int limit = arguments != null && arguments.has("limit") ? arguments.get("limit").asInt() : 50;
        int offset = arguments != null && arguments.has("offset") ? arguments.get("offset").asInt() : 0;
        try {
            return service.listRuns(limit, offset);
        } catch (VistierieUnavailableException e) {
            return Map.of("items", List.of(), "total", 0, "costAvailable", false, "unavailable", true);
        }
    }
}
