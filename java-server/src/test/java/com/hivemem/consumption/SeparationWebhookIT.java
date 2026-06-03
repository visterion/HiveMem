package com.hivemem.consumption;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SeparationWebhookIT extends ConsumptionITSupport {

    private static byte[] pdfWithPages(int n) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < n; i++) doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void appliesBoundariesIntoCommittedAndPendingCells(@TempDir Path tempRoot) throws Exception {
        // 1. 5-page batch PDF -> upload to SeaweedFS + insert an awaiting job
        byte[] batch = pdfWithPages(5);
        UUID corr = UUID.randomUUID();
        String key = "consumption/batch-" + corr + ".pdf";
        seaweed.uploadBytes(key, batch, "application/pdf");
        Path src = Files.write(tempRoot.resolve("batch.pdf"), batch);
        jobRepo.create(corr, key, "batch.pdf", src.toString(), 5, "documents");
        // Vistierie's callback carries the run id (not the correlation id), so the job is matched by
        // the run id captured at dispatch.
        String runId = "run-" + UUID.randomUUID();
        jobRepo.attachRunId(corr, runId);

        // 2. Build ConsumptionService (separationClient -> null; rasterizer/tesseract not used by apply()).
        ConsumptionProperties cp = new ConsumptionProperties();
        cp.setEnabled(true);
        cp.setDir(tempRoot.toString());
        cp.setRealm("documents");
        cp.setConfidenceThreshold(0.80);
        ConsumptionService svc = buildService(cp);

        // 3. cut after p3 (conf .95 -> committed), cut after p4 (conf .40 -> pending)
        svc.apply(new SeparationResult(runId, "done",
                new SeparationResult.Output(List.of(
                        new SeparationResult.Boundary(3, 0.95),
                        new SeparationResult.Boundary(4, 0.40))),
                null));

        // 4. 3 sub-docs: parts [1-3],[4],[5]. statuses committed, committed(.95), pending(.40).
        //    Order-independent assertion: total=3, committed=2, pending=1.
        int total = dsl.fetchOne(
                "SELECT count(*) FROM cells WHERE source LIKE 'consumption:%'").get(0, Integer.class);
        int committed = dsl.fetchOne(
                "SELECT count(*) FROM cells WHERE source LIKE 'consumption:%' AND status='committed'")
                .get(0, Integer.class);
        int pending = dsl.fetchOne(
                "SELECT count(*) FROM cells WHERE source LIKE 'consumption:%' AND status='pending'")
                .get(0, Integer.class);

        assertEquals(3, total, "expected three sub-documents");
        assertEquals(2, committed, "two committed (first doc + high-confidence boundary)");
        assertEquals(1, pending, "one pending (low-confidence boundary)");

        // Job marked done and batch moved to processed/.
        assertTrue(jobRepo.findAwaiting(corr).isEmpty(), "job should no longer be awaiting");
        assertTrue(Files.exists(tempRoot.resolve("processed").resolve("batch.pdf")),
                "batch source should be moved to processed/");
    }

    @Test
    void emptyBoundariesIngestsWholeBatchAsOneDocument(@TempDir Path tempRoot) throws Exception {
        byte[] batch = pdfWithPages(3);
        UUID corr = UUID.randomUUID();
        String key = "consumption/batch-" + corr + ".pdf";
        seaweed.uploadBytes(key, batch, "application/pdf");
        Path src = Files.write(tempRoot.resolve("one.pdf"), batch);
        jobRepo.create(corr, key, "one.pdf", src.toString(), 3, "documents");
        String runId = "run-" + UUID.randomUUID();
        jobRepo.attachRunId(corr, runId);

        ConsumptionProperties cp = new ConsumptionProperties();
        cp.setEnabled(true);
        cp.setDir(tempRoot.toString());
        cp.setRealm("documents");
        ConsumptionService svc = buildService(cp);

        // status=done with no boundaries means "the whole stream is one document".
        svc.apply(new SeparationResult(runId, "done", new SeparationResult.Output(List.of()), null));

        int total = dsl.fetchOne(
                "SELECT count(*) FROM cells WHERE source LIKE 'consumption:%'").get(0, Integer.class);
        assertEquals(1, total, "empty boundaries -> single committed document");
        assertTrue(jobRepo.findAwaiting(corr).isEmpty(), "job should be done");
    }

    @Test
    void failedRunLeavesJobAwaitingForReconcile(@TempDir Path tempRoot) throws Exception {
        byte[] batch = pdfWithPages(4);
        UUID corr = UUID.randomUUID();
        String key = "consumption/batch-" + corr + ".pdf";
        seaweed.uploadBytes(key, batch, "application/pdf");
        Path src = Files.write(tempRoot.resolve("bad.pdf"), batch);
        jobRepo.create(corr, key, "bad.pdf", src.toString(), 4, "documents");
        String runId = "run-" + UUID.randomUUID();
        jobRepo.attachRunId(corr, runId);

        ConsumptionProperties cp = new ConsumptionProperties();
        cp.setEnabled(true);
        cp.setDir(tempRoot.toString());
        cp.setRealm("documents");
        ConsumptionService svc = buildService(cp);

        // A failed run must NOT mark the job failed or ingest anything: leave it awaiting so the
        // reconcile sweep degrades it later. Nothing is lost.
        svc.apply(new SeparationResult(runId, "failed", null, "model error"));

        int total = dsl.fetchOne(
                "SELECT count(*) FROM cells WHERE source LIKE 'consumption:%'").get(0, Integer.class);
        assertEquals(0, total, "failed run ingests nothing");
        assertTrue(jobRepo.findAwaitingByRunId(runId).isPresent(),
                "job must remain awaiting so the reconcile sweep can degrade it");
    }
}
