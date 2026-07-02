package com.hivemem.consumption;

import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Re-stages files the pipeline can no longer see: crash-stranded files in processing/ (ledger row
 *  still 'processing' past the stale threshold) and failed/ files still under the retry limit.
 *  Matching files are moved back to the watch root so the next poll re-ingests them. Content-based
 *  dedup makes re-runs safe. Runs on a schedule AND once at startup (post-restart recovery). */
@Component
@ConditionalOnProperty(name = "hivemem.consumption.enabled", havingValue = "true")
public class ConsumptionRecoverySweep implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ConsumptionRecoverySweep.class);

    private final ConsumptionProperties props;
    private final ConsumptionFileRepository repo;
    private final ConsumptionFileMover mover;
    private final Path root;

    public ConsumptionRecoverySweep(ConsumptionProperties props, ConsumptionFileRepository repo) {
        this.props = props;
        this.repo = repo;
        this.root = Path.of(props.getDir());
        this.mover = new ConsumptionFileMover(root);
    }

    @Override public void run(ApplicationArguments args) { recover(); }

    @Scheduled(fixedRateString = "#{@consumptionProperties.recoveryInterval.toMillis()}")
    public void recover() {
        int stale = (int) props.getRecoveryStaleThreshold().toSeconds();
        for (var r : repo.findStaleProcessing(stale, 500)) {
            reStage(root.resolve(ConsumptionFileMover.PROCESSING).resolve(r.filename()), r, "stale-processing");
        }
        for (var r : repo.findRetriableFailed(props.getFailedRetryLimit(), 500)) {
            reStage(root.resolve(ConsumptionFileMover.FAILED).resolve(r.filename()), r, "retry-failed");
        }
    }

    private void reStage(Path file, ConsumptionFileRepository.Row r, String why) {
        if (!Files.isRegularFile(file)) return; // ledger row but no physical file — skip safely
        try {
            mover.moveToRoot(file);
            repo.touch(r.sha256()); // bump updated_at so a slow-but-alive job isn't re-staged every sweep
            log.info("Recovery re-staged {} ({}, attempts={})", r.filename(), why, r.attempts());
        } catch (Exception e) {
            log.warn("Recovery could not re-stage {}: {}", r.filename(), e.toString());
        }
    }
}
