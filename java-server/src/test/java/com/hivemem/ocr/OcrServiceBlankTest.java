package com.hivemem.ocr;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hivemem.attachment.SeaweedFsClient;
import com.hivemem.write.WriteToolService;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class OcrServiceBlankTest {

    private byte[] png(boolean ink) throws Exception {
        BufferedImage img = new BufferedImage(100, 140, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 100, 140);
        if (ink) { g.setColor(Color.BLACK); g.fillRect(0, 0, 100, 40); }
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    /** Stubs the streaming rasterize(...) overload OcrService calls, invoking the given
     *  consumer once per fake page — mirrors the real rasterizer's page-by-page contract. */
    private static void stubPages(PdfPageRasterizer raster, List<byte[]> pages) throws Exception {
        doAnswer(invocation -> {
            PdfPageRasterizer.PageConsumer consumer = invocation.getArgument(3);
            for (int i = 0; i < pages.size(); i++) {
                consumer.accept(i, pages.get(i));
            }
            return null;
        }).when(raster).rasterize(any(), anyInt(), anyInt(), any());
    }

    private OcrService build(OcrRepository repo, SeaweedFsClient seaweed, WriteToolService writeService,
                            TesseractRunner tess, PdfPageRasterizer raster) {
        OcrProperties props = new OcrProperties();
        props.setVisionFallbackEnabled(false);
        return new OcrService(props, repo, seaweed, writeService, null, null, tess, raster, null);
    }

    @Test
    void dropsBlankPageKeepsRealPageAndRenumbers() throws Exception {
        UUID cellId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        OcrRepository repo = mock(OcrRepository.class);
        when(repo.tryClaim(any())).thenReturn(true); // open the concurrency-claim gate
        WriteToolService writeService = mock(WriteToolService.class);
        SeaweedFsClient seaweed = mock(SeaweedFsClient.class);
        when(seaweed.download(anyString())).thenReturn(new ByteArrayInputStream(new byte[] {1}));
        PdfPageRasterizer raster = mock(PdfPageRasterizer.class);
        stubPages(raster, List.of(png(true), png(false)));
        TesseractRunner tess = mock(TesseractRunner.class);
        when(tess.ocr(eq(png(true)), any(), anyInt())).thenReturn("Real invoice text");
        when(tess.ocr(eq(png(false)), any(), anyInt())).thenReturn(""); // blank page → empty OCR
        when(writeService.reviseCell(any(), eq(cellId), anyString(), any()))
                .thenReturn(Map.of("old_id", cellId.toString(), "new_id", newId.toString()));

        build(repo, seaweed, writeService, tess, raster).processOne(cellId, "key.pdf");

        verify(writeService).reviseCell(any(), eq(cellId), contains("Real invoice text"), any());
        verify(repo, never()).softDeleteBlankCell(any());
    }

    @Test
    void softDeletesCellWhenAllPagesBlank() throws Exception {
        UUID cellId = UUID.randomUUID();
        OcrRepository repo = mock(OcrRepository.class);
        when(repo.tryClaim(any())).thenReturn(true); // open the concurrency-claim gate
        WriteToolService writeService = mock(WriteToolService.class);
        SeaweedFsClient seaweed = mock(SeaweedFsClient.class);
        when(seaweed.download(anyString())).thenReturn(new ByteArrayInputStream(new byte[] {1}));
        PdfPageRasterizer raster = mock(PdfPageRasterizer.class);
        stubPages(raster, List.of(png(false)));
        TesseractRunner tess = mock(TesseractRunner.class);
        when(tess.ocr(any(), any(), anyInt())).thenReturn("");

        build(repo, seaweed, writeService, tess, raster).processOne(cellId, "key.pdf");

        // Safety-critical ordering: the ocr_pending tag is cleared before the cell is retired,
        // so a soft-deleted blank cell is never left looking like it still needs OCR.
        org.mockito.InOrder ord = org.mockito.Mockito.inOrder(repo);
        ord.verify(repo).removeOcrPendingTag(cellId);
        ord.verify(repo).softDeleteBlankCell(cellId);
        verify(writeService, never()).reviseCell(any(), eq(cellId), anyString(), any());
    }
}
