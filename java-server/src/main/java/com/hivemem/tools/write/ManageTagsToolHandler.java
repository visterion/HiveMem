package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.search.CellSelector;
import com.hivemem.search.CellSelectorSchemas;
import com.hivemem.write.WriteArgumentParser;
import com.hivemem.write.WriteToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Order(41)
public class ManageTagsToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public ManageTagsToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "manage_tags";
    }

    @Override
    public String description() {
        return "Add and/or remove tags on one or more cells in a single transaction. "
                + "Exactly one of cell_ids or where must be provided (single cell = cell_ids:[id]). "
                + "At least one of add or remove must be provided. "
                + "where matches are capped at 1000 cells; matches over 200 require confirm: true. "
                + "Operations are idempotent. Returns {updated: N, matched: N}.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .optionalUuidList("cell_ids", "UUIDs of cells to update (exactly one of cell_ids/where)")
                .optionalObject("where", "Selector: realm | realm_in | signal | topic | tags | query | status "
                        + "(exactly one of cell_ids/where). \"none\" realm = cells without a realm.",
                        CellSelectorSchemas.where())
                .optionalBoolean("confirm", "Required (true) when where matches more than 200 cells")
                .optionalStringList("add", "Tags to add to every cell (idempotent union)")
                .optionalStringList("remove", "Tags to remove from every cell (idempotent)")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        List<UUID> cellIds = WriteArgumentParser.optionalUuidList(arguments, "cell_ids");
        JsonNode whereNode = arguments == null ? null : arguments.get("where");
        boolean hasWhere = whereNode != null && !whereNode.isNull();
        if ((cellIds == null) == !hasWhere) {
            throw new IllegalArgumentException("exactly one of cell_ids or where must be provided");
        }
        List<String> addTags = WriteArgumentParser.optionalTextList(arguments, "add");
        List<String> removeTags = WriteArgumentParser.optionalTextList(arguments, "remove");
        if ((addTags == null || addTags.isEmpty()) && (removeTags == null || removeTags.isEmpty())) {
            throw new IllegalArgumentException("at least one of add or remove required");
        }
        if (cellIds != null) {
            return writeToolService.bulkTag(principal, cellIds, addTags, removeTags);
        }
        boolean confirm = arguments.path("confirm").asBoolean(false);
        return writeToolService.bulkTagBySelector(
                principal, CellSelector.fromJson(whereNode), addTags, removeTags, confirm);
    }
}
