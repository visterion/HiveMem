package com.hivemem.ocr;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PdfPageRasterizer {

    /**
     * Renders each page (up to {@code maxPages}) at {@code dpi} and hands it to
     * {@code consumer} one page at a time, discarding the rendered bytes before moving to the
     * next page. Rendering all pages of a large PDF (up to 50 @ 300 DPI) up front and holding
     * every PNG in memory for the whole OCR run could exhaust the heap on big documents —
     * this keeps at most one rendered page alive at a time. Prefer this overload for
     * single-pass consumers (see {@link com.hivemem.ocr.OcrService}); callers that genuinely
     * need random/multi-pass access to every page (e.g. consumption's batch reassembly, which
     * makes two passes over all pages) should use {@link #rasterize(byte[], int, int)} instead.
     */
    public void rasterize(byte[] pdfBytes, int dpi, int maxPages, PageConsumer consumer) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pageCount = Math.min(doc.getNumberOfPages(), maxPages);
            for (int i = 0; i < pageCount; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, dpi);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", baos);
                consumer.accept(i, baos.toByteArray());
            }
        }
    }

    /**
     * Renders every page (up to {@code maxPages}) and returns them all as a list. Materializes
     * every page's PNG bytes in memory at once — only use this when the caller genuinely needs
     * multi-pass/random access to all pages (e.g. consumption's two-pass batch reassembly).
     * Single-pass consumers should use {@link #rasterize(byte[], int, int, PageConsumer)}.
     */
    public List<byte[]> rasterize(byte[] pdfBytes, int dpi, int maxPages) throws IOException {
        List<byte[]> pages = new ArrayList<>();
        rasterize(pdfBytes, dpi, maxPages, (pageIndex, pngBytes) -> pages.add(pngBytes));
        return pages;
    }

    /** Receives one rendered page at a time; {@code pageIndex} is 0-based. */
    @FunctionalInterface
    public interface PageConsumer {
        void accept(int pageIndex, byte[] pngBytes) throws IOException;
    }
}
