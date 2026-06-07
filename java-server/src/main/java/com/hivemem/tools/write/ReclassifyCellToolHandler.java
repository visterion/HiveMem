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
@Order(37)
public class ReclassifyCellToolHandler implements ToolHandler {

    private static final String[] SIGNAL_VALUES = {
            "facts", "events", "discoveries", "preferences", "advice"
    };

    private final WriteToolService writeToolService;

    public ReclassifyCellToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "reclassify_cell";
    }

    @Override
    public String description() {
        return "Reclassify a cell in-place: update realm/topic/signal without creating a new revision. "
                + "Leaves content, embeddings, tunnels, facts, and references untouched. "
                + "Use for taxonomy migrations (e.g. renaming realms, re-homing cells). "
                + "At least one of realm/topic/signal must be provided.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredUuid("cell_id", "UUID of the cell to reclassify")
                .optionalString("realm", "New realm (free-form; normalized to lowercase with spaces replaced by dashes)")
                .optionalString("topic", "New topic (free-form; normalized to lowercase with spaces replaced by dashes)")
                .optionalEnumString("signal", "New signal classification", SIGNAL_VALUES)
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        UUID cellId = WriteArgumentParser.requiredUuid(arguments, "cell_id");
        String realm = WriteArgumentParser.optionalText(arguments, "realm");
        String topic = WriteArgumentParser.optionalText(arguments, "topic");
        String signal = WriteArgumentParser.optionalText(arguments, "signal");
        return writeToolService.reclassifyCell(principal, cellId, realm, topic, signal);
    }
}
