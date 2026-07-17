package com.hivemem.web;

import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.mcp.McpResponse;
import com.hivemem.mcp.ToolCallDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Focused controller unit test with a mocked {@link ToolCallDispatcher}. A full-stack
 * {@code @SpringBootTest} isn't meaningful yet: {@code HumanAuthFilter} still guards
 * every {@code /api/**} request and, absent a session, never populates
 * {@link AuthFilter#PRINCIPAL_ATTRIBUTE} — so a bare POST would just 401 before ever
 * reaching this controller. HumanAuthFilter (Task 6) is what actually populates the
 * principal for {@code /api/tools/call} callers (Access JWT or session cookie). This
 * test instead verifies the controller's own contract in isolation: it reads the
 * principal request attribute {@code AuthFilter} already defines and delegates to the
 * dispatcher exactly like {@code McpController} does for {@code /mcp}.
 */
class ApiToolsControllerTest {

    private ToolCallDispatcher dispatcher;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dispatcher = mock(ToolCallDispatcher.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ApiToolsController(dispatcher)).build();
    }

    @Test
    void delegatesToDispatcherWithPrincipalFromRequestAttribute() throws Exception {
        AuthPrincipal principal = new AuthPrincipal("alice", AuthRole.READER);
        when(dispatcher.dispatch(eq(principal), eq("1"), any(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(McpResponse.toolResult("1", "{}")));

        mockMvc.perform(post("/api/tools/call")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":"1","method":"tools/call",
                                 "params":{"name":"search","arguments":{}}}
                                """)
                        .requestAttr(AuthFilter.PRINCIPAL_ATTRIBUTE, principal))
                .andExpect(status().isOk());

        verify(dispatcher).dispatch(eq(principal), eq("1"), any(JsonNode.class));
    }
}
