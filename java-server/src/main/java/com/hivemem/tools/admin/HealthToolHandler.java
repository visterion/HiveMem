package com.hivemem.tools.admin;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.write.AdminToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(42)
public class HealthToolHandler implements ToolHandler {

    private final AdminToolService adminToolService;

    public HealthToolHandler(AdminToolService adminToolService) {
        this.adminToolService = adminToolService;
    }

    @Override
    public String name() {
        return "health";
    }

    @Override
    public String description() {
        return "Check DB connection, extension versions, counts, db size, and disk free.";
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        return adminToolService.health();
    }
}
