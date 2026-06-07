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
public class BulkTagToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public BulkTagToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "bulk_tag";
    }

    @Override
    public String description() {
        return "Add and/or remove tags on multiple cells in a single transaction. "
                + "At least one of add_tags or remove_tags must be provided. "
                + "Operations are idempotent. Returns {updated: N} with the number of cells processed.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredUuidList("cell_ids", "UUIDs of cells to update")
                .optionalStringList("add_tags", "Tags to add to every cell (idempotent union)")
                .optionalStringList("remove_tags", "Tags to remove from every cell (idempotent)")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        List<UUID> cellIds = WriteArgumentParser.requiredUuidList(arguments, "cell_ids");
        List<String> addTags = WriteArgumentParser.optionalTextList(arguments, "add_tags");
        List<String> removeTags = WriteArgumentParser.optionalTextList(arguments, "remove_tags");
        if ((addTags == null || addTags.isEmpty()) && (removeTags == null || removeTags.isEmpty())) {
            throw new IllegalArgumentException("at least one of add_tags or remove_tags required");
        }
        return writeToolService.bulkTag(principal, cellIds, addTags, removeTags);
    }
}
