package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

class PageMetadataExtractorTest {

    private static final byte[] PNG = new byte[] {1, 2, 3};

    @Test
    void parsesAllFields() {
        VisionMultiClient vm = mock(VisionMultiClient.class);
        when(vm.group(eq("documents"), anyString(), anyList())).thenReturn("""
                {"sender":"Vattenfall Europe Sales GmbH","date":"30.12.2019","page_label":"1/3",
                 "doc_type":"letter","reference":"836 616 772 789","summary":"Order confirmation.",
                 "blank":false}""");
        PageMetadataExtractor.PageMetadata m =
                new PageMetadataExtractor(vm).extract("documents", 16, PNG);
        assertEquals(16, m.page());
        assertEquals("Vattenfall Europe Sales GmbH", m.sender());
        assertEquals("30.12.2019", m.date());
        assertEquals("1/3", m.pageLabel());
        assertEquals("letter", m.docType());
        assertEquals("836 616 772 789", m.reference());
        assertEquals("Order confirmation.", m.summary());
        assertFalse(m.blank());
        verify(vm).group(eq("documents"), anyString(), argThat(imgs -> imgs.size() == 1));
    }

    @Test
    void jsonNullsBecomeJavaNulls() {
        VisionMultiClient vm = mock(VisionMultiClient.class);
        when(vm.group(anyString(), anyString(), anyList())).thenReturn(
                "{\"sender\":null,\"date\":null,\"page_label\":null,\"doc_type\":\"blank\","
                        + "\"reference\":null,\"summary\":\"Blank back side.\",\"blank\":true}");
        PageMetadataExtractor.PageMetadata m =
                new PageMetadataExtractor(vm).extract("documents", 11, PNG);
        assertNull(m.sender());
        assertNull(m.date());
        assertNull(m.pageLabel());
        assertTrue(m.blank());
    }

    @Test
    void failureRetriesOnceThenReturnsNullRow() {
        VisionMultiClient vm = mock(VisionMultiClient.class);
        when(vm.group(anyString(), anyString(), anyList())).thenThrow(new RuntimeException("boom"));
        PageMetadataExtractor.PageMetadata m =
                new PageMetadataExtractor(vm).extract("documents", 4, PNG);
        assertEquals(4, m.page());
        assertNull(m.sender());
        assertNull(m.docType());
        assertFalse(m.blank());
        verify(vm, times(2)).group(anyString(), anyString(), anyList());
    }
}
