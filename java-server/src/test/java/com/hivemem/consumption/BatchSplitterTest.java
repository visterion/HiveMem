package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

class BatchSplitterTest {

    private static byte[] pdfWithPages(int n) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < n; i++) doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static int pageCount(byte[] pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf)) { return d.getNumberOfPages(); }
    }

    @Test
    void splitsAtBoundaries() throws Exception {
        byte[] src = pdfWithPages(5);
        List<byte[]> parts = new BatchSplitter().split(src, List.of(3));
        assertEquals(2, parts.size());
        assertEquals(3, pageCount(parts.get(0)));
        assertEquals(2, pageCount(parts.get(1)));
    }

    @Test
    void emptyBoundariesYieldWholeDoc() throws Exception {
        byte[] src = pdfWithPages(4);
        List<byte[]> parts = new BatchSplitter().split(src, List.of());
        assertEquals(1, parts.size());
        assertEquals(4, pageCount(parts.get(0)));
    }

    @Test
    void ignoresOutOfRangeAndDuplicateBoundaries() throws Exception {
        byte[] src = pdfWithPages(3);
        List<byte[]> parts = new BatchSplitter().split(src, List.of(0, 1, 1, 3, 9));
        assertEquals(2, parts.size());     // only boundary at 1 is valid (after p1); 3==total is a no-op
        assertEquals(1, pageCount(parts.get(0)));
        assertEquals(2, pageCount(parts.get(1)));
    }
}
