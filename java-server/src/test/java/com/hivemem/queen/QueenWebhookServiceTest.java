package com.hivemem.queen;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.cells.CellReadRepository;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.search.CellSearchRepository.RankedRow;
import com.hivemem.write.WriteToolService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueenWebhookServiceTest {

    private final CellReadRepository cells = mock(CellReadRepository.class);
    private final CellSearchRepository search = mock(CellSearchRepository.class);
    private final EmbeddingClient embedding = mock(EmbeddingClient.class);
    private final WriteToolService writes = mock(WriteToolService.class);
    private final QueenRepository repo = mock(QueenRepository.class);

    private QueenWebhookService service() {
        QueenProperties p = new QueenProperties();
        p.setIsolatedBatchLimit(20);
        return new QueenWebhookService(p, repo, cells, search, embedding, writes);
    }

    @Test
    void findIsolatedCellsCapsAtBatchLimit() {
        QueenProperties p = new QueenProperties();
        p.setIsolatedBatchLimit(2);
        QueenWebhookService svc = new QueenWebhookService(p, repo, cells, search, embedding, writes);
        svc.findIsolatedCells(1000);
        verify(repo).findIsolatedCellIds(2);
    }

    @Test
    void searchExcludesSelf() {
        UUID self = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(cells.findCell(eq(self), any())).thenReturn(Optional.of(Map.of("content", "abc")));
        when(embedding.encodeQuery("abc")).thenReturn(List.of(0.1f, 0.2f));
        when(search.rankedSearch(any(), anyString(), isNull(), isNull(), isNull(), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any()))
                .thenReturn(List.of(
                        new RankedRow(self, "self", "selfsum", "work", "facts", "t", List.of(), 3,
                                OffsetDateTime.now(), OffsetDateTime.now(), null,
                                1.0, 0, 0, 0, 0, 0, 1.0),
                        new RankedRow(other, "o", "othersum", "work", "facts", "t", List.of(), 3,
                                OffsetDateTime.now(), OffsetDateTime.now(), null,
                                0.8, 0, 0, 0, 0, 0, 0.8)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates =
                (List<Map<String, Object>>) service().searchSimilarCells(self.toString(), 5).get("candidates");
        assertThat(candidates).extracting(c -> c.get("cell_id")).containsExactly(other.toString());
    }

    @Test
    void ingestWritesPendingAndSkipsDuplicatesAndBadRelations() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        UUID dupTo = UUID.randomUUID();
        when(repo.tunnelExists(from, to, "related_to")).thenReturn(false);
        when(repo.tunnelExists(from, dupTo, "related_to")).thenReturn(true);

        int written = service().ingestProposals(List.of(
                Map.of("from_cell", from.toString(), "to_cell", to.toString(),
                        "relation", "related_to", "note", "linked"),
                Map.of("from_cell", from.toString(), "to_cell", dupTo.toString(),
                        "relation", "related_to"),
                Map.of("from_cell", from.toString(), "to_cell", to.toString(),
                        "relation", "bogus")));

        assertThat(written).isEqualTo(1);
        verify(writes).addTunnel(
                argThatIsQueenAgent(), eq(from), eq(to), eq("related_to"), eq("linked"), eq("pending"));
        verify(writes, never()).addTunnel(any(), eq(from), eq(dupTo), anyString(), any(), anyString());
    }

    @Test
    void ingestToleratesNonMapItems() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        List<Map<String, Object>> bad = (List) java.util.List.of("not-a-map");
        int written = service().ingestProposals(bad);
        org.assertj.core.api.Assertions.assertThat(written).isEqualTo(0);
    }

    private static AuthPrincipal argThatIsQueenAgent() {
        return org.mockito.ArgumentMatchers.argThat(
                pr -> pr != null && "queen".equals(pr.name()) && pr.role() == AuthRole.AGENT);
    }
}
