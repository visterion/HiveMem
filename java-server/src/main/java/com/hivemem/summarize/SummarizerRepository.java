package com.hivemem.summarize;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SummarizerRepository {

    private final DSLContext dsl;

    public SummarizerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<UUID> findCellsNeedingSummary(int limit) {
        var rows = dsl.fetch(
                "SELECT id FROM cells WHERE 'needs_summary' = ANY(tags) "
                + "AND status='committed' AND valid_until IS NULL "
                + "AND ('summarize_throttled' != ALL(tags) OR created_at < now() - interval '15 minutes') "
                + "ORDER BY created_at LIMIT ?", limit);
        List<UUID> ids = new ArrayList<>();
        for (Record r : rows) ids.add(r.get(0, UUID.class));
        return ids;
    }

    public Optional<CellSnapshot> findCellSnapshot(UUID id) {
        var rec = dsl.fetchOptional(
                "SELECT id, content, summary, key_points, insight, tags FROM cells WHERE id = ? AND status='committed' AND valid_until IS NULL", id);
        return rec.map(r -> new CellSnapshot(
                r.get("id", UUID.class),
                r.get("content", String.class),
                r.get("summary", String.class),
                arrayOrEmpty(r.get("key_points")),
                r.get("insight", String.class),
                arrayOrEmpty(r.get("tags"))
        ));
    }

    /** Mark a cell as needing a summary (idempotent). */
    public void tagNeedsSummary(UUID id) {
        dsl.execute(
                "UPDATE cells SET tags = "
                + "  CASE WHEN 'needs_summary' = ANY(tags) THEN tags ELSE array_append(tags, 'needs_summary') END "
                + "WHERE id = ?", id);
    }

    /** Remove the needs_summary tag once a summary has been written. */
    public void removeNeedsSummaryTag(UUID id) {
        dsl.execute(
                "UPDATE cells SET tags = array_remove(tags, 'needs_summary') WHERE id = ?", id);
    }

    /** Tag a cell as throttled (rate-limited) to defer it for the next backfill. */
    public void tagThrottled(UUID id) {
        dsl.execute(
                "UPDATE cells SET tags = "
                + "  CASE WHEN 'summarize_throttled' = ANY(tags) THEN tags ELSE array_append(tags, 'summarize_throttled') END "
                + "WHERE id = ?", id);
    }

    private static List<String> arrayOrEmpty(Object array) {
        if (array == null) return List.of();
        if (array instanceof java.sql.Array a) {
            try {
                Object[] inner = (Object[]) a.getArray();
                List<String> out = new ArrayList<>(inner.length);
                for (Object o : inner) out.add(String.valueOf(o));
                return out;
            } catch (Exception e) {
                return List.of();
            }
        }
        if (array instanceof Object[] arr) {
            List<String> out = new ArrayList<>(arr.length);
            for (Object o : arr) out.add(String.valueOf(o));
            return out;
        }
        return List.of();
    }

    public void setDocumentType(UUID id, String documentType) {
        dsl.execute("UPDATE cells SET document_type = ? WHERE id = ?", documentType, id);
    }

    /** Store the short LLM-generated title in the cell's topic column. */
    public void setTopic(UUID id, String topic) {
        dsl.execute("UPDATE cells SET topic = ? WHERE id = ?", topic, id);
    }

    /** Set the cell's valid_from (the document's own date). Direct row update, like setTopic. */
    public void setValidFrom(UUID id, java.time.OffsetDateTime validFrom) {
        dsl.execute("UPDATE cells SET valid_from = ?::timestamptz WHERE id = ?", validFrom, id);
    }

    /** Append a tag if not already present (idempotent). */
    public void applyTag(UUID id, String tag) {
        dsl.execute(
                "UPDATE cells SET tags = "
                + "  CASE WHEN ? = ANY(tags) THEN tags ELSE array_append(tags, ?) END "
                + "WHERE id = ?", tag, tag, id);
    }

    /**
     * The highest-confidence committed document_date fact for a cell (its source_id), or null.
     * Used by the tax/date backfill to set valid_from without a fresh LLM call.
     */
    public String findDocumentDateFact(UUID cellId) {
        return dsl.fetchOptional(
                "SELECT \"object\" FROM facts "
                + "WHERE source_id = ? AND predicate = 'document_date' AND status = 'committed' "
                + "AND valid_until IS NULL "
                + "ORDER BY confidence DESC NULLS LAST LIMIT 1", cellId)
                .map(r -> r.get("object", String.class))
                .orElse(null);
    }

    /**
     * Documents not yet processed by the tax/date backfill: have a summary, are current, and
     * lack the 'tax_scanned' marker tag. Idempotent finder (marker is set after processing).
     */
    public List<UUID> findDocumentsNeedingTaxScan(int limit) {
        var rows = dsl.fetch(
                "SELECT id FROM cells WHERE realm='documents' "
                + "AND summary IS NOT NULL AND summary <> '' "
                + "AND status='committed' AND valid_until IS NULL "
                + "AND ('tax_scanned' != ALL(tags) OR tags IS NULL) "
                + "ORDER BY created_at DESC LIMIT ?", limit);
        List<UUID> ids = new ArrayList<>();
        for (Record r : rows) ids.add(r.get(0, UUID.class));
        return ids;
    }

    /**
     * Documents that already have a summary but no title yet (topic IS NULL) — the targets of the
     * one-shot title backfill. Such cells never re-enter the summarize path (it skips cells that
     * already have a summary), so they need this dedicated, cheap title-only pass.
     */
    public List<UUID> findDocumentsNeedingTitle(int limit) {
        var rows = dsl.fetch(
                "SELECT id FROM cells WHERE realm='documents' AND topic IS NULL "
                + "AND summary IS NOT NULL AND summary <> '' "
                + "AND status='committed' AND valid_until IS NULL "
                + "ORDER BY created_at DESC LIMIT ?", limit);
        List<UUID> ids = new ArrayList<>();
        for (Record r : rows) ids.add(r.get(0, UUID.class));
        return ids;
    }

    /** The committed summary for a cell, or null. Used by the title backfill. */
    public String findSummary(UUID id) {
        return dsl.fetchOptional(
                "SELECT summary FROM cells WHERE id = ? AND status='committed' AND valid_until IS NULL", id)
                .map(r -> r.get("summary", String.class))
                .orElse(null);
    }

    /**
     * Look up the source-attachment's MIME type and filename for a cell, joining via
     * cell_attachments.extraction_source=true. Returns empty if cell has no source attachment.
     */
    public Optional<AttachmentMeta> findCellAttachmentMeta(UUID cellId) {
        var rec = dsl.fetchOptional(
                "SELECT a.mime_type, a.original_filename "
                + "FROM cell_attachments ca "
                + "JOIN attachments a ON a.id = ca.attachment_id "
                + "WHERE ca.cell_id = ? AND ca.extraction_source = true "
                + "LIMIT 1", cellId);
        return rec.map(r -> new AttachmentMeta(
                r.get("mime_type", String.class),
                r.get("original_filename", String.class)));
    }

    public record AttachmentMeta(String mimeType, String filename) {}

    public record CellSnapshot(
            UUID id,
            String content,
            String summary,
            List<String> keyPoints,
            String insight,
            List<String> tags
    ) {}
}
