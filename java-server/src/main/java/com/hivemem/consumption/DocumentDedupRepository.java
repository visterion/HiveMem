package com.hivemem.consumption;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentDedupRepository {

    private final DSLContext dsl;

    public DocumentDedupRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public record TargetCell(UUID id, String content, String source, OffsetDateTime createdAt) {}
    public record Candidate(UUID id, String content, double cosine) {}
    public record AttachmentKeys(UUID attachmentId, String s3KeyOriginal, String s3KeyThumbnail) {}

    /** The current (live) cell to evaluate, or empty if it is not current/committed. */
    public Optional<TargetCell> findTarget(UUID cellId) {
        Record r = dsl.fetchOne(
                "SELECT id, content, source, created_at FROM cells "
                + "WHERE id = ? AND valid_until IS NULL AND status = 'committed'", cellId);
        return r == null ? Optional.empty()
                : Optional.of(new TargetCell(
                        r.get("id", UUID.class),
                        r.get("content", String.class),
                        r.get("source", String.class),
                        r.get("created_at", OffsetDateTime.class)));
    }

    /**
     * Current committed scan cells whose embedding is within {@code recallThreshold} cosine of the
     * target AND that are strictly older (created_at, id tie-break). Ordered by closeness.
     */
    public List<Candidate> findSimilarOlderCandidates(UUID cellId, double recallThreshold, int k) {
        // The HNSW index idx_cells_embedding is an expression index on (embedding::vector(dim)); a
        // bare `embedding <=> ...` on the untyped vector column bypasses it and forces a sequential
        // scan (see KgSearchRepository.semanticSearch for the same fix on facts). The cast's typmod
        // must be a literal that textually matches the index expression, so it can't be a bind
        // parameter — but it also must not be hardcoded (the live dimension is determined at
        // runtime, not a fixed literal anywhere in the codebase; hardcoding would silently stop
        // using the index, or error outright, the moment the embedding model/dimension changes).
        // Instead read the target cell's OWN embedding dimension via vector_dims() and interpolate
        // it as a literal: it is guaranteed to match the live index dimension, because
        // EmbeddingMigrationService NULLs out every cell's embedding on a model/dimension change
        // before any stale-dimension vector could exist.
        Record dimRow = dsl.fetchOne(
                "SELECT vector_dims(embedding) AS dim FROM cells WHERE id = ? AND valid_until IS NULL",
                cellId);
        Integer dim = dimRow == null ? null : dimRow.get("dim", Integer.class);
        if (dim == null) {
            return List.of(); // target has no embedding (or isn't live) — nothing to compare against
        }
        String sql = ("""
                WITH target AS (SELECT embedding, created_at FROM cells WHERE id = ? AND valid_until IS NULL)
                SELECT c.id, c.content, 1 - (c.embedding::vector(%1$d) <=> t.embedding::vector(%1$d)) AS cosine
                FROM cells c, target t
                WHERE c.valid_until IS NULL
                  AND c.status = 'committed'
                  AND c.source LIKE 'consumption:%%'
                  AND c.embedding IS NOT NULL
                  AND c.id <> ?
                  AND (c.created_at < t.created_at
                       OR (c.created_at = t.created_at AND c.id < ?))
                  AND (1 - (c.embedding::vector(%1$d) <=> t.embedding::vector(%1$d))) >= ?
                ORDER BY c.embedding::vector(%1$d) <=> t.embedding::vector(%1$d)
                LIMIT ?
                """).formatted(dim);
        List<Candidate> out = new ArrayList<>();
        for (Record r : dsl.fetch(sql, cellId, cellId, cellId, recallThreshold, k)) {
            out.add(new Candidate(
                    r.get("id", UUID.class),
                    r.get("content", String.class),
                    r.get("cosine", Double.class)));
        }
        return out;
    }

    /** All live committed consumption-sourced cell ids, oldest first (id tie-break). */
    public List<UUID> findLiveConsumptionCellIdsOldestFirst() {
        List<UUID> ids = new ArrayList<>();
        for (Record r : dsl.fetch(
                "SELECT id FROM cells "
                + "WHERE source LIKE 'consumption:%' AND valid_until IS NULL AND status = 'committed' "
                + "ORDER BY created_at ASC, id ASC")) {
            ids.add(r.get("id", UUID.class));
        }
        return ids;
    }

    public int softDeleteCell(UUID cellId) {
        return dsl.execute(
                "UPDATE cells SET valid_until = now() WHERE id = ? AND valid_until IS NULL", cellId);
    }

    /**
     * Atomically write the {@code duplicate_of} audit tunnel AND soft-delete the duplicate cell in a
     * single transaction. This keeps the core dedup invariant: we never soft-delete a cell without
     * recording why it disappeared, and never leave a {@code duplicate_of} tunnel hanging off a cell
     * that is still live. Attachment/S3 cleanup is deliberately NOT part of this transaction (it is
     * external, ref-count-guarded, and an orphaned binary is harmless next to losing the audit link).
     */
    public void linkAndSoftDelete(UUID duplicateCellId, UUID originalCellId, String note, String createdBy) {
        dsl.transaction(cfg -> {
            DSLContext tx = DSL.using(cfg);
            tx.execute(
                    "INSERT INTO tunnels (from_cell, to_cell, relation, note, status, created_by) "
                    + "VALUES (?, ?, 'duplicate_of', ?, 'committed', ?)",
                    duplicateCellId, originalCellId, note, createdBy);
            tx.execute(
                    "UPDATE cells SET valid_until = now() WHERE id = ? AND valid_until IS NULL",
                    duplicateCellId);
        });
    }

    /** Number of OTHER current cells linked to the attachment (excludes {@code excludingCellId}). */
    public int countOtherLiveCellsForAttachment(UUID attachmentId, UUID excludingCellId) {
        Record r = dsl.fetchOne(
                "SELECT count(*) AS n FROM cell_attachments ca "
                + "JOIN cells c ON c.id = ca.cell_id "
                + "WHERE ca.attachment_id = ? AND c.valid_until IS NULL AND ca.cell_id <> ?",
                attachmentId, excludingCellId);
        return r == null ? 0 : r.get("n", Long.class).intValue();
    }

    /** Extraction-source attachment keys for a cell, regardless of the cell's live/deleted state.
     *  Intended to be called right after a soft-delete, to drive S3 cleanup of the discarded copy. */
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
