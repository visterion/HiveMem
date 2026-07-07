package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Order(49)
public class BlueprintsMissingToolHandler implements ToolHandler {

    private final ReadToolService readToolService;

    public BlueprintsMissingToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "blueprints_missing";
    }

    @Override
    public String description() {
        return "Realms that have active cells but no active blueprint — backlog for orientation docs.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        return Map.of("realms", readToolService.blueprintsMissing());
    }
}
