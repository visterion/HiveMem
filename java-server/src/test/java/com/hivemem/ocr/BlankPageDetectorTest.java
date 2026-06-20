package com.hivemem.ocr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class BlankPageDetectorTest {

    private byte[] png(java.util.function.Consumer<Graphics2D> paint) throws Exception {
        BufferedImage img = new BufferedImage(200, 280, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 280);
        paint.accept(g);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @Test
    void allWhiteIsBlank() throws Exception {
        byte[] white = png(g -> {});
        assertTrue(BlankPageDetector.isNearWhite(white, 0.995));
    }

    @Test
    void pageWithTextBlockIsNotBlank() throws Exception {
        byte[] withInk = png(g -> { g.setColor(Color.BLACK); g.fillRect(0, 0, 200, 28); });
        assertFalse(BlankPageDetector.isNearWhite(withInk, 0.995));
    }

    @Test
    void faintSpeckSurvivesConservativeThreshold() throws Exception {
        byte[] speck = png(g -> { g.setColor(Color.BLACK); g.fillRect(0, 0, 4, 4); });
        assertTrue(BlankPageDetector.isNearWhite(speck, 0.995));
    }

    @Test
    void undecodableBytesAreNotBlank() {
        assertFalse(BlankPageDetector.isNearWhite(new byte[] {1, 2, 3}, 0.995));
    }
}
