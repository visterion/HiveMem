package com.hivemem.tools.read;

import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.cells.CellReadRepository;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.EmbeddingUnavailableException;
import com.hivemem.kg.KgEntityRepository;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.search.CellSelectorRepository;
import com.hivemem.search.ConfidenceThresholds;
import com.hivemem.search.DataQualityRepository;
import com.hivemem.search.DocumentListRepository;
import com.hivemem.search.FacetRepository;
import com.hivemem.search.KgSearchRepository;
import com.hivemem.search.MediaListRepository;
import com.hivemem.search.SearchWeightsProperties;
import com.hivemem.write.AdminToolService;
import org.junit.jupiter.api.Test;

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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * When the embedding service is down, {@code search} must degrade to keyword-only
 * ranking (NULL query embedding) instead of hard-failing — mirroring the
 * {@code search_kg} fallback.
 */
class SearchEmbeddingFallbackTest {

    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final CellSearchRepository cellSearchRepository = mock(CellSearchRepository.class);

    private ReadToolService service() {
        return new ReadToolService(
                mock(CellReadRepository.class),
                mock(KgSearchRepository.class),
                cellSearchRepository,
                embeddingClient,
                mock(AdminToolService.class),
                mock(SearchWeightsProperties.class),
                new ConfidenceThresholds(0.20),
                mock(AttachmentRepository.class),
                mock(FacetRepository.class),
                mock(DocumentListRepository.class),
                mock(MediaListRepository.class),
                mock(CellSelectorRepository.class),
                mock(DataQualityRepository.class),
                mock(KgEntityRepository.class)
        );
    }

    @Test
    void searchFallsBackToKeywordOnlyWhenEmbeddingUnavailable() {
        when(embeddingClient.encodeQuery(anyString()))
                .thenThrow(new EmbeddingUnavailableException("embedding service down", null));
        CellSearchRepository.RankedRow row = new CellSearchRepository.RankedRow(
                UUID.randomUUID(), "content", "summary", "realm", "facts", "topic",
                List.of(), 3, OffsetDateTime.now(), OffsetDateTime.now(), null,
                0.0, 0.5, 0.3, 0.6, 0.0, 0.0, 0.4);
        when(cellSearchRepository.rankedSearch(
                isNull(), anyString(), any(), any(), any(), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                any(), any(), any()))
                .thenReturn(List.of(row));

        List<Map<String, Object>> results = service().search(
                "deployment notes", 10, null, null, null,
                CellFieldSelection.forSearch(null),
                0.30, 0.15, 0.15, 0.15, 0.15, 0.10,
                null, null, null, true);

        assertThat(results).hasSize(1);
        // Keyword-only ranking: the null embedding must be forwarded to ranked_search.
        verify(cellSearchRepository).rankedSearch(
                isNull(), eq("deployment notes"), isNull(), isNull(), isNull(), eq(10),
                eq(0.30), eq(0.15), eq(0.15), eq(0.15), eq(0.15), eq(0.10),
                isNull(), isNull(), isNull());
        // The sixth sub-score is projected alongside the other five.
        assertThat(results.get(0)).containsKey("score_graph_proximity");
    }
}
