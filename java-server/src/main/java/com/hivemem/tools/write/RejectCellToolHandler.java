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
@Order(40)
public class RejectCellToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public RejectCellToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "reject_cell";
    }

    @Override
    public String description() {
        return "Mark a cell as rejected (soft-delete): the live committed or pending version is set to "
                + "status=rejected and drops out of search. Idempotent if already rejected. "
                + "Targets the live version only. Does not modify the cell's facts or tunnels. "
                + "Returns {id, status: \"rejected\"}.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredUuid("cell_id", "UUID of the cell to reject")
                .optionalString("reason", "Optional reason recorded in the op-log (e.g. \"duplicate of <id>\")")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        UUID cellId = WriteArgumentParser.requiredUuid(arguments, "cell_id");
        String reason = WriteArgumentParser.optionalText(arguments, "reason");
        return writeToolService.rejectCell(principal, cellId, reason);
    }
}
