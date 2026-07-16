package com.hivemem.auth;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.EmbeddingInfo;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for token management that close the gap between
 * the Python test_token_management.py (43 tests) and the existing Java coverage.
 *
 * <p>Focuses on: SHA-256 hashing verification, role-based tool permissions,
 * tool filtering per role, schema constraints, expiry edge cases,
 * concurrent/bulk token inserts, rate limiting over HTTP, and cache behavior.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(TokenManagementIntegrationTest.TestConfig.class)
class TokenManagementIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        EmbeddingClient testEmbeddingClient() {
            return new FixedEmbeddingClient();
        }
    }


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
    private DSLContext dslContext;

    @Autowired
    @Qualifier("dbTokenService")
    private TokenService dbTokenService;

    @Autowired
    private ToolPermissionService toolPermissionService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimiter rateLimiter;

    @MockitoBean(name = "httpEmbeddingClient")
    private EmbeddingClient embeddingClient;

    @BeforeEach
    void resetDatabase() {
        rateLimiter.clearAll();
        dslContext.execute("TRUNCATE TABLE api_tokens CASCADE");
        when(embeddingClient.getInfo()).thenReturn(new EmbeddingInfo("test-model", 1024));
    }

    // ── Schema & Hashing ────────────────────────────────────────────────

    @Test
    void apiTokensTableExists() {
        Record row = dslContext.fetchOne("""
                SELECT count(*) AS cnt FROM information_schema.tables
                WHERE table_name = 'api_tokens'
                """);
        assertThat(row).isNotNull();
        assertThat(row.get("cnt", Long.class)).isEqualTo(1L);
    }

    @Test
    void tokenHashIsSha256AndPlaintextIsNeverStored() throws Exception {
        String plaintext = "secret-token-value";
        String expectedHash = sha256(plaintext);

        dslContext.execute("""
                INSERT INTO api_tokens (token_hash, name, role)
                VALUES (?, ?, ?)
                """, expectedHash, "hash-test", "writer");

        Record row = dslContext.fetchOne(
                "SELECT token_hash FROM api_tokens WHERE name = ?", "hash-test");
        assertThat(row).isNotNull();
        assertThat(row.get("token_hash", String.class)).isEqualTo(expectedHash);

        // Plaintext must never appear in any column
        Record countRow = dslContext.fetchOne("""
                SELECT count(*) AS cnt FROM api_tokens
                WHERE token_hash = ? OR name = ?
                """, plaintext, plaintext);
        assertThat(countRow).isNotNull();
        assertThat(countRow.get("cnt", Long.class)).isZero();
    }

    @Test
    void duplicateNameRejectedByUniqueConstraint() throws Exception {
        dslContext.execute("""
                INSERT INTO api_tokens (token_hash, name, role)
                VALUES (?, ?, ?)
                """, sha256("tok-1"), "dup-test", "reader");

        assertThatThrownBy(() -> dslContext.execute("""
                INSERT INTO api_tokens (token_hash, name, role)
                VALUES (?, ?, ?)
                """, sha256("tok-2"), "dup-test", "admin"))
                .hasMessageContaining("api_tokens_name_key");
    }

    @Test
    void duplicateHashRejectedByUniqueConstraint() throws Exception {
        String hash = sha256("same-token");

        dslContext.execute("""
                INSERT INTO api_tokens (token_hash, name, role)
                VALUES (?, ?, ?)
                """, hash, "first-name", "reader");

        assertThatThrownBy(() -> dslContext.execute("""
                INSERT INTO api_tokens (token_hash, name, role)
                VALUES (?, ?, ?)
                """, hash, "second-name", "admin"))
                .hasMessageContaining("api_tokens_token_hash_key");
    }

    @Test
    void invalidRoleRejectedByCheckConstraint() {
        assertThatThrownBy(() -> dslContext.execute("""
                INSERT INTO api_tokens (token_hash, name, role)
                VALUES (?, ?, ?)
                """, sha256("bad-role-tok"), "bad-role-user", "superuser"))
                .hasMessageContaining("api_tokens_role_check");
    }

    // ── Role-based validation ───────────────────────────────────────────

    @Test
    void validateTokenReturnsCorrectRoleForEachAuthRole() throws Exception {
        for (AuthRole role : AuthRole.values()) {
            String name = "role-" + role.wireValue();
            String plaintext = "tok-" + role.wireValue();
            dslContext.execute("""
                    INSERT INTO api_tokens (token_hash, name, role)
                    VALUES (?, ?, ?)
                    """, sha256(plaintext), name, role.wireValue());

            var principal = dbTokenService.validateToken(plaintext);
            assertThat(principal)
                    .as("Validating token for role %s", role)
                    .isPresent();
            assertThat(principal.orElseThrow().role()).isEqualTo(role);
            assertThat(principal.orElseThrow().name()).isEqualTo(name);
        }
    }

    // ── Expiry edge cases ───────────────────────────────────────────────

    @Test
    void tokenExpiringInThePastFailsValidation() throws Exception {
        insertToken("past-expiry", "past-tok", "admin",
                OffsetDateTime.now().minusSeconds(1), null);

        assertThat(dbTokenService.validateToken("past-tok")).isEmpty();
    }

    @Test
    void tokenExpiringFarInFuturePassesValidation() throws Exception {
        insertToken("future-expiry", "future-tok", "writer",
                OffsetDateTime.now().plusDays(365), null);

        assertThat(dbTokenService.validateToken("future-tok")).isPresent();
    }

    @Test
    void tokenWithNullExpiryNeverExpires() throws Exception {
        insertToken("null-expiry", "null-exp-tok", "reader", null, null);

        assertThat(dbTokenService.validateToken("null-exp-tok")).isPresent();
    }

    // ── Tool permissions (ToolPermissionService) ────────────────────────

    @Test
    void adminSeesAllRegisteredTools() {
        Set<String> adminTools = toolPermissionService.allowedTools(AuthRole.ADMIN);
        // Admin must include every read, write, and admin tool
        assertThat(adminTools).contains(
                "search", "wake_up",
                "add_cell", "kg_add",
                "approve_pending", "health"
        );
    }

    @Test
    void readerSeesOnlyReadTools() {
        Set<String> readerTools = toolPermissionService.allowedTools(AuthRole.READER);
        assertThat(readerTools).contains("search", "wake_up",
                "pending_approvals", "list_agents");
        assertThat(readerTools).doesNotContain(
                "add_cell", "kg_add",
                "approve_pending", "health");
    }

    @Test
    void writerCannotApprove() {
        Set<String> writerTools = toolPermissionService.allowedTools(AuthRole.WRITER);
        assertThat(writerTools).contains("add_cell", "kg_add");
        assertThat(writerTools).doesNotContain("approve_pending", "health");
    }

    @Test
    void agentMatchesWriter() {
        Set<String> writerTools = toolPermissionService.allowedTools(AuthRole.WRITER);
        Set<String> agentTools = toolPermissionService.allowedTools(AuthRole.AGENT);
        assertThat(agentTools).isEqualTo(writerTools);
    }

    @Test
    void noUnknownToolsInRoles() {
        Set<String> allTools = toolPermissionService.allowedTools(AuthRole.ADMIN);
        for (AuthRole role : AuthRole.values()) {
            Set<String> roleTools = toolPermissionService.allowedTools(role);
            Set<String> unknown = new HashSet<>(roleTools);
            unknown.removeAll(allTools);
            assertThat(unknown)
                    .as("Role %s should have no tools outside ALL_TOOLS", role)
                    .isEmpty();
        }
    }

    // ── Tool filtering over HTTP (tools/list with different roles) ──────

    @Test
    void readerTokenSeesOnlyReadToolsOverHttp() throws Exception {
        insertToken("http-reader", "reader-http-tok", "reader",
                OffsetDateTime.now().plusHours(1), null);

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer reader-http-tok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[*].name", hasItem("search")))
                .andExpect(jsonPath("$.result.tools[*].name", hasItem("wake_up")))
                .andExpect(jsonPath("$.result.tools[*].name", not(hasItem("add_cell"))))
                .andExpect(jsonPath("$.result.tools[*].name", not(hasItem("approve_pending"))))
                .andExpect(jsonPath("$.result.tools[*].name", not(hasItem("health"))));
    }

    @Test
    void writerTokenSeesReadAndWriteButNotAdminOverHttp() throws Exception {
        insertToken("http-writer", "writer-http-tok", "writer",
                OffsetDateTime.now().plusHours(1), null);

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-http-tok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[*].name", hasItem("search")))
                .andExpect(jsonPath("$.result.tools[*].name", hasItem("add_cell")))
                .andExpect(jsonPath("$.result.tools[*].name", not(hasItem("approve_pending"))))
                .andExpect(jsonPath("$.result.tools[*].name", not(hasItem("health"))));
    }

    // ── Rate limiting over HTTP ─────────────────────────────────────────

    @Test
    void rateLimitReturns429AfterMaxFailedAttempts() throws Exception {
        // Exhaust the rate limiter with bad tokens
        for (int i = 0; i < RateLimiter.MAX_FAILED_ATTEMPTS; i++) {
            mockMvc.perform(post("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer bad-token-" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        // Next attempt should be rate-limited (429)
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer another-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                                """))
                .andExpect(status().is(429));
    }

    // ── Bulk token uniqueness ───────────────────────────────────────────

    @Test
    void bulkCreationProducesUniqueHashes() throws Exception {
        Set<String> hashes = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            String hash = sha256("bulk-token-" + i);
            hashes.add(hash);
            dslContext.execute("""
                    INSERT INTO api_tokens (token_hash, name, role)
                    VALUES (?, ?, ?)
                    """, hash, "bulk-" + i, "reader");
        }
        // All 20 hashes must be distinct
        assertThat(hashes).hasSize(20);

        Record countRow = dslContext.fetchOne(
                "SELECT count(*) AS cnt FROM api_tokens WHERE name LIKE 'bulk-%'");
        assertThat(countRow).isNotNull();
        assertThat(countRow.get("cnt", Long.class)).isEqualTo(20L);
    }

    // ── Tool call permission enforcement over HTTP ──────────────────────

    @Test
    void readerCallingWriteToolGetsForbiddenOverHttp() throws Exception {
        insertToken("perm-reader", "perm-reader-tok", "reader",
                OffsetDateTime.now().plusHours(1), null);

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer perm-reader-tok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                                 "params":{"name":"add_cell","arguments":{}}}
                                """))
                .andExpect(status().isForbidden());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

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

    // ── CRUD via TokenService API (parity with Python security.create_token / list_tokens / revoke_token) ─

    @Test
    void createTokenReturnsUsablePlaintextAndPersistsHashOnly() {
        String plaintext = dbTokenService.createToken("alpha", AuthRole.WRITER, null, null, null);

        assertThat(plaintext).isNotBlank();
        // Validating the returned plaintext succeeds
        var principal = dbTokenService.validateToken(plaintext);
        assertThat(principal).isPresent();
        assertThat(principal.orElseThrow().name()).isEqualTo("alpha");
        assertThat(principal.orElseThrow().role()).isEqualTo(AuthRole.WRITER);

        // Plaintext is nowhere in the DB — only the SHA-256 hash is stored
        Long rowsWithPlaintext = dslContext.fetchOne(
                "SELECT count(*) AS c FROM api_tokens WHERE token_hash = ?",
                plaintext
        ).get("c", Long.class);
        assertThat(rowsWithPlaintext).isZero();
    }

    @Test
    void createTokenWithExpiryPersistsExpiresAt() {
        dbTokenService.createToken("ephemeral", AuthRole.READER, 7, null, null);

        OffsetDateTime expiresAt = dslContext.fetchOne(
                "SELECT expires_at FROM api_tokens WHERE name = ?",
                "ephemeral"
        ).get("expires_at", OffsetDateTime.class);

        assertThat(expiresAt).isNotNull();
        assertThat(expiresAt).isAfter(OffsetDateTime.now().plusDays(6));
        assertThat(expiresAt).isBefore(OffsetDateTime.now().plusDays(8));
    }

    @Test
    void createTokenWithDuplicateNameRaises() {
        dbTokenService.createToken("clash", AuthRole.READER, null, null, null);

        assertThatThrownBy(() -> dbTokenService.createToken("clash", AuthRole.WRITER, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("clash");
    }

    @Test
    void createTokenProducesUniqueHashesForBulkInserts() {
        Set<String> plaintexts = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            plaintexts.add(dbTokenService.createToken("bulk-" + i, AuthRole.READER, null, null, null));
        }
        assertThat(plaintexts).hasSize(50);

        Long distinctHashes = dslContext.fetchOne("""
                SELECT count(DISTINCT token_hash) AS c FROM api_tokens WHERE name LIKE 'bulk-%'
                """).get("c", Long.class);
        assertThat(distinctHashes).isEqualTo(50L);
    }

    @Test
    void listTokensReturnsAllInCreationOrder() {
        dbTokenService.createToken("first", AuthRole.READER, null, null, null);
        dbTokenService.createToken("second", AuthRole.WRITER, null, null, null);
        dbTokenService.createToken("third", AuthRole.ADMIN, 30, null, null);

        var summaries = dbTokenService.listTokens(true, 100);

        assertThat(summaries).extracting(TokenSummary::name).containsExactly("first", "second", "third");
        assertThat(summaries).extracting(TokenSummary::role)
                .containsExactly(AuthRole.READER, AuthRole.WRITER, AuthRole.ADMIN);
        assertThat(summaries.get(2).expiresAt()).isNotNull();
    }

    @Test
    void listTokensRespectsLimit() {
        for (int i = 0; i < 10; i++) {
            dbTokenService.createToken("limit-" + i, AuthRole.READER, null, null, null);
        }

        var summaries = dbTokenService.listTokens(true, 3);

        assertThat(summaries).hasSize(3);
    }

    @Test
    void listTokensExcludeRevokedHidesRevoked() {
        dbTokenService.createToken("keep", AuthRole.READER, null, null, null);
        dbTokenService.createToken("gone", AuthRole.READER, null, null, null);
        dbTokenService.revokeToken("gone");

        var all = dbTokenService.listTokens(true, 100);
        var active = dbTokenService.listTokens(false, 100);

        assertThat(all).extracting(TokenSummary::name).contains("keep", "gone");
        assertThat(active).extracting(TokenSummary::name).containsExactly("keep");
    }

    @Test
    void listTokensMarksStatusCorrectly() throws Exception {
        // active
        dbTokenService.createToken("fresh", AuthRole.READER, null, null, null);
        // revoked
        dbTokenService.createToken("killed", AuthRole.READER, null, null, null);
        dbTokenService.revokeToken("killed");
        // expired: insert directly with past expires_at
        insertToken("old", "old-plaintext", "reader",
                OffsetDateTime.now().minusDays(1), null);

        var summaries = dbTokenService.listTokens(true, 100);

        assertThat(summaries).anySatisfy(s -> {
            assertThat(s.name()).isEqualTo("fresh");
            assertThat(s.status()).isEqualTo(TokenSummary.Status.ACTIVE);
        });
        assertThat(summaries).anySatisfy(s -> {
            assertThat(s.name()).isEqualTo("killed");
            assertThat(s.status()).isEqualTo(TokenSummary.Status.REVOKED);
        });
        assertThat(summaries).anySatisfy(s -> {
            assertThat(s.name()).isEqualTo("old");
            assertThat(s.status()).isEqualTo(TokenSummary.Status.EXPIRED);
        });
    }

    @Test
    void revokeTokenStopsFutureValidation() {
        String plaintext = dbTokenService.createToken("ephem", AuthRole.WRITER, null, null, null);
        assertThat(dbTokenService.validateToken(plaintext)).isPresent();

        dbTokenService.revokeToken("ephem");

        assertThat(dbTokenService.validateToken(plaintext)).isEmpty();
    }

    @Test
    void revokeUnknownTokenRaises() {
        assertThatThrownBy(() -> dbTokenService.revokeToken("does-not-exist"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    void doubleRevokeRaises() {
        dbTokenService.createToken("once", AuthRole.READER, null, null, null);
        dbTokenService.revokeToken("once");

        assertThatThrownBy(() -> dbTokenService.revokeToken("once"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already revoked");
    }

    @Test
    void getTokenInfoReturnsMetadataWithoutPlaintextOrHash() {
        dbTokenService.createToken("inspect", AuthRole.ADMIN, 14, null, null);

        var info = dbTokenService.getTokenInfo("inspect");

        assertThat(info).isPresent();
        TokenSummary summary = info.orElseThrow();
        assertThat(summary.name()).isEqualTo("inspect");
        assertThat(summary.role()).isEqualTo(AuthRole.ADMIN);
        assertThat(summary.createdAt()).isNotNull();
        assertThat(summary.expiresAt()).isNotNull();
        assertThat(summary.revokedAt()).isNull();
        assertThat(summary.status()).isEqualTo(TokenSummary.Status.ACTIVE);
    }

    @Test
    void getTokenInfoReturnsEmptyForUnknownName() {
        assertThat(dbTokenService.getTokenInfo("no-such-token")).isEmpty();
    }

    @Test
    void e2eTokenLifecycle() {
        // Create → validate → info → revoke → info (revoked) → validate (empty) → list (excludes when filtered)
        String plaintext = dbTokenService.createToken("lifecycle", AuthRole.WRITER, 30, null, null);

        assertThat(dbTokenService.validateToken(plaintext)).isPresent();
        assertThat(dbTokenService.getTokenInfo("lifecycle")
                .orElseThrow().status()).isEqualTo(TokenSummary.Status.ACTIVE);

        dbTokenService.revokeToken("lifecycle");

        assertThat(dbTokenService.getTokenInfo("lifecycle")
                .orElseThrow().status()).isEqualTo(TokenSummary.Status.REVOKED);
        assertThat(dbTokenService.validateToken(plaintext)).isEmpty();
        assertThat(dbTokenService.listTokens(false, 100))
                .extracting(TokenSummary::name).doesNotContain("lifecycle");
    }
}

// Not ported from Python (intentionally out of scope for this class):
// - test_agent_write_forces_pending / test_writer_write_is_committed / test_agent_fact_forces_pending:
//   domain logic tests, not token management (covered in WriteToolsIntegrationTest)
// - test_e2e_agent_writes_admin_approves: domain approval flow (covered in AgentFleetIntegrationTest)
// - test_cache_evicts_at_max_size: CachedTokenService is disabled in test profile; Caffeine eviction is a library concern
// - test_reader_tool_set_count / test_writer_tool_set_count / test_admin_tool_set_count: exact counts are brittle; covered by contains/doesNotContain assertions
// - test_filter_tools_for_admin: already covered by HttpTokenLifecycleIntegrationTest.validAdminTokenRoundTripsOverMcp
