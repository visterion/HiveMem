package com.hivemem.tools.read;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B3 (M14): the recursive traverse CTE re-qualifies every edge at every depth on cyclic
 * graphs; a high max_depth can pin a DB connection for a long time. max_depth must be
 * rejected (not silently clamped) once it exceeds the (lowered) server-side cap.
 */
class TraverseToolHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ReadToolService readToolService = Mockito.mock(ReadToolService.class);
    private final TraverseToolHandler handler = new TraverseToolHandler(readToolService);
    private final AuthPrincipal principal = new AuthPrincipal("test", AuthRole.WRITER);

    @Test
    void rejectsMaxDepthAboveTheLoweredCap() throws Exception {
        JsonNode args = MAPPER.readTree("""
                {"cell_id": "00000000-0000-0000-0000-000000000001", "max_depth": 100}
                """);

        assertThatThrownBy(() -> handler.call(principal, args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid max_depth");
    }

    @Test
    void acceptsMaxDepthAtTheNewCap() throws Exception {
        Mockito.when(readToolService.traverse(Mockito.any(UUID.class), Mockito.eq(10), Mockito.isNull(), Mockito.anyInt()))
                .thenReturn(Map.of("edges", java.util.List.of(), "node_count", 1, "truncated", false));
        JsonNode args = MAPPER.readTree("""
                {"cell_id": "00000000-0000-0000-0000-000000000001", "max_depth": 10}
                """);

        Object result = handler.call(principal, args);

        assertThat(result).isInstanceOf(Map.class);
    }

    @Test
    void rejectsMaxDepthJustAboveTheNewCap() throws Exception {
        JsonNode args = MAPPER.readTree("""
                {"cell_id": "00000000-0000-0000-0000-000000000001", "max_depth": 11}
                """);

        assertThatThrownBy(() -> handler.call(principal, args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid max_depth");
    }
}
