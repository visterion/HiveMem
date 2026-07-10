package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@Order(20)
public class GetAttachmentInfoToolHandler implements ToolHandler {

    private final AttachmentRepository repo;
    private final DSLContext dsl;

    public GetAttachmentInfoToolHandler(AttachmentRepository repo, DSLContext dsl) {
        this.repo = repo;
        this.dsl = dsl;
    }

    @Override
    public String name() { return "get_attachment_info"; }

    @Override
    public String description() {
        return "Get metadata for a single attachment by ID. " +
               "Returns cell_id (the extraction Cell), thumbnail_uri and content_uri for HTTP access.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("attachment_id", "UUID of the attachment")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        UUID id = WriteArgumentParser.requiredUuid(arguments, "attachment_id");
        Map<String, Object> row = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Attachment not found: " + id));

        Record cellRow = dsl.fetchOne("""
                SELECT cell_id FROM cell_attachments
                WHERE attachment_id = ? AND extraction_source = true
                LIMIT 1
                """, id);

        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("cell_id", cellRow != null ? cellRow.get("cell_id", UUID.class).toString() : null);
        result.put("thumbnail_uri", row.get("s3_key_thumbnail") != null
                ? "hivemem://attachments/" + id + "/thumbnail" : null);
        result.put("content_uri", "hivemem://attachments/" + id + "/content");

        result.remove("s3_key_original");
        result.remove("s3_key_thumbnail");

        return result;
    }
}
