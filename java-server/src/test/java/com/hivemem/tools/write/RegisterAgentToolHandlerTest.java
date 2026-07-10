package com.hivemem.tools.write;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.write.WriteToolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class RegisterAgentToolHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal("test", AuthRole.WRITER);

    private WriteToolService writeToolService;
    private RegisterAgentToolHandler handler;

    @BeforeEach
    void setUp() {
        writeToolService = Mockito.mock(WriteToolService.class);
        handler = new RegisterAgentToolHandler(writeToolService, MAPPER);
    }

    @Test
    void schemaConformantStringAutonomyIsUnwrappedToRawJson() {
        // Schema declares autonomy as "JSON object as string" — a TextNode. Its
        // toString() would be the quoted/escaped literal, which ?::jsonb stores
        // as a jsonb string scalar (H12). The handler must pass the raw JSON.
        JsonNode args = MAPPER.readTree("""
                {"name":"scout","focus":"exploration",
                 "autonomy":"{\\"default\\":\\"auto\\"}",
                 "model_routing":"{\\"default\\":\\"haiku\\"}"}
                """);

        handler.call(PRINCIPAL, args);

        ArgumentCaptor<String> autonomy = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> routing = ArgumentCaptor.forClass(String.class);
        Mockito.verify(writeToolService).registerAgent(
                eq("scout"), eq("exploration"), autonomy.capture(), any(), routing.capture(), any());
        assertThat(autonomy.getValue()).isEqualTo("{\"default\":\"auto\"}");
        assertThat(routing.getValue()).isEqualTo("{\"default\":\"haiku\"}");
    }

    @Test
    void rawObjectAutonomyIsSerializedAsJson() {
        JsonNode args = MAPPER.readTree("""
                {"name":"scout","focus":"exploration","autonomy":{"default":"auto"}}
                """);

        handler.call(PRINCIPAL, args);

        ArgumentCaptor<String> autonomy = ArgumentCaptor.forClass(String.class);
        Mockito.verify(writeToolService).registerAgent(
                anyString(), anyString(), autonomy.capture(), any(), any(), any());
        assertThat(autonomy.getValue()).isEqualTo("{\"default\":\"auto\"}");
    }

    @Test
    void invalidJsonStringAutonomyIsRejected() {
        JsonNode args = MAPPER.readTree("""
                {"name":"scout","focus":"exploration","autonomy":"not json"}
                """);

        assertThatThrownBy(() -> handler.call(PRINCIPAL, args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("autonomy");
    }

    @Test
    void absentAutonomyPassesNull() {
        JsonNode args = MAPPER.readTree("""
                {"name":"scout","focus":"exploration","tools":["search"]}
                """);

        handler.call(PRINCIPAL, args);

        Mockito.verify(writeToolService).registerAgent(
                eq("scout"), eq("exploration"), eq(null), eq(null), eq(null), eq(List.of("search")));
    }

    @Test
    void missingRequiredNameThrowsIllegalArgument() {
        JsonNode args = MAPPER.readTree("""
                {"focus":"exploration"}
                """);

        assertThatThrownBy(() -> handler.call(PRINCIPAL, args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing name");
    }
}
