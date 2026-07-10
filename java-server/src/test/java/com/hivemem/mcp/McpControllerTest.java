package com.hivemem.mcp;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.TokenService;
import com.hivemem.auth.ToolPermissionService;
import com.hivemem.embedding.EmbeddingMigrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextBeforeModesTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.test.context.web.ServletTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.startsWith;
import org.springframework.test.web.servlet.MvcResult;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = McpControllerTest.TestConfig.class)
@TestExecutionListeners(
        listeners = {
                ServletTestExecutionListener.class,
                DirtiesContextBeforeModesTestExecutionListener.class,
                DependencyInjectionTestExecutionListener.class,
                DirtiesContextTestExecutionListener.class
        },
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS
)
@WebAppConfiguration
class McpControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AuthFilter authFilter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(authFilter)
                .build();
    }

    @Test
    void postMcpToolsListReturnsVisibleToolsForAuthenticatedPrincipal() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools").isArray())
                .andExpect(jsonPath("$.result.tools[0].name").value("status"));
    }

    @Test
    void postMcpWithUnknownMethodReturnsMethodNotFoundError() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":2,"method":"does/not-exist"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32601));
    }

    @Test
    void postMcpToolsCallReturnsStructuredJsonContent() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":3,
                                  "method":"tools/call",
                                  "params":{"name":"status","arguments":{}}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.content[0].text").isString());
    }

    @Test
    void postMcpToolsCallWithoutRequiredArgumentsReturnsInvalidParams() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":4,
                                  "method":"tools/call",
                                  "params":{"name":"search","arguments":{}}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Missing query"));
    }

    @Test
    void postMcpToolsCallExecutionFailureReturnsIsErrorToolResult() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":30,
                                  "method":"tools/call",
                                  "params":{"name":"add_cell","arguments":{"msg":"boom"}}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text").value("boom"));
    }

    @Test
    void postMcpToolsCallExecutionFailureWithoutMessageFallsBackToClassName() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":31,
                                  "method":"tools/call",
                                  "params":{"name":"add_cell","arguments":{}}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text").value("IllegalStateException"));
    }

    @Test
    void postMcpToolsCallWithUnregisteredToolReturnsInvalidParams() throws Exception {
        // get_cell is permitted for the writer role but has no handler in this test config.
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":32,
                                  "method":"tools/call",
                                  "params":{"name":"get_cell","arguments":{}}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Unknown tool: get_cell"));
    }

    @Test
    void responseSerializesIdEvenWhenNull() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":null,"method":"foo/bar"}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"id\":null")))
                .andExpect(jsonPath("$.error.code").value(-32601));
    }

    @Test
    void postMcpToolsCallWithoutPermissionReturnsForbiddenError() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer reader-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":5,
                                  "method":"tools/call",
                                  "params":{"name":"add_cell","arguments":{}}
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(-32003))
                .andExpect(jsonPath("$.error.message").value("Tool not permitted: add_cell"));
    }

    @Test
    void postMcpToolsCallWithBlankToolNameReturnsInvalidParams() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":6,
                                  "method":"tools/call",
                                  "params":{"name":"   ","arguments":{}}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Missing tool name"));
    }

    // --- MCP Protocol Compliance Tests ---

    @Test
    void initializeReturnsProtocolVersionAndSessionHeader() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":10,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"))
                .andExpect(jsonPath("$.result.capabilities.tools").exists())
                .andExpect(jsonPath("$.result.serverInfo.name").value("hivemem"))
                .andExpect(header().exists("Mcp-Session-Id"))
                .andExpect(header().string("Mcp-Session-Id", org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyOrNullString())));
    }

    @Test
    void initializeResponseOmitsErrorField() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":11,"method":"initialize","params":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void notificationInitializedReturns202WithEmptyBody() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","method":"notifications/initialized"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
    }

    @Test
    void notificationCancelledReturns202() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":99,"reason":"user cancelled"}}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
    }

    @Test
    void getSseEndpointReturns200WithEventStream() throws Exception {
        // SseEmitter is async; MockMvc starts the async request.
        // We only verify that the endpoint accepts GET and starts an async response
        // (not 405 like before the fix).
        mockMvc.perform(get("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());
    }

    @Test
    void deleteMcpReturns200() throws Exception {
        mockMvc.perform(delete("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token"))
                .andExpect(status().isOk());
    }

    @Test
    void pingReturnsEmptyResult() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":12,"method":"ping"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").isMap())
                .andExpect(jsonPath("$.result").isEmpty())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void toolsListExposesNonEmptyInputSchemaPropertiesForParameterisedTools() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":20,"method":"tools/list"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[1].name").value("search"))
                .andExpect(jsonPath("$.result.tools[1].inputSchema.properties.query").exists())
                .andExpect(jsonPath("$.result.tools[1].inputSchema.properties.limit").exists())
                .andExpect(jsonPath("$.result.tools[1].inputSchema.required").isArray());
    }

    @Test
    void unknownMethodReturnsMethodNotFound() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":13,"method":"foo/bar"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32601));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({
            AuthFilter.class,
            com.hivemem.auth.RateLimiter.class,
            ToolPermissionService.class,
            ToolRegistry.class,
            McpController.class
    })
    static class TestConfig {

        @Bean
        EmbeddingMigrationService embeddingMigrationService() {
            return Mockito.mock(EmbeddingMigrationService.class);
        }

        @Bean
        @org.springframework.context.annotation.Primary
        TokenService tokenService() {
            return new com.hivemem.auth.support.FixedTokenService(token -> switch (token) {
                case "good-token" -> Optional.of(new AuthPrincipal("token-1", AuthRole.WRITER));
                case "reader-token" -> Optional.of(new AuthPrincipal("token-2", AuthRole.READER));
                default -> Optional.empty();
            });
        }

        @Bean
        @Order(1)
        ToolHandler statusToolHandler() {
            return new ToolHandler() {
                @Override
                public String name() {
                    return "status";
                }

                @Override
                public String description() {
                    return "Counts of drawers, facts, tunnels, wings list, and last activity.";
                }

                @Override
                public Object call(AuthPrincipal principal, JsonNode arguments) {
                    return java.util.Map.of(
                            "type", "json",
                            "status", "ok",
                            "principal", principal.name()
                    );
                }
            };
        }

        @Bean
        @Order(3)
        ToolHandler failingToolHandler() {
            return new ToolHandler() {
                @Override
                public String name() {
                    return "add_cell";
                }

                @Override
                public String description() {
                    return "Always fails; exercises the isError tool-result path.";
                }

                @Override
                public Object call(AuthPrincipal principal, JsonNode arguments) {
                    if (arguments != null && arguments.hasNonNull("msg")) {
                        throw new RuntimeException(arguments.get("msg").asText());
                    }
                    throw new IllegalStateException();
                }
            };
        }

        @Bean
        @Order(2)
        ToolHandler searchToolHandler() {
            return new ToolHandler() {
                @Override
                public String name() {
                    return "search";
                }

                @Override
                public String description() {
                    return "5-signal ranked search.";
                }

                @Override
                public java.util.Map<String, Object> inputSchema() {
                    return ToolInputSchema.object()
                            .requiredString("query", "Full-text search query")
                            .optionalInteger("limit", "Maximum results")
                            .build();
                }

                @Override
                public Object call(AuthPrincipal principal, JsonNode arguments) {
                    if (arguments == null || !arguments.hasNonNull("query")) {
                        throw new IllegalArgumentException("Missing query");
                    }
                    return java.util.Map.of(
                            "type", "json",
                            "query", arguments.get("query").asText(),
                            "results", java.util.List.of()
                    );
                }
            };
        }
    }
}
