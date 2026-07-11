package com.hivemem.attachment;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class AttachmentRepository {

    private final DSLContext dsl;

    public AttachmentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Map<String, Object>> findByHash(String fileHash) {
        Record row = dsl.fetchOne(
                "SELECT id, file_hash, mime_type, original_filename, size_bytes, " +
                "s3_key_original, s3_key_thumbnail, uploaded_by, created_at, page_count " +
                "FROM attachments WHERE file_hash = ?",
                fileHash);
        return Optional.ofNullable(row).map(this::toMap);
    }

    public Optional<Map<String, Object>> findById(UUID id) {
        Record row = dsl.fetchOne(
                "SELECT id, file_hash, mime_type, original_filename, size_bytes, " +
                "s3_key_original, s3_key_thumbnail, uploaded_by, created_at, page_count " +
                "FROM attachments WHERE id = ? AND deleted_at IS NULL",
                id);
        return Optional.ofNullable(row).map(this::toMap);
    }

    /**
     * Race-free against concurrent identical uploads: when another transaction inserted
     * the same file_hash between the dedup check and this insert, the upsert reactivates
     * the winner's row (mirroring {@link #reactivate}) instead of failing with a unique
     * violation — which would abort the surrounding Postgres transaction.
     */
    public Map<String, Object> insert(
            String fileHash, String mimeType, String originalFilename,
            long sizeBytes, String s3KeyOriginal, String s3KeyThumbnail,
            String uploadedBy, Integer pageCount) {
        Record row = dsl.fetchOne("""
                INSERT INTO attachments
                  (file_hash, mime_type, original_filename, size_bytes,
                   s3_key_original, s3_key_thumbnail, uploaded_by, page_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (file_hash) DO UPDATE SET
                  deleted_at = NULL,
                  s3_key_thumbnail = COALESCE(attachments.s3_key_thumbnail, EXCLUDED.s3_key_thumbnail)
                RETURNING id, file_hash, mime_type, original_filename, size_bytes,
                          s3_key_original, s3_key_thumbnail, uploaded_by, created_at, page_count
                """,
                fileHash, mimeType, originalFilename, sizeBytes,
                s3KeyOriginal, s3KeyThumbnail, uploadedBy, pageCount);
        return Optional.ofNullable(row)
                .map(this::toMap)
                .orElseThrow(() -> new NoSuchElementException("Attachment insert returned no row for hash: " + fileHash));
    }

    /** Idempotent: clears deleted_at if soft-deleted, updates thumbnail key if currently null. */
    public Map<String, Object> reactivate(UUID id, String s3KeyThumbnail) {
        Record row = dsl.fetchOne("""
                UPDATE attachments
                SET deleted_at = NULL,
                    s3_key_thumbnail = COALESCE(s3_key_thumbnail, ?)
                WHERE id = ?
                RETURNING id, file_hash, mime_type, original_filename, size_bytes,
                          s3_key_original, s3_key_thumbnail, uploaded_by, created_at, page_count
                """, s3KeyThumbnail, id);
        return Optional.ofNullable(row)
                .map(this::toMap)
                .orElseThrow(() -> new NoSuchElementException("Attachment not found for reactivation: " + id));
    }

    public void linkExtractionCell(UUID attachmentId, UUID cellId) {
        dsl.execute("""
                INSERT INTO cell_attachments (cell_id, attachment_id, extraction_source)
                VALUES (?, ?, true)
                ON CONFLICT (cell_id, attachment_id) DO NOTHING
                """, cellId, attachmentId);
    }

    public List<Map<String, Object>> findByCellId(UUID cellId) {
        return dsl.fetch("""
                SELECT a.id, a.file_hash, a.mime_type, a.original_filename,
                       a.size_bytes, a.s3_key_original, a.s3_key_thumbnail,
                       a.uploaded_by, a.created_at, a.page_count
                FROM attachments a
                JOIN cell_attachments ca ON ca.attachment_id = a.id
                WHERE ca.cell_id = ? AND a.deleted_at IS NULL
                ORDER BY a.created_at DESC
                """, cellId)
                .map(this::toMap);
    }

    public boolean softDelete(UUID id) {
        int updated = dsl.execute(
                "UPDATE attachments SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL", id);
        return updated > 0;
    }

    public record DiagramRow(UUID attachmentId, UUID cellId, String fileHash, String mimeType, String diagramSource) {}

    public record AttachmentInfo(UUID attachmentId, String mimeType, String s3KeyOriginal) {}

    /** Diagrams whose attachment row has no thumbnail yet. */
    public List<DiagramRow> findDiagramsWithoutThumbnail(Set<String> mimeTypes, int limit) {
        if (mimeTypes == null || mimeTypes.isEmpty()) return List.of();
        var rows = dsl.fetch(
                "SELECT a.id AS attachment_id, ca.cell_id AS cell_id, a.file_hash, a.mime_type, c.content AS source "
                + "FROM attachments a "
                + "JOIN cell_attachments ca ON ca.attachment_id = a.id AND ca.extraction_source = true "
                + "JOIN cells c ON c.id = ca.cell_id AND c.valid_until IS NULL "
                + "WHERE a.s3_key_thumbnail IS NULL "
                + "  AND a.deleted_at IS NULL "
                + "  AND a.mime_type = ANY(?) "
                + "ORDER BY a.created_at DESC LIMIT ?",
                mimeTypes.toArray(new String[0]), limit);
        List<DiagramRow> out = new ArrayList<>();
        for (var r : rows) {
            out.add(new DiagramRow(
                    r.get("attachment_id", UUID.class),
                    r.get("cell_id", UUID.class),
                    r.get("file_hash", String.class),
                    r.get("mime_type", String.class),
                    r.get("source", String.class)));
        }
        return out;
    }

    /** Cells tagged vision_pending whose source-attachment is still around. */
    public List<UUID> findCellsWithVisionPending(int limit) {
        var rows = dsl.fetch(
                "SELECT id FROM cells "
                + "WHERE tags @> ARRAY['vision_pending']::text[] "
                + "  AND status = 'committed' AND valid_until IS NULL "
                + "ORDER BY created_at LIMIT ?", limit);
        List<UUID> out = new ArrayList<>();
        for (var r : rows) out.add(r.get(0, UUID.class));
        return out;
    }

    public record StorageKeys(UUID id, String mimeType, String s3KeyOriginal, String s3KeyThumbnail) {}

    /** All live attachments with their S3 keys — used by the aws-chunked repair sweep. */
    public List<StorageKeys> findAllStorageKeys() {
        return dsl.fetch(
                "SELECT id, mime_type, s3_key_original, s3_key_thumbnail "
                + "FROM attachments WHERE deleted_at IS NULL ORDER BY created_at")
                .map(r -> new StorageKeys(
                        r.get("id", UUID.class),
                        r.get("mime_type", String.class),
                        r.get("s3_key_original", String.class),
                        r.get("s3_key_thumbnail", String.class)));
    }

    public void updateThumbnailKey(UUID attachmentId, String s3KeyThumbnail) {
        dsl.execute("UPDATE attachments SET s3_key_thumbnail = ? WHERE id = ?",
                s3KeyThumbnail, attachmentId);
    }

    public record ExtractionCellSeed(String content, List<String> tags) {}

    /**
     * Latest live, committed extraction cell for an attachment — used on dedup re-uploads
     * to seed the new cell with already-enriched (OCR/vision) content and its subtype
     * tags instead of re-running the pipeline.
     */
    public Optional<ExtractionCellSeed> findExtractionCellSeed(UUID attachmentId) {
        return dsl.fetchOptional(
                "SELECT c.content, c.tags FROM cells c "
                + "JOIN cell_attachments ca ON ca.cell_id = c.id "
                + "WHERE ca.attachment_id = ? AND ca.extraction_source = true "
                + "  AND c.valid_until IS NULL AND c.status = 'committed' "
                + "ORDER BY c.created_at DESC LIMIT 1", attachmentId)
                .map(r -> {
                    String[] tags = r.get("tags", String[].class);
                    return new ExtractionCellSeed(
                            r.get("content", String.class),
                            tags == null ? List.of() : List.of(tags));
                });
    }

    public Optional<AttachmentInfo> findAttachmentForCell(UUID cellId) {
        var rec = dsl.fetchOptional(
                "SELECT a.id, a.mime_type, a.s3_key_original "
                + "FROM cell_attachments ca "
                + "JOIN attachments a ON a.id = ca.attachment_id "
                + "WHERE ca.cell_id = ? AND ca.extraction_source = true AND a.deleted_at IS NULL "
                + "LIMIT 1", cellId);
        return rec.map(r -> new AttachmentInfo(
                r.get("id", UUID.class),
                r.get("mime_type", String.class),
                r.get("s3_key_original", String.class)));
    }

    private Map<String, Object> toMap(Record row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.get("id", UUID.class).toString());
        m.put("file_hash", row.get("file_hash", String.class));
        m.put("mime_type", row.get("mime_type", String.class));
        m.put("original_filename", row.get("original_filename", String.class));
        m.put("size_bytes", row.get("size_bytes", Long.class));
        m.put("s3_key_original", row.get("s3_key_original", String.class));
        m.put("s3_key_thumbnail", row.get("s3_key_thumbnail", String.class));
        m.put("uploaded_by", row.get("uploaded_by", String.class));
        m.put("created_at", row.get("created_at").toString());
        try {
            m.put("page_count", row.get("page_count", Integer.class));
        } catch (Exception ignored) {
            // queries that don't SELECT page_count won't have it in the record
        }
        return m;
    }
}
