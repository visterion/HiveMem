package com.hivemem.summarize;

import com.hivemem.consumption.DocumentDedupService;
import com.hivemem.extraction.ExtractionProfileRegistry;
import com.hivemem.extraction.ExtractionProperties;
import com.hivemem.queen.ArchivistTrigger;
import com.hivemem.write.WriteToolService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies SummarizerService fires the on-demand archivist trigger once a cell settles after
 * summarization (or gives up trying) -- the gap this closes: long/OCR'd documents previously only
 * settled via the daily cron, since only OcrService/AttachmentEnrichmentService called the trigger.
 */
class SummarizerServiceTriggerTest {

    @Test
    void givesUpAndNotifiesArchivistWhenSummarizerProducesNoSummary() {
        SummarizerProperties props = new SummarizerProperties();
        ExtractionProperties extractionProps = new ExtractionProperties();
        SummarizerRepository repo = mock(SummarizerRepository.class);
        WriteToolService writeService = mock(WriteToolService.class);
        ExtractionProfileRegistry registry = mock(ExtractionProfileRegistry.class);
        AnthropicSummarizer anthropic = mock(AnthropicSummarizer.class);
        SummarizeBudgetTracker budget = mock(SummarizeBudgetTracker.class);
        DocumentDedupService dedup = mock(DocumentDedupService.class);
        ArchivistTrigger trigger = mock(ArchivistTrigger.class);

        UUID cellId = UUID.randomUUID();

        when(repo.tryClaim(cellId)).thenReturn(true);
        when(repo.findCellSnapshot(cellId)).thenReturn(Optional.of(
                new SummarizerRepository.CellSnapshot(cellId, "long scanned text", null,
                        List.of(), null, List.of())));
        when(repo.findCellAttachmentMeta(any())).thenReturn(Optional.empty());
        // Empty summary -> the "give up" branch, which removes needs_summary but must still
        // notify the archivist since the cell is now settled (no other tag will fire it).
        when(anthropic.summarize(any(), any())).thenReturn(
                new SummaryResult(null, null, List.of(), null, List.of(), null, List.of(), null, false, 0, 0));

        SummarizerService service = new SummarizerService(
                props, extractionProps, repo, budget, anthropic, writeService, registry, dedup);
        service.archivistTrigger = trigger; // package-private test seam (mirrors OcrService)

        service.summarizeOne(cellId);

        verify(trigger).maybeTrigger(cellId);
    }
}
