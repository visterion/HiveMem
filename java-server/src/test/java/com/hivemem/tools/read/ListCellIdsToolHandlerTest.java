package com.hivemem.tools.read;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B4 (LOW): list_cell_ids silently clamped an out-of-range limit (1-1000) / offset (>= 0)
 * instead of rejecting it, unlike SearchToolHandler.boundedLimit which throws. A caller
 * passing limit=2000 should get an error, not a silently-clamped 1000.
 */
class ListCellIdsToolHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ReadToolService readToolService = Mockito.mock(ReadToolService.class);
    private final ListCellIdsToolHandler handler = new ListCellIdsToolHandler(readToolService);
    private final AuthPrincipal principal = new AuthPrincipal("test", AuthRole.WRITER);

    @Test
    void rejectsLimitAboveMax() throws Exception {
        JsonNode args = MAPPER.readTree("{\"limit\": 2000}");

        assertThatThrownBy(() -> handler.call(principal, args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void rejectsLimitBelowMin() throws Exception {
        JsonNode args = MAPPER.readTree("{\"limit\": 0}");

        assertThatThrownBy(() -> handler.call(principal, args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void rejectsNegativeOffset() throws Exception {
        JsonNode args = MAPPER.readTree("{\"offset\": -1}");

        assertThatThrownBy(() -> handler.call(principal, args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset");
    }

    @Test
    void acceptsValidLimitAndOffset() throws Exception {
        Mockito.when(readToolService.listCellIds(Mockito.any(), Mockito.eq(50), Mockito.eq(10)))
                .thenReturn(Map.of("ids", java.util.List.of(), "total", 0));
        JsonNode args = MAPPER.readTree("{\"limit\": 50, \"offset\": 10}");

        Object result = handler.call(principal, args);

        assertThat(result).isInstanceOf(Map.class);
    }
}
