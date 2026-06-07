package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import com.hivemem.attachment.AttachmentService;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Component
@Order(38)
public class UploadAttachmentToolHandler implements ToolHandler {

    private final AttachmentService service;

    public UploadAttachmentToolHandler(AttachmentService service) {
        this.service = service;
    }

    @Override
    public String name() { return "upload_attachment"; }

    @Override
    public String description() {
        return "Upload a file and store extracted content in a new Cell. " +
               "Pass file content as Base64 in `data`. " +
               "`realm` is required. Provide `cell_id` to tunnel the new Cell to an existing one. " +
               "For files >1 MB prefer the HTTP endpoint POST /api/attachments (multipart).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("realm",    "Realm for the new extraction Cell (e.g. Projects)")
                .optionalEnumString("signal", "Signal classification",
                        "facts", "events", "discoveries", "preferences", "advice")
                .optionalString("topic",    "Topic for the new extraction Cell")
                .optionalString("cell_id",  "UUID of an existing cell — creates a related_to tunnel")
                .requiredString("filename", "Original filename including extension (e.g. report.pdf)")
                .requiredString("mime_type","MIME type (e.g. application/pdf, image/png, message/rfc822)")
                .requiredString("data",     "Base64-encoded file content")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        if (principal.role() == AuthRole.READER) throw new IllegalArgumentException("Reader role cannot upload");
        String realm    = arguments.get("realm").asText();
        String signal   = arguments.has("signal")  ? arguments.get("signal").asText()  : null;
        String topic    = arguments.has("topic")   ? arguments.get("topic").asText()   : null;
        UUID linkCellId = arguments.has("cell_id") ? UUID.fromString(arguments.get("cell_id").asText()) : null;
        String filename = arguments.get("filename").asText();
        String mimeType = arguments.get("mime_type").asText();
        byte[] bytes    = Base64.getDecoder().decode(arguments.get("data").asText());
        try {
            return service.ingest(new ByteArrayInputStream(bytes), filename, mimeType,
                    realm, signal, topic, linkCellId, principal.name());
        } catch (Exception e) {
            throw new RuntimeException("Upload failed: " + e.getMessage(), e);
        }
    }
}
