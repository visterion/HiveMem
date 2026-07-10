package com.hivemem.tools.write;

import com.hivemem.attachment.AttachmentService;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class UploadAttachmentToolHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AttachmentService service;
    private UploadAttachmentToolHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(AttachmentService.class);
        handler = new UploadAttachmentToolHandler(service);
    }

    @Test
    void name_isUploadAttachment() throws Exception {
        assertEquals("upload_attachment", handler.name());
    }

    @Test
    void description_mentionsBase64() throws Exception {
        assertNotNull(handler.description());
        assert handler.description().contains("Base64");
    }

    @Test
    void inputSchema_declaresRequiredFields() throws Exception {
        Map<String, Object> schema = handler.inputSchema();
        assertEquals("object", schema.get("type"));
        Map<?,?> props = (Map<?,?>) schema.get("properties");
        assertNotNull(props.get("realm"));
        assertNotNull(props.get("filename"));
        assertNotNull(props.get("mime_type"));
        assertNotNull(props.get("data"));
    }

    @Test
    void readerRoleIsRejected() throws Exception {
        AuthPrincipal reader = new AuthPrincipal("r", AuthRole.READER);
        JsonNode args = MAPPER.readTree("""
                {"realm":"work","filename":"a.txt","mime_type":"text/plain","data":""}""");

        assertThrows(IllegalArgumentException.class, () -> handler.call(reader, args));
        verifyNoInteractions(service);
    }

    @Test
    void minimalArgs_decodesAndCallsService() throws Exception {
        Map<String, Object> ingestResult = Map.of("id", UUID.randomUUID().toString());
        when(service.ingest(any(InputStream.class), eq("hello.txt"), eq("text/plain"),
                eq("work"), isNull(), isNull(), isNull(), eq("alice")))
                .thenReturn(ingestResult);

        AuthPrincipal alice = new AuthPrincipal("alice", AuthRole.WRITER);
        String b64 = Base64.getEncoder().encodeToString("hello".getBytes());
        JsonNode args = MAPPER.readTree(String.format("""
                {"realm":"work","filename":"hello.txt","mime_type":"text/plain","data":"%s"}""", b64));

        Object out = handler.call(alice, args);
        assertSame(ingestResult, out);
    }

    @Test
    void allOptionalArgs_passedThrough() throws Exception {
        UUID linkId = UUID.randomUUID();
        when(service.ingest(any(InputStream.class), eq("a.pdf"), eq("application/pdf"),
                eq("legal"), eq("facts"), eq("vertrag-x"), eq(linkId), eq("bob")))
                .thenReturn(Map.of());

        AuthPrincipal bob = new AuthPrincipal("bob", AuthRole.WRITER);
        JsonNode args = MAPPER.readTree(String.format("""
                {"realm":"legal","signal":"facts","topic":"vertrag-x","cell_id":"%s",
                 "filename":"a.pdf","mime_type":"application/pdf","data":"aGk="}""", linkId));

        Object out = handler.call(bob, args);
        assertNotNull(out);
    }

    @Test
    void invalidUuidThrows() throws Exception {
        AuthPrincipal alice = new AuthPrincipal("alice", AuthRole.WRITER);
        JsonNode args = MAPPER.readTree("""
                {"realm":"work","cell_id":"not-a-uuid",
                 "filename":"a.txt","mime_type":"text/plain","data":""}""");

        assertThrows(IllegalArgumentException.class, () -> handler.call(alice, args));
        verifyNoInteractions(service);
    }

    @Test
    void serviceFailureWrappedAsRuntimeException() throws Exception {
        doThrow(new RuntimeException("disk full"))
                .when(service).ingest(any(), any(), any(), any(), any(), any(), any(), any());

        AuthPrincipal alice = new AuthPrincipal("alice", AuthRole.WRITER);
        JsonNode args = MAPPER.readTree("""
                {"realm":"work","filename":"a.txt","mime_type":"text/plain","data":"aGk="}""");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> handler.call(alice, args));
        assert ex.getMessage().startsWith("Upload failed:");
    }
}
