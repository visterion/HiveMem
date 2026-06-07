package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(13)
public class ListAgentsToolHandler implements ToolHandler {

    private final ReadToolService readToolService;

    public ListAgentsToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "list_agents";
    }

    @Override
    public String description() {
        return "Registered agents ordered by name.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        return readToolService.listAgents();
    }
}
