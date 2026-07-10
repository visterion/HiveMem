package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@Order(19)
public class ListAttachmentsToolHandler implements ToolHandler {

    private final AttachmentRepository repo;

    public ListAttachmentsToolHandler(AttachmentRepository repo) {
        this.repo = repo;
    }

    @Override
    public String name() { return "list_attachments"; }

    @Override
    public String description() {
        return "List all file attachments linked to a specific cell. Returns metadata only (no file content).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("cell_id", "UUID of the cell")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        UUID cellId = WriteArgumentParser.requiredUuid(arguments, "cell_id");
        return repo.findByCellId(cellId);
    }
}
