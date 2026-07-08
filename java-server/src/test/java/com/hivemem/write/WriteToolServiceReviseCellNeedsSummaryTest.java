package com.hivemem.write;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.kg.KgEntityRepository;
import com.hivemem.search.CellSelectorRepository;
import com.hivemem.summarize.CellNeedsSummaryEvent;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PushDispatcher;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class WriteToolServiceReviseCellNeedsSummaryTest {

    @Test
    void reviseCellTagsNeedsSummaryWhenContentLongAndNoSummary() {
        WriteToolRepository repo = mock(WriteToolRepository.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        OpLogWriter opLog = mock(OpLogWriter.class);
        PushDispatcher push = mock(PushDispatcher.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        CellSelectorRepository cellSelectorRepository = mock(CellSelectorRepository.class);
        KgEntityRepository kgEntityRepository = mock(KgEntityRepository.class);

        UUID newId = UUID.randomUUID();
        UUID oldId = UUID.randomUUID();
        // Production returns new_id as a STRING — reproduce that exactly.
        when(repo.reviseCell(any(), any(), any(), any(), anyString(), anyString()))
                .thenReturn(Map.of("old_id", oldId.toString(), "new_id", newId.toString()));
        when(embedding.encodeForCell(any(), any())).thenReturn(null); // embedding value irrelevant to this test

        WriteToolService service = new WriteToolService(
                repo, embedding, opLog, push, events, cellSelectorRepository, kgEntityRepository);

        String longContent = "x".repeat(600); // > 500 → needs summary
        AuthPrincipal admin = new AuthPrincipal("system", AuthRole.ADMIN);
        service.reviseCell(admin, oldId, longContent, null);

        verify(repo).tagNeedsSummary(newId);
        verify(events).publishEvent(any(CellNeedsSummaryEvent.class));
    }
}
