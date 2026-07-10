package com.hivemem.consumption;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.hivemem.attachment.AttachmentService;
import com.hivemem.attachment.SeaweedFsClient;

@Component
@ConditionalOnProperty(name = "hivemem.consumption.enabled", havingValue = "true")
public class SeparationReconcileSweep {

    private static final Logger log = LoggerFactory.getLogger(SeparationReconcileSweep.class);
    private static final int STALE_SECONDS = 600;   // 10 min without a webhook = stale
    private static final int BATCH = 10;

    private final SeparationJobRepository jobs;
    private final SeaweedFsClient seaweed;
    private final AttachmentService attachments;
    private final ConsumptionFileMover mover;
    private final ConsumptionFileRepository fileRepo;   // may be null (ledger disabled)

    public SeparationReconcileSweep(ConsumptionProperties props, SeparationJobRepository jobs,
            SeaweedFsClient seaweed, AttachmentService attachments,
            ObjectProvider<ConsumptionFileRepository> fileRepoProvider) {
        this.jobs = jobs;
        this.seaweed = seaweed;
        this.attachments = attachments;
        this.mover = new ConsumptionFileMover(Path.of(props.getDir()));
        this.fileRepo = fileRepoProvider.getIfAvailable();
    }

    @Scheduled(fixedRateString = "${hivemem.consumption.reconcile-interval-ms:300000}")
    public void reconcile() {
        List<SeparationJobRepository.Job> stale = jobs.findStale(STALE_SECONDS, BATCH);
        for (SeparationJobRepository.Job job : stale) {
            degrade(job);   // we do not persist digests, so degrade rather than re-dispatch
        }
    }

    /** Package-private for tests. */
    void degrade(SeparationJobRepository.Job job) {
        // Atomically claim the job BEFORE doing any work: a webhook apply() racing this sweep near
        // the stale threshold must not land the batch twice (split sub-docs AND a pending doc).
        if (!jobs.claim(job.correlationId())) {
            log.info("Separation job {} no longer awaiting (claimed by apply?); skipping degrade",
                    job.correlationId());
            return;
        }
        try {
            byte[] pdf = seaweed.downloadBytes(job.s3Key());
            try (InputStream in = new java.io.ByteArrayInputStream(pdf)) {
                attachments.ingest(in, job.originalName(), "application/pdf", job.realm(),
                        null, null, null, "consumption", "pending", "consumption:");
            }
            // Mark done BEFORE the move: the pending doc is already ingested, so a move failure must
            // not flip the job back to 'failed'. The source path is the processing/ staged path.
            jobs.markDone(job.correlationId());
            try { mover.moveToProcessed(Path.of(job.sourcePath())); }
            catch (Exception moveErr) {
                log.warn("Could not move {} to processed/: {}", job.sourcePath(), moveErr.toString());
            }
            log.info("Degraded separation job {} -> single pending document", job.correlationId());
        } catch (Exception e) {
            log.warn("Degrade failed for job {}: {}", job.correlationId(), e.toString());
            jobs.markFailed(job.correlationId());
            // The ledger row was marked 'done' at dispatch; flip it back to 'failed' and move the
            // file to failed/ so the recovery sweep's bounded retry owns re-ingesting the batch.
            failLedgerAndMoveFailed(Path.of(job.sourcePath()), "degrade failed: " + e);
        }
    }

    /** Mirror of ConsumptionService#failLedgerAndMoveFailed: hash the staged file from disk, flip
     *  its ledger row to 'failed', and move it to failed/ persisting the landed filename. */
    private void failLedgerAndMoveFailed(Path staged, String reason) {
        String hash = null;
        if (fileRepo != null) {
            try { hash = ConsumptionService.sha256(Files.readAllBytes(staged)); }
            catch (Exception readErr) {
                log.warn("Could not hash {} for ledger recovery: {}", staged, readErr.toString());
            }
            if (hash != null) fileRepo.markFailed(hash, reason);
        }
        try {
            Path dest = mover.moveToFailed(staged);
            if (fileRepo != null && hash != null && dest != null) {
                fileRepo.updateFilename(hash, dest.getFileName().toString());
            }
        } catch (Exception io) {
            log.error("Could not move {} to failed/: {}", staged, io.toString());
        }
    }
}
