package com.hivemem.write;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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
 * C3: kg_add must compute the fact embedding (an HTTP call to the embedding service) BEFORE
 * taking the advisory lock / opening the write transaction — the contradiction check does not
 * need the embedding, so there is no reason to hold a pooled connection/lock across that HTTP
 * round trip. See WriteToolService.kgAdd and WriteToolRepository#inTransaction.
 */
class WriteToolServiceKgAddOrderingTest {

    @Test
    void encodeDocumentIsCalledBeforeAdvisoryLock() {
        WriteToolRepository repo = mock(WriteToolRepository.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        OpLogWriter opLog = mock(OpLogWriter.class);
        PushDispatcher push = mock(PushDispatcher.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        CellSelectorRepository cellSelectorRepository = mock(CellSelectorRepository.class);
        KgEntityRepository kgEntityRepository = mock(KgEntityRepository.class);

        when(kgEntityRepository.resolve(anyString())).thenReturn("subject");
        when(embedding.encodeDocument(anyString())).thenReturn(List.of(0.1f, 0.2f));
        when(repo.checkContradiction(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(repo.addFact(any(), any(), any(), anyDouble(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("id", UUID.randomUUID().toString()));
        // inTransaction just runs the supplier inline (no real DB in this unit test).
        when(repo.inTransaction(org.mockito.ArgumentMatchers.<java.util.function.Supplier<Map<String, Object>>>any()))
                .thenAnswer(invocation -> {
                    java.util.function.Supplier<?> work = invocation.getArgument(0);
                    return work.get();
                });

        WriteToolService service = new WriteToolService(
                repo, embedding, opLog, push, events, cellSelectorRepository, kgEntityRepository);

        AuthPrincipal writer = new AuthPrincipal("alice", AuthRole.WRITER);
        service.kgAdd(writer, "subject", "predicate", "object", 0.9, null, null, null, "supersede");

        InOrder inOrder = inOrder(embedding, repo);
        inOrder.verify(embedding).encodeDocument(anyString());
        inOrder.verify(repo).advisoryXactLock(anyString());
        inOrder.verify(repo).addFact(any(), any(), any(), anyDouble(), any(), any(), any(), any(), any());
    }
}
