package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsumptionFileRepositoryIT extends ConsumptionITSupport {

    private ConsumptionFileRepository repo;

    @BeforeEach
    void setUp() {
        dsl.execute("DELETE FROM consumption_file");
        repo = new ConsumptionFileRepository(dsl);
    }

    @Test
    void upsertIncrementsAttemptsOnSameHash() {
        repo.startProcessing("abc123", "scan.pdf");
        repo.startProcessing("abc123", "scan.pdf");

        var row = repo.findByHash("abc123");
        assertTrue(row.isPresent());
        assertEquals(2, row.get().attempts());
        assertEquals("processing", row.get().state());
    }

    @Test
    void markDoneAndFailedSetState() {
        repo.startProcessing("h1", "done.pdf");
        repo.markDone("h1");
        var done = repo.findByHash("h1");
        assertTrue(done.isPresent());
        assertEquals("done", done.get().state());

        repo.startProcessing("h2", "fail.pdf");
        repo.markFailed("h2", "boom");
        var failed = repo.findByHash("h2");
        assertTrue(failed.isPresent());
        assertEquals("failed", failed.get().state());
        assertEquals("boom", failed.get().lastError());
    }

    @Test
    void findStaleProcessingReturnsOldRows() {
        repo.startProcessing("h3", "stale.pdf");
        // Backdate the row so it appears stale (older than 60 seconds)
        dsl.execute("UPDATE consumption_file SET updated_at = now() - interval '120 seconds' WHERE sha256=?", "h3");

        List<ConsumptionFileRepository.Row> stale = repo.findStaleProcessing(60, 100);
        assertTrue(stale.stream().anyMatch(r -> r.sha256().equals("h3")),
                "expected h3 in stale results but got: " + stale);
    }

    @Test
    void updateFilenamePersistsMovedName() {
        repo.startProcessing("h4", "orig.pdf");
        repo.updateFilename("h4", "orig-1.pdf");

        var row = repo.findByHash("h4");
        assertTrue(row.isPresent());
        assertEquals("orig-1.pdf", row.get().filename(),
                "ledger must reflect the collision-suffixed name the mover actually used");
    }

    @Test
    void startProcessingRefreshesFilenameOnConflict() {
        repo.startProcessing("h5", "scan.pdf");
        // Re-staged under a collision-suffixed name (recovery sweep moveToRoot may rename)
        repo.startProcessing("h5", "scan-1.pdf");

        var row = repo.findByHash("h5");
        assertTrue(row.isPresent());
        assertEquals("scan-1.pdf", row.get().filename(),
                "conflict upsert must refresh the filename, not keep the stale original");
        assertEquals(2, row.get().attempts());
    }

    @Test
    void findRetriableFailedRespectsAttemptsLimit() {
        // Row with attempts=1, maxAttempts=3 → should appear
        repo.startProcessing("hr1", "retry.pdf");
        repo.markFailed("hr1", "transient");

        // Row with attempts=3, maxAttempts=3 → should NOT appear
        repo.startProcessing("hr2", "exhausted.pdf");
        repo.startProcessing("hr2", "exhausted.pdf");
        repo.startProcessing("hr2", "exhausted.pdf");
        repo.markFailed("hr2", "exhausted");

        List<ConsumptionFileRepository.Row> retriable = repo.findRetriableFailed(3, 100);
        assertTrue(retriable.stream().anyMatch(r -> r.sha256().equals("hr1")),
                "hr1 (1 attempt < 3) should be retriable");
        assertTrue(retriable.stream().noneMatch(r -> r.sha256().equals("hr2")),
                "hr2 (3 attempts >= 3) should NOT be retriable");
    }
}
