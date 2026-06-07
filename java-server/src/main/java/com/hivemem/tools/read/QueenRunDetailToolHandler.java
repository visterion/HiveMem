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
@Order(22)
public class QueenRunDetailToolHandler implements ToolHandler {

    private final QueenRunsService service;

    public QueenRunDetailToolHandler(QueenRunsService service) {
        this.service = service;
    }

    @Override
    public String name() { return "queen_run_detail"; }

    @Override
    public String description() {
        return "Detail + event timeline for a single Queen/Bee run (admin-only).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("run_id", "Vistierie run id.")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        if (arguments == null || !arguments.hasNonNull("run_id")) {
            throw new IllegalArgumentException("Missing run_id");
        }
        String runId = arguments.get("run_id").asText();
        if (runId.isBlank()) {
            throw new IllegalArgumentException("Missing run_id");
        }
        try {
            return service.runDetail(runId);
        } catch (VistierieUnavailableException e) {
            return Map.of("run", Map.of(), "events", List.of(), "unavailable", true);
        }
    }
}
