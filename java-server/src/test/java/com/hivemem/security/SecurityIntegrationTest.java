package com.hivemem.security;

import com.hivemem.auth.AuthRole;
import com.hivemem.auth.RateLimiter;
import com.hivemem.auth.ToolPermissionService;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.EmbeddingInfo;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.hivemem.auth.LoginController;
import org.springframework.mock.web.MockHttpSession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security regression tests ported from Python's test_security.py.
 *
 * <p>Tests in this class verify HTTP-level security behavior:
 * auth enforcement, tool permission gating, decision validation,
 * X-Forwarded-For anti-spoofing, and safe defaults.
 *
 * <p>Path-traversal at the validator level is covered by {@link PathSafetyTest}.
 * This class tests the HTTP integration layer on top of that.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(SecurityIntegrationTest.TestConfig.class)
class SecurityIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        EmbeddingClient testEmbeddingClient() {
            return new FixedEmbeddingClient();
        }
    }


    private static final String TOOLS_LIST_REQUEST = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list"}
            """;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem")
            .withUsername("hivemem")
            .withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null
                            ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig())
                            .withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DSLContext dslContext;

    @Autowired
    private RateLimiter rateLimiter;

    @MockitoBean(name = "httpEmbeddingClient")
    private EmbeddingClient embeddingClient;

    @BeforeEach
    void resetState() {
        rateLimiter.clearAll();
        dslContext.execute("TRUNCATE TABLE api_tokens CASCADE");
        when(embeddingClient.getInfo()).thenReturn(new EmbeddingInfo("test-model", 1024));
    }

    // ---- Helpers ----

    private void insertToken(String name, String plaintext, String role) throws Exception {
        insertToken(name, plaintext, role, OffsetDateTime.now().plusHours(1), null);
    }

    private void insertToken(
            String name,
            String plaintext,
            String role,
            OffsetDateTime expiresAt,
            OffsetDateTime revokedAt
    ) throws Exception {
        dslContext.execute("""
                INSERT INTO api_tokens (token_hash, name, role, expires_at, revoked_at)
                VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz)
                """, sha256(plaintext), name, role, expiresAt, revokedAt);
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String toolsCallRequest(String toolName, String argumentsJson) {
        return """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                """.formatted(toolName, argumentsJson);
    }

    // ================================================================
    // CRIT-2: Tool filtering at tools/list (one test per role)
    // ================================================================

    @Nested
    class ToolListFiltering {

        @Test
        void readerSees20Tools() throws Exception {
            insertToken("reader-user", "reader-token", "reader");

            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer reader-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TOOLS_LIST_REQUEST))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.tools", hasSize(20)));
        }

        @Test
        void writerSees37Tools() throws Exception {
            insertToken("writer-user", "writer-token", "writer");

            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TOOLS_LIST_REQUEST))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.tools", hasSize(41)));
        }

        @Test
        void agentSees41Tools() throws Exception {
            insertToken("agent-user", "agent-token", "agent");

            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer agent-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TOOLS_LIST_REQUEST))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.tools", hasSize(41)));
        }

        @Test
        void adminSees45Tools() throws Exception {
            insertToken("admin-user", "admin-token", "admin");

            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TOOLS_LIST_REQUEST))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.tools", hasSize(45)));
        }
    }

    // ================================================================
    // CRIT-2: Tool call enforcement (role matrix via HTTP)
    // ================================================================

    @Nested
    class ToolCallEnforcement {

        @Test
        void readerCannotCallWriteTool() throws Exception {
            insertToken("reader-user", "reader-token", "reader");

            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer reader-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toolsCallRequest("add_cell", "{}")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(-32003))
                    .andExpect(jsonPath("$.error.message").value("Tool not permitted: add_cell"));
        }

        @Test
        void readerCannotCallAdminTool() throws Exception {
            insertToken("reader-user", "reader-token", "reader");

            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer reader-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toolsCallRequest("approve_pending", "{}")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(-32003));
        }

        @Test
        void writerCannotCallAdminTool() throws Exception {
            insertToken("writer-user", "writer-token", "writer");

            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toolsCallRequest("approve_pending", "{}")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(-32003));
        }

        @Test
        void agentCannotCallApprovePending() throws Exception {
            insertToken("agent-user", "agent-token", "agent");

            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer agent-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toolsCallRequest("approve_pending", "{}")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(-32003));
        }
    }

    // ================================================================
    // CRIT-3: Decision allowlist on approve_pending
    // ================================================================

    @Nested
    class DecisionValidation {

        @ParameterizedTest
        @ValueSource(strings = {"deleted", "banana", "admin_override", "pending"})
        void rejectsInvalidDecision(String invalidDecision) throws Exception {
            insertToken("admin-user", "admin-token", "admin");

            String body = toolsCallRequest("approve_pending",
                    """
                    {"ids":[],"decision":"%s"}
                    """.formatted(invalidDecision));

            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value(-32602))
                    .andExpect(jsonPath("$.error.message").value(
                            org.hamcrest.Matchers.containsString("Invalid decision")));
        }

        @ParameterizedTest
        @ValueSource(strings = {"committed", "rejected"})
        void acceptsValidDecision(String validDecision) throws Exception {
            insertToken("admin-user", "admin-token", "admin");

            // Call with empty ids list -- should succeed without error (no-op)
            String body = toolsCallRequest("approve_pending",
                    """
                    {"ids":[],"decision":"%s"}
                    """.formatted(validDecision));

            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.error").doesNotExist());
        }
    }

    // ================================================================
    // IMP-1: X-Forwarded-For anti-spoofing
    // ================================================================

    @Nested
    class XForwardedForAntiSpoofing {

        /**
         * AuthFilter uses request.getRemoteAddr() (line 42), never X-Forwarded-For.
         * This test confirms that sending an XFF header with a spoofed IP does not
         * bypass rate limiting -- the rate limiter still tracks the real client IP.
         */
        @Test
        void xffDoesNotAffectRateLimitBucketing() throws Exception {
            // Exhaust rate limit with 5 failures (no valid token)
            for (int i = 0; i < 5; i++) {
                mockMvc.perform(post("/mcp")
                                .header("X-Forwarded-For", "1.2.3.4")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(TOOLS_LIST_REQUEST))
                        .andExpect(status().isUnauthorized());
            }

            // The 6th request should be rate-limited despite different XFF
            mockMvc.perform(post("/mcp")
                            .header("X-Forwarded-For", "5.6.7.8")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TOOLS_LIST_REQUEST))
                    .andExpect(status().is(429));
        }

        /**
         * Even with a valid token, XFF must not influence the client identity
         * used for rate-limit bucketing. The valid token should succeed because
         * rate limiting is keyed on the real remote address, not the spoofed XFF.
         */
        @Test
        void validTokenSucceedsRegardlessOfXff() throws Exception {
            insertToken("admin-user", "admin-token", "admin");

            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                            .header("X-Forwarded-For", "1.2.3.4")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TOOLS_LIST_REQUEST))
                    .andExpect(status().isOk());
        }
    }

    // ================================================================
    // IMP-2: Safe defaults (unauthenticated requests)
    // ================================================================

    @Nested
    class SafeDefaults {

        @Test
        void noAuthorizationHeaderReturns401() throws Exception {
            mockMvc.perform(post("/mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TOOLS_LIST_REQUEST))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void emptyBearerTokenReturns401() throws Exception {
            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer ")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TOOLS_LIST_REQUEST))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void basicAuthSchemeReturns401() throws Exception {
            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Basic abc123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TOOLS_LIST_REQUEST))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void invalidTokenReturns401() throws Exception {
            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer nonexistent-token-abc123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TOOLS_LIST_REQUEST))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * Confirms that unauthenticated requests never reach the controller.
         * The AuthFilter rejects before any principal is set, so the default
         * behavior is denial -- no accidental admin access.
         */
        @Test
        void unauthenticatedRequestNeverReachesController() throws Exception {
            // If this returned 200, it would mean the request bypassed auth
            mockMvc.perform(post("/mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toolsCallRequest("health", "{}")))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ================================================================
    // McpEndpointAuth: regression + new session-cookie path
    // ================================================================

    @Nested
    class McpEndpointAuth {

        @Test
        void bearerTokenStillWorksAfterSessionFilterAdded() throws Exception {
            insertToken("admin-user", "admin-token", "admin");

            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TOOLS_LIST_REQUEST))
                    .andExpect(status().isOk());
        }

        @Test
        void noAuthReturns401AfterSessionFilterAdded() throws Exception {
            mockMvc.perform(post("/mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TOOLS_LIST_REQUEST))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void sessionCookieGrantsAccessToMcp() throws Exception {
            insertToken("admin-user", "admin-token", "admin");

            MockHttpSession session = new MockHttpSession();
            session.setAttribute(LoginController.SESSION_TOKEN_KEY, "admin-token");

            mockMvc.perform(post("/mcp")
                            .session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TOOLS_LIST_REQUEST))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.tools").isArray());
        }
    }

    // ================================================================
    // Unit tests: ToolPermissionService (no Spring context needed)
    // ================================================================

    @Nested
    class ToolPermissionServiceTests {

        private final ToolPermissionService service = new ToolPermissionService();

        @Test
        void readerCannotAccessWriteTools() {
            assertThat(service.isAllowed(AuthRole.READER, "add_cell")).isFalse();
            assertThat(service.isAllowed(AuthRole.READER, "kg_add")).isFalse();
            assertThat(service.isAllowed(AuthRole.READER, "add_tunnel")).isFalse();
        }

        @Test
        void readerCannotAccessAdminTools() {
            assertThat(service.isAllowed(AuthRole.READER, "approve_pending")).isFalse();
            assertThat(service.isAllowed(AuthRole.READER, "health")).isFalse();
        }

        @Test
        void writerCannotAccessAdminTools() {
            assertThat(service.isAllowed(AuthRole.WRITER, "approve_pending")).isFalse();
            assertThat(service.isAllowed(AuthRole.WRITER, "health")).isFalse();
        }

        @Test
        void agentCannotApproveOwnSuggestions() {
            assertThat(service.isAllowed(AuthRole.AGENT, "approve_pending")).isFalse();
            assertThat(service.isAllowed(AuthRole.AGENT, "health")).isFalse();
        }

        @Test
        void adminCanCallEveryTool() {
            Set<String> adminTools = service.allowedTools(AuthRole.ADMIN);
            for (String tool : adminTools) {
                assertThat(service.isAllowed(AuthRole.ADMIN, tool))
                        .as("Admin must be allowed to call %s", tool)
                        .isTrue();
            }
            // Spot-check that admin set includes admin-only tools
            assertThat(adminTools).contains(
                    "approve_pending",
                    "health"
            );
        }

        @Test
        void allAdminToolsExcludedFromWriterAndAgent() {
            Set<String> writerTools = service.allowedTools(AuthRole.WRITER);
            Set<String> agentTools = service.allowedTools(AuthRole.AGENT);

            for (String adminTool : Set.of(
                    "approve_pending", "health")) {
                assertThat(writerTools)
                        .as("Admin tool %s must not appear in writer role", adminTool)
                        .doesNotContain(adminTool);
                assertThat(agentTools)
                        .as("Admin tool %s must not appear in agent role", adminTool)
                        .doesNotContain(adminTool);
            }
        }

        @Test
        void nullRoleReturnsEmptyToolSet() {
            assertThat(service.allowedTools(null)).isEmpty();
            assertThat(service.isAllowed(null, "status")).isFalse();
        }
    }
}

// Not ported from test_security.py:
//
// test_default_identity_is_reader (IMP-2)
//   -- Python uses a ContextVar default; Java AuthFilter rejects unauthenticated
//      requests before any principal is set. No ContextVar equivalent.
//      Covered indirectly by SafeDefaults.unauthenticatedRequestNeverReachesController.
//
// test_log_access_no_accessed_by_param (IMP-3)
//   -- log_access is no longer a client-callable tool; access is logged automatically
//      on every successful get_drawer call inside ReadToolService. The principal is
//      always injected from the auth token, never from client JSON.
//
// test_token_hash_not_in_list / test_token_hash_not_in_info
//   -- These test the token management CLI / admin API which is not yet ported
//      to the Java server. The DB column is never selected in DbTokenService.
//
// test_allowed_import_dirs_exist_or_are_safe / test_path_traversal_*
//   -- Mining tools (mine_file, mine_directory) have been removed from the MCP surface.
