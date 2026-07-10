package com.hivemem.attachment;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** CRUD for {@code attachment_image_meta} (per-image EXIF, 1:0..1 with attachments). */
@Repository
public class ImageMetaRepository {

    private final DSLContext dsl;

    public ImageMetaRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public record ImageMetaRow(
            UUID attachmentId, Integer width, Integer height, OffsetDateTime takenAt,
            String cameraMake, String cameraModel, Double gpsLat, Double gpsLon,
            String placeName, Integer orientation, String geocodeStatus) {}

    /** Insert-or-replace the per-image row. Idempotent (used by ingest + backfill). */
    public void upsert(UUID attachmentId, ExifData d, String geocodeStatus) {
        dsl.execute("""
                INSERT INTO attachment_image_meta
                  (attachment_id, width, height, taken_at, camera_make, camera_model,
                   gps_lat, gps_lon, orientation, geocode_status)
                VALUES (?, ?, ?, ?::timestamptz, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (attachment_id) DO UPDATE SET
                  width = EXCLUDED.width, height = EXCLUDED.height, taken_at = EXCLUDED.taken_at,
                  camera_make = EXCLUDED.camera_make, camera_model = EXCLUDED.camera_model,
                  gps_lat = EXCLUDED.gps_lat, gps_lon = EXCLUDED.gps_lon,
                  orientation = EXCLUDED.orientation, geocode_status = EXCLUDED.geocode_status
                """,
                attachmentId, d.width(), d.height(), d.takenAt(), d.cameraMake(), d.cameraModel(),
                d.gpsLat(), d.gpsLon(), d.orientation(), geocodeStatus);
    }

    public void updatePlace(UUID attachmentId, String placeName, String geocodeStatus) {
        dsl.execute("UPDATE attachment_image_meta SET place_name = ?, geocode_status = ? " +
                "WHERE attachment_id = ?", placeName, geocodeStatus, attachmentId);
    }

    public Optional<ImageMetaRow> findByAttachmentId(UUID attachmentId) {
        Record r = dsl.fetchOne(
                "SELECT * FROM attachment_image_meta WHERE attachment_id = ?", attachmentId);
        return Optional.ofNullable(r).map(ImageMetaRepository::toRow);
    }

    /** Image attachments (not soft-deleted) that have no metadata row yet — for backfill. */
    public List<UUID> findImageAttachmentsWithoutMeta() {
        List<UUID> ids = new ArrayList<>();
        for (Record r : dsl.fetch("""
                SELECT a.id FROM attachments a
                LEFT JOIN attachment_image_meta m ON m.attachment_id = a.id
                WHERE a.deleted_at IS NULL AND a.mime_type LIKE 'image/%' AND m.attachment_id IS NULL
                """)) {
            ids.add(r.get("id", UUID.class));
        }
        return ids;
    }

    public record PendingGeocode(UUID attachmentId, double gpsLat, double gpsLon) {}

    /** Rows still awaiting reverse-geocoding — revisited by the hourly retry sweep. */
    public List<PendingGeocode> findPendingGeocodes(int limit) {
        List<PendingGeocode> out = new ArrayList<>();
        for (Record r : dsl.fetch(
                "SELECT attachment_id, gps_lat, gps_lon FROM attachment_image_meta "
                + "WHERE geocode_status = 'pending' AND gps_lat IS NOT NULL AND gps_lon IS NOT NULL "
                + "ORDER BY attachment_id LIMIT ?", limit)) {
            out.add(new PendingGeocode(
                    r.get("attachment_id", UUID.class),
                    r.get("gps_lat", Double.class),
                    r.get("gps_lon", Double.class)));
        }
        return out;
    }

    public record ResolvedPlace(double gpsLat, double gpsLon, String placeName) {}

    /** Already-resolved rows — used to seed the in-memory geocode cache after a restart. */
    public List<ResolvedPlace> findResolvedPlaces() {
        List<ResolvedPlace> out = new ArrayList<>();
        for (Record r : dsl.fetch(
                "SELECT gps_lat, gps_lon, place_name FROM attachment_image_meta "
                + "WHERE geocode_status = 'done' AND place_name IS NOT NULL "
                + "  AND gps_lat IS NOT NULL AND gps_lon IS NOT NULL")) {
            out.add(new ResolvedPlace(
                    r.get("gps_lat", Double.class),
                    r.get("gps_lon", Double.class),
                    r.get("place_name", String.class)));
        }
        return out;
    }

    private static ImageMetaRow toRow(Record r) {
        return new ImageMetaRow(
                r.get("attachment_id", UUID.class),
                r.get("width", Integer.class),
                r.get("height", Integer.class),
                r.get("taken_at", OffsetDateTime.class),
                r.get("camera_make", String.class),
                r.get("camera_model", String.class),
                r.get("gps_lat", Double.class),
                r.get("gps_lon", Double.class),
                r.get("place_name", String.class),
                r.get("orientation", Integer.class),
                r.get("geocode_status", String.class));
    }
}
