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
@Order(27)
public class ReviseFactToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public ReviseFactToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "revise_fact";
    }

    @Override
    public String description() {
        return "Revise a fact by closing the current version and inserting a new one.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredUuid("old_id", "UUID of the fact version to close")
                .requiredString("new_object", "New object value for the revised fact")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        UUID oldId = WriteArgumentParser.requiredUuid(arguments, "old_id");
        String newObject = WriteArgumentParser.requiredText(arguments, "new_object");
        return writeToolService.reviseFact(principal, oldId, newObject);
    }
}
