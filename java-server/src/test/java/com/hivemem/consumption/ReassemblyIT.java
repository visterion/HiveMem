package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hivemem.ocr.PdfPageRasterizer;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end reassembly: a 3-page batch where the vision model assigns pages 1+3 to doc A and page 2
 * to doc B (non-contiguous). Asserts two committed cells (source consumption:%) and the staged file
 * moved into processed/.
 */
class ReassemblyIT extends ConsumptionITSupport {

    private static byte[] pdfWithPages(int n) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < n; i++) doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void reassemblesNonContiguousPagesIntoTwoDocuments(@TempDir Path root) throws Exception {
        byte[] pdf = pdfWithPages(3);
        Path staged = root.resolve("batch.pdf");
        Files.write(staged, pdf);

        ConsumptionProperties cp = new ConsumptionProperties();
        cp.setRealm("documents");
        cp.setReassemblyEnabled(true);
        cp.setBlankFilterEnabled(false); // blank A4 test pages would otherwise be dropped

        // Task 6 note: this test predates the 3-pass rewrite (Task 5) and still exercised the old
        // single-vision-model grouping flow. Minimally adapted here (forced by the compiler — the
        // orchestrator constructor signature changed) by mocking the three new passes directly instead
        // of driving a VisionMultiClient; full re-validation of this IT against the new pipeline is
        // Task 6 work.
        PageOrienter orienter = mock(PageOrienter.class);
        when(orienter.orient(anyString(), anyInt(), any()))
                .thenAnswer(inv -> new PageOrienter.PageOrientation(inv.getArgument(1), 0, false, 0.9));
        PageMetadataExtractor extractor = mock(PageMetadataExtractor.class);
        when(extractor.extract(anyString(), anyInt(), any()))
                .thenAnswer(inv -> new PageMetadataExtractor.PageMetadata(inv.getArgument(1),
                        "S", null, null, "letter", null, "p", false));
        MailingAssembler assembler = mock(MailingAssembler.class);
        DocGroup docA = new DocGroup("A", null);
        docA.minConfidence = 0.9;
        docA.pages.add(1);
        docA.pages.add(3);
        DocGroup docB = new DocGroup("B", null);
        docB.minConfidence = 0.9;
        docB.pages.add(2);
        when(assembler.assemble(anyString(), anyList())).thenReturn(List.of(docA, docB));

        ReassemblyOrchestrator orch = new ReassemblyOrchestrator(
                cp, new PdfPageRasterizer(), orienter, extractor, assembler, new PageReassembler(cp),
                new BatchSplitter(), attachments, new ConsumptionFileMover(root));

        orch.reassemble(staged, pdf, 3);

        int cells = dsl.fetchOne("SELECT count(*) FROM cells WHERE source LIKE 'consumption:%'")
                .get(0, Integer.class);
        assertEquals(2, cells, "two reassembled documents should be ingested");

        int committed = dsl.fetchOne(
                "SELECT count(*) FROM cells WHERE source LIKE 'consumption:%' AND status='committed'")
                .get(0, Integer.class);
        assertEquals(2, committed, "both documents committed (confidence 0.9 >= threshold 0.5)");

        assertFalse(Files.exists(staged), "staged file must be moved out of the root");
        try (var s = Files.list(root.resolve("processed"))) {
            assertTrue(s.findAny().isPresent(), "a file must exist under processed/");
        }
    }

    @Test
    void overMaxPagesBatchIsRoutedToFailedNotReassembled(@TempDir Path root) throws Exception {
        byte[] pdf = pdfWithPages(3);
        Path staged = root.resolve("big.pdf");
        Files.write(staged, pdf);

        ConsumptionProperties cp = new ConsumptionProperties();
        cp.setRealm("documents");
        cp.setReassemblyEnabled(true);
        cp.setMaxPages(2); // batch (3 pages) exceeds the cap

        PageOrienter orienter = mock(PageOrienter.class);
        PageMetadataExtractor extractor = mock(PageMetadataExtractor.class);
        MailingAssembler assembler = mock(MailingAssembler.class);

        ReassemblyOrchestrator orch = new ReassemblyOrchestrator(
                cp, new PdfPageRasterizer(), orienter, extractor, assembler, new PageReassembler(cp),
                new BatchSplitter(), attachments, new ConsumptionFileMover(root));

        orch.reassemble(staged, pdf, 3);

        int cells = dsl.fetchOne("SELECT count(*) FROM cells WHERE source LIKE 'consumption:%'")
                .get(0, Integer.class);
        assertEquals(0, cells, "an over-limit batch must not produce any consumption cells");

        // None of the three passes must run for an over-limit batch.
        org.mockito.Mockito.verifyNoInteractions(orienter, extractor, assembler);

        assertFalse(Files.exists(staged), "staged file must be moved out of the root");
        try (var s = Files.list(root.resolve("failed"))) {
            assertTrue(s.findAny().isPresent(), "the batch must be routed to failed/");
        }
    }
}
