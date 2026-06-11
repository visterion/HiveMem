package com.hivemem.attachment;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

@Component
public class ImageAttachmentParser implements AttachmentParser {

    private static final int THUMBNAIL_MAX_WIDTH = 500;

    @Override
    public boolean supports(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    @Override
    public ParseResult parse(InputStream content) throws Exception {
        byte[] bytes = content.readAllBytes();
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
        if (src == null) return ParseResult.empty();
        BufferedImage upright = applyOrientation(src, readOrientation(bytes));
        BufferedImage scaled = scale(upright, THUMBNAIL_MAX_WIDTH);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(scaled, "JPEG", out);
        return ParseResult.withThumbnail(null, out.toByteArray());
    }

    private static int readOrientation(byte[] bytes) {
        try {
            Metadata md = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            ExifIFD0Directory d = md.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (d != null && d.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return d.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception ignored) {
            // no/unreadable EXIF → assume normal orientation
        }
        return 1;
    }

    /** Apply EXIF orientation (1..8) so the image is visually upright. */
    private static BufferedImage applyOrientation(BufferedImage img, int orientation) {
        if (orientation <= 1) return img;
        int w = img.getWidth(), h = img.getHeight();
        AffineTransform tx = new AffineTransform();
        boolean swap = false;
        switch (orientation) {
            case 2 -> { tx.scale(-1, 1); tx.translate(-w, 0); }                 // mirror horizontal
            case 3 -> { tx.translate(w, h); tx.rotate(Math.PI); }              // 180
            case 4 -> { tx.scale(1, -1); tx.translate(0, -h); }                // mirror vertical
            case 5 -> { tx.rotate(Math.PI / 2); tx.scale(1, -1); swap = true; } // transpose
            case 6 -> { tx.translate(h, 0); tx.rotate(Math.PI / 2); swap = true; } // 90 CW
            case 7 -> { tx.scale(-1, 1); tx.translate(-h, 0); tx.rotate(Math.PI / 2); swap = true; } // transverse
            case 8 -> { tx.translate(0, w); tx.rotate(-Math.PI / 2); swap = true; } // 90 CCW
            default -> { return img; }
        }
        BufferedImage dst = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, new AffineTransformOp(tx, AffineTransformOp.TYPE_BILINEAR), 0, 0);
        g.dispose();
        return dst;
    }

    private BufferedImage scale(BufferedImage src, int maxWidth) {
        if (src.getWidth() <= maxWidth) {
            BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(src, 0, 0, null);
            g.dispose();
            return rgb;
        }
        int height = (int) ((long) src.getHeight() * maxWidth / src.getWidth());
        BufferedImage dst = new BufferedImage(maxWidth, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, maxWidth, height, null);
        g.dispose();
        return dst;
    }
}
