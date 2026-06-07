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
@Order(33)
public class UpdateBlueprintToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public UpdateBlueprintToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "update_blueprint";
    }

    @Override
    public String description() {
        return "Create or update a blueprint for a realm.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("realm", "Realm this blueprint belongs to")
                .requiredString("title", "Blueprint title")
                .requiredString("narrative", "Narrative description of the realm")
                .optionalStringList("signal_order", "Preferred display order of signals")
                .optionalUuidList("key_cells", "UUIDs of key cells to highlight")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String realm = WriteArgumentParser.requiredText(arguments, "realm");
        String title = WriteArgumentParser.requiredText(arguments, "title");
        String narrative = WriteArgumentParser.requiredText(arguments, "narrative");
        List<String> signalOrder = WriteArgumentParser.optionalTextList(arguments, "signal_order");
        List<UUID> keyCells = WriteArgumentParser.optionalUuidList(arguments, "key_cells");
        return writeToolService.updateBlueprint(principal, realm, title, narrative, signalOrder, keyCells);
    }
}
