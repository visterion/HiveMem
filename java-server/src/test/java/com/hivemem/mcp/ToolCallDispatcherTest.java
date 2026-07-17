package com.hivemem.mcp;

import tools.jackson.databind.ObjectMapper;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The authorization gates (role permission, realm denial, embedding-migration gate,
 * read-argument rewrite, response filter) must live in the dispatcher, not in the
 * controller — otherwise /api/tools/call would bypass them entirely.
 */
class ToolCallDispatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void readerCallingWriteToolIsForbidden() throws Exception {
        ToolCallDispatcher dispatcher = TestDispatchers.withTools("add_cell");
        AuthPrincipal reader = new AuthPrincipal("r", AuthRole.READER);

        var params = MAPPER.readTree("""
                {"name":"add_cell","arguments":{}}
                """);

        var response = dispatcher.dispatch(reader, 1, params);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void missingToolNameIsInvalidParams() throws Exception {
        ToolCallDispatcher dispatcher = TestDispatchers.withTools("add_cell");
        AuthPrincipal admin = new AuthPrincipal("a", AuthRole.ADMIN);

        var response = dispatcher.dispatch(admin, 1, MAPPER.readTree("{}"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(MAPPER.writeValueAsString(response.getBody())).contains("Missing tool name");
    }
}
