package com.hivemem.hooks;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.search.CellSearchRepository.RankedRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class HookContextServiceTest {

    private CellSearchRepository repo;
    private EmbeddingClient embed;
    private HookContextService svc;
    private HookProperties props;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(CellSearchRepository.class);
        embed = Mockito.mock(EmbeddingClient.class);
        when(embed.encodeQuery(anyString())).thenReturn(List.of(0f));
        when(repo.findReferencesForCells(any())).thenReturn(Map.of());
        props = new HookProperties();
        svc = new HookContextService(repo, embed, new SkipHeuristics(),
                new SessionInjectionCache(), new ContextFormatter(), props);
    }

    @Test
    void skipsTrivialPrompt() {
        ContextResult result = svc.contextFor(new HookContextRequest("UserPromptSubmit", "ok", "s1", null));
        assertThat(result.formattedContext()).isEmpty();
        assertThat(result.citedSources()).isEmpty();
        Mockito.verifyNoInteractions(repo);
    }

    @Test
    void emptyWhenAllResultsBelowThreshold() {
        when(repo.rankedSearch(any(), anyString(), any(), any(), any(), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(List.of(weakRow()));
        ContextResult result = svc.contextFor(new HookContextRequest(
                "UserPromptSubmit", "What was the plan for project X phase 3?", "s1", null));
        assertThat(result.formattedContext()).isEmpty();
        assertThat(result.citedSources()).isEmpty();
    }

    @Test
    void formatsResultsAboveThreshold() {
        when(repo.rankedSearch(any(), anyString(), any(), any(), any(), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(List.of(strongRow()));
        ContextResult result = svc.contextFor(new HookContextRequest(
                "UserPromptSubmit", "What was the plan for project X phase 3?", "s1", null));
        assertThat(result.formattedContext()).contains("<hivemem_context");
        assertThat(result.citedSources()).hasSize(1);
    }

    @Test
    void dedupSuppressesRepeatedInjection() {
        RankedRow row = strongRow();
        when(repo.rankedSearch(any(), anyString(), any(), any(), any(), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(List.of(row));
        var req = new HookContextRequest(
                "UserPromptSubmit", "What was the plan for project X phase 3?", "s1", null);
        svc.contextFor(req);
        ContextResult second = svc.contextFor(req);
        assertThat(second.formattedContext()).isEmpty();
    }

    @Test
    void disabledReturnsEmpty() {
        props.setEnabled(false);
        ContextResult result = svc.contextFor(new HookContextRequest(
                "UserPromptSubmit", "What was the plan for project X phase 3?", "s1", null));
        assertThat(result.formattedContext()).isEmpty();
        Mockito.verifyNoInteractions(repo);
    }

    @Test
    void searchExceptionReturnsEmpty() {
        when(repo.rankedSearch(any(), anyString(), any(), any(), any(), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));
        ContextResult result = svc.contextFor(new HookContextRequest(
                "UserPromptSubmit", "What was the plan for project X phase 3?", "s1", null));
        assertThat(result.formattedContext()).isEmpty();
    }

    @Test
    void semanticFloorFiltersKeywordOnlyMatch() {
        RankedRow keywordOnly = new RankedRow(UUID.randomUUID(), "x", "keyword match", "r", "s", "t",
                List.of(), 3, List.of(), null, OffsetDateTime.now(), null, null,
                0.1, 0.9, 0.0, 0.0, 0.0, 0.0, 0.70);
        when(repo.rankedSearch(any(), anyString(), any(), any(), any(), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(List.of(keywordOnly));
        ContextResult result = svc.contextFor(new HookContextRequest(
                "UserPromptSubmit", "What was the plan for project X phase 3?", "s1", null));
        assertThat(result.formattedContext()).isEmpty();
    }

    @Test
    void usesHookPrecisionWeightsNotSearchWeights() {
        when(repo.rankedSearch(any(), anyString(), any(), any(), any(), anyInt(),
                eq(0.70), eq(0.10), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(List.of(strongRow()));

        ContextResult result = svc.contextFor(new HookContextRequest(
                "UserPromptSubmit", "What was the plan for project X phase 3?", "s1", null));

        assertThat(result.formattedContext()).contains("<hivemem_context");
    }

    @Test
    void cwdProjectMatchingCellSortedFirst() {
        UUID projectId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        RankedRow projectCell = new RankedRow(projectId, "x", "hivemem summary", "tech", "s", "hivemem",
                List.of("hivemem"), 1, List.of(), null, OffsetDateTime.now(), null, null,
                0.8, 0.0, 0.0, 0.0, 0.0, 0.0, 0.80);
        RankedRow otherCell = new RankedRow(otherId, "x", "other summary", "tech", "s", "ansible",
                List.of("ansible"), 1, List.of(), null, OffsetDateTime.now(), null, null,
                0.85, 0.0, 0.0, 0.0, 0.0, 0.0, 0.85);
        when(repo.rankedSearch(any(), anyString(), any(), any(), any(), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(List.of(otherCell, projectCell));

        ContextResult result = svc.contextFor(new HookContextRequest(
                "UserPromptSubmit", "What was the plan for project X phase 3?", "s2", "/root/hivemem"),
                0.5, 5);

        assertThat(result.formattedContext()).isNotEmpty();
        int projectPos = result.formattedContext().indexOf("hivemem summary");
        int otherPos = result.formattedContext().indexOf("other summary");
        assertThat(projectPos).isLessThan(otherPos);
    }

    @Test
    void citedSourcesContainAttributionForReturnedCell() {
        RankedRow row = strongRow();
        when(repo.rankedSearch(any(), anyString(), any(), any(), any(), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(List.of(row));
        CellSearchRepository.RefRow refRow =
                new CellSearchRepository.RefRow(row.id(), "My Source", "https://src.com");
        when(repo.findReferencesForCells(any())).thenReturn(Map.of(row.id(), List.of(refRow)));

        ContextResult result = svc.contextFor(new HookContextRequest(
                "UserPromptSubmit", "What was the plan for project X phase 3?", "s3", null));

        assertThat(result.citedSources()).hasSize(1);
        SourceAttribution attr = result.citedSources().get(0);
        assertThat(attr.referenceTitle()).isEqualTo("My Source");
        assertThat(attr.referenceUrl()).isEqualTo("https://src.com");
        assertThat(attr.realm()).isEqualTo("r");
        assertThat(attr.topic()).isEqualTo("t");
    }

    private RankedRow weakRow() {
        return new RankedRow(UUID.randomUUID(), "x", "x", "r", "s", "t",
                List.of(), 3, List.of(), null, OffsetDateTime.now(), null, null,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.10);
    }

    private RankedRow strongRow() {
        return new RankedRow(UUID.randomUUID(), "x", "Phase 3 plan", "r", "s", "t",
                List.of(), 1, List.of(), null, OffsetDateTime.now(), null, null,
                0.9, 0.0, 0.0, 0.0, 0.0, 0.0, 0.90);
    }
}
