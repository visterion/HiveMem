package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.search.CellSelector;
import com.hivemem.search.CellSelectorSchemas;
import com.hivemem.write.WriteArgumentParser;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Order(47)
public class ListCellIdsToolHandler implements ToolHandler {

    private final ReadToolService readToolService;

    public ListCellIdsToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() { return "list_cell_ids"; }

    @Override
    public String description() {
        return "List only cell IDs (+realm/signal/topic) matching a filter — the cheap discovery "
                + "primitive for bulk operations. Cross-realm via where.realm_in. "
                + "Returns {ids: [...], total: N}, paginated via limit/offset.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .optionalObject("where", "Filter: realm | realm_in | signal | topic | tags | query | status",
                        CellSelectorSchemas.where())
                .optionalIntegerInRange("limit", "Max ids to return (default 200)", 1, 1000)
                .optionalInteger("offset", "Pagination offset (default 0)")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        CellSelector selector = CellSelector.fromJson(arguments == null ? null : arguments.get("where"));
        Integer limit = WriteArgumentParser.optionalInteger(arguments, "limit");
        Integer offset = WriteArgumentParser.optionalInteger(arguments, "offset");
        return readToolService.listCellIds(selector,
                limit == null ? 200 : limit,
                offset == null ? 0 : offset);
    }
}
