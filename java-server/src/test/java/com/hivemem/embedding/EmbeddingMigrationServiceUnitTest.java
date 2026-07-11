package com.hivemem.embedding;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
        verify(repo, never()).fetchCellBatch(any(), anyInt(), anyInt());
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
}
