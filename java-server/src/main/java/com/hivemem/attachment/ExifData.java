package com.hivemem.attachment;

import java.time.OffsetDateTime;

/** Parsed EXIF metadata for an image attachment. All fields nullable (EXIF is often partial). */
public record ExifData(
        Integer width,
        Integer height,
        OffsetDateTime takenAt,
        String cameraMake,
        String cameraModel,
        Double gpsLat,
        Double gpsLon,
        Integer orientation) {

    static final ExifData EMPTY = new ExifData(null, null, null, null, null, null, null, null);
}
