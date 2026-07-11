package com.hivemem.write;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.kg.KgEntityRepository;
import com.hivemem.search.CellSelectorRepository;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PushDispatcher;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Cfix-a: addCell must compute the cell embedding (an HTTP call to the embedding service) BEFORE
 * the advisory lock / write transaction opens — mirrors the C3 fix already applied to kg_add. See
 * WriteToolService.addCell and WriteToolRepository#inTransaction.
 */
class WriteToolServiceAddCellOrderingTest {

    @Test
    void encodeForCellIsCalledBeforeTransactionOpensAndWritesStayAtomic() {
        WriteToolRepository repo = mock(WriteToolRepository.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        OpLogWriter opLog = mock(OpLogWriter.class);
        PushDispatcher push = mock(PushDispatcher.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        CellSelectorRepository cellSelectorRepository = mock(CellSelectorRepository.class);
        KgEntityRepository kgEntityRepository = mock(KgEntityRepository.class);

        when(embedding.encodeForCell(anyString(), any())).thenReturn(List.of(0.1f, 0.2f));
        when(repo.checkDuplicateCell(anyString(), anyDouble())).thenReturn(List.of());
        UUID insertedId = UUID.randomUUID();
        when(repo.addCell(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any()))
                .thenReturn(Map.of("id", insertedId.toString()));
        // inTransaction just runs the supplier inline (no real DB in this unit test).
        when(repo.inTransaction(org.mockito.ArgumentMatchers.<java.util.function.Supplier<Map<String, Object>>>any()))
                .thenAnswer(invocation -> {
                    java.util.function.Supplier<?> work = invocation.getArgument(0);
                    return work.get();
                });

        WriteToolService service = new WriteToolService(
                repo, embedding, opLog, push, events, cellSelectorRepository, kgEntityRepository);

        AuthPrincipal writer = new AuthPrincipal("alice", AuthRole.WRITER);
        Map<String, Object> result = service.addCell(writer, "some content", "work", "facts", null,
                null, List.of(), null, null, null, null, null, null, null, 0.9);

        // Ordering: the embedding HTTP call happens before the advisory lock / transaction opens.
        InOrder inOrder = inOrder(embedding, repo);
        inOrder.verify(embedding).encodeForCell(anyString(), any());
        inOrder.verify(repo).inTransaction(any());
        inOrder.verify(repo).advisoryXactLock(anyString());
        inOrder.verify(repo).addCell(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());

        // Atomicity: the dedupe check-then-insert both happen inside the one inTransaction call
        // (exactly once each), preserving the original all-or-nothing behavior.
        org.mockito.Mockito.verify(repo, times(1)).inTransaction(any());
        org.mockito.Mockito.verify(repo, times(1)).checkDuplicateCell(anyString(), anyDouble());
        org.mockito.Mockito.verify(repo, times(1)).addCell(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any());
        org.junit.jupiter.api.Assertions.assertEquals(true, result.get("inserted"));
    }
}
