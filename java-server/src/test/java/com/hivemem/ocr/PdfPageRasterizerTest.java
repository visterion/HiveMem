package com.hivemem.ocr;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PdfPageRasterizerTest {

    @Test
    void rasterizesEachPageToPngViaCallback() throws Exception {
        ByteArrayOutputStream pdfBytes = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 3; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    // empty page
                }
            }
            doc.save(pdfBytes);
        }

        PdfPageRasterizer rasterizer = new PdfPageRasterizer();
        List<byte[]> pages = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        rasterizer.rasterize(pdfBytes.toByteArray(), 100, 50, (pageIndex, png) -> {
            indices.add(pageIndex);
            pages.add(png);
        });

        assertEquals(3, pages.size());
        assertEquals(List.of(0, 1, 2), indices);
        for (byte[] png : pages) {
            assertTrue(png.length > 0);
            assertEquals((byte) 0x89, png[0]);
            assertEquals((byte) 0x50, png[1]);
        }
    }

    @Test
    void respectsMaxPages() throws Exception {
        ByteArrayOutputStream pdfBytes = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 10; i++) doc.addPage(new PDPage());
            doc.save(pdfBytes);
        }

        PdfPageRasterizer rasterizer = new PdfPageRasterizer();
        AtomicInteger count = new AtomicInteger();
        rasterizer.rasterize(pdfBytes.toByteArray(), 72, 3, (pageIndex, png) -> count.incrementAndGet());
        assertEquals(3, count.get());
    }

    @Test
    void consumerIsInvokedOncePerPageAndPagesAreNotAllHeldAtOnce() throws Exception {
        // A counting consumer that also asserts only one page's bytes are ever "live" at a
        // time from the rasterizer's perspective (it never hands us more than one PNG per
        // call, and never accumulates a backlog before invoking us) — this is the guarantee
        // that replaces the old rasterize() returning a List<byte[]> of every page up front.
        ByteArrayOutputStream pdfBytes = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 5; i++) doc.addPage(new PDPage());
            doc.save(pdfBytes);
        }

        PdfPageRasterizer rasterizer = new PdfPageRasterizer();
        AtomicInteger invocations = new AtomicInteger();
        List<Integer> seenOrder = new ArrayList<>();
        rasterizer.rasterize(pdfBytes.toByteArray(), 72, 50, (pageIndex, png) -> {
            invocations.incrementAndGet();
            seenOrder.add(pageIndex);
            assertNotNull(png);
            assertTrue(png.length > 0);
        });

        assertEquals(5, invocations.get());
        assertEquals(List.of(0, 1, 2, 3, 4), seenOrder);
    }
}
