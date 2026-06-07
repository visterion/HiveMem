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
@Order(25)
public class KgInvalidateToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public KgInvalidateToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "kg_invalidate";
    }

    @Override
    public String description() {
        return "Invalidate a fact by setting valid_until to now.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredUuid("fact_id", "UUID of the fact to invalidate")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        UUID factId = WriteArgumentParser.requiredUuid(arguments, "fact_id");
        return writeToolService.kgInvalidate(factId);
    }
}
