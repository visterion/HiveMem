package com.hivemem.attachment;

import com.hivemem.ocr.OcrProperties;
import com.hivemem.ocr.ScanDetector;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

@Component
public class PdfAttachmentParser implements AttachmentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfAttachmentParser.class);

    private static final int THUMBNAIL_MAX_WIDTH = 500;
    private static final float RENDER_DPI = 150f;
    private static final int DEFAULT_SCAN_THRESHOLD = 50;

    private final OcrProperties ocrProperties;
    private final ScanDetector scanDetector = new ScanDetector();

    @Autowired
    public PdfAttachmentParser(OcrProperties ocrProperties) {
        this.ocrProperties = ocrProperties;
    }

    /** Test/no-config fallback. */
    public PdfAttachmentParser() {
        this.ocrProperties = null;
    }

    @Override
    public boolean supports(String mimeType) {
        return "application/pdf".equals(mimeType);
    }

    @Override
    public ParseResult parse(InputStream content) throws Exception {
        try (PDDocument doc = Loader.loadPDF(content.readAllBytes())) {
            String text = new PDFTextStripper().getText(doc);
            int pageCount = doc.getNumberOfPages();
            int threshold = (ocrProperties != null)
                    ? ocrProperties.getScanDetectionThreshold()
                    : DEFAULT_SCAN_THRESHOLD;
            boolean scanLikely = scanDetector.isScan(text, pageCount, threshold);
            // A thumbnail render failure must never discard successfully-extracted text
            // (or the scanLikely signal, which drives OCR).
            byte[] thumbnail = null;
            try {
                thumbnail = renderFirstPageThumbnail(doc);
            } catch (Exception e) {
                log.warn("PDF page-1 thumbnail render failed, keeping extracted text: {}",
                        e.getMessage());
            }
            return ParseResult.withThumbnailAndScan(
                    text.isBlank() ? null : text.strip(), thumbnail, scanLikely, pageCount);
        }
    }

    private byte[] renderFirstPageThumbnail(PDDocument doc) throws Exception {
        PDFRenderer renderer = new PDFRenderer(doc);
        BufferedImage full = renderer.renderImageWithDPI(0, RENDER_DPI);
        BufferedImage scaled = scale(full, THUMBNAIL_MAX_WIDTH);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(scaled, "JPEG", out);
        return out.toByteArray();
    }

    private BufferedImage scale(BufferedImage src, int maxWidth) {
        if (src.getWidth() <= maxWidth) return src;
        int height = (int) ((long) src.getHeight() * maxWidth / src.getWidth());
        BufferedImage dst = new BufferedImage(maxWidth, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, maxWidth, height, null);
        g.dispose();
        return dst;
    }
}
