package com.hivemem.ocr;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OcrRepository {

    private final DSLContext dsl;

    public OcrRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Returns cell IDs that need OCR (or failed OCR > 1h ago). */
    public List<UUID> findCellsPendingOcr(int limit) {
        var rows = dsl.fetch(
                "SELECT id FROM cells WHERE "
                + "  ('ocr_pending' = ANY(tags) "
                + "    OR ('ocr_failed' = ANY(tags) AND created_at < now() - interval '1 hour')) "
                + "AND status = 'committed' AND valid_until IS NULL "
                + "ORDER BY created_at LIMIT ?", limit);
        List<UUID> ids = new ArrayList<>();
        for (Record r : rows) ids.add(r.get(0, UUID.class));
        return ids;
    }

    public Optional<AttachmentInfo> findAttachmentForCell(UUID cellId) {
        var rec = dsl.fetchOptional(
                "SELECT a.id, a.s3_key_original FROM attachments a "
                + "JOIN cell_attachments ca ON ca.attachment_id = a.id "
                + "WHERE ca.cell_id = ? AND ca.extraction_source = true "
                + "LIMIT 1", cellId);
        return rec.map(r -> new AttachmentInfo(
                r.get("id", UUID.class),
                r.get("s3_key_original", String.class)));
    }

    public void tagFailed(UUID cellId) {
        dsl.execute(
                "UPDATE cells SET tags = "
                + "  CASE WHEN 'ocr_failed' = ANY(tags) THEN tags ELSE array_append(tags, 'ocr_failed') END "
                + "WHERE id = ?", cellId);
    }

    public void removeOcrPendingTag(UUID cellId) {
        dsl.execute(
                "UPDATE cells SET tags = array_remove(array_remove(tags, 'ocr_pending'), 'ocr_failed') "
                + "WHERE id = ?", cellId);
    }

    /** Soft-delete a cell that OCR'd to entirely blank content (retire it; it has no replacement). */
    public int softDeleteBlankCell(UUID cellId) {
        return dsl.execute(
                "UPDATE cells SET valid_until = now() WHERE id = ? AND valid_until IS NULL", cellId);
    }

    public record AttachmentInfo(UUID attachmentId, String s3Key) {}
}
