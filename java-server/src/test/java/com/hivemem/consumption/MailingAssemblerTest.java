package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClientException;

class MailingAssemblerTest {

    private static PageMetadataExtractor.PageMetadata meta(int page, String sender, String date) {
        return new PageMetadataExtractor.PageMetadata(page, sender, date, null, "letter", null,
                "a page", false);
    }

    @Test
    void parsesMailingsIntoOrderedDocGroups() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.complete(eq("documents"), anyString())).thenReturn("""
                [{"mailing":"vf","description":"Vattenfall order 30.12.2019","confidence":0.9,
                  "pages":[16,15,14,12]},
                 {"mailing":"suez","description":"SUEZ invoice","confidence":0.8,"pages":[17]}]""");
        List<DocGroup> groups = new MailingAssembler(cc)
                .assemble("documents", List.of(meta(12, "Vattenfall", null),
                        meta(14, "Vattenfall", "30.12.2019"), meta(15, "Vattenfall", "30.12.2019"),
                        meta(16, "Vattenfall", "30.12.2019"), meta(17, "SUEZ", "30.09.2020")));
        assertEquals(2, groups.size());
        assertEquals("vf", groups.get(0).id);
        assertEquals("Vattenfall order 30.12.2019", groups.get(0).descriptor);
        assertEquals(List.of(16, 15, 14, 12), groups.get(0).pages); // reading order preserved
        assertEquals(0.9, groups.get(0).minConfidence, 1e-9);
        assertEquals(List.of(17), groups.get(1).pages);
    }

    @Test
    void rendersOneRowPerPageWithPythonStyleNulls() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.complete(anyString(), anyString()))
                .thenReturn("[{\"mailing\":\"m\",\"description\":\"d\",\"confidence\":1.0,\"pages\":[1]}]");
        new MailingAssembler(cc).assemble("documents", List.of(
                new PageMetadataExtractor.PageMetadata(1, "BEV", null, null, "letter",
                        "Vertrags-Nr 1509275", "Confirmation letter.", false)));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(cc).complete(anyString(), prompt.capture());
        // matches the validated row format: nulls rendered as None, strings single-quoted
        assertTrue(prompt.getValue().contains(
                "- page 1: sender='BEV', date=None, printed_page_label=None, blank=false, "
                        + "reference='Vertrags-Nr 1509275', content='letter' - 'Confirmation letter.'"));
    }

    @Test
    void multiLineSummaryRendersOnOneRow() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.complete(anyString(), anyString()))
                .thenReturn("[{\"mailing\":\"m\",\"description\":\"d\",\"confidence\":1.0,\"pages\":[1]}]");
        new MailingAssembler(cc).assemble("documents", List.of(
                new PageMetadataExtractor.PageMetadata(1, "BEV", null, null, "letter",
                        null, "line one\nline two\r\nline three", false)));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(cc).complete(anyString(), prompt.capture());
        String rowsSection = prompt.getValue();
        assertTrue(rowsSection.contains("'line one line two  line three'"));
        // exactly one row for this page: no embedded newline broke it into multiple lines
        assertEquals(1, rowsSection.split("- page 1:", -1).length - 1);
    }

    @Test
    void garbageOutputThrows() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.complete(anyString(), anyString())).thenReturn("sorry, I cannot help with that");
        MailingAssembler assembler = new MailingAssembler(cc);
        List<PageMetadataExtractor.PageMetadata> pages = List.of(meta(1, "X", null));
        assertThrows(IllegalStateException.class, () -> assembler.assemble("documents", pages));
    }

    @Test
    void transientFailureIsRetriedOnce() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.complete(eq("documents"), anyString()))
                .thenThrow(new RestClientException("boom"))
                .thenReturn("[{\"mailing\":\"m\",\"description\":\"d\",\"confidence\":1.0,\"pages\":[1]}]");
        List<DocGroup> groups = new MailingAssembler(cc)
                .assemble("documents", List.of(meta(1, "X", null)));
        assertEquals(1, groups.size());
        verify(cc, times(2)).complete(eq("documents"), anyString());
    }

    @Test
    void persistentFailureThrowsAfterTwoAttempts() {
        CompleteClient cc = mock(CompleteClient.class);
        when(cc.complete(eq("documents"), anyString()))
                .thenThrow(new RestClientException("boom"));
        MailingAssembler assembler = new MailingAssembler(cc);
        List<PageMetadataExtractor.PageMetadata> pages = List.of(meta(1, "X", null));
        assertThrows(RestClientException.class, () -> assembler.assemble("documents", pages));
        verify(cc, times(2)).complete(eq("documents"), anyString());
    }
}
