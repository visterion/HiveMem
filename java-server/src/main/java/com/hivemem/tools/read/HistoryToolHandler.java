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
@Order(9)
public class HistoryToolHandler implements ToolHandler {

    private final ReadToolService readToolService;

    public HistoryToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "history";
    }

    @Override
    public String description() {
        return "Trace revisions of a cell or fact by id.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("type", "Entity type: 'cell' or 'fact'")
                .requiredUuid("id", "UUID of the cell or fact")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String type = requiredText(arguments, "type");
        String id = requiredText(arguments, "id");
        UUID uuid = UUID.fromString(id);
        return switch (type) {
            case "cell" -> readToolService.cellHistory(uuid);
            case "fact" -> readToolService.factHistory(uuid);
            default -> throw new IllegalArgumentException("Invalid type, must be 'cell' or 'fact'");
        };
    }

    private static String requiredText(JsonNode arguments, String field) {
        if (arguments == null || !arguments.hasNonNull(field)) {
            throw new IllegalArgumentException("Missing " + field);
        }
        String value = arguments.get(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return value;
    }
}
