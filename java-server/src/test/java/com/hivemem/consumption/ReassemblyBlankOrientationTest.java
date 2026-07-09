package com.hivemem.consumption;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hivemem.attachment.AttachmentService;
import com.hivemem.ocr.PdfPageRasterizer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReassemblyBlankOrientationTest {

    private byte[] png(boolean ink) throws Exception {
        BufferedImage img = new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 120, 160);
        if (ink) { g.setColor(Color.BLACK); g.fillRect(0, 0, 120, 40); }
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private byte[] nPagePdf(int n) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < n; i++) doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static DocGroup group(String id, double confidence, int... pages) {
        DocGroup g = new DocGroup(id, null);
        g.minConfidence = confidence;
        for (int p : pages) g.pages.add(p);
        return g;
    }

    @Test
    void blankPageIsDroppedFromDocument() throws Exception {
        ConsumptionProperties props = new ConsumptionProperties();
        PdfPageRasterizer rasterizer = mock(PdfPageRasterizer.class);
        PageOrienter orienter = mock(PageOrienter.class);
        PageMetadataExtractor extractor = mock(PageMetadataExtractor.class);
        MailingAssembler assembler = mock(MailingAssembler.class);
        PageReassembler reassembler = mock(PageReassembler.class);
        AttachmentService attachments = mock(AttachmentService.class);
        ConsumptionFileMover mover = mock(ConsumptionFileMover.class);

        when(orienter.orient(anyString(), anyInt(), any()))
                .thenAnswer(inv -> new PageOrienter.PageOrientation(inv.getArgument(1), 0, false, 0.9));
        when(extractor.extract(anyString(), anyInt(), any()))
                .thenAnswer(inv -> new PageMetadataExtractor.PageMetadata(inv.getArgument(1),
                        "S", null, null, "letter", null, "p", false));
        when(assembler.assemble(anyString(), anyList())).thenReturn(List.of(group("d", 0.9, 1, 2)));

        when(rasterizer.rasterize(any(), anyInt(), anyInt())).thenReturn(List.of(png(true), png(false)));
        when(reassembler.toDocuments(any(), eq(2)))
                .thenReturn(List.of(new PageReassembler.ResultDoc(List.of(1, 2), "committed")));

        ReassemblyOrchestrator orch = new ReassemblyOrchestrator(props, rasterizer, orienter, extractor,
                assembler, reassembler, new BatchSplitter(), attachments, mover);
        orch.reassemble(Path.of("Scan_x.pdf"), nPagePdf(2), 2);

        ArgumentCaptor<InputStream> pdf = ArgumentCaptor.forClass(InputStream.class);
        verify(attachments, times(1)).ingest(pdf.capture(), anyString(), eq("application/pdf"),
                any(), any(), any(), any(), eq("consumption"), anyString(), eq("consumption:"));
        try (PDDocument ingested = Loader.loadPDF(pdf.getValue().readAllBytes())) {
            Assertions.assertEquals(1, ingested.getNumberOfPages(),
                    "blank page must be dropped from the ingested document");
        }
        verify(mover).moveToProcessed(any());
    }

    @Test
    void allBlankDocumentIsNotIngested() throws Exception {
        ConsumptionProperties props = new ConsumptionProperties();
        PdfPageRasterizer rasterizer = mock(PdfPageRasterizer.class);
        PageOrienter orienter = mock(PageOrienter.class);
        PageMetadataExtractor extractor = mock(PageMetadataExtractor.class);
        MailingAssembler assembler = mock(MailingAssembler.class);
        PageReassembler reassembler = mock(PageReassembler.class);
        AttachmentService attachments = mock(AttachmentService.class);
        ConsumptionFileMover mover = mock(ConsumptionFileMover.class);

        when(orienter.orient(anyString(), anyInt(), any()))
                .thenAnswer(inv -> new PageOrienter.PageOrientation(inv.getArgument(1), 0, false, 0.9));
        when(extractor.extract(anyString(), anyInt(), any()))
                .thenAnswer(inv -> new PageMetadataExtractor.PageMetadata(inv.getArgument(1),
                        "S", null, null, "letter", null, "p", false));
        when(assembler.assemble(anyString(), anyList())).thenReturn(List.of(group("d", 0.9, 1)));

        when(rasterizer.rasterize(any(), anyInt(), anyInt())).thenReturn(List.of(png(false)));
        when(reassembler.toDocuments(any(), eq(1)))
                .thenReturn(List.of(new PageReassembler.ResultDoc(List.of(1), "pending")));

        ReassemblyOrchestrator orch = new ReassemblyOrchestrator(props, rasterizer, orienter, extractor,
                assembler, reassembler, new BatchSplitter(), attachments, mover);
        orch.reassemble(Path.of("Scan_blank.pdf"), nPagePdf(1), 1);

        verify(attachments, never()).ingest(any(), anyString(), any(), any(),
                any(), any(), any(), any(), anyString(), any());
        verify(mover).moveToProcessed(any());
    }

    @Test
    void llmRotationFlowsThroughToIngestedPdf() throws Exception {
        ConsumptionProperties props = new ConsumptionProperties();
        PdfPageRasterizer rasterizer = mock(PdfPageRasterizer.class);
        PageOrienter orienter = mock(PageOrienter.class);
        PageMetadataExtractor extractor = mock(PageMetadataExtractor.class);
        MailingAssembler assembler = mock(MailingAssembler.class);
        PageReassembler reassembler = mock(PageReassembler.class);
        AttachmentService attachments = mock(AttachmentService.class);
        ConsumptionFileMover mover = mock(ConsumptionFileMover.class);

        // The (single, non-blank) page is detected upside-down → must be rotated 180° in the stored PDF.
        when(orienter.orient(anyString(), anyInt(), any()))
                .thenReturn(new PageOrienter.PageOrientation(1, 180, false, 0.99));
        when(extractor.extract(anyString(), anyInt(), any()))
                .thenAnswer(inv -> new PageMetadataExtractor.PageMetadata(inv.getArgument(1),
                        "S", null, null, "letter", null, "p", false));
        when(assembler.assemble(anyString(), anyList())).thenReturn(List.of(group("d", 0.9, 1)));

        when(rasterizer.rasterize(any(), anyInt(), anyInt())).thenReturn(List.of(png(true)));
        when(reassembler.toDocuments(any(), eq(1)))
                .thenReturn(List.of(new PageReassembler.ResultDoc(List.of(1), "committed")));

        ReassemblyOrchestrator orch = new ReassemblyOrchestrator(props, rasterizer, orienter, extractor,
                assembler, reassembler, new BatchSplitter(), attachments, mover);
        orch.reassemble(Path.of("Scan_rot.pdf"), nPagePdf(1), 1);

        ArgumentCaptor<InputStream> pdf = ArgumentCaptor.forClass(InputStream.class);
        verify(attachments, times(1)).ingest(pdf.capture(), anyString(), eq("application/pdf"),
                any(), any(), any(), any(), eq("consumption"), anyString(), eq("consumption:"));
        try (PDDocument ingested = Loader.loadPDF(pdf.getValue().readAllBytes())) {
            Assertions.assertEquals(180, ingested.getPage(0).getRotation(),
                    "LLM-detected rotation must be applied to the stored PDF page");
        }
    }

    @Test
    void llmBlankFromPass1IsDroppedEvenWhenPixelsHaveInk() throws Exception {
        ConsumptionProperties props = new ConsumptionProperties();
        PdfPageRasterizer rasterizer = mock(PdfPageRasterizer.class);
        PageOrienter orienter = mock(PageOrienter.class);
        PageMetadataExtractor extractor = mock(PageMetadataExtractor.class);
        MailingAssembler assembler = mock(MailingAssembler.class);
        PageReassembler reassembler = mock(PageReassembler.class);
        AttachmentService attachments = mock(AttachmentService.class);
        ConsumptionFileMover mover = mock(ConsumptionFileMover.class);

        // Page 2 has ink pixels but pass-1 orientation marks it blank — the LLM verdict must win.
        when(orienter.orient(anyString(), anyInt(), any())).thenAnswer(inv -> {
            int page = inv.getArgument(1);
            return new PageOrienter.PageOrientation(page, 0, page == 2, 0.9);
        });
        when(extractor.extract(anyString(), anyInt(), any()))
                .thenAnswer(inv -> new PageMetadataExtractor.PageMetadata(inv.getArgument(1),
                        "S", null, null, "letter", null, "p", false));
        when(assembler.assemble(anyString(), anyList())).thenReturn(List.of(group("d", 0.9, 1, 2)));

        when(rasterizer.rasterize(any(), anyInt(), anyInt())).thenReturn(List.of(png(true), png(true)));
        when(reassembler.toDocuments(any(), eq(2)))
                .thenReturn(List.of(new PageReassembler.ResultDoc(List.of(1, 2), "committed")));

        ReassemblyOrchestrator orch = new ReassemblyOrchestrator(props, rasterizer, orienter, extractor,
                assembler, reassembler, new BatchSplitter(), attachments, mover);
        orch.reassemble(Path.of("Scan_llm_blank.pdf"), nPagePdf(2), 2);

        ArgumentCaptor<InputStream> pdf = ArgumentCaptor.forClass(InputStream.class);
        verify(attachments, times(1)).ingest(pdf.capture(), anyString(), eq("application/pdf"),
                any(), any(), any(), any(), eq("consumption"), anyString(), eq("consumption:"));
        try (PDDocument ingested = Loader.loadPDF(pdf.getValue().readAllBytes())) {
            Assertions.assertEquals(1, ingested.getNumberOfPages(),
                    "LLM blank verdict from pass 1 must drop the page even though the pixel signal has ink");
        }
        verify(mover).moveToProcessed(any());
    }
}
