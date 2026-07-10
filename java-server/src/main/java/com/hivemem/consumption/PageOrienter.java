package com.hivemem.consumption;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/** Pass 1 of the 3-pass reassembly: per-page orientation + blankness via a forced-choice call.
 *  The model sees the page twice — original (A) and rotated 180° (B) — and picks the upright one.
 *  Validated 15/15 + 13/13 on real duplex batches; absolute "how is this rotated?" judgments and
 *  Tesseract OSD both fail on number/table-heavy pages. Only 0/180 exist for a duplex feeder. */
public class PageOrienter {

    private static final Logger log = LoggerFactory.getLogger(PageOrienter.class);

    /** Orientation verdict for one page. rotation is clockwise degrees to make it upright (0|180). */
    public record PageOrientation(int page, int rotation, boolean blank, double confidence) {}

    static final String PROMPT = """
            Image A and image B show the SAME scanned page; B is A rotated by 180 degrees.
            Exactly one of them is upright (text readable left-to-right, top-to-bottom).
            Read the text — letters, numbers, table headers — and decide which one is upright.
            If the page is essentially blank, answer with your best guess and blank=true.
            Reply with STRICT JSON only: {"upright":"A"|"B","blank":<bool>,"confidence":<0.0-1.0>}""";

    private final VisionMultiClient vision;

    public PageOrienter(VisionMultiClient vision) {
        this.vision = vision;
    }

    /** Decide orientation of one rasterized page. Never throws: after one retry it falls back to
     *  rotation 0 / not blank / confidence 0.0 so a single bad page cannot sink the batch. */
    public PageOrientation orient(String realm, int page, byte[] png) {
        // Build the rotated render + both base64 payloads ONCE per page, not once per attempt —
        // the rotate/encode of a full-page render is the expensive part of a retry.
        List<VisionMultiClient.Image> images;
        try {
            images = List.of(
                    new VisionMultiClient.Image("image/png", Base64.getEncoder().encodeToString(png)),
                    new VisionMultiClient.Image("image/png", Base64.getEncoder().encodeToString(rotate180Png(png))));
        } catch (Exception e) {
            log.warn("Orientation image prep failed for page {}: {}", page, e.toString());
            return new PageOrientation(page, 0, false, 0.0);
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                JsonNode n = LlmJson.parseObject(vision.group(realm, PROMPT, images));
                int rotation = "B".equalsIgnoreCase(n.path("upright").asString("A")) ? 180 : 0;
                return new PageOrientation(page, rotation, n.path("blank").asBoolean(false),
                        n.path("confidence").asDouble(0.0));
            } catch (Exception e) {
                log.warn("Orientation attempt {}/2 failed for page {}: {}", attempt, page, e.toString());
            }
        }
        return new PageOrientation(page, 0, false, 0.0);
    }

    /** Rotate a PNG by 180° in memory (used for image B here and for upright pass-2 inputs). */
    static byte[] rotate180Png(byte[] png) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
            if (src == null) throw new UncheckedIOException(new IOException("unreadable PNG"));
            BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            g.rotate(Math.PI, src.getWidth() / 2.0, src.getHeight() / 2.0);
            g.drawImage(src, 0, 0, null);
            g.dispose();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(dst, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("PNG rotate failed", e);
        }
    }
}
