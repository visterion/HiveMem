package com.hivemem.tools.read;

import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.cells.CellReadRepository;
import com.hivemem.embedding.EmbeddingClient;
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

import static org.mockito.Mockito.mock;

final class ReadToolServiceTestFactory {
    private ReadToolServiceTestFactory() {}

    static ReadToolService withDocumentListRepository(DocumentListRepository repo) {
        return new ReadToolService(
                mock(CellReadRepository.class),
                mock(KgSearchRepository.class),
                mock(CellSearchRepository.class),
                mock(EmbeddingClient.class),
                mock(AdminToolService.class),
                mock(SearchWeightsProperties.class),
                mock(ConfidenceThresholds.class),
                mock(AttachmentRepository.class),
                mock(FacetRepository.class),
                repo,
                mock(MediaListRepository.class),
                mock(CellSelectorRepository.class),
                mock(DataQualityRepository.class),
                mock(KgEntityRepository.class)
        );
    }

    static ReadToolService withEmbeddingAndKgSearch(EmbeddingClient embeddingClient, KgSearchRepository kgSearchRepository) {
        return new ReadToolService(
                mock(CellReadRepository.class),
                kgSearchRepository,
                mock(CellSearchRepository.class),
                embeddingClient,
                mock(AdminToolService.class),
                mock(SearchWeightsProperties.class),
                mock(ConfidenceThresholds.class),
                mock(AttachmentRepository.class),
                mock(FacetRepository.class),
                mock(DocumentListRepository.class),
                mock(MediaListRepository.class),
                mock(CellSelectorRepository.class),
                mock(DataQualityRepository.class),
                mock(KgEntityRepository.class)
        );
    }
}
