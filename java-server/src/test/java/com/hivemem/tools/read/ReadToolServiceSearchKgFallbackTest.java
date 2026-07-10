package com.hivemem.tools.read;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.search.KgSearchRepository;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B2 (M16): when the embedding sidecar is down, search_kg's semantic path throws and
 * previously fell through to an unfiltered {@code kgSearchRepository.search(null, null, null, limit)}
 * — returning the newest N facts in the whole KG regardless of the query text. This must instead
 * filter by the query text and mark the result as degraded.
 */
class ReadToolServiceSearchKgFallbackTest {

    @Test
    void embeddingFailureWithNoExplicitFiltersFallsBackToQueryTextIlikeAndMarksDegraded() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.encodeQuery("foo")).thenThrow(new RuntimeException("sidecar down"));

        KgSearchRepository kgSearchRepository = mock(KgSearchRepository.class);
        Map<String, Object> matching = row("Foo Corp", "makes", "widgets");
        when(kgSearchRepository.searchText(eq("foo"), eq(10))).thenReturn(List.of(matching));

        ReadToolService service = ReadToolServiceTestFactory.withEmbeddingAndKgSearch(embeddingClient, kgSearchRepository);

        List<Map<String, Object>> results = service.searchKg("foo", null, null, null, 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0)).containsEntry("subject", "Foo Corp");
        assertThat(results.get(0)).containsEntry("degraded", true);
    }

    @Test
    void embeddingFailureWithExplicitSubjectFilterStillUsesIlikeSearchAndMarksDegraded() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.encodeQuery("foo")).thenThrow(new RuntimeException("sidecar down"));

        KgSearchRepository kgSearchRepository = mock(KgSearchRepository.class);
        Map<String, Object> matching = row("HiveMem", "runs on", "PostgreSQL");
        when(kgSearchRepository.search(eq("HiveMem"), isNull(), isNull(), eq(10))).thenReturn(List.of(matching));

        ReadToolService service = ReadToolServiceTestFactory.withEmbeddingAndKgSearch(embeddingClient, kgSearchRepository);

        List<Map<String, Object>> results = service.searchKg("foo", "HiveMem", null, null, 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0)).containsEntry("degraded", true);
    }

    private static Map<String, Object> row(String subject, String predicate, String object) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subject", subject);
        m.put("predicate", predicate);
        m.put("object", object);
        return m;
    }
}
