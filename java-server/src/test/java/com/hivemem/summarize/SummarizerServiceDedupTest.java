package com.hivemem.summarize;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hivemem.consumption.DocumentDedupService;
import com.hivemem.extraction.ExtractionProfileRegistry;
import com.hivemem.extraction.ExtractionProperties;
import com.hivemem.write.WriteToolService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SummarizerServiceDedupTest {

    private final SummarizerProperties props = new SummarizerProperties();
    private final ExtractionProperties extractionProps = new ExtractionProperties();
    private final SummarizerRepository repo = mock(SummarizerRepository.class);
    private final WriteToolService writeService = mock(WriteToolService.class);
    private final ExtractionProfileRegistry registry = mock(ExtractionProfileRegistry.class);
    private final AnthropicSummarizer anthropic = mock(AnthropicSummarizer.class);
    private final SummarizeBudgetTracker budget = mock(SummarizeBudgetTracker.class);
    private final DocumentDedupService dedup = mock(DocumentDedupService.class);

    private final UUID cellId = UUID.randomUUID();

    /** Build via the package-private test constructor so the internally-built
     *  AnthropicSummarizer/SummarizeBudgetTracker are replaced by mocks (no reflection). */
    private SummarizerService newService() {
        return new SummarizerService(
                props, extractionProps, repo, budget, anthropic, writeService, registry, dedup);
    }

    private void stubSummary() {
        when(repo.findCellSnapshot(cellId)).thenReturn(Optional.of(
                new SummarizerRepository.CellSnapshot(cellId, "long scanned text", null,
                        List.of(), null, List.of())));
        when(repo.findCellAttachmentMeta(any())).thenReturn(Optional.empty()); // avoid NPE in pickProfile
        SummaryResult result = new SummaryResult(
                "Title", "a generated summary", List.of(), null, List.of(),
                "invoice", List.of(), "de", false, 1, 1);
        when(anthropic.summarize(any(), any())).thenReturn(result);
    }

    @Test
    void summarizeOneRunsDedupOnNewRevision() {
        stubSummary();
        UUID newId = UUID.randomUUID();
        when(writeService.reviseCellWithSummary(any(), eq(cellId), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("old_id", cellId.toString(), "new_id", newId.toString()));

        newService().summarizeOne(cellId);

        verify(dedup).findAndDiscardDuplicate(newId); // runs on the NEW revision, not cellId
    }

    @Test
    void summarizeOneSkipsDedupWhenNoNewRevision() {
        stubSummary();
        // No "new_id" in the result → newId is null → dedup must be skipped.
        when(writeService.reviseCellWithSummary(any(), eq(cellId), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("old_id", cellId.toString()));

        newService().summarizeOne(cellId);

        verify(dedup, never()).findAndDiscardDuplicate(any());
    }
}
