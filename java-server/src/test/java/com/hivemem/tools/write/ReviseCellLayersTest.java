package com.hivemem.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.kg.KgEntityRepository;
import com.hivemem.search.CellSelectorRepository;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PushDispatcher;
import com.hivemem.write.WriteToolRepository;
import com.hivemem.write.WriteToolService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests (Mockito, no DB) for the {@code revise_cell} key_points/insight extension and the
 * tool-call-XML-artifact guard shared by {@code add_cell} and {@code revise_cell}. Mirrors the
 * mocking style of {@code WriteToolServiceReviseCellNeedsSummaryTest} /
 * {@code WriteToolServiceAddCellOrderingTest}.
 */
class ReviseCellLayersTest {

    private static WriteToolService newService(WriteToolRepository repo, EmbeddingClient embedding) {
        OpLogWriter opLog = mock(OpLogWriter.class);
        PushDispatcher push = mock(PushDispatcher.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        CellSelectorRepository cellSelectorRepository = mock(CellSelectorRepository.class);
        KgEntityRepository kgEntityRepository = mock(KgEntityRepository.class);
        return new WriteToolService(repo, embedding, opLog, push, events, cellSelectorRepository, kgEntityRepository);
    }

    @Test
    void reviseCellPersistsKeyPointsAndInsight() {
        WriteToolRepository repo = mock(WriteToolRepository.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.encodeForCell(any(), any())).thenReturn(null);

        UUID oldId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        when(repo.reviseCell(eq(oldId), eq("c2"), eq("s2"), eq(List.of("p1", "p2")), eq("i2"),
                isNull(), any(), anyString(), anyString()))
                .thenReturn(Map.of("old_id", oldId.toString(), "new_id", newId.toString()));

        WriteToolService service = newService(repo, embedding);
        AuthPrincipal admin = new AuthPrincipal("system", AuthRole.ADMIN);

        Map<String, Object> result = service.reviseCell(
                admin, oldId, "c2", "s2", List.of("p1", "p2"), "i2");

        assertThat(result.get("new_id")).isEqualTo(newId.toString());
        verify(repo).reviseCell(eq(oldId), eq("c2"), eq("s2"), eq(List.of("p1", "p2")), eq("i2"),
                isNull(), any(), anyString(), anyString());
    }

    @Test
    void reviseCellOmittedLayersCarryOver() {
        WriteToolRepository repo = mock(WriteToolRepository.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.encodeForCell(any(), any())).thenReturn(null);

        UUID oldId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        // The 6-arg repository overload carries key_points/insight/tags over from the old
        // revision unchanged (see WriteToolRepository#reviseCell javadoc) — it must be the one
        // invoked when the caller omits both layers, exactly as before this feature existed.
        when(repo.reviseCell(eq(oldId), eq("c2"), eq("s2"), any(), anyString(), anyString()))
                .thenReturn(Map.of("old_id", oldId.toString(), "new_id", newId.toString()));

        WriteToolService service = newService(repo, embedding);
        AuthPrincipal admin = new AuthPrincipal("system", AuthRole.ADMIN);

        Map<String, Object> result = service.reviseCell(admin, oldId, "c2", "s2", null, null);

        assertThat(result.get("new_id")).isEqualTo(newId.toString());
        verify(repo).reviseCell(eq(oldId), eq("c2"), eq("s2"), any(), anyString(), anyString());
        verify(repo, never()).reviseCell(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void addCellRejectsToolCallArtifactInSummary() {
        WriteToolRepository repo = mock(WriteToolRepository.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        WriteToolService service = newService(repo, embedding);
        AuthPrincipal admin = new AuthPrincipal("system", AuthRole.ADMIN);

        String corruptedSummary = "ok</summary>\n<parameter name=\"key_points\">[\"x\"]";

        assertThatThrownBy(() -> service.addCell(
                admin, "content", null, null, null, null, List.of(), null,
                corruptedSummary, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("malformed tool-call payload in summary");

        verify(embedding, never()).encodeForCell(any(), any());
        verify(repo, never()).inTransaction(any());
    }

    @Test
    void reviseCellRejectsToolCallArtifactInContent() {
        WriteToolRepository repo = mock(WriteToolRepository.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        WriteToolService service = newService(repo, embedding);
        AuthPrincipal admin = new AuthPrincipal("system", AuthRole.ADMIN);
        UUID oldId = UUID.randomUUID();

        String corruptedContent = "ok</content>\n<parameter name=\"insight\">bad";

        assertThatThrownBy(() -> service.reviseCell(admin, oldId, corruptedContent, "summary"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("malformed tool-call payload in new_content");

        verify(embedding, never()).encodeForCell(any(), any());
        verify(repo, never()).reviseCell(any(), any(), any(), any(), any(), any());
        verify(repo, never()).reviseCell(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void cleanFieldsWithHarmlessXmlPass() {
        WriteToolRepository repo = mock(WriteToolRepository.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.encodeForCell(any(), any())).thenReturn(List.of(0.1f));
        when(repo.checkDuplicateCell(anyString(), anyDouble())).thenReturn(List.of());
        UUID insertedId = UUID.randomUUID();
        when(repo.addCell(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any()))
                .thenReturn(Map.of("id", insertedId.toString()));
        when(repo.inTransaction(org.mockito.ArgumentMatchers.<java.util.function.Supplier<Map<String, Object>>>any()))
                .thenAnswer(invocation -> {
                    java.util.function.Supplier<?> work = invocation.getArgument(0);
                    return work.get();
                });

        WriteToolService service = newService(repo, embedding);
        AuthPrincipal admin = new AuthPrincipal("system", AuthRole.ADMIN);

        String harmlessXml = "<config><param>x</param></config>";

        Map<String, Object> result = service.addCell(
                admin, harmlessXml, null, null, null, null, List.of(), null,
                harmlessXml, null, null, null, null, null, null);

        assertThat(result.get("inserted")).isEqualTo(true);
    }
}
