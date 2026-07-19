package com.hivemem.tools.read;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SearchToolHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal("writer-1", AuthRole.WRITER);

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

    @Test
    void queryIsNoLongerRequiredInSchema() {
        Map<String, Object> schema = handler.inputSchema();
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");

        // No required fields at all now that 'query' became optional -> the schema builder
        // omits the "required" key entirely rather than emitting an empty array.
        assertThat(required == null || !required.contains("query")).isTrue();
    }

    @Test
    void blankQueryWithRealmFilterRoutesToBrowsePath() throws Exception {
        JsonNode args = MAPPER.readTree("{\"query\": \"\", \"realm\": \"engineering\"}");

        handler.call(PRINCIPAL, args);

        verify(readToolService).searchBrowse(
                anyInt(), eq("engineering"), isNull(), isNull(), any(), isNull(), isNull(), isNull());
        verify(readToolService, never()).search(
                any(), anyInt(), any(), any(), any(), any(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                any(), any(), any(), anyBoolean());
    }

    @Test
    void missingQueryWithTagsFilterRoutesToBrowsePath() throws Exception {
        JsonNode args = MAPPER.readTree("{\"tags\": [\"infra\"]}");

        handler.call(PRINCIPAL, args);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> tagsCaptor = ArgumentCaptor.forClass(List.class);
        verify(readToolService).searchBrowse(
                anyInt(), isNull(), isNull(), isNull(), any(), tagsCaptor.capture(), isNull(), isNull());
        assertThat(tagsCaptor.getValue()).containsExactly("infra");
    }

    @Test
    void nonBlankQueryStillUsesRankedSearchPath() throws Exception {
        JsonNode args = MAPPER.readTree("{\"query\": \"vector search\"}");

        handler.call(PRINCIPAL, args);

        verify(readToolService).search(
                eq("vector search"), anyInt(), isNull(), isNull(), isNull(), any(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                isNull(), isNull(), isNull(), anyBoolean());
        verify(readToolService, never()).searchBrowse(
                anyInt(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void stringifiedLimitAndWhereAreCoerced() throws Exception {
        // Some LLM/mcp providers stringify tool arguments (observed live: limit:"20" and a
        // stringified where object). The search tool must coerce them instead of failing with
        // "Invalid limit" — for a native mcp tool that error is run-fatal.
        JsonNode args = MAPPER.readTree(
                "{\"query\": \"prey\", \"limit\": \"20\", \"where\": \"{\\\"realm\\\": \\\"dracul-research\\\"}\"}");

        handler.call(PRINCIPAL, args);

        verify(readToolService).search(
                eq("prey"), eq(20), eq("dracul-research"), isNull(), isNull(), any(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                isNull(), isNull(), isNull(), anyBoolean());
    }

    @Test
    void nonNumericStringLimitStillRejected() throws Exception {
        JsonNode args = MAPPER.readTree("{\"query\": \"prey\", \"limit\": \"lots\"}");

        assertThatThrownBy(() -> handler.call(PRINCIPAL, args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid limit");
    }

    @Test
    void blankQueryWithoutAnyFilterStillFailsWithMissingQuery() throws Exception {
        JsonNode args = MAPPER.readTree("{\"query\": \"\"}");

        assertThatThrownBy(() -> handler.call(PRINCIPAL, args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing query");
        Mockito.verifyNoInteractions(readToolService);
    }

    @Test
    void absentQueryWithoutAnyFilterStillFailsWithMissingQuery() throws Exception {
        JsonNode args = MAPPER.readTree("{}");

        assertThatThrownBy(() -> handler.call(PRINCIPAL, args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing query");
        Mockito.verifyNoInteractions(readToolService);
    }
}
