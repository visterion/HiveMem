package com.hivemem.tools.read;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class GetCellToolHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal("test", AuthRole.READER);

    private ReadToolService readToolService;
    private GetCellToolHandler handler;

    @BeforeEach
    void setUp() {
        readToolService = Mockito.mock(ReadToolService.class);
        handler = new GetCellToolHandler(readToolService);
    }

    @Test
    void missingCellThrowsCellNotFoundInsteadOfReturningNull() {
        // M31: a nonexistent id used to serialize as the literal text "null"
        // inside a successful tool result.
        UUID id = UUID.randomUUID();
        when(readToolService.getCell(any(), any(UUID.class), any())).thenReturn(null);
        JsonNode args = MAPPER.readTree("{\"cell_id\":\"" + id + "\"}");

        assertThatThrownBy(() -> handler.call(PRINCIPAL, args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cell not found: " + id);
    }

    @Test
    void existingCellIsReturnedUnchanged() {
        UUID id = UUID.randomUUID();
        Map<String, Object> cell = Map.of("id", id.toString(), "summary", "s");
        when(readToolService.getCell(any(), any(UUID.class), any())).thenReturn(cell);
        JsonNode args = MAPPER.readTree("{\"cell_id\":\"" + id + "\"}");

        assertThat(handler.call(PRINCIPAL, args)).isEqualTo(cell);
    }

    @Test
    void missingCellIdArgumentThrowsIllegalArgument() {
        assertThatThrownBy(() -> handler.call(PRINCIPAL, MAPPER.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing cell_id");
    }
}
