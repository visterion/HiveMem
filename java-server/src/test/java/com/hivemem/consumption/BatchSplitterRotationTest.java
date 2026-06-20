package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

class BatchSplitterRotationTest {

    private byte[] threePagePdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 3; i++) doc.addPage(new PDPage(PDRectangle.A4)); // rotation 0
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    @Test
    void appliesRotationToImportedPages() throws Exception {
        byte[] pdf = threePagePdf();
        List<byte[]> parts = new BatchSplitter().assemble(
                pdf, List.of(List.of(1, 2, 3)), Map.of(2, 180));

        assertEquals(1, parts.size());
        try (PDDocument out = Loader.loadPDF(parts.get(0))) {
            assertEquals(0, out.getPage(0).getRotation());
            assertEquals(180, out.getPage(1).getRotation());
            assertEquals(0, out.getPage(2).getRotation());
        }
    }

    @Test
    void twoArgOverloadStillWorksWithNoRotation() throws Exception {
        byte[] pdf = threePagePdf();
        List<byte[]> parts = new BatchSplitter().assemble(pdf, List.of(List.of(1, 2)));
        assertEquals(1, parts.size());
        try (PDDocument out = Loader.loadPDF(parts.get(0))) {
            assertEquals(2, out.getNumberOfPages());
            assertEquals(0, out.getPage(0).getRotation());
        }
    }
}
