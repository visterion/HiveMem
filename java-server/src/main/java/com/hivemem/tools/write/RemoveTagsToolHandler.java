package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import com.hivemem.write.WriteToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Order(43)
public class RemoveTagsToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public RemoveTagsToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "remove_tags";
    }

    @Override
    public String description() {
        return "Remove one or more tags from a cell (idempotent — tags not present are ignored). "
                + "Returns {updated: 1} when the cell was found, {updated: 0} if not found or already closed.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredUuid("cell_id", "UUID of the cell to modify")
                .requiredStringList("tags", "Tags to remove (strings, must be non-empty)")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        UUID cellId = WriteArgumentParser.requiredUuid(arguments, "cell_id");
        List<String> tags = WriteArgumentParser.optionalTextList(arguments, "tags");
        if (tags == null || tags.isEmpty()) {
            throw new IllegalArgumentException("Missing tags");
        }
        return writeToolService.removeTags(principal, cellId, tags);
    }
}
