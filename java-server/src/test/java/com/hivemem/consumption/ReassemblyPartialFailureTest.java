package com.hivemem.consumption;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hivemem.attachment.AttachmentService;
import com.hivemem.ocr.PageOsd;
import com.hivemem.ocr.PdfPageRasterizer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;

class ReassemblyPartialFailureTest {

    private byte[] inkPng() throws Exception {
        BufferedImage img = new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 120, 160);
        g.setColor(Color.BLACK); g.fillRect(0, 0, 120, 40);
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

    /** When the first sub-doc ingests successfully but the second throws, the whole batch must be
     *  routed to failed/ and moveToProcessed must NEVER be called. */
    @Test
    void partialIngestFailureRoutesWholeToFailed() throws Exception {
        ConsumptionProperties props = new ConsumptionProperties();
        PdfPageRasterizer rasterizer = mock(PdfPageRasterizer.class);
        PageGrouper grouper = mock(PageGrouper.class);
        PageReassembler reassembler = mock(PageReassembler.class);
        AttachmentService attachments = mock(AttachmentService.class);
        ConsumptionFileMover mover = mock(ConsumptionFileMover.class);
        PageOsd osd = mock(PageOsd.class);
        when(osd.detectRotation(any(), anyInt())).thenReturn(0);

        // Two pages, both with ink (non-blank)
        byte[] page = inkPng();
        when(rasterizer.rasterize(any(), anyInt(), anyInt())).thenReturn(List.of(page, page));
        // Two documents: page 1 → doc 1, page 2 → doc 2
        when(reassembler.toDocuments(any(), anyInt())).thenReturn(List.of(
                new PageReassembler.ResultDoc(List.of(1), "committed"),
                new PageReassembler.ResultDoc(List.of(2), "committed")));

        // First call to attachments.ingest succeeds; second throws
        doThrow(new RuntimeException("DB down"))
                .when(attachments).ingest(any(InputStream.class), anyString(), anyString(),
                        any(), any(), any(), any(), anyString(), anyString(), anyString());

        Path stagedPath = Path.of("Scan_two_pages.pdf");
        ReassemblyOrchestrator orch = new ReassemblyOrchestrator(
                props, rasterizer, grouper, reassembler, new BatchSplitter(), attachments, mover, osd);
        orch.reassemble(stagedPath, nPagePdf(2), 2);

        verify(mover).moveToFailed(stagedPath);
        verify(mover, never()).moveToProcessed(any());
    }

    /** FIX 4: when reassembly throws (forcing degrade path) AND the degrade ingest also throws,
     *  the file must be routed to failed/ and moveToProcessed must NEVER be called. */
    @Test
    void degradeIngestFailureRoutesToFailed() throws Exception {
        ConsumptionProperties props = new ConsumptionProperties();
        PdfPageRasterizer rasterizer = mock(PdfPageRasterizer.class);
        PageGrouper grouper = mock(PageGrouper.class);
        PageReassembler reassembler = mock(PageReassembler.class);
        AttachmentService attachments = mock(AttachmentService.class);
        ConsumptionFileMover mover = mock(ConsumptionFileMover.class);
        ConsumptionFileRepository fileRepo = mock(ConsumptionFileRepository.class);
        PageOsd osd = mock(PageOsd.class);

        // Make rasterizer throw to force the degrade path
        when(rasterizer.rasterize(any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("rasterizer crashed"));

        // Make the degrade attachments.ingest also throw
        doThrow(new RuntimeException("S3 down"))
                .when(attachments).ingest(any(InputStream.class), anyString(), anyString(),
                        any(), any(), any(), any(), anyString(), anyString(), anyString());

        Path stagedPath = Path.of("Scan_degrade_fail.pdf");
        ReassemblyOrchestrator orch = new ReassemblyOrchestrator(
                props, rasterizer, grouper, reassembler, new BatchSplitter(), attachments, mover, osd);
        orch.reassemble(stagedPath, nPagePdf(2), 2, "deadbeef", fileRepo);

        verify(mover).moveToFailed(stagedPath);
        verify(mover, never()).moveToProcessed(any());
        verify(fileRepo).markFailed(eq("deadbeef"), anyString());
        verify(fileRepo, never()).markDone(any());
    }

    /** When both sub-docs ingest successfully, the batch must go to processed/ (regression guard). */
    @Test
    void allSuccessfulIngestsRoutesToProcessed() throws Exception {
        ConsumptionProperties props = new ConsumptionProperties();
        PdfPageRasterizer rasterizer = mock(PdfPageRasterizer.class);
        PageGrouper grouper = mock(PageGrouper.class);
        PageReassembler reassembler = mock(PageReassembler.class);
        AttachmentService attachments = mock(AttachmentService.class);
        ConsumptionFileMover mover = mock(ConsumptionFileMover.class);
        PageOsd osd = mock(PageOsd.class);
        when(osd.detectRotation(any(), anyInt())).thenReturn(0);

        byte[] page = inkPng();
        when(rasterizer.rasterize(any(), anyInt(), anyInt())).thenReturn(List.of(page, page));
        when(reassembler.toDocuments(any(), anyInt())).thenReturn(List.of(
                new PageReassembler.ResultDoc(List.of(1), "committed"),
                new PageReassembler.ResultDoc(List.of(2), "committed")));

        Path stagedPath = Path.of("Scan_two_ok.pdf");
        ReassemblyOrchestrator orch = new ReassemblyOrchestrator(
                props, rasterizer, grouper, reassembler, new BatchSplitter(), attachments, mover, osd);
        orch.reassemble(stagedPath, nPagePdf(2), 2);

        verify(mover).moveToProcessed(stagedPath);
        verify(mover, never()).moveToFailed(any());
    }
}
