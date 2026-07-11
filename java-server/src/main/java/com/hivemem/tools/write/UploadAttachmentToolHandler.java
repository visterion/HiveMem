package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import com.hivemem.attachment.AttachmentService;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Component
@Order(38)
public class UploadAttachmentToolHandler implements ToolHandler {

    /**
     * Upper bound on the inline Base64 {@code data} payload length (in characters, not
     * decoded bytes). Guards against a caller sending a huge inline payload that would
     * otherwise be fully allocated by {@link Base64.Decoder#decode(String)} before any
     * size check — this is a cheap {@code String.length()} check before that allocation.
     */
    static final int MAX_INLINE_BASE64 = 2 * 1024 * 1024;

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
               "For files larger than ~1 MB prefer the HTTP endpoint POST /api/attachments (multipart); " +
               "inline Base64 payloads are hard-capped at " + (MAX_INLINE_BASE64 / (1024 * 1024)) + " MB.";
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
        String realm    = WriteArgumentParser.requiredText(arguments, "realm");
        String signal   = WriteArgumentParser.optionalText(arguments, "signal");
        String topic    = WriteArgumentParser.optionalText(arguments, "topic");
        UUID linkCellId = WriteArgumentParser.optionalUuid(arguments, "cell_id");
        String filename = WriteArgumentParser.requiredText(arguments, "filename");
        String mimeType = WriteArgumentParser.requiredText(arguments, "mime_type");
        String data     = WriteArgumentParser.requiredText(arguments, "data");
        if (data.length() > MAX_INLINE_BASE64) {
            throw new IllegalArgumentException(
                    "Inline upload too large (Base64 payload capped at "
                            + (MAX_INLINE_BASE64 / (1024 * 1024)) + " MB); use POST /api/attachments for larger files");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid data (not valid Base64)");
        }
        try {
            return service.ingest(new ByteArrayInputStream(bytes), filename, mimeType,
                    realm, signal, topic, linkCellId, principal.name());
        } catch (Exception e) {
            throw new RuntimeException("Upload failed: " + e.getMessage(), e);
        }
    }
}
