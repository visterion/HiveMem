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
@Order(32)
public class AddTunnelToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public AddTunnelToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "add_tunnel";
    }

    @Override
    public String description() {
        return "Create a cell-to-cell tunnel.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredUuid("from_cell", "UUID of the source cell")
                .requiredUuid("to_cell", "UUID of the target cell")
                .requiredEnumString("relation", "Tunnel relation",
                        "related_to", "builds_on", "contradicts", "refines")
                .optionalString("note", "Optional note about this tunnel")
                .optionalEnumString("status", "Initial status (default: committed)",
                        "pending", "committed", "rejected")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        UUID fromCell = WriteArgumentParser.requiredUuid(arguments, "from_cell");
        UUID toCell = WriteArgumentParser.requiredUuid(arguments, "to_cell");
        String relation = WriteArgumentParser.requiredText(arguments, "relation");
        String note = WriteArgumentParser.optionalText(arguments, "note");
        String status = WriteArgumentParser.optionalText(arguments, "status");
        return writeToolService.addTunnel(principal, fromCell, toCell, relation, note, status);
    }
}
