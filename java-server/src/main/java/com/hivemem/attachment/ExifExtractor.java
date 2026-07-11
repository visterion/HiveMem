package com.hivemem.attachment;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.lang.GeoLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Iterator;
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

                // EXIF PixelXDimension/PixelYDimension — present on many cameras/phones even
                // when the JPEG frame header (below) is absent or unreadable.
                if (sub.containsTag(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH)) {
                    width = sub.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH);
                }
                if (sub.containsTag(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT)) {
                    height = sub.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT);
                }
            }

            // JPEG frame header (SOF segment) — always present in a JPEG regardless of EXIF,
            // and read as plain metadata (no image decode).
            if (width == null || height == null) {
                JpegDirectory jpeg = metadata.getFirstDirectoryOfType(JpegDirectory.class);
                if (jpeg != null) {
                    if (width == null && jpeg.containsTag(JpegDirectory.TAG_IMAGE_WIDTH)) {
                        width = jpeg.getInteger(JpegDirectory.TAG_IMAGE_WIDTH);
                    }
                    if (height == null && jpeg.containsTag(JpegDirectory.TAG_IMAGE_HEIGHT)) {
                        height = jpeg.getInteger(JpegDirectory.TAG_IMAGE_HEIGHT);
                    }
                }
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

        // Header-only dimension fallback: ImageReader reads the image header (width/height)
        // without decoding pixel data, unlike ImageIO.read()'s full-bitmap decode — important
        // for large photos where a full decode means a huge heap allocation just to learn the
        // dimensions we usually already have from EXIF/JPEG metadata above.
        if (width == null || height == null) {
            int[] dims = readDimensionsHeaderOnly(bytes);
            if (dims != null) {
                width = dims[0];
                height = dims[1];
            }
        }

        // Last-resort fallback: a full decode, only when both metadata and header-only reads
        // failed to yield dimensions (e.g. a format ImageIO has no header-only reader for).
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

    /** Reads width/height from the image header via {@link ImageReader}, without decoding pixel
     *  data. Returns null if no reader can handle the bytes or the header can't be parsed. */
    private static int[] readDimensionsHeaderOnly(byte[] bytes) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                return new int[]{w, h};
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            log.debug("Header-only dimension read failed: {}", e.getMessage());
            return null;
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
