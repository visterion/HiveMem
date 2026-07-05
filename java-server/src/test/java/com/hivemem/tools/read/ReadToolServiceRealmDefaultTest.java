package com.hivemem.tools.read;

import com.hivemem.search.DocumentListRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadToolServiceRealmDefaultTest {

    @Test
    void sentinelNoneMapsToNullRealmBind() {
        DocumentListRepository repo = mock(DocumentListRepository.class);
        when(repo.listDocuments(null, null, null, null, null, "newest", 50, 0))
                .thenReturn(List.<Map<String, Object>>of());

        ReadToolService service = ReadToolServiceTestFactory.withDocumentListRepository(repo);
        service.listDocuments("none", null, null, null, null, "newest", 50, 0);

        verify(repo).listDocuments(null, null, null, null, null, "newest", 50, 0);
    }

    @Test
    void omittedRealmDefaultsToDocuments() {
        DocumentListRepository repo = mock(DocumentListRepository.class);
        when(repo.listDocuments("documents", null, null, null, null, "newest", 50, 0))
                .thenReturn(List.<Map<String, Object>>of());

        ReadToolService service = ReadToolServiceTestFactory.withDocumentListRepository(repo);
        service.listDocuments(null, null, null, null, null, "newest", 50, 0);

        verify(repo).listDocuments("documents", null, null, null, null, "newest", 50, 0);
    }

    @Test
    void concreteRealmPassesThroughUnchanged() {
        DocumentListRepository repo = mock(DocumentListRepository.class);
        when(repo.listDocuments("dracul", null, null, null, null, "newest", 50, 0))
                .thenReturn(List.<Map<String, Object>>of());

        ReadToolService service = ReadToolServiceTestFactory.withDocumentListRepository(repo);
        service.listDocuments("dracul", null, null, null, null, "newest", 50, 0);

        verify(repo).listDocuments("dracul", null, null, null, null, "newest", 50, 0);
    }
}
