-- V0033: per-image EXIF metadata, 1:0..1 with attachments (only images get a row).
CREATE TABLE attachment_image_meta (
    attachment_id   UUID PRIMARY KEY REFERENCES attachments(id) ON DELETE CASCADE,
    width           INTEGER,
    height          INTEGER,
    taken_at        TIMESTAMPTZ,
    camera_make     TEXT,
    camera_model    TEXT,
    gps_lat         DOUBLE PRECISION,
    gps_lon         DOUBLE PRECISION,
    place_name      TEXT,
    orientation     INTEGER,
    geocode_status  TEXT NOT NULL DEFAULT 'none'   -- none | pending | done | failed
);

CREATE INDEX idx_image_meta_taken_at ON attachment_image_meta (taken_at);
