package com.hivemem.queen;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Existing taxonomy (realm→topic with counts), excluding the inbox staging realm. */
    public List<Map<String, Object>> listTaxonomy() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Record row : db.fetch("""
                SELECT realm, topic, SUM(cell_count) AS cell_count
                FROM realm_stats
                WHERE realm <> 'inbox'
                GROUP BY realm, topic
                ORDER BY realm, topic
                """)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("realm", row.get("realm", String.class));
            m.put("topic", row.get("topic", String.class));
            m.put("cell_count", row.get("cell_count", Long.class));
            out.add(m);
        }
        return out;
    }

    /** Op-log entries produced by the inbox-archivist (moves + skips), newest first. */
    public List<Map<String, Object>> findArchivistLog(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Record row : db.fetch("""
                SELECT op_type, payload::text AS payload, created_at
                FROM ops_log
                WHERE op_type IN ('reclassify_cell', 'archivist_skip')
                  AND payload->>'agent_id' = 'inbox-archivist'
                ORDER BY created_at DESC
                LIMIT ?
                """, limit)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("op_type", row.get("op_type", String.class));
            m.put("payload", row.get("payload", String.class));
            m.put("at", String.valueOf(row.get("created_at")));
            out.add(m);
        }
        return out;
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
