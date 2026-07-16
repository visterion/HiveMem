package com.hivemem.queen;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.write.WriteToolService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QueenWebhookServiceArchivistTest {

    private final QueenProperties props = new QueenProperties();
    private final QueenRepository repo = mock(QueenRepository.class);
    private final WriteToolService writes = mock(WriteToolService.class);
    // Other collaborators unused by these methods -> pass mocks/nulls as the constructor allows.
    private final QueenWebhookService svc = new QueenWebhookService(
            props, repo, mock(com.hivemem.cells.CellReadRepository.class),
            mock(com.hivemem.search.CellSearchRepository.class),
            mock(com.hivemem.embedding.EmbeddingClient.class), writes);

    @Test
    void findInboxCellsClampsToBatchLimitAndStringifies() {
        props.setInboxBatchLimit(20);
        UUID a = UUID.randomUUID();
        when(repo.findInboxCellIds(anyInt())).thenReturn(List.of(a));
        Map<String, Object> out = svc.findInboxCells(999);
        assertThat(out.get("cell_ids")).isEqualTo(List.of(a.toString()));
        verify(repo).findInboxCellIds(20); // clamped
    }

    @Test
    void listTaxonomyNestsTopicsUnderRealmsWithSignals() {
        when(repo.listTaxonomy()).thenReturn(List.of(
                Map.of("realm", "work", "topic", "steuer", "cell_count", 2L),
                Map.of("realm", "work", "topic", "reisen", "cell_count", 1L)));
        Map<String, Object> out = svc.listTaxonomy();
        assertThat(out.get("signals")).isEqualTo(List.of("facts","events","discoveries","preferences","advice"));
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> realms = (List<Map<String,Object>>) out.get("realms");
        assertThat(realms).hasSize(1);
        assertThat(realms.get(0).get("realm")).isEqualTo("work");
    }

    @Test
    void reclassifyRejectsInboxTarget() {
        assertThatThrownBy(() -> svc.reclassifyInboxCell(UUID.randomUUID().toString(), "inbox", "facts", "t", "r"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(writes);
    }

    @Test
    void reclassifyRejectsNullRealm() {
        assertThatThrownBy(() -> svc.reclassifyInboxCell(UUID.randomUUID().toString(), null, "facts", "t", "r"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(writes);
    }

    @Test
    void reclassifyDelegatesWithArchivistPrincipal() {
        String id = UUID.randomUUID().toString();
        svc.reclassifyInboxCell(id, "work", "facts", "steuer", "Rechnung");
        verify(writes).reclassifyCell(argThat((AuthPrincipal p) -> p.name().equals("inbox-archivist")),
                eq(UUID.fromString(id)), eq("work"), eq("steuer"), eq("facts"), eq("Rechnung"));
    }

    @Test
    void skipDelegatesWithArchivistPrincipal() {
        String id = UUID.randomUUID().toString();
        svc.skipInboxCell(id, "ambiguous");
        verify(writes).skipInboxCell(argThat((AuthPrincipal p) -> p.name().equals("inbox-archivist")),
                eq(UUID.fromString(id)), eq("ambiguous"));
    }
}
