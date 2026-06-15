package com.hivemem.consumption;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentDedupRepository {

    private final DSLContext dsl;

    public DocumentDedupRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public record TargetCell(UUID id, String content, OffsetDateTime createdAt) {}
    public record Candidate(UUID id, String content, double cosine) {}
    public record AttachmentKeys(UUID attachmentId, String s3KeyOriginal, String s3KeyThumbnail) {}

    /** The current (live) cell to evaluate, or empty if it is not current/committed. */
    public Optional<TargetCell> findTarget(UUID cellId) {
        Record r = dsl.fetchOne(
                "SELECT id, content, created_at FROM cells "
                + "WHERE id = ? AND valid_until IS NULL", cellId);
        return r == null ? Optional.empty()
                : Optional.of(new TargetCell(
                        r.get("id", UUID.class),
                        r.get("content", String.class),
                        r.get("created_at", OffsetDateTime.class)));
    }

    /**
     * Current committed scan cells whose embedding is within {@code recallThreshold} cosine of the
     * target AND that are strictly older (created_at, id tie-break). Ordered by closeness.
     */
    public List<Candidate> findSimilarOlderCandidates(UUID cellId, double recallThreshold, int k) {
        String sql = """
                WITH target AS (SELECT embedding, created_at FROM cells WHERE id = ?)
                SELECT c.id, c.content, 1 - (c.embedding <=> t.embedding) AS cosine
                FROM cells c, target t
                WHERE c.valid_until IS NULL
                  AND c.status = 'committed'
                  AND c.source LIKE 'consumption:%'
                  AND c.embedding IS NOT NULL
                  AND c.id <> ?
                  AND (c.created_at < t.created_at
                       OR (c.created_at = t.created_at AND c.id < ?))
                  AND (1 - (c.embedding <=> t.embedding)) >= ?
                ORDER BY c.embedding <=> t.embedding
                LIMIT ?
                """;
        List<Candidate> out = new ArrayList<>();
        for (Record r : dsl.fetch(sql, cellId, cellId, cellId, recallThreshold, k)) {
            out.add(new Candidate(
                    r.get("id", UUID.class),
                    r.get("content", String.class),
                    r.get("cosine", Double.class)));
        }
        return out;
    }

    public int softDeleteCell(UUID cellId) {
        return dsl.execute(
                "UPDATE cells SET valid_until = now() WHERE id = ? AND valid_until IS NULL", cellId);
    }

    /** Number of OTHER current cells linked to the attachment (excludes {@code excludingCellId}). */
    public int countOtherLiveCellsForAttachment(UUID attachmentId, UUID excludingCellId) {
        Record r = dsl.fetchOne(
                "SELECT count(*) AS n FROM cell_attachments ca "
                + "JOIN cells c ON c.id = ca.cell_id "
                + "WHERE ca.attachment_id = ? AND c.valid_until IS NULL AND ca.cell_id <> ?",
                attachmentId, excludingCellId);
        return r == null ? 0 : r.get("n", Integer.class);
    }

    public Optional<AttachmentKeys> findAttachmentKeysForCell(UUID cellId) {
        Record r = dsl.fetchOne(
                "SELECT a.id, a.s3_key_original, a.s3_key_thumbnail FROM attachments a "
                + "JOIN cell_attachments ca ON ca.attachment_id = a.id "
                + "WHERE ca.cell_id = ? AND ca.extraction_source = true LIMIT 1", cellId);
        return r == null ? Optional.empty()
                : Optional.of(new AttachmentKeys(
                        r.get("id", UUID.class),
                        r.get("s3_key_original", String.class),
                        r.get("s3_key_thumbnail", String.class)));
    }
}
