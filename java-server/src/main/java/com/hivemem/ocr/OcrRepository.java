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

    /**
     * Returns cell IDs that need OCR, plus retryable failures.
     * Fresh {@code ocr_pending} cells always come first so a backlog of failures can
     * never starve the queue, and cells escalated to {@code ocr_failed_permanent}
     * (after 3 attempts, see {@link #tagFailed}) are never retried.
     */
    public List<UUID> findCellsPendingOcr(int limit) {
        var rows = dsl.fetch(
                "SELECT id FROM cells WHERE "
                + "  (tags @> ARRAY['ocr_pending']::text[] "
                + "    OR (tags @> ARRAY['ocr_failed']::text[] "
                + "        AND NOT (tags @> ARRAY['ocr_failed_permanent']::text[]) "
                + "        AND created_at < now() - interval '1 hour')) "
                + "AND status = 'committed' AND valid_until IS NULL "
                + "ORDER BY CASE WHEN tags @> ARRAY['ocr_failed']::text[] THEN 1 ELSE 0 END, created_at "
                + "LIMIT ?", limit);
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

    /**
     * Escalates one step per failure: ocr_failed → ocr_failed_2 → ocr_failed_permanent.
     * Permanent failures are excluded from {@link #findCellsPendingOcr}, capping a
     * permanently broken document at 3 OCR attempts instead of retrying forever.
     */
    public void tagFailed(UUID cellId) {
        dsl.execute(
                "UPDATE cells SET tags = CASE "
                + "  WHEN 'ocr_failed_2' = ANY(tags) THEN array_append(tags, 'ocr_failed_permanent') "
                + "  WHEN 'ocr_failed' = ANY(tags) THEN array_append(tags, 'ocr_failed_2') "
                + "  ELSE array_append(tags, 'ocr_failed') END "
                + "WHERE id = ? AND NOT ('ocr_failed_permanent' = ANY(tags))", cellId);
    }

    public void removeOcrPendingTag(UUID cellId) {
        dsl.execute(
                "UPDATE cells SET tags = array_remove(array_remove(array_remove(array_remove("
                + "tags, 'ocr_pending'), 'ocr_failed'), 'ocr_failed_2'), 'ocr_failed_permanent') "
                + "WHERE id = ?", cellId);
    }

    /** Soft-delete a cell that OCR'd to entirely blank content (retire it; it has no replacement). */
    public int softDeleteBlankCell(UUID cellId) {
        return dsl.execute(
                "UPDATE cells SET valid_until = now() WHERE id = ? AND valid_until IS NULL", cellId);
    }

    public record AttachmentInfo(UUID attachmentId, String s3Key) {}
}
