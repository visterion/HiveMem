package com.hivemem.embedding;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingMigrationServiceUnitTest {

    private final EmbeddingClient client = mock(EmbeddingClient.class);
    private final EmbeddingStateRepository repo = mock(EmbeddingStateRepository.class);
    private final EmbeddingMigrationService service = new EmbeddingMigrationService(client, repo);

    @Test
    void unknownStopWordConfigurationFailsBeforeAnythingIsTouched() {
        when(client.getInfo()).thenReturn(new EmbeddingInfo("new-model", 768));
        when(repo.unknownStopWordConfigs()).thenReturn(List.of("klingon"));

        assertThatThrownBy(() -> service.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hivemem.search.keyword.stop-word-configs")
                .hasMessageContaining("klingon");

        verify(repo, never()).loadStoredInfo();
        verify(repo, never()).dropEmbeddingIndex();
        verify(repo, never()).replaceRankedSearchFunction(anyInt());
        verify(repo, never()).saveInfo(any());
    }

    @Test
    void isReencodingActiveStartsFalse() {
        assertThat(service.isReencodingActive()).isFalse();
    }

    @Test
    void getProgressIsEmptyWhenNotActive() {
        assertThat(service.getProgress()).isEmpty();
    }

    @Test
    void getCurrentDimensionDelegatesToClient() {
        when(client.getInfo()).thenReturn(new EmbeddingInfo("m", 768));
        assertThat(service.getCurrentDimension()).isEqualTo(768);
    }

    @Test
    void runFailsLoudlyWhenEmbeddingServiceUnreachable() {
        // Fast retry budget (3 attempts, no backoff) so this still-always-failing case
        // doesn't slow the suite down with the production 10x3s retry budget.
        EmbeddingMigrationService fastRetryService =
                new EmbeddingMigrationService(client, repo, 3, 0);
        when(client.getInfo()).thenThrow(new RuntimeException("connection refused"));
        assertThatThrownBy(() -> fastRetryService.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Embedding service unreachable");
        verify(client, times(3)).getInfo();
    }

    @Test
    void runRetriesStartupInfoCallAndProceedsOnceItSucceeds() {
        EmbeddingInfo info = new EmbeddingInfo("bge-m3", 1024);
        // Fails twice, then succeeds on the third attempt.
        when(client.getInfo())
                .thenThrow(new RuntimeException("not ready"))
                .thenThrow(new RuntimeException("not ready"))
                .thenReturn(info);
        when(repo.loadStoredInfo()).thenReturn(Optional.empty());

        EmbeddingMigrationService fastRetryService =
                new EmbeddingMigrationService(client, repo, 10, 0);

        fastRetryService.run(null);

        verify(client, times(3)).getInfo();
        verify(repo).saveInfo(info);
    }

    @Test
    void runGivesUpAfterExhaustingStartupRetryBudget() {
        when(client.getInfo()).thenThrow(new RuntimeException("still down"));
        EmbeddingMigrationService fastRetryService =
                new EmbeddingMigrationService(client, repo, 5, 0);

        assertThatThrownBy(() -> fastRetryService.run(null))
                .isInstanceOf(IllegalStateException.class);

        verify(client, times(5)).getInfo();
    }

    @Test
    void firstRunSavesInfoAndCreatesIndex() {
        EmbeddingInfo info = new EmbeddingInfo("bge-m3", 1024);
        when(client.getInfo()).thenReturn(info);
        when(repo.loadStoredInfo()).thenReturn(Optional.empty());

        service.run(null);

        verify(repo).saveInfo(info);
        verify(repo).createEmbeddingIndex(1024);
        verify(repo).createChunkEmbeddingIndex(1024);
        verify(repo).replaceRankedSearchFunction(1024);
        verify(repo, never()).tryAdvisoryLock(anyLong());
    }

    @Test
    void matchingModelDoesNotReencodeButReassertsIndex() {
        EmbeddingInfo info = new EmbeddingInfo("bge-m3", 1024);
        when(client.getInfo()).thenReturn(info);
        when(repo.loadStoredInfo()).thenReturn(Optional.of(info));

        service.run(null);

        verify(repo).createEmbeddingIndex(1024);
        verify(repo).createChunkEmbeddingIndex(1024);
        verify(repo).replaceRankedSearchFunction(1024);
        verify(repo, never()).saveInfo(any());
        verify(repo, never()).tryAdvisoryLock(anyLong());
        verify(repo, never()).dropEmbeddingIndex();
    }

    @Test
    void modelChangeWithLockHeldAbortsBeforeBackup() {
        when(client.getInfo()).thenReturn(new EmbeddingInfo("new-model", 768));
        when(repo.loadStoredInfo()).thenReturn(Optional.of(new EmbeddingInfo("old-model", 1024)));
        when(repo.tryAdvisoryLock(anyLong())).thenReturn(false);

        assertThatThrownBy(() -> service.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Embedding reencoding failed");

        verify(repo, never()).dropEmbeddingIndex();
        verify(repo, never()).countCellsWithContent();
        verify(repo, never()).fetchCellBatch(any(), anyInt(), anyInt(), anyBoolean());
        // After failure, the active flag should be reset
        assertThat(service.isReencodingActive()).isFalse();
    }

    @Test
    void modelChangeOnlyDimensionDifferentTriggersReencodeAttempt() {
        // same model name but different dim — still mismatch
        when(client.getInfo()).thenReturn(new EmbeddingInfo("bge-m3", 768));
        when(repo.loadStoredInfo()).thenReturn(Optional.of(new EmbeddingInfo("bge-m3", 1024)));
        when(repo.tryAdvisoryLock(anyLong())).thenReturn(false);

        assertThatThrownBy(() -> service.run(null)).isInstanceOf(IllegalStateException.class);
        verify(repo, times(1)).tryAdvisoryLock(anyLong());
    }

    @Test
    void abortsBeforeDroppingIndex_whenDimensionExceedsPgvectorLimit() {
        when(client.getInfo()).thenReturn(new EmbeddingInfo("some-model/mrl4096", 4096));
        when(repo.loadStoredInfo())
                .thenReturn(Optional.of(new EmbeddingInfo("old-model", 384)));

        assertThatThrownBy(() -> service.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unusable dimension: 4096");

        verify(repo, never()).loadStoredInfo();
        verify(repo, never()).dropEmbeddingIndex();
        verify(repo, never()).dropFactsEmbeddingIndex();
    }

    @Test
    void abortsBeforeDroppingIndex_whenDimensionIsZero() {
        when(client.getInfo()).thenReturn(new EmbeddingInfo("broken-model", 0));
        when(repo.loadStoredInfo())
                .thenReturn(Optional.of(new EmbeddingInfo("old-model", 384)));

        assertThatThrownBy(() -> service.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unusable dimension: 0");

        verify(repo, never()).loadStoredInfo();
        verify(repo, never()).dropEmbeddingIndex();
        verify(repo, never()).dropFactsEmbeddingIndex();
    }

    @Test
    void abortsBeforeDroppingIndex_whenDimensionIsNegative() {
        when(client.getInfo()).thenReturn(new EmbeddingInfo("broken-model", -1));
        when(repo.loadStoredInfo())
                .thenReturn(Optional.of(new EmbeddingInfo("old-model", 384)));

        assertThatThrownBy(() -> service.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unusable dimension: -1");

        verify(repo, never()).loadStoredInfo();
        verify(repo, never()).dropEmbeddingIndex();
        verify(repo, never()).dropFactsEmbeddingIndex();
    }

    @Test
    void abortsOnFirstRun_whenDimensionExceedsPgvectorLimit() {
        when(client.getInfo()).thenReturn(new EmbeddingInfo("some-model/mrl4096", 4096));
        when(repo.loadStoredInfo()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unusable dimension");

        verify(repo, never()).saveInfo(any());
        verify(repo, never()).createEmbeddingIndex(anyInt());
        verify(repo, never()).createFactsEmbeddingIndex(anyInt());
    }

    @Test
    void allowsDimensionAtThePgvectorLimit() {
        EmbeddingInfo info = new EmbeddingInfo("boundary-model", 2000);
        when(client.getInfo()).thenReturn(info);
        when(repo.loadStoredInfo()).thenReturn(Optional.empty());

        service.run(null);

        verify(repo).createEmbeddingIndex(2000);
    }

    @Test
    void abortsJustPastThePgvectorLimit() {
        when(client.getInfo()).thenReturn(new EmbeddingInfo("boundary-model", 2001));
        when(repo.loadStoredInfo()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unusable dimension: 2001");

        verify(repo, never()).createEmbeddingIndex(anyInt());
    }

    /**
     * Exercises the Step-5 wiring inside {@link EmbeddingMigrationService#reencode}: unlike every
     * other test in this class, {@code tryAdvisoryLock} returns true here so the reencode body
     * actually runs. The real {@code hivemem-backup} process is swapped out via the package-private
     * backup-runner seam so the test doesn't exec a binary.
     */
    @Test
    void reencodeWiring_sameDimensionIdentityChangeForcesFullPassAndSplitsClearByStatus() {
        EmbeddingMigrationService reencodingService =
                new EmbeddingMigrationService(client, repo, 10, 0, () -> { });

        // Same dimension (1024 -> 1024), different model: an identity-only change. The plain
        // dimension predicate would select zero rows for this case, so forceAll must be true.
        when(client.getInfo()).thenReturn(new EmbeddingInfo("new-model", 1024));
        when(repo.loadStoredInfo()).thenReturn(Optional.of(new EmbeddingInfo("old-model", 1024)));
        when(repo.tryAdvisoryLock(anyLong())).thenReturn(true);
        when(repo.loadProgress()).thenReturn(Optional.empty());

        UUID committedId = UUID.randomUUID();
        UUID pendingId = UUID.randomUUID();
        EmbeddingStateRepository.CellRow committedRow =
                new EmbeddingStateRepository.CellRow(committedId, "content", null, "committed");
        EmbeddingStateRepository.CellRow pendingRow =
                new EmbeddingStateRepository.CellRow(pendingId, "content", null, "pending");
        when(repo.fetchCellBatch(any(), eq(1024), anyInt(), eq(true)))
                .thenReturn(List.of(committedRow, pendingRow), List.of());
        // encodeForCell returning null drives both rows into the clear-branch under test.
        when(client.encodeForCell(any(), any())).thenReturn(null);
        when(repo.fetchFactBatch(any(), anyInt(), anyInt(), anyBoolean())).thenReturn(List.of());

        reencodingService.run(null);

        // Called twice: once returning the two rows above, once returning an empty batch to
        // terminate the keyset-pagination loop. Both calls must carry forceAll=true.
        verify(repo, times(2)).fetchCellBatch(any(), eq(1024), anyInt(), eq(true));
        verify(repo).clearEmbeddingAndTagNeedsSummary(committedId);
        verify(repo).clearEmbedding(pendingId);
        verify(repo, never()).clearEmbedding(committedId);
        verify(repo, never()).clearEmbeddingAndTagNeedsSummary(pendingId);
    }

    /**
     * A crashed pass leaves a progress row behind (clearProgress() only runs on success). If the
     * operator then switches to a same-dimension model before the next boot, {@code from.dimension()
     * != to.dimension()} would otherwise be true (stored info was never updated by the crashed
     * pass either), so the plain dimension check alone would wrongly skip rows already written at
     * the crashed pass's intermediate dimension. The leftover progress row must force a full pass
     * regardless of what the dimension comparison says.
     */
    @Test
    void forceAllAlsoTriggersOnALeftoverProgressRow_evenWhenDimensionsDiffer() {
        EmbeddingMigrationService reencodingService =
                new EmbeddingMigrationService(client, repo, 10, 0, () -> { });

        when(client.getInfo()).thenReturn(new EmbeddingInfo("model-c", 1024));
        when(repo.loadStoredInfo()).thenReturn(Optional.of(new EmbeddingInfo("model-a", 384)));
        when(repo.tryAdvisoryLock(anyLong())).thenReturn(true);
        when(repo.loadProgress()).thenReturn(Optional.of("50/100"));
        when(repo.fetchCellBatch(any(), anyInt(), anyInt(), anyBoolean())).thenReturn(List.of());
        when(repo.fetchFactBatch(any(), anyInt(), anyInt(), anyBoolean())).thenReturn(List.of());

        reencodingService.run(null);

        verify(repo).fetchCellBatch(any(), eq(1024), anyInt(), eq(true));
        verify(repo).fetchFactBatch(any(), eq(1024), anyInt(), eq(true));
    }

    /**
     * Design §3.5 "Modellwechsel": chunks are derived data, so a model change discards
     * {@code cell_chunks} and rebuilds its HNSW index rather than re-encoding it in place. The
     * ordering matters: discard/rebuild must happen before {@code replaceRankedSearchFunction}
     * activates the new-dimension function, so no query can ever see old-dimension chunk vectors
     * against it.
     */
    @Test
    void modelChangeDiscardsChunksAndRebuildsChunkIndexBeforeActivatingNewFunction() {
        EmbeddingMigrationService reencodingService =
                new EmbeddingMigrationService(client, repo, 10, 0, () -> { });

        when(client.getInfo()).thenReturn(new EmbeddingInfo("new-model", 768));
        when(repo.loadStoredInfo()).thenReturn(Optional.of(new EmbeddingInfo("old-model", 1024)));
        when(repo.tryAdvisoryLock(anyLong())).thenReturn(true);
        when(repo.loadProgress()).thenReturn(Optional.empty());
        when(repo.fetchCellBatch(any(), anyInt(), anyInt(), anyBoolean())).thenReturn(List.of());
        when(repo.fetchFactBatch(any(), anyInt(), anyInt(), anyBoolean())).thenReturn(List.of());

        reencodingService.run(null);

        verify(repo).discardChunks();
        verify(repo).dropVectorIndexes("cell_chunks");
        verify(repo).createChunkEmbeddingIndex(768);
        verify(repo).replaceRankedSearchFunction(768);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(repo);
        order.verify(repo).dropEmbeddingIndex();
        order.verify(repo).discardChunks();
        order.verify(repo).dropVectorIndexes("cell_chunks");
        order.verify(repo).createChunkEmbeddingIndex(768);
        order.verify(repo).replaceRankedSearchFunction(768);
    }
}
