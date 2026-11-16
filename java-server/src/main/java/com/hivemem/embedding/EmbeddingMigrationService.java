package com.hivemem.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** Startup /info can legitimately fail a few times if the embedding sidecar boots slower
     *  than the Java process — retry with a bounded budget instead of aborting the whole
     *  server on the first attempt (a crash-loop under a slow-booting sidecar). Only the
     *  STARTUP call is retried; per-request getInfo() callers (e.g. getCurrentDimension())
     *  are unaffected. */
    private static final int DEFAULT_STARTUP_RETRY_ATTEMPTS = 10;
    private static final long DEFAULT_STARTUP_RETRY_BACKOFF_MS = 3000;

    private final EmbeddingClient embeddingClient;
    private final EmbeddingStateRepository stateRepository;
    private final int startupRetryAttempts;
    private final long startupRetryBackoffMs;
    private final Runnable backupRunner;
    private final AtomicBoolean reencodingActive = new AtomicBoolean(false);

    @Autowired
    public EmbeddingMigrationService(EmbeddingClient embeddingClient, EmbeddingStateRepository stateRepository) {
        this(embeddingClient, stateRepository, DEFAULT_STARTUP_RETRY_ATTEMPTS, DEFAULT_STARTUP_RETRY_BACKOFF_MS);
    }

    /** Test seam: allows a small attempt count / zero backoff so retry tests run fast. */
    EmbeddingMigrationService(EmbeddingClient embeddingClient, EmbeddingStateRepository stateRepository,
                              int startupRetryAttempts, long startupRetryBackoffMs) {
        this(embeddingClient, stateRepository, startupRetryAttempts, startupRetryBackoffMs, null);
    }

    /** Test seam: allows a stub backup runner so tests that drive {@link #reencode} don't exec
     *  the real {@code hivemem-backup} binary. {@code null} keeps the production behavior. */
    EmbeddingMigrationService(EmbeddingClient embeddingClient, EmbeddingStateRepository stateRepository,
                              int startupRetryAttempts, long startupRetryBackoffMs, Runnable backupRunner) {
        this.embeddingClient = embeddingClient;
        this.stateRepository = stateRepository;
        this.startupRetryAttempts = Math.max(1, startupRetryAttempts);
        this.startupRetryBackoffMs = Math.max(0, startupRetryBackoffMs);
        this.backupRunner = backupRunner != null ? backupRunner : this::runBackupProcess;
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
            currentInfo = getInfoWithStartupRetry();
        } catch (Exception e) {
            log.error("Failed to reach embedding service /info endpoint after {} attempt(s). "
                    + "Cannot validate model compatibility.", startupRetryAttempts, e);
            throw new IllegalStateException("Embedding service unreachable at startup", e);
        }

        log.info("Embedding service reports: model={}, dimension={}", currentInfo.model(), currentInfo.dimension());

        // pgvector's HNSW index caps at 2000 dimensions. Validate BEFORE anything
        // destructive: past this point we drop indexes and rewrite every vector.
        int dim = currentInfo.dimension();
        if (dim <= 0 || dim > 2000) {
            throw new IllegalStateException("Embedding service reports an unusable dimension: " + dim
                    + " (must be 1..2000 for a pgvector HNSW index). Refusing to migrate.");
        }

        // An unknown text search configuration would only fail when ranked_search is created,
        // which on the re-encode path is after the backup and the full re-encode. Refuse it here.
        List<String> unknownStopWordConfigs = stateRepository.unknownStopWordConfigs();
        if (!unknownStopWordConfigs.isEmpty()) {
            throw new IllegalStateException("hivemem.search.keyword.stop-word-configs names text search "
                    + "configurations the database does not have: " + unknownStopWordConfigs
                    + ". Fix the setting or leave it empty. Refusing to start.");
        }
        List<String> stopWordConfigs = stateRepository.stopWordConfigs();
        if (stopWordConfigs.isEmpty()) {
            log.info("Keyword signal uses every query word (no stop-word configurations set)");
        } else {
            log.info("Keyword signal drops stop words of the configurations {}", stopWordConfigs);
        }

        Optional<EmbeddingInfo> storedInfo = stateRepository.loadStoredInfo();

        if (storedInfo.isEmpty()) {
            log.info("First run — saving embedding model info: model={}, dimension={}",
                    currentInfo.model(), currentInfo.dimension());
            stateRepository.saveInfo(currentInfo);
            stateRepository.createEmbeddingIndex(currentInfo.dimension());
            stateRepository.createFactsEmbeddingIndex(currentInfo.dimension());
            stateRepository.createChunkEmbeddingIndex(currentInfo.dimension());
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
            stateRepository.createChunkEmbeddingIndex(currentInfo.dimension());
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

    /**
     * Bounded retry around the startup {@code /info} call: an embedding sidecar that boots
     * slower than the Java process must not crash-loop the whole server on the first attempt.
     */
    private EmbeddingInfo getInfoWithStartupRetry() throws InterruptedException {
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= startupRetryAttempts; attempt++) {
            try {
                return embeddingClient.getInfo();
            } catch (Exception e) {
                lastFailure = e;
                if (attempt < startupRetryAttempts) {
                    log.warn("Embedding service /info not reachable yet (attempt {}/{}): {}. Retrying in {} ms...",
                            attempt, startupRetryAttempts, e.getMessage(), startupRetryBackoffMs);
                    if (startupRetryBackoffMs > 0) {
                        Thread.sleep(startupRetryBackoffMs);
                    }
                }
            }
        }
        throw new IllegalStateException(
                "Embedding service /info still unreachable after " + startupRetryAttempts + " attempts",
                lastFailure);
    }

    private void reencode(EmbeddingInfo from, EmbeddingInfo to) {
        if (!stateRepository.tryAdvisoryLock(ADVISORY_LOCK_ID)) {
            throw new IllegalStateException("Another instance is already reencoding. Aborting.");
        }

        try {
            backupRunner.run();

            // The branch that leads here is reached only when model or dimension differs
            // (see the early-return above for the matching case), so equal dimensions mean
            // the identity string changed and no row would be selected by the dimension
            // predicate. forceAll drops that predicate so the pass still re-encodes
            // everything; see fetchCellBatch's javadoc for the crash-resume trade-off this
            // implies.
            //
            // A leftover progress row also forces a full pass: clearProgress() only runs on
            // a clean success, so a row surviving into this run means a previous pass crashed
            // partway through. If that crashed pass had already written some rows at an
            // intermediate dimension (e.g. A(384d) -> B(1024d) crashes, operator then points
            // at C(1024d)), "from" here compares stored (still A/384, since saveInfo() also
            // only runs on success) against current (C/1024) — different dimensions, so the
            // plain dimension predicate would treat every B-generation 1024d row as already
            // correct and skip it, leaving B and C vectors mixed in one index. Forcing a full
            // pass whenever a progress row exists closes that gap.
            boolean forceAll = from.dimension() == to.dimension() || stateRepository.loadProgress().isPresent();

            // total is informational only (progress reporting) — loop termination below is
            // driven exclusively by an empty batch, never by this precomputed count, since a
            // count taken before the loop starts cannot account for the id-keyset predicate's
            // own view of "still needs work" (nor would it need to: the predicate loop is
            // self-terminating).
            int total = stateRepository.countCellsWithContent();
            log.info("Reencoding {} cells: {} → {}", total, from.model(), to.model());

            stateRepository.dropEmbeddingIndex();
            log.info("Dropped HNSW index");

            // Chunks are derived data (design §3.5): discard-and-rebuild, not re-encode-in-place,
            // so the window "old chunk dimension, newly rendered ranked_search function" never
            // exists. Must happen before ensureRankedSearchFunction(to.dimension()) below — the
            // sweep repopulates cell_chunks on its own schedule; until then every cell ranks by
            // its own vector only, exactly like today.
            stateRepository.discardChunks();
            // discardChunks() TRUNCATEs cell_chunks, which empties the HNSW index but does not drop
            // it -- the old-dimension idx_cell_chunks_embedding still exists under its fixed name
            // afterward. This drop is therefore still required: without it,
            // createChunkEmbeddingIndex's CREATE INDEX IF NOT EXISTS below would silently no-op
            // against the stale-dimension index instead of creating one for the new dimension.
            stateRepository.dropVectorIndexes("cell_chunks");
            stateRepository.createChunkEmbeddingIndex(to.dimension());
            log.info("Discarded cell_chunks and rebuilt its HNSW index for dimension {}", to.dimension());

            int done = 0;
            java.util.UUID afterCellId = null;
            while (true) {
                List<EmbeddingStateRepository.CellRow> batch =
                        stateRepository.fetchCellBatch(afterCellId, to.dimension(), BATCH_SIZE, forceAll);
                if (batch.isEmpty()) {
                    break;
                }
                for (EmbeddingStateRepository.CellRow row : batch) {
                    List<Float> embedding = embeddingClient.encodeForCell(row.content(), row.summary());
                    if (embedding == null) {
                        // encodeForCell returns null by contract for long content without a
                        // summary. Clear the old-model vector (it would break the new HNSW index
                        // cast) and, for committed rows, keep needs_summary so the summarizer
                        // fills it in later. Non-committed rows skip the tag: it would be inert
                        // since SummarizerRepository.findCellsNeedingSummary only looks at
                        // committed rows. Do NOT abort the whole migration and brick the startup.
                        if ("committed".equals(row.status())) {
                            stateRepository.clearEmbeddingAndTagNeedsSummary(row.id());
                        } else {
                            stateRepository.clearEmbedding(row.id());
                        }
                        continue;
                    }
                    stateRepository.updateEmbedding(row.id(), embedding);
                }
                done += batch.size();
                afterCellId = batch.get(batch.size() - 1).id();
                stateRepository.saveProgress(done, total);
                log.info("Reencoding progress: {}/{}", done, total);
            }

            stateRepository.createEmbeddingIndex(to.dimension());
            log.info("Recreated HNSW index");

            int totalFacts = stateRepository.countFacts();
            log.info("Reencoding {} facts: {} → {}", totalFacts, from.model(), to.model());

            stateRepository.dropFactsEmbeddingIndex();
            log.info("Dropped facts HNSW index");

            int doneFacts = 0;
            java.util.UUID afterFactId = null;
            while (true) {
                List<EmbeddingStateRepository.FactRow> batch =
                        stateRepository.fetchFactBatch(afterFactId, to.dimension(), BATCH_SIZE, forceAll);
                if (batch.isEmpty()) {
                    break;
                }
                for (EmbeddingStateRepository.FactRow row : batch) {
                    List<Float> embedding = embeddingClient.encodeDocument(
                            row.subject() + " " + row.predicate() + " " + row.object());
                    stateRepository.updateFactEmbedding(row.id(), embedding);
                }
                doneFacts += batch.size();
                afterFactId = batch.get(batch.size() - 1).id();
                log.info("Reencoding facts progress: {}/{}", doneFacts, totalFacts);
            }

            stateRepository.createFactsEmbeddingIndex(to.dimension());
            log.info("Recreated facts HNSW index");

            ensureRankedSearchFunction(to.dimension());

            stateRepository.saveInfo(to);
            stateRepository.clearProgress();
            embeddingClient.invalidateCaches();
            log.info("Reencoding complete. Model updated to: {} ({}d)", to.model(), to.dimension());
        } finally {
            stateRepository.releaseAdvisoryLock(ADVISORY_LOCK_ID);
        }
    }

    private void runBackupProcess() {
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // only re-interrupt when actually interrupted
            throw new IllegalStateException("Backup interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Backup failed", e);
        }
    }

    private void ensureRankedSearchFunction(int dimension) {
        stateRepository.replaceRankedSearchFunction(dimension);
        log.info("Recreated ranked_search function for dimension {}", dimension);
    }
}
