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
@Order(44)
public class BulkReclassifyToolHandler implements ToolHandler {

    private static final String[] SIGNAL_VALUES = {
            "facts", "events", "discoveries", "preferences", "advice"
    };

    private final WriteToolService writeToolService;

    public BulkReclassifyToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "bulk_reclassify";
    }

    @Override
    public String description() {
        return "Reclassify multiple cells in-place (realm/signal/topic) in a single transaction. "
                + "At least one of realm/signal/topic must be provided. "
                + "Returns {updated: N} with the number of cells processed.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredUuidList("cell_ids", "UUIDs of cells to reclassify")
                .optionalString("realm", "New realm for all cells (free-form; normalized to lowercase-dashes)")
                .optionalEnumString("signal", "New signal for all cells", SIGNAL_VALUES)
                .optionalString("topic", "New topic for all cells (free-form; normalized to lowercase-dashes)")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        List<UUID> cellIds = WriteArgumentParser.requiredUuidList(arguments, "cell_ids");
        String realm = WriteArgumentParser.optionalText(arguments, "realm");
        String signal = WriteArgumentParser.optionalText(arguments, "signal");
        String topic = WriteArgumentParser.optionalText(arguments, "topic");
        if (realm == null && signal == null && topic == null) {
            throw new IllegalArgumentException("at least one of realm/signal/topic required");
        }
        return writeToolService.bulkReclassify(principal, cellIds, realm, signal, topic);
    }
}
