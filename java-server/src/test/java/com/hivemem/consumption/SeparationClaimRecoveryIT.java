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

/** Covers the atomic job claim (apply vs. reconcile-degrade race) and the failure paths that hand
 *  a broken batch back to the ledger's bounded-retry recovery sweep. */
class SeparationClaimRecoveryIT extends ConsumptionITSupport {

    private static byte[] pdfWithPages(int n) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < n; i++) doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private ConsumptionProperties props(Path tempRoot) {
        ConsumptionProperties cp = new ConsumptionProperties();
        cp.setEnabled(true);
        cp.setDir(tempRoot.toString());
        cp.setRealm("documents");
        return cp;
    }

    private int consumptionCellCount() {
        return dsl.fetchOne("SELECT count(*) FROM cells WHERE source LIKE 'consumption:%'")
                .get(0, Integer.class);
    }

    @Test
    void claimIsSingleWinner(@TempDir Path tempRoot) throws Exception {
        byte[] batch = pdfWithPages(2);
        UUID corr = UUID.randomUUID();
        Path src = Files.write(tempRoot.resolve("claim.pdf"), batch);
        jobRepo.create(corr, "consumption/batch-" + corr + ".pdf", "claim.pdf", src.toString(), 2, "documents");

        assertTrue(jobRepo.claim(corr), "first claimant must win");
        assertFalse(jobRepo.claim(corr), "second claimant must lose (job no longer awaiting)");
        assertFalse(jobRepo.claim(UUID.randomUUID()), "claiming an unknown job must fail");

        jobRepo.markDone(corr);
        assertFalse(jobRepo.claim(corr), "a done job must not be claimable");
    }

    /** H4: the sweep fetched a stale job, but a webhook apply() claimed it in the meantime.
     *  degrade() must skip — otherwise the batch lands twice (sub-docs AND a pending doc). */
    @Test
    void degradeSkipsWhenJobAlreadyClaimed(@TempDir Path tempRoot) throws Exception {
        byte[] batch = pdfWithPages(3);
        UUID corr = UUID.randomUUID();
        String key = "consumption/batch-" + corr + ".pdf";
        seaweed.uploadBytes(key, batch, "application/pdf");
        Path src = Files.write(tempRoot.resolve("race.pdf"), batch);
        jobRepo.create(corr, key, "race.pdf", src.toString(), 3, "documents");

        // Sweep reads the stale job row...
        SeparationJobRepository.Job job = jobRepo.findAwaiting(corr).orElseThrow();
        // ...then the webhook apply() wins the claim before degrade() runs.
        assertTrue(jobRepo.claim(corr));

        SeparationReconcileSweep sweep = new SeparationReconcileSweep(
                props(tempRoot), jobRepo, seaweed, attachments, providerOf(null));
        sweep.degrade(job);

        assertEquals(0, consumptionCellCount(), "losing claimant must not ingest anything");
        assertTrue(Files.exists(src), "losing claimant must not move the staged file");
    }

    /** Regression guard: with the claim in place, a genuinely stale job still degrades normally. */
    @Test
    void reconcileStillDegradesStaleJobToOnePendingDocument(@TempDir Path tempRoot) throws Exception {
        byte[] batch = pdfWithPages(3);
        UUID corr = UUID.randomUUID();
        String key = "consumption/batch-" + corr + ".pdf";
        seaweed.uploadBytes(key, batch, "application/pdf");
        Path src = Files.write(tempRoot.resolve("stale.pdf"), batch);
        jobRepo.create(corr, key, "stale.pdf", src.toString(), 3, "documents");
        dsl.execute("UPDATE consumption_jobs SET updated_at = now() - interval '20 minutes' "
                + "WHERE correlation_id = ?", corr);

        SeparationReconcileSweep sweep = new SeparationReconcileSweep(
                props(tempRoot), jobRepo, seaweed, attachments, providerOf(null));
        sweep.reconcile();

        assertEquals(1, consumptionCellCount(), "stale job degrades into exactly one document");
        assertEquals(1, dsl.fetchOne("SELECT count(*) FROM cells WHERE source LIKE 'consumption:%' "
                        + "AND status='pending'").get(0, Integer.class),
                "degraded document must be pending");
        assertTrue(jobRepo.findAwaiting(corr).isEmpty(), "job must no longer be awaiting");
        assertTrue(Files.exists(tempRoot.resolve("processed").resolve("stale.pdf")),
                "batch source moved to processed/");
    }

    /** M10 + M9: when apply() fails after dispatch marked the ledger 'done', the ledger row must
     *  flip back to 'failed' (under the landed failed/ filename) so the recovery sweep's bounded
     *  retry owns the batch instead of it dead-ending. */
    @Test
    void applyFailureFlipsLedgerToFailedForBoundedRetry(@TempDir Path tempRoot) throws Exception {
        byte[] batch = pdfWithPages(2);
        String hash = ConsumptionService.sha256(batch);
        UUID corr = UUID.randomUUID();
        // NOTE: s3 key deliberately NOT uploaded -> downloadBytes throws inside apply()
        String key = "consumption/batch-" + corr + ".pdf";
        Path src = Files.write(tempRoot.resolve("boom.pdf"), batch);
        jobRepo.create(corr, key, "boom.pdf", src.toString(), 2, "documents");
        String runId = "run-" + UUID.randomUUID();
        jobRepo.attachRunId(corr, runId);

        ConsumptionFileRepository fileRepo = new ConsumptionFileRepository(dsl);
        fileRepo.startProcessing(hash, "boom.pdf");
        fileRepo.markDone(hash); // as separateStaged does when the dispatch hands off ownership

        ConsumptionService svc = buildService(props(tempRoot), fileRepo);
        svc.apply(new SeparationResult(runId, "done",
                new SeparationResult.Output(List.of()), null));

        var row = fileRepo.findByHash(hash).orElseThrow();
        assertEquals("failed", row.state(), "ledger must flip back to failed for bounded retry");
        assertTrue(Files.exists(tempRoot.resolve("failed").resolve(row.filename())),
                "physical file must sit in failed/ under the filename persisted in the ledger");
        assertFalse(Files.exists(src), "staged file must be moved out of processing");
        assertTrue(jobRepo.findAwaiting(corr).isEmpty(), "job must no longer be awaiting");
        assertEquals(0, consumptionCellCount(), "failed apply must not leave ingested cells");
    }

    /** M10: same recovery hand-off when the reconcile sweep's degrade() fails. */
    @Test
    void degradeFailureFlipsLedgerToFailedForBoundedRetry(@TempDir Path tempRoot) throws Exception {
        byte[] batch = pdfWithPages(2);
        String hash = ConsumptionService.sha256(batch);
        UUID corr = UUID.randomUUID();
        // s3 key NOT uploaded -> downloadBytes throws inside degrade()
        String key = "consumption/batch-" + corr + ".pdf";
        Path src = Files.write(tempRoot.resolve("degrade-boom.pdf"), batch);
        jobRepo.create(corr, key, "degrade-boom.pdf", src.toString(), 2, "documents");
        dsl.execute("UPDATE consumption_jobs SET updated_at = now() - interval '20 minutes' "
                + "WHERE correlation_id = ?", corr);

        ConsumptionFileRepository fileRepo = new ConsumptionFileRepository(dsl);
        fileRepo.startProcessing(hash, "degrade-boom.pdf");
        fileRepo.markDone(hash);

        SeparationReconcileSweep sweep = new SeparationReconcileSweep(
                props(tempRoot), jobRepo, seaweed, attachments, providerOf(fileRepo));
        sweep.reconcile();

        var row = fileRepo.findByHash(hash).orElseThrow();
        assertEquals("failed", row.state(), "ledger must flip back to failed for bounded retry");
        assertTrue(Files.exists(tempRoot.resolve("failed").resolve(row.filename())),
                "physical file must sit in failed/ under the filename persisted in the ledger");
        assertTrue(jobRepo.findAwaiting(corr).isEmpty(), "job must no longer be awaiting");
    }
}
