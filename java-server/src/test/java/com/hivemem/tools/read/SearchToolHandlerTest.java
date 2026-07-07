package com.hivemem.tools.read;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchToolHandlerTest {

    private final ReadToolService readToolService = Mockito.mock(ReadToolService.class);
    private final SearchToolHandler handler = new SearchToolHandler(readToolService);

    @Test
    void includeEnumContainsRealm() {
        Map<String, Object> schema = handler.inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> includeItems = (Map<String, Object>) ((Map<String, Object>) properties.get("include")).get("items");
        @SuppressWarnings("unchecked")
        List<String> enumValues = (List<String>) includeItems.get("enum");

        assertThat(enumValues).contains("realm");
    }
}
