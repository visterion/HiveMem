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

    /**
     * FIX D5c: width/height must come from metadata (JPEG SOF segment / EXIF
     * PixelXDimension/PixelYDimension), NOT from an always-on full {@code ImageIO.read()}
     * decode of the whole bitmap. Proven here by corrupting the JPEG's entropy-coded scan
     * data (after the header) so a full decode is unreliable/expensive, while dimensions must
     * still come back correct because they were read from the header, never from pixels.
     */
    @Test
    void dimensionsSurviveCorruptedScanDataProvingNoFullDecodeIsRequired() throws Exception {
        byte[] jpeg = baseJpeg(200, 150);
        byte[] corrupted = jpeg.clone();
        // Locate the Start-Of-Scan marker (FF DA); scramble everything after its header
        // (leaving the EOI marker FF D9 at the very end alone) so the compressed pixel data
        // is garbage while the SOF0 header (which carries width/height) stays intact.
        int sosIndex = -1;
        for (int i = 0; i < corrupted.length - 1; i++) {
            if ((corrupted[i] & 0xFF) == 0xFF && (corrupted[i + 1] & 0xFF) == 0xDA) {
                sosIndex = i;
                break;
            }
        }
        assertThat(sosIndex).isGreaterThan(0);
        int scanDataStart = sosIndex + 14; // past the SOS marker's own header bytes
        for (int i = scanDataStart; i < corrupted.length - 2; i++) {
            corrupted[i] = (byte) 0x00;
        }

        ExifData d = extractor.extract(corrupted);
        assertThat(d.width()).isEqualTo(200);
        assertThat(d.height()).isEqualTo(150);
    }

    @Test
    void largeImageDimensionsAreCorrectWithoutExif() throws Exception {
        // A size representative of a real photo — exercises the same header-metadata path a
        // full decode would have needed a large heap allocation for.
        ExifData d = extractor.extract(baseJpeg(4000, 3000));
        assertThat(d.width()).isEqualTo(4000);
        assertThat(d.height()).isEqualTo(3000);
    }
}
