package com.hivemem.tools.read;

import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.attachment.AttachmentService;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.tools.write.UploadAttachmentToolHandler;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M27: missing/invalid required arguments must surface as IllegalArgumentException
 * (mapped to JSON-RPC -32602 by McpController), not as an NPE (-32603).
 */
class AttachmentToolHandlerArgumentsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AuthPrincipal WRITER = new AuthPrincipal("test", AuthRole.WRITER);

    private final ListAttachmentsToolHandler listHandler =
            new ListAttachmentsToolHandler(Mockito.mock(AttachmentRepository.class));
    private final GetAttachmentInfoToolHandler infoHandler =
            new GetAttachmentInfoToolHandler(Mockito.mock(AttachmentRepository.class), Mockito.mock(DSLContext.class));
    private final UploadAttachmentToolHandler uploadHandler =
            new UploadAttachmentToolHandler(Mockito.mock(AttachmentService.class));

    @Test
    void listAttachmentsWithoutCellIdThrowsIllegalArgument() {
        assertThatThrownBy(() -> listHandler.call(WRITER, MAPPER.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing cell_id");
        assertThatThrownBy(() -> listHandler.call(WRITER, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing cell_id");
    }

    @Test
    void listAttachmentsWithMalformedUuidThrowsIllegalArgument() {
        JsonNode args = MAPPER.readTree("""
                {"cell_id":"not-a-uuid"}
                """);
        assertThatThrownBy(() -> listHandler.call(WRITER, args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid cell_id");
    }

    @Test
    void getAttachmentInfoWithoutAttachmentIdThrowsIllegalArgument() {
        assertThatThrownBy(() -> infoHandler.call(WRITER, MAPPER.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing attachment_id");
        assertThatThrownBy(() -> infoHandler.call(WRITER, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing attachment_id");
    }

    @Test
    void uploadAttachmentWithoutMimeTypeThrowsIllegalArgument() {
        JsonNode args = MAPPER.readTree("""
                {"realm":"projects","filename":"report.pdf","data":"aGVsbG8="}
                """);
        assertThatThrownBy(() -> uploadHandler.call(WRITER, args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing mime_type");
    }

    @Test
    void uploadAttachmentWithoutAnyArgumentsThrowsIllegalArgument() {
        assertThatThrownBy(() -> uploadHandler.call(WRITER, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing realm");
    }

    @Test
    void uploadAttachmentWithExplicitNullOptionalsDoesNotNpe() {
        // L-M1: JSON null for optional fields must be treated as absent (hasNonNull).
        JsonNode args = MAPPER.readTree("""
                {"realm":"projects","filename":"report.pdf","mime_type":"application/pdf",
                 "data":"aGVsbG8=","signal":null,"topic":null,"cell_id":null}
                """);
        // Must not throw: parsing succeeds, service is a mock.
        uploadHandler.call(WRITER, args);
    }

    @Test
    void uploadAttachmentWithInvalidBase64ThrowsIllegalArgument() {
        JsonNode args = MAPPER.readTree("""
                {"realm":"projects","filename":"report.pdf","mime_type":"application/pdf","data":"%%%"}
                """);
        assertThatThrownBy(() -> uploadHandler.call(WRITER, args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid data (not valid Base64)");
    }
}
