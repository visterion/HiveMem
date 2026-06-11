package com.hivemem.attachment;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.lang.GeoLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.TimeZone;

/** Reads EXIF metadata from image bytes. Never throws — returns nulls on any failure. */
@Component
public class ExifExtractor {

    private static final Logger log = LoggerFactory.getLogger(ExifExtractor.class);

    public ExifData extract(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return ExifData.EMPTY;

        Integer width = null, height = null, orientation = null;
        OffsetDateTime takenAt = null;
        String make = null, model = null;
        Double lat = null, lon = null;

        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));

            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0 != null) {
                make = trimToNull(ifd0.getString(ExifIFD0Directory.TAG_MAKE));
                model = trimToNull(ifd0.getString(ExifIFD0Directory.TAG_MODEL));
                if (ifd0.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                    orientation = ifd0.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
                }
            }

            ExifSubIFDDirectory sub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (sub != null) {
                Date d = sub.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, TimeZone.getTimeZone("UTC"));
                if (d != null) takenAt = d.toInstant().atOffset(ZoneOffset.UTC);
            }

            GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            if (gps != null) {
                GeoLocation loc = gps.getGeoLocation();
                if (loc != null && !loc.isZero()) {
                    lat = loc.getLatitude();
                    lon = loc.getLongitude();
                }
            }
        } catch (Exception e) {
            log.debug("EXIF read failed: {}", e.getMessage());
        }

        // Dimension fallback: always available once the image decodes.
        if (width == null || height == null) {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
                if (img != null) {
                    width = img.getWidth();
                    height = img.getHeight();
                }
            } catch (Exception e) {
                log.debug("Dimension fallback decode failed: {}", e.getMessage());
            }
        }

        return new ExifData(width, height, takenAt, make, model, lat, lon, orientation);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
