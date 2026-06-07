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
@Order(28)
public class LinkReferenceToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public LinkReferenceToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "link_reference";
    }

    @Override
    public String description() {
        return "Link a reference to a cell.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredUuid("cell_id", "UUID of the cell to link to")
                .requiredUuid("reference_id", "UUID of the reference to link")
                .optionalEnumString("relation", "Link relation (default: source)",
                        "source", "inspired_by", "contradicts", "extends")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        UUID cellId = WriteArgumentParser.requiredUuid(arguments, "cell_id");
        UUID referenceId = WriteArgumentParser.requiredUuid(arguments, "reference_id");
        String relation = WriteArgumentParser.optionalText(arguments, "relation");
        if (relation == null) {
            relation = "source";
        }
        return writeToolService.linkReference(cellId, referenceId, relation);
    }
}
