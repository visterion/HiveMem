package com.hivemem.queen;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Read queries supporting the Queen survey: isolated cells (no tunnel as either endpoint,
 * any status — so open pending proposals also suppress re-proposal) and a dedup check.
 */
@Repository
public class QueenRepository {

    private final DSLContext db;

    public QueenRepository(DSLContext db) {
        this.db = db;
    }

    public List<UUID> findIsolatedCellIds(int limit) {
        List<UUID> ids = new ArrayList<>();
        for (Record row : db.fetch("""
                SELECT c.id
                FROM active_cells c
                WHERE NOT EXISTS (
                    SELECT 1 FROM tunnels t
                    WHERE (t.from_cell = c.id OR t.to_cell = c.id)
                      AND t.valid_until IS NULL
                )
                ORDER BY c.created_at DESC
                LIMIT ?
                """, limit)) {
            ids.add(row.get("id", UUID.class));
        }
        return ids;
    }

    /**
     * Inbox cells ready for classification: realm='inbox', live+committed, not already
     * declined by the archivist, and enrichment settled — i.e. none of the "not-ready"
     * tags remain, OR OCR has terminally failed (ocr_failed_permanent) so the cell will
     * never get cleaner. FIFO by created_at; archivist_skipped cells drop out so a poison
     * cell cannot starve the queue.
     */
    public List<UUID> findInboxCellIds(int limit) {
        List<UUID> ids = new ArrayList<>();
        for (Record row : db.fetch("""
                SELECT c.id
                FROM active_cells c
                WHERE c.realm = 'inbox'
                  AND NOT ('archivist_skipped' = ANY(c.tags))
                  -- settled: no non-OCR stage pending, AND OCR either not pending or terminally failed.
                  -- The ocr_failed_permanent rescue is scoped ONLY to the ocr_pending clause -- it must NOT
                  -- bypass a still-running vision/kroki/summary stage.
                  AND NOT (c.tags && ARRAY['vision_pending','kroki_pending','needs_summary']::text[])
                  AND (NOT ('ocr_pending' = ANY(c.tags)) OR 'ocr_failed_permanent' = ANY(c.tags))
                ORDER BY c.created_at ASC
                LIMIT ?
                """, limit)) {
            ids.add(row.get("id", UUID.class));
        }
        return ids;
    }

    public boolean tunnelExists(UUID fromCell, UUID toCell, String relation) {
        Record row = db.fetchOne("""
                SELECT 1 AS hit
                FROM tunnels
                WHERE from_cell = ? AND to_cell = ? AND relation = ?
                  AND valid_until IS NULL
                LIMIT 1
                """, fromCell, toCell, relation);
        return row != null;
    }
}
