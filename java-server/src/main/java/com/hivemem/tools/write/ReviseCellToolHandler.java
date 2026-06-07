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
@Order(35)
public class ReviseCellToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public ReviseCellToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "revise_cell";
    }

    @Override
    public String description() {
        return "Revise a cell by closing the current version and inserting a new one.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredUuid("old_id", "UUID of the cell version to close")
                .requiredString("new_content", "New content for the revised cell")
                .optionalString("new_summary", "New summary (auto-generated if omitted)")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        UUID oldId = WriteArgumentParser.requiredUuid(arguments, "old_id");
        String newContent = WriteArgumentParser.requiredText(arguments, "new_content");
        String newSummary = WriteArgumentParser.optionalText(arguments, "new_summary");
        return writeToolService.reviseCell(principal, oldId, newContent, newSummary);
    }
}
