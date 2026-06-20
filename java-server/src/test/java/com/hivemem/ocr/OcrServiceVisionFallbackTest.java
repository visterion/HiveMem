package com.hivemem.ocr;

import com.hivemem.attachment.SeaweedFsClient;
import com.hivemem.attachment.VisionBudgetTracker;
import com.hivemem.attachment.VisionClient;
import com.hivemem.consumption.DocumentDedupService;
import com.hivemem.write.WriteToolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit-tests the Tesseract→Vision fallback decision in {@link OcrService}.
 * Uses the package-private test constructor so we can swap in mocked
 * {@link TesseractRunner}, {@link PdfPageRasterizer}, and {@link VisionClient}.
 */
class OcrServiceVisionFallbackTest {

    private OcrProperties props;
    private OcrRepository repo;
    private SeaweedFsClient seaweed;
    private WriteToolService writeService;
    private VisionClient visionClient;
    private VisionBudgetTracker visionBudget;
    private TesseractRunner tesseract;
    private PdfPageRasterizer rasterizer;
    private DocumentDedupService dedup;

    @BeforeEach
    void setUp() throws Exception {
        props = new OcrProperties();
        props.setEnabled(true);
        props.setLanguages("eng");
        props.setMaxPages(10);
        props.setRenderDpi(300);
        props.setCallTimeoutSeconds(60);
        props.setVisionFallbackEnabled(true);
        props.setVisionFallbackMinCharsPerPage(30);
        props.setVisionFallbackMaxPagesPerDoc(5);

        repo = mock(OcrRepository.class);
        seaweed = mock(SeaweedFsClient.class);
        writeService = mock(WriteToolService.class);
        visionClient = mock(VisionClient.class);
        visionBudget = mock(VisionBudgetTracker.class);
        tesseract = mock(TesseractRunner.class);
        rasterizer = mock(PdfPageRasterizer.class);

        when(seaweed.download(anyString())).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(rasterizer.rasterize(any(), anyInt(), anyInt()))
                .thenReturn(List.of("PAGE1".getBytes(), "PAGE2".getBytes()));
        when(writeService.reviseCell(any(), any(), anyString(), any()))
                .thenReturn(Map.of("new_id", UUID.randomUUID().toString()));
        when(visionClient.isEnabled()).thenReturn(true);
        when(visionBudget.canSpend()).thenReturn(true);
        dedup = mock(DocumentDedupService.class);
        when(dedup.findAndDiscardDuplicate(any())).thenReturn(java.util.Optional.empty());
    }

    private OcrService build() {
        return new OcrService(props, repo, seaweed, writeService,
                visionClient, visionBudget, tesseract, rasterizer, dedup);
    }

    @Test
    void invokesVision_whenTesseractOutputBelowThreshold() throws Exception {
        when(tesseract.ocr(any(), anyString(), anyInt())).thenReturn("xx"); // sparse → triggers fallback
        when(visionClient.transcribe(any(), eq("image/png")))
                .thenReturn(new VisionClient.VisionResult(
                        "FULL VISION TRANSCRIPT", 100, 50));

        UUID cellId = UUID.randomUUID();
        build().processOne(cellId, "key");

        verify(visionClient, times(2)).transcribe(any(), eq("image/png"));
        verify(visionBudget, times(2)).recordCall(100, 50);

        var contentCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(writeService).reviseCell(any(), eq(cellId), contentCaptor.capture(), any());
        String content = contentCaptor.getValue();
        assertTrue(content.contains("FULL VISION TRANSCRIPT"),
                "Expected vision output in revised content, got: " + content);
        assertTrue(content.contains("[page=1]"));
        assertTrue(content.contains("[page=2]"));
    }

    @Test
    void skipsVision_whenTesseractOutputSufficient() throws Exception {
        String dense = "x".repeat(200);
        when(tesseract.ocr(any(), anyString(), anyInt())).thenReturn(dense);

        build().processOne(UUID.randomUUID(), "key");

        verify(visionClient, never()).transcribe(any(), anyString());
        verify(visionBudget, never()).recordCall(anyInt(), anyInt());
    }

    @Test
    void skipsVision_whenFallbackDisabled() throws Exception {
        props.setVisionFallbackEnabled(false);
        when(tesseract.ocr(any(), anyString(), anyInt())).thenReturn("xx");

        build().processOne(UUID.randomUUID(), "key");

        verify(visionClient, never()).transcribe(any(), anyString());
    }

    @Test
    void skipsVision_whenBudgetExhausted() throws Exception {
        when(tesseract.ocr(any(), anyString(), anyInt())).thenReturn("xx");
        when(visionBudget.canSpend()).thenReturn(false);

        build().processOne(UUID.randomUUID(), "key");

        verify(visionClient, never()).transcribe(any(), anyString());
    }

    @Test
    void respectsMaxPagesPerDocCap() throws Exception {
        when(rasterizer.rasterize(any(), anyInt(), anyInt())).thenReturn(List.of(
                "P1".getBytes(), "P2".getBytes(), "P3".getBytes(),
                "P4".getBytes(), "P5".getBytes(), "P6".getBytes()));
        props.setVisionFallbackMaxPagesPerDoc(2);
        when(tesseract.ocr(any(), anyString(), anyInt())).thenReturn("xx");
        when(visionClient.transcribe(any(), anyString()))
                .thenReturn(new VisionClient.VisionResult("V", 10, 10));

        build().processOne(UUID.randomUUID(), "key");

        verify(visionClient, times(2)).transcribe(any(), anyString());
    }

    @Test
    void usesTesseractWhenVisionThrows() throws Exception {
        when(tesseract.ocr(any(), anyString(), anyInt())).thenReturn("ab"); // sparse but non-empty
        when(visionClient.transcribe(any(), anyString()))
                .thenThrow(new RuntimeException("API down"));

        UUID cellId = UUID.randomUUID();
        build().processOne(cellId, "key");

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(writeService).reviseCell(any(), eq(cellId), captor.capture(), any());
        // Falls back to whatever Tesseract produced (sparse but non-empty)
        assertTrue(captor.getValue().contains("ab"),
                "Expected sparse Tesseract output retained, got: " + captor.getValue());
    }

    @Test
    void runsDedupOnlyForShortDocs() throws Exception {
        // Two pages, each short → total OCR'd text (incl. "[page=N]\n" prefixes) stays well under
        // the 500-char summary threshold, so the doc is embedded at revise time and dedup runs now.
        when(tesseract.ocr(any(), anyString(), anyInt())).thenReturn("short recognized text");
        UUID newId = UUID.randomUUID();
        when(writeService.reviseCell(any(), any(), anyString(), any()))
                .thenReturn(Map.of("new_id", newId.toString()));

        build().processOne(UUID.randomUUID(), "key");

        verify(dedup).findAndDiscardDuplicate(eq(newId));
    }

    @Test
    void skipsDedupForLongDocs() throws Exception {
        // Long per-page text → total OCR'd text exceeds the 500-char summary threshold, so the doc
        // needs a summary and is NOT embedded yet; dedup must be deferred to the summarizer.
        when(tesseract.ocr(any(), anyString(), anyInt())).thenReturn("y".repeat(400)); // 2 pages → >800 chars
        UUID newId = UUID.randomUUID();
        when(writeService.reviseCell(any(), any(), anyString(), any()))
                .thenReturn(Map.of("new_id", newId.toString()));

        build().processOne(UUID.randomUUID(), "key");

        verify(dedup, never()).findAndDiscardDuplicate(any());
    }
}
