package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(11)
public class PendingApprovalsToolHandler implements ToolHandler {

    private final ReadToolService readToolService;

    public PendingApprovalsToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "pending_approvals";
    }

    @Override
    public String description() {
        return "Pending cells, facts, and tunnels awaiting approval.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        return readToolService.pendingApprovals();
    }
}
