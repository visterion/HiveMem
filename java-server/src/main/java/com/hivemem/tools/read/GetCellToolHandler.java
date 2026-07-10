package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@Order(4)
public class GetCellToolHandler implements ToolHandler {

    private static final String[] INCLUDE_FIELDS =
            CellFieldSelection.getCellIncludeFields().toArray(new String[0]);

    private final ReadToolService readToolService;

    public GetCellToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "get_cell";
    }

    @Override
    public String description() {
        return "Single cell by UUID with metadata by default; use include to request content or other optional fields.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredUuid("cell_id", "UUID of the cell to retrieve")
                .optionalEnumStringList("include", "Optional fields to return. Defaults to summary, key_points, insight, tags, importance, source, actionability, status, created_at.", INCLUDE_FIELDS)
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        if (arguments == null || !arguments.hasNonNull("cell_id")) {
            throw new IllegalArgumentException("Missing cell_id");
        }

        String cellId = arguments.get("cell_id").asText();
        if (cellId.isBlank()) {
            throw new IllegalArgumentException("Missing cell_id");
        }

        CellFieldSelection selection = CellFieldSelection.forGetCell(CellFieldSelection.parseInclude(arguments));
        Object cell = readToolService.getCell(principal, UUID.fromString(cellId), selection);
        if (cell == null) {
            // Without this the client receives the literal text "null" as a success.
            throw new IllegalArgumentException("Cell not found: " + cellId);
        }
        return cell;
    }
}
