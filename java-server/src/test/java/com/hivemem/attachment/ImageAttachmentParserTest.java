package com.hivemem.attachment;

import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageAttachmentParserTest {

    private final ImageAttachmentParser parser = new ImageAttachmentParser();

    private static byte[] jpegWithOrientation(int w, int h, short orientation) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream base = new ByteArrayOutputStream();
        ImageIO.write(img, "JPEG", base);
        TiffOutputSet set = new TiffOutputSet();
        TiffOutputDirectory root = set.getOrCreateRootDirectory();
        root.add(TiffTagConstants.TIFF_TAG_ORIENTATION, orientation);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExifRewriter().updateExifMetadataLossless(base.toByteArray(), out, set);
        return out.toByteArray();
    }

    @Test
    void orientation6SwapsThumbnailDimensions() throws Exception {
        byte[] src = jpegWithOrientation(120, 80, (short) 6); // landscape, must become portrait
        ParseResult r = parser.parse(new ByteArrayInputStream(src));
        assertThat(r.hasThumbnail()).isTrue();
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(r.thumbnail()));
        assertThat(thumb.getHeight()).isGreaterThan(thumb.getWidth());
    }

    @Test
    void orientation1KeepsLandscape() throws Exception {
        byte[] src = jpegWithOrientation(120, 80, (short) 1);
        ParseResult r = parser.parse(new ByteArrayInputStream(src));
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(r.thumbnail()));
        assertThat(thumb.getWidth()).isGreaterThan(thumb.getHeight());
    }

    @Test
    void supports_imageMimeTypes() {
        assertTrue(parser.supports("image/png"));
        assertTrue(parser.supports("image/jpeg"));
        assertTrue(parser.supports("image/gif"));
    }

    @Test
    void supports_rejectsNonImage() {
        assertFalse(parser.supports("application/pdf"));
        assertFalse(parser.supports(null));
    }

    @Test
    void parse_smallImage_returnsThumbnailWithoutScaling() throws Exception {
        byte[] png = makePng(200, 100);

        var result = parser.parse(new ByteArrayInputStream(png));

        assertNotNull(result.thumbnail());
        assertNull(result.extractedText());
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(result.thumbnail()));
        assertNotNull(out);
        // Small image: width preserved.
        assert out.getWidth() == 200;
    }

    @Test
    void parse_largeImage_isScaledTo500WideKeepingAspect() throws Exception {
        byte[] png = makePng(1000, 400);

        var result = parser.parse(new ByteArrayInputStream(png));

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(result.thumbnail()));
        assert out.getWidth() == 500;
        // 1000:400 aspect → 500:200
        assert out.getHeight() == 200;
    }

    @Test
    void parse_invalidImageBytes_returnsEmpty() throws Exception {
        var result = parser.parse(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));

        assertNull(result.thumbnail());
        assertNull(result.extractedText());
    }

    private static byte[] makePng(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", out);
        return out.toByteArray();
    }
}
