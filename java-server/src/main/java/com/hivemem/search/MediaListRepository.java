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
 * Lists image attachments for the Photos gallery: committed cells classified
 * {@code subtype_photo_general} or {@code subtype_whiteboard_photo}, joined to their
 * extraction-source image attachment and per-image EXIF, ordered by capture date.
 */
@Repository
public class MediaListRepository {

    private final DSLContext dslContext;

    public MediaListRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    public List<Map<String, Object>> listMedia(String realm, String sort, int limit, int offset) {
        String dir = "oldest".equals(sort) ? "ASC" : "DESC";
        String sql =
                "SELECT c.id AS cell_id, c.realm, c.summary, c.tags, " +
                "a.id AS attachment_id, a.mime_type, a.size_bytes, a.created_at, " +
                "m.taken_at, m.width, m.height, m.camera_make, m.camera_model, " +
                "m.gps_lat, m.gps_lon, m.place_name " +
                "FROM cells c " +
                "JOIN cell_attachments ca ON ca.cell_id = c.id AND ca.extraction_source = true " +
                "JOIN attachments a ON a.id = ca.attachment_id AND a.deleted_at IS NULL " +
                "    AND a.mime_type LIKE 'image/%' " +
                "LEFT JOIN attachment_image_meta m ON m.attachment_id = a.id " +
                "WHERE c.valid_until IS NULL " +
                "AND c.status = 'committed' " +
                "AND c.tags::varchar[] && ARRAY['subtype_photo_general','subtype_whiteboard_photo']::varchar[] " +
                "AND (?::text IS NULL OR c.realm = ?) " +
                "ORDER BY COALESCE(m.taken_at, a.created_at) " + dir + ", a.id " +
                "LIMIT ? OFFSET ?";

        List<Object> binds = new ArrayList<>();
        binds.add(realm); binds.add(realm);
        binds.add(limit); binds.add(offset);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Record row : dslContext.fetch(sql, binds.toArray())) {
            result.add(toRow(row));
        }
        return result;
    }

    private static Map<String, Object> toRow(Record row) {
        Map<String, Object> m = new LinkedHashMap<>();

        UUID cellId = row.get("cell_id", UUID.class);
        UUID attId = row.get("attachment_id", UUID.class);
        m.put("cell_id", cellId == null ? null : cellId.toString());
        m.put("attachment_id", attId == null ? null : attId.toString());
        m.put("realm", row.get("realm", String.class));
        m.put("summary", row.get("summary", String.class));

        Object rawTags = row.get("tags");
        m.put("tags", rawTags instanceof String[] arr ? Arrays.asList(arr) : List.of());

        m.put("mime_type", row.get("mime_type", String.class));
        Number size = row.get("size_bytes", Number.class);
        m.put("size_bytes", size == null ? null : size.longValue());

        OffsetDateTime createdAt = row.get("created_at", OffsetDateTime.class);
        m.put("created_at", createdAt == null ? null : createdAt.toString());
        OffsetDateTime takenAt = row.get("taken_at", OffsetDateTime.class);
        m.put("taken_at", takenAt == null ? null : takenAt.toString());

        m.put("width", row.get("width", Integer.class));
        m.put("height", row.get("height", Integer.class));
        m.put("camera_make", row.get("camera_make", String.class));
        m.put("camera_model", row.get("camera_model", String.class));
        m.put("gps_lat", row.get("gps_lat", Double.class));
        m.put("gps_lon", row.get("gps_lon", Double.class));
        m.put("place_name", row.get("place_name", String.class));

        m.put("thumbnail_uri", attId == null ? null : "hivemem://attachments/" + attId + "/thumbnail");
        m.put("content_uri", attId == null ? null : "hivemem://attachments/" + attId + "/content");
        return m;
    }
}
