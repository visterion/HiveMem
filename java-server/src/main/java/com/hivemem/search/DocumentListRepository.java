package com.hivemem.search;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Repository for browsing documents (cells) without a full-text query.
 *
 * <p>Supports filter/sort/paging and joins the extraction-source attachment so callers
 * can display thumbnails and page counts without additional round-trips.
 */
@Repository
public class DocumentListRepository {

    private final DSLContext dslContext;

    public DocumentListRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    /**
     * Browse active cells with optional filters, a fixed sort, and limit/offset paging.
     *
     * @param realm   realm to filter on; a Java {@code null} matches cells where
     *                {@code realm IS NULL} (the service layer maps the sentinel "none" to
     *                this and defaults an omitted realm to "documents")
     * @param signal  optional signal filter
     * @param topic   optional topic filter
     * @param tags    optional tag-overlap filter (any of these tags)
     * @param status  optional status filter; defaults to "committed" when null/blank.
     *                Pass the sentinel {@code "all"} to bypass the status filter entirely
     *                (all statuses returned) — used by the Scans grid so its count basis
     *                matches facet_count's unfiltered default.
     * @param sort    sort order: newest | oldest | title (default newest)
     * @param limit   maximum rows to return
     * @param offset  number of rows to skip
     * @return list of row maps with document and attachment metadata
     */
    public List<Map<String, Object>> listDocuments(
            String realm,
            String signal,
            String topic,
            List<String> tags,
            String status,
            String sort,
            int limit,
            int offset
    ) {
        String[] tagsArr = (tags != null && !tags.isEmpty()) ? tags.toArray(new String[0]) : null;
        String orderClause = resolveOrder(sort);

        String sql =
                "SELECT c.id, c.realm, c.signal, c.topic, c.summary, c.tags, c.importance, " +
                "c.status, c.created_at, " +
                "a.id AS attachment_id, a.mime_type, a.page_count, " +
                "(a.s3_key_thumbnail IS NOT NULL) AS has_thumbnail, " +
                "f.avg_confidence AS confidence, " +
                "corr.object AS correspondent " +
                "FROM cells c " +
                "LEFT JOIN cell_attachments ca ON ca.cell_id = c.id AND ca.extraction_source = true " +
                "LEFT JOIN attachments a ON a.id = ca.attachment_id AND a.deleted_at IS NULL " +
                "LEFT JOIN LATERAL (SELECT AVG(confidence)::real AS avg_confidence " +
                "    FROM active_facts WHERE source_id = c.id) f ON true " +
                "LEFT JOIN LATERAL (SELECT object FROM active_facts " +
                "    WHERE source_id = c.id AND predicate IN ('vendor','party') " +
                "    ORDER BY predicate LIMIT 1) corr ON true " +
                "WHERE (c.valid_until IS NULL OR c.valid_until > now()) " +
                "AND c.realm IS NOT DISTINCT FROM ? " +
                "AND (? = 'all' OR c.status = COALESCE(?, 'committed')) " +
                // No cast on c.tags: the column is text[] and a varchar[] cast would defeat
                // the idx_cells_tags GIN index.
                "AND (?::text[] IS NULL OR c.tags && ?::text[]) " +
                "AND (?::text IS NULL OR c.signal = ?) " +
                "AND (?::text IS NULL OR c.topic = ?) " +
                "ORDER BY " + orderClause + " " +
                "LIMIT ? OFFSET ?";

        // Build bind list explicitly so jOOQ receives Object references and respects
        // the ?::text[] literal casts rather than auto-casting String[] → varchar[].
        List<Object> binds = new ArrayList<>();
        binds.add(realm);
        binds.add(status);
        binds.add(status);
        binds.add(tagsArr);  binds.add(tagsArr);
        binds.add(signal);   binds.add(signal);
        binds.add(topic);    binds.add(topic);
        binds.add(limit);
        binds.add(offset);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Record row : dslContext.fetch(sql, binds.toArray())) {
            result.add(toRow(row));
        }
        return result;
    }

    /**
     * Returns a safe ORDER BY clause based on an allow-listed sort value.
     * Never interpolates user input — only maps fixed enum values to fixed SQL fragments.
     */
    private static String resolveOrder(String sort) {
        if (sort == null) {
            return "c.created_at DESC, c.id";
        }
        return switch (sort) {
            case "newest" -> "c.created_at DESC, c.id";
            case "oldest" -> "c.created_at ASC, c.id";
            case "title"  -> "c.summary ASC NULLS LAST, c.id";
            default       -> "c.created_at DESC, c.id";
        };
    }

    private static Map<String, Object> toRow(Record row) {
        Map<String, Object> m = new LinkedHashMap<>();

        UUID id = row.get("id", UUID.class);
        m.put("id", id == null ? null : id.toString());
        m.put("realm", row.get("realm", String.class));
        m.put("signal", row.get("signal", String.class));
        m.put("topic", row.get("topic", String.class));
        m.put("summary", row.get("summary", String.class));

        // tags: String[] → List<String>
        Object rawTags = row.get("tags");
        if (rawTags instanceof String[] arr) {
            m.put("tags", Arrays.asList(arr));
        } else {
            m.put("tags", List.of());
        }

        m.put("importance", row.get("importance", Integer.class));
        m.put("status", row.get("status", String.class));

        OffsetDateTime createdAt = row.get("created_at", OffsetDateTime.class);
        m.put("created_at", createdAt == null ? null : createdAt.toString());

        UUID attachmentId = row.get("attachment_id", UUID.class);
        m.put("attachment_id", attachmentId == null ? null : attachmentId.toString());
        m.put("mime_type", row.get("mime_type", String.class));
        m.put("page_count", row.get("page_count", Integer.class));

        // has_thumbnail: the SQL expression returns Boolean
        Boolean hasThumbnail = row.get("has_thumbnail", Boolean.class);
        m.put("has_thumbnail", hasThumbnail != null && hasThumbnail);

        // confidence: nullable average of active fact confidences for this document
        Number confidence = row.get("confidence", Number.class);
        m.put("confidence", confidence == null ? null : confidence.doubleValue());

        // correspondent: first active vendor/party fact object for this document (nullable)
        m.put("correspondent", row.get("correspondent", String.class));

        return m;
    }
}
