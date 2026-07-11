package com.hivemem.write;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class WriteToolServiceAddCellDedupeTest {

    @Test
    void addCell_withDedupeThreshold_andNullEmbedding_doesNotThrowNPE() {
        WriteToolRepository repo = mock(WriteToolRepository.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        OpLogWriter opLog = mock(OpLogWriter.class);
        PushDispatcher push = mock(PushDispatcher.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        CellSelectorRepository cellSelectorRepository = mock(CellSelectorRepository.class);
        KgEntityRepository kgEntityRepository = mock(KgEntityRepository.class);

        // Contract: encodeForCell returns null when summary is blank/absent AND content
        // exceeds CONTENT_EMBED_MAX_CHARS (500 chars).
        when(embedding.encodeForCell(any(), any())).thenReturn(null);

        UUID insertedId = UUID.randomUUID();
        when(repo.addCell(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any()))
                .thenReturn(Map.of("id", insertedId.toString()));
        // Cfix-a: addCell()'s DB writes now run inside writeToolRepository.inTransaction(...) —
        // just run the supplier inline (no real DB in this unit test).
        when(repo.inTransaction(org.mockito.ArgumentMatchers.<java.util.function.Supplier<Map<String, Object>>>any()))
                .thenAnswer(invocation -> {
                    java.util.function.Supplier<?> work = invocation.getArgument(0);
                    return work.get();
                });

        WriteToolService service = new WriteToolService(
                repo, embedding, opLog, push, events, cellSelectorRepository, kgEntityRepository);

        String longContent = "x".repeat(600); // > 500 chars, no summary → embedding == null
        AuthPrincipal writer = new AuthPrincipal("alice", AuthRole.WRITER);

        Map<String, Object> result = service.addCell(
                writer, longContent, "work", "facts", null, null, null, null,
                null, null, null, null, null, null, 0.9);

        assertNotNull(result);
        assertFalse(Boolean.FALSE.equals(result.get("inserted")) && result.containsKey("duplicates"));
        // Dedupe check must be skipped entirely when embedding is null.
        verify(repo, never()).checkDuplicateCell(anyString(), anyDouble());
    }
}
