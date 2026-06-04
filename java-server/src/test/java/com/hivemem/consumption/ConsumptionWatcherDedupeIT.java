package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hivemem.ocr.OcrProperties;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Regression test for BUG 1: a multi-page PDF in the watch root must be dispatched for separation
 * EXACTLY once across multiple poll cycles, and must be removed from the watch root (staged into
 * processing/). Without the staging move the watcher would re-scan and re-dispatch on every cycle.
 */
class ConsumptionWatcherDedupeIT extends ConsumptionITSupport {

    private static byte[] pdfWithPages(int n) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < n; i++) doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void multiPagePdfDispatchedExactlyOnceAcrossPolls(@TempDir Path root) throws Exception {
        Files.write(root.resolve("batch.pdf"), pdfWithPages(2));

        ConsumptionProperties cp = new ConsumptionProperties();
        cp.setEnabled(true);
        cp.setDir(root.toString());
        cp.setRealm("documents");
        cp.setStableSeconds(0); // stabilizes immediately on second sighting

        VistierieSeparationClient mockClient = mock(VistierieSeparationClient.class);
        ObjectProvider<VistierieSeparationClient> provider = new ObjectProvider<>() {
            @Override public VistierieSeparationClient getObject(Object... args) { return mockClient; }
            @Override public VistierieSeparationClient getObject() { return mockClient; }
            @Override public VistierieSeparationClient getIfAvailable() { return mockClient; }
            @Override public VistierieSeparationClient getIfUnique() { return mockClient; }
            @Override public Stream<VistierieSeparationClient> stream() { return Stream.of(mockClient); }
        };

        ObjectProvider<VisionMultiClient> nullVisionProvider = new ObjectProvider<>() {
            @Override public VisionMultiClient getObject(Object... args) { return null; }
            @Override public VisionMultiClient getObject() { return null; }
            @Override public VisionMultiClient getIfAvailable() { return null; }
            @Override public VisionMultiClient getIfUnique() { return null; }
            @Override public Stream<VisionMultiClient> stream() { return Stream.empty(); }
        };
        ConsumptionService svc = new ConsumptionService(
                cp, attachments, new OcrProperties(), seaweed, jobRepo, provider, nullVisionProvider);

        // Fixed clock far in the future so (now - mtime) >= stableMillis always holds.
        Clock clock = Clock.fixed(Instant.now().plusSeconds(3600), ZoneOffset.UTC);
        // Runnable::run = direct executor so processStaged runs synchronously within poll() (deterministic).
        ConsumptionWatcher watcher = new ConsumptionWatcher(cp, svc, Runnable::run, clock);

        watcher.poll(); // first sighting: records, no dispatch
        watcher.poll(); // stable: dispatch + stage to processing/
        watcher.poll(); // file gone from root: nothing
        watcher.poll(); // file gone from root: nothing

        verify(mockClient, times(1)).dispatch(any(), any());

        int jobRows = dsl.fetchOne("SELECT count(*) FROM consumption_jobs").get(0, Integer.class);
        assertEquals(1, jobRows, "exactly one separation job should be created");

        assertFalse(Files.exists(root.resolve("batch.pdf")),
                "source must be removed from the watch root");
        try (var s = Files.list(root.resolve("processing"))) {
            assertTrue(s.findAny().isPresent(), "a file must exist under processing/");
        }
    }
}
