package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import com.hivemem.write.WriteToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@Order(33)
public class RemoveTunnelToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public RemoveTunnelToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "remove_tunnel";
    }

    @Override
    public String description() {
        return "Soft-delete a tunnel by setting valid_until.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredUuid("tunnel_id", "UUID of the tunnel to remove")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        UUID tunnelId = WriteArgumentParser.requiredUuid(arguments, "tunnel_id");
        return writeToolService.removeTunnel(tunnelId);
    }
}
