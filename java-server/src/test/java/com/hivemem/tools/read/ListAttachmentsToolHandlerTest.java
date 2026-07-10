package com.hivemem.tools.read;

import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B4 (LOW): list_attachments returned raw AttachmentRepository.toMap rows, exposing
 * internal fields (s3_key_original, s3_key_thumbnail, file_hash, uploaded_by) that
 * GetAttachmentInfoToolHandler already strips for the single-attachment lookup.
 */
class ListAttachmentsToolHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void responseOmitsInternalStorageAndUploaderFields() throws Exception {
        AttachmentRepository repo = mock(AttachmentRepository.class);
        UUID cellId = UUID.randomUUID();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID().toString());
        row.put("file_hash", "abc123");
        row.put("mime_type", "image/jpeg");
        row.put("original_filename", "photo.jpg");
        row.put("size_bytes", 1024L);
        row.put("s3_key_original", "originals/abc123");
        row.put("s3_key_thumbnail", "thumbnails/abc123");
        row.put("uploaded_by", "writer-token");
        row.put("created_at", "2026-01-01T00:00:00Z");
        row.put("page_count", null);
        when(repo.findByCellId(cellId)).thenReturn(List.of(row));

        ListAttachmentsToolHandler handler = new ListAttachmentsToolHandler(repo);
        JsonNode args = MAPPER.readTree("{\"cell_id\": \"" + cellId + "\"}");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) handler.call(
                new AuthPrincipal("test", AuthRole.WRITER), args);

        assertThat(result).hasSize(1);
        Map<String, Object> resultRow = result.get(0);
        assertThat(resultRow).doesNotContainKeys(
                "s3_key_original", "s3_key_thumbnail", "file_hash", "uploaded_by");
        assertThat(resultRow).containsEntry("mime_type", "image/jpeg");
        assertThat(resultRow).containsEntry("original_filename", "photo.jpg");
    }
}
