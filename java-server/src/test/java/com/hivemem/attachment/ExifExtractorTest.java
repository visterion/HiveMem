package com.hivemem.attachment;

import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class ExifExtractorTest {

    private final ExifExtractor extractor = new ExifExtractor();

    private static byte[] baseJpeg(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "JPEG", out);
        return out.toByteArray();
    }

    private static byte[] jpegWithExif() throws Exception {
        byte[] base = baseJpeg(120, 80);
        TiffOutputSet set = new TiffOutputSet();
        TiffOutputDirectory root = set.getOrCreateRootDirectory();
        root.add(TiffTagConstants.TIFF_TAG_MAKE, "Apple");
        root.add(TiffTagConstants.TIFF_TAG_MODEL, "iPhone 16 Pro");
        root.add(TiffTagConstants.TIFF_TAG_ORIENTATION, (short) 6);
        TiffOutputDirectory exif = set.getOrCreateExifDirectory();
        exif.add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, "2026:05:12 14:30:00");
        set.setGpsInDegrees(8.4660, 49.4874); // (longitude, latitude)
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExifRewriter().updateExifMetadataLossless(base, out, set);
        return out.toByteArray();
    }

    @Test
    void extractsAllExifFields() throws Exception {
        ExifData d = extractor.extract(jpegWithExif());
        assertThat(d.width()).isEqualTo(120);
        assertThat(d.height()).isEqualTo(80);
        assertThat(d.cameraMake()).isEqualTo("Apple");
        assertThat(d.cameraModel()).isEqualTo("iPhone 16 Pro");
        assertThat(d.orientation()).isEqualTo(6);
        assertThat(d.takenAt()).isNotNull();
        assertThat(d.takenAt().toString()).startsWith("2026-05-12T14:30");
        assertThat(d.gpsLat()).isCloseTo(49.4874, offset(0.001));
        assertThat(d.gpsLon()).isCloseTo(8.4660, offset(0.001));
    }

    @Test
    void noExifStillReturnsDimensionsAndNullsElsewhere() throws Exception {
        ExifData d = extractor.extract(baseJpeg(64, 48));
        assertThat(d.width()).isEqualTo(64);
        assertThat(d.height()).isEqualTo(48);
        assertThat(d.cameraMake()).isNull();
        assertThat(d.takenAt()).isNull();
        assertThat(d.gpsLat()).isNull();
    }

    @Test
    void garbageBytesReturnAllNulls() {
        ExifData d = extractor.extract(new byte[]{1, 2, 3, 4});
        assertThat(d.width()).isNull();
        assertThat(d.gpsLat()).isNull();
        assertThat(d.cameraMake()).isNull();
    }
}
