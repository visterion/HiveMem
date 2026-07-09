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
@Order(37)
public class ReclassifyToolHandler implements ToolHandler {

    private static final String[] SIGNAL_VALUES = {
            "facts", "events", "discoveries", "preferences", "advice"
    };

    private final WriteToolService writeToolService;

    public ReclassifyToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "reclassify";
    }

    @Override
    public String description() {
        return "Reclassify one or more cells in-place (realm/signal/topic) without creating a new revision. "
                + "Exactly one of cell_ids or where must be provided (single cell = cell_ids:[id]). "
                + "At least one of realm/signal/topic must be provided. "
                + "where matches are capped at 1000 cells; matches over 200 require confirm: true. "
                + "A where selector with status: \"rejected\" is not reclassifiable (rolls back the batch). "
                + "Returns {updated: N, matched: N}.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .optionalUuidList("cell_ids", "UUIDs of cells to reclassify (exactly one of cell_ids/where)")
                .optionalObject("where", "Selector: realm | realm_in | signal | topic | tags | query | status "
                        + "(exactly one of cell_ids/where). \"none\" realm = cells without a realm.",
                        CellSelectorSchemas.where())
                .optionalBoolean("confirm", "Required (true) when where matches more than 200 cells")
                .optionalString("realm", "New realm for all cells (free-form; normalized to lowercase-dashes)")
                .optionalEnumString("signal", "New signal for all cells", SIGNAL_VALUES)
                .optionalString("topic", "New topic for all cells (free-form; normalized to lowercase-dashes)")
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
        String realm = WriteArgumentParser.optionalText(arguments, "realm");
        String signal = WriteArgumentParser.optionalText(arguments, "signal");
        String topic = WriteArgumentParser.optionalText(arguments, "topic");
        if (realm == null && signal == null && topic == null) {
            throw new IllegalArgumentException("at least one of realm/signal/topic required");
        }
        if (cellIds != null) {
            return writeToolService.bulkReclassify(principal, cellIds, realm, signal, topic);
        }
        boolean confirm = arguments.path("confirm").asBoolean(false);
        return writeToolService.bulkReclassifyBySelector(
                principal, CellSelector.fromJson(whereNode), realm, signal, topic, confirm);
    }
}
