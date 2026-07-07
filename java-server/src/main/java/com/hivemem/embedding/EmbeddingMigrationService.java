package com.hivemem.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Order(1)
public class EmbeddingMigrationService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingMigrationService.class);
    private static final long ADVISORY_LOCK_ID = 8421_0001L;
    private static final int BATCH_SIZE = 100;

    private final EmbeddingClient embeddingClient;
    private final EmbeddingStateRepository stateRepository;
    private final AtomicBoolean reencodingActive = new AtomicBoolean(false);

    public EmbeddingMigrationService(EmbeddingClient embeddingClient, EmbeddingStateRepository stateRepository) {
        this.embeddingClient = embeddingClient;
        this.stateRepository = stateRepository;
    }

    public boolean isReencodingActive() {
        return reencodingActive.get();
    }

    public Optional<String> getProgress() {
        if (!reencodingActive.get()) {
            return Optional.empty();
        }
        return stateRepository.loadProgress();
    }

    public int getCurrentDimension() {
        return embeddingClient.getInfo().dimension();
    }

    @Override
    public void run(ApplicationArguments args) {
        EmbeddingInfo currentInfo;
        try {
            currentInfo = embeddingClient.getInfo();
        } catch (Exception e) {
            log.error("Failed to reach embedding service /info endpoint. Cannot validate model compatibility.", e);
            throw new IllegalStateException("Embedding service unreachable at startup", e);
        }

        log.info("Embedding service reports: model={}, dimension={}", currentInfo.model(), currentInfo.dimension());

        Optional<EmbeddingInfo> storedInfo = stateRepository.loadStoredInfo();

        if (storedInfo.isEmpty()) {
            log.info("First run — saving embedding model info: model={}, dimension={}",
                    currentInfo.model(), currentInfo.dimension());
            stateRepository.saveInfo(currentInfo);
            stateRepository.createEmbeddingIndex(currentInfo.dimension());
            stateRepository.createFactsEmbeddingIndex(currentInfo.dimension());
            log.info("Created HNSW index for dimension {}", currentInfo.dimension());
            ensureRankedSearchFunction(currentInfo.dimension());
            return;
        }

        EmbeddingInfo stored = storedInfo.get();
        if (stored.model().equals(currentInfo.model()) && stored.dimension() == currentInfo.dimension()) {
            log.info("Embedding model matches stored state. No reencoding needed.");
            // Safety net: an operator who dropped the index manually or a pre-V0012
            // deployment won't have one yet. CREATE INDEX IF NOT EXISTS is a no-op
            // when the index already exists.
            stateRepository.createEmbeddingIndex(currentInfo.dimension());
            stateRepository.createFactsEmbeddingIndex(currentInfo.dimension());
            ensureRankedSearchFunction(currentInfo.dimension());
            return;
        }

        log.warn("Embedding model change detected! stored=[{}, {}] current=[{}, {}]",
                stored.model(), stored.dimension(), currentInfo.model(), currentInfo.dimension());

        reencodingActive.set(true);
        try {
            reencode(stored, currentInfo);
        } catch (Exception e) {
            log.error("Reencoding failed. Server will shut down. Restore from backup to recover.", e);
            throw new IllegalStateException("Embedding reencoding failed", e);
        } finally {
            reencodingActive.set(false);
        }
    }

    private void reencode(EmbeddingInfo from, EmbeddingInfo to) {
        if (!stateRepository.tryAdvisoryLock(ADVISORY_LOCK_ID)) {
            throw new IllegalStateException("Another instance is already reencoding. Aborting.");
        }

        try {
            runBackup();

            int total = stateRepository.countCellsWithContent();
            log.info("Reencoding {} cells: {} → {}", total, from.model(), to.model());

            stateRepository.dropEmbeddingIndex();
            log.info("Dropped HNSW index");

            int done = 0;
            while (done < total) {
                List<EmbeddingStateRepository.CellRow> batch = stateRepository.fetchCellBatch(done, BATCH_SIZE);
                if (batch.isEmpty()) {
                    break;
                }
                for (EmbeddingStateRepository.CellRow row : batch) {
                    List<Float> embedding = embeddingClient.encodeForCell(row.content(), row.summary());
                    stateRepository.updateEmbedding(row.id(), embedding);
                }
                done += batch.size();
                stateRepository.saveProgress(done, total);
                log.info("Reencoding progress: {}/{}", done, total);
            }

            stateRepository.createEmbeddingIndex(to.dimension());
            log.info("Recreated HNSW index");

            int totalFacts = stateRepository.countFactsCommitted();
            log.info("Reencoding {} facts: {} → {}", totalFacts, from.model(), to.model());

            stateRepository.dropFactsEmbeddingIndex();
            log.info("Dropped facts HNSW index");

            int doneFacts = 0;
            while (doneFacts < totalFacts) {
                List<EmbeddingStateRepository.FactRow> batch = stateRepository.fetchFactBatch(doneFacts, BATCH_SIZE);
                if (batch.isEmpty()) {
                    break;
                }
                for (EmbeddingStateRepository.FactRow row : batch) {
                    List<Float> embedding = embeddingClient.encodeDocument(
                            row.subject() + " " + row.predicate() + " " + row.object());
                    stateRepository.updateFactEmbedding(row.id(), embedding);
                }
                doneFacts += batch.size();
                log.info("Reencoding facts progress: {}/{}", doneFacts, totalFacts);
            }

            stateRepository.createFactsEmbeddingIndex(to.dimension());
            log.info("Recreated facts HNSW index");

            ensureRankedSearchFunction(to.dimension());

            stateRepository.saveInfo(to);
            stateRepository.clearProgress();
            log.info("Reencoding complete. Model updated to: {} ({}d)", to.model(), to.dimension());
        } finally {
            stateRepository.releaseAdvisoryLock(ADVISORY_LOCK_ID);
        }
    }

    private void runBackup() {
        log.info("Running pre-reencoding backup...");
        try {
            ProcessBuilder pb = new ProcessBuilder("hivemem-backup");
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("Backup failed with exit code " + exitCode);
            }
            log.info("Backup completed successfully");
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Backup failed", e);
        }
    }

    private void ensureRankedSearchFunction(int dimension) {
        stateRepository.replaceRankedSearchFunction(dimension);
        log.info("Recreated ranked_search function for dimension {}", dimension);
    }
}
