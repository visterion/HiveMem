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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code entity_overview} must rank its cells like a default {@code search}. */
class EntityOverviewWeightsTest {

    @Test
    void entityOverviewUsesTheBalancedProfile() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.encodeQuery(anyString()))
                .thenThrow(new EmbeddingUnavailableException("embedding service down", null));
        CellSearchRepository cellSearchRepository = mock(CellSearchRepository.class);
        KgEntityRepository kgEntityRepository = mock(KgEntityRepository.class);
        when(kgEntityRepository.resolve("Acme")).thenReturn("Acme");
        ReadToolService service = new ReadToolService(
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
                kgEntityRepository);
        when(cellSearchRepository.rankedSearch(any(), anyString(), any(), any(), any(), eq(5),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                any(), any(), any()))
                .thenReturn(List.of());

        service.entityOverview("Acme", 5);

        verify(cellSearchRepository).rankedSearch(
                isNull(), eq("Acme"), isNull(), isNull(), isNull(), eq(5),
                eq(0.20), eq(0.25), eq(0.15), eq(0.15), eq(0.15), eq(0.10),
                isNull(), isNull(), isNull());
    }
}
