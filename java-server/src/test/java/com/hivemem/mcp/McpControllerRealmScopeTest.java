package com.hivemem.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.RateLimiter;
import com.hivemem.auth.TokenService;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration coverage for the realm-ACL enforcement wired into
 * {@link McpController#handleToolCall}: deny (Optional&lt;String&gt; from
 * {@code realmDenial}), rewrite ({@code rewriteReadArgs}) and filter
 * ({@code filterReadResponse}) around the real tool handlers, driven end-to-end
 * through {@code POST /mcp}.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(McpControllerRealmScopeTest.TestConfig.class)
@Testcontainers
class McpControllerRealmScopeTest {

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
    private ObjectMapper objectMapper;

    @Autowired
    private RateLimiter rateLimiter;

    private static final String SCOPED_WRITER_TOKEN = "scoped-writer-token";
    private static final String UNSCOPED_WRITER_TOKEN = "unscoped-writer-token";

    @BeforeEach
    void resetDatabase() {
        rateLimiter.clearAll();
        dslContext.execute("TRUNCATE TABLE access_log, agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cells CASCADE");
        dslContext.execute("REFRESH MATERIALIZED VIEW cell_popularity");
    }

    // ── writes: WRITE_REALM_ARG_TOOLS (add_cell) ──────────────────────────

    @Test
    void addCellToOwnWriteRealmSucceeds() throws Exception {
        JsonNode body = callTool(SCOPED_WRITER_TOKEN, "add_cell", Map.of(
                "content", "scoped writer note", "realm", "dracul-research"));
        assertThat(body.path("error").isMissingNode()).isTrue();
        assertThat(body.path("result").path("isError").asBoolean(false)).isFalse();
    }

    @Test
    void addCellToReadOnlyRealmIsForbidden() throws Exception {
        MvcResult result = mockMvc.perform(toolCallRequest(SCOPED_WRITER_TOKEN, "add_cell", Map.of(
                        "content", "should not land", "realm", "dracul")))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("error").path("code").asInt()).isEqualTo(-32003);
    }

    @Test
    void addCellToForeignRealmIsForbidden() throws Exception {
        mockMvc.perform(toolCallRequest(SCOPED_WRITER_TOKEN, "add_cell", Map.of(
                        "content", "should not land", "realm", "personal")))
                .andExpect(status().isForbidden());
    }

    // ── writes: KG_GLOBAL_WRITES (kg_add) ─────────────────────────────────

    @Test
    void kgAddSucceedsForScopedWriter() throws Exception {
        JsonNode body = callTool(SCOPED_WRITER_TOKEN, "kg_add", Map.of(
                "subject", "Dracul", "predicate", "hunts", "object_", "PEAD"));
        assertThat(body.path("error").isMissingNode()).isTrue();
        assertThat(body.path("result").path("isError").asBoolean(false)).isFalse();
    }

    // ── writes: WRITE_DENY_WHEN_SCOPED ────────────────────────────────────

    @Test
    void reclassifyIsForbiddenForScopedWriter() throws Exception {
        mockMvc.perform(toolCallRequest(SCOPED_WRITER_TOKEN, "reclassify", Map.of()))
                .andExpect(status().isForbidden());
    }

    @Test
    void reviseCellIsForbiddenForScopedWriter() throws Exception {
        mockMvc.perform(toolCallRequest(SCOPED_WRITER_TOKEN, "revise_cell", Map.of()))
                .andExpect(status().isForbidden());
    }

    // ── reads: READ_RESPONSE_FILTER_TOOLS (search, get_cell) ──────────────

    @Test
    void searchOnlyReturnsRowsFromVisibleRealms() throws Exception {
        insertCell(UUID.fromString("00000000-0000-0000-0000-0000000d0001"),
                "dracul research cell about anomalies", "dracul-research");
        insertCell(UUID.fromString("00000000-0000-0000-0000-0000000d0002"),
                "dracul read-only cell about anomalies", "dracul");
        insertCell(UUID.fromString("00000000-0000-0000-0000-0000000d0003"),
                "personal cell about anomalies", "personal");

        JsonNode results = callToolResultArray(SCOPED_WRITER_TOKEN, "search", Map.of(
                "query", "anomalies", "limit", 10));

        assertThat(results).isNotEmpty();
        for (JsonNode row : results) {
            assertThat(row.path("realm").asText()).isIn("dracul-research", "dracul");
        }
    }

    // ── reads: READ_RESPONSE_FILTER_TOOLS (get_cell) ───────────────────────

    @Test
    void getCellOfForeignRealmReturnsEmpty() throws Exception {
        UUID personalCellId = UUID.fromString("00000000-0000-0000-0000-0000000d0010");
        insertCell(personalCellId, "personal note not visible", "personal");

        JsonNode body = callTool(SCOPED_WRITER_TOKEN, "get_cell", Map.of(
                "cell_id", personalCellId.toString()));
        assertThat(body.path("error").isMissingNode()).isTrue();
        String text = body.path("result").path("content").get(0).path("text").asText();
        assertThat(text).isEqualTo("null");
    }

    // ── reads: READ_ARG_PRECHECK_TOOLS (list) ──────────────────────────────

    @Test
    void listOfForeignRealmIsForbidden() throws Exception {
        mockMvc.perform(toolCallRequest(SCOPED_WRITER_TOKEN, "list", Map.of("realm", "personal")))
                .andExpect(status().isForbidden());
    }

    // ── C1: read-enumeration isolation (list / facet_count / list_cell_ids / status) ──

    /** Seed one cell in each of four realms; two visible (dracul-research, dracul), two foreign. */
    private void seedFourRealms() {
        insertCell(UUID.fromString("00000000-0000-0000-0000-0000000c0001"),
                "cell in dracul-research", "dracul-research");
        insertCell(UUID.fromString("00000000-0000-0000-0000-0000000c0002"),
                "cell in dracul", "dracul");
        insertCell(UUID.fromString("00000000-0000-0000-0000-0000000c0003"),
                "cell in personal", "personal");
        insertCell(UUID.fromString("00000000-0000-0000-0000-0000000c0004"),
                "cell in work", "work");
        dslContext.execute("REFRESH MATERIALIZED VIEW cell_popularity");
    }

    @Test
    void listNoArgsReturnsOnlyVisibleRealms() throws Exception {
        seedFourRealms();
        JsonNode realms = callToolResultArray(SCOPED_WRITER_TOKEN, "list", Map.of());
        assertThat(realms).isNotEmpty();
        for (JsonNode row : realms) {
            assertThat(row.path("value").asText()).isIn("dracul-research", "dracul");
        }
    }

    @Test
    void facetCountOverRealmReturnsNoForeignRealm() throws Exception {
        seedFourRealms();
        JsonNode tree = callToolResultArray(SCOPED_WRITER_TOKEN, "facet_count",
                Map.of("fields", List.of("realm")));
        JsonNode realmBuckets = tree.path("realm");
        assertThat(realmBuckets.isArray()).isTrue();
        assertThat(realmBuckets).isNotEmpty();
        for (JsonNode bucket : realmBuckets) {
            assertThat(bucket.path("value").asText()).isIn("dracul-research", "dracul");
        }
    }

    @Test
    void listCellIdsNoWhereReturnsNoForeignRows() throws Exception {
        seedFourRealms();
        JsonNode tree = callToolResultArray(SCOPED_WRITER_TOKEN, "list_cell_ids", Map.of());
        JsonNode ids = tree.path("ids");
        assertThat(ids.isArray()).isTrue();
        assertThat(ids).isNotEmpty();
        for (JsonNode row : ids) {
            assertThat(row.path("realm").asText()).isIn("dracul-research", "dracul");
        }
    }

    @Test
    void listCellIdsForeignWhereRealmInIsForbidden() throws Exception {
        seedFourRealms();
        mockMvc.perform(toolCallRequest(SCOPED_WRITER_TOKEN, "list_cell_ids",
                        Map.of("where", Map.of("realm_in", List.of("personal")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void statusRealmsAreFilteredToVisibleRealms() throws Exception {
        seedFourRealms();
        JsonNode body = callTool(SCOPED_WRITER_TOKEN, "status", Map.of());
        String text = body.path("result").path("content").get(0).path("text").asText();
        JsonNode snapshot = objectMapper.readTree(text);
        JsonNode realms = snapshot.path("realms");
        assertThat(realms.isArray()).isTrue();
        for (JsonNode realm : realms) {
            String value = realm.isObject() ? realm.path("value").asText() : realm.asText();
            assertThat(value).isIn("dracul-research", "dracul");
        }
    }

    @Test
    void addCellWithMissingRealmIsForbiddenForScopedWriter() throws Exception {
        mockMvc.perform(toolCallRequest(SCOPED_WRITER_TOKEN, "add_cell",
                        Map.of("content", "no realm named")))
                .andExpect(status().isForbidden());
    }

    // ── reads: READ_DENY_WHEN_SCOPED ───────────────────────────────────────

    @Test
    void traverseIsForbiddenForScopedReader() throws Exception {
        mockMvc.perform(toolCallRequest(SCOPED_WRITER_TOKEN, "traverse", Map.of(
                        "cell_id", UUID.randomUUID().toString())))
                .andExpect(status().isForbidden());
    }

    @Test
    void historyIsForbiddenForScopedReader() throws Exception {
        mockMvc.perform(toolCallRequest(SCOPED_WRITER_TOKEN, "history", Map.of(
                        "cell_id", UUID.randomUUID().toString())))
                .andExpect(status().isForbidden());
    }

    @Test
    void entityOverviewIsForbiddenForScopedReader() throws Exception {
        mockMvc.perform(toolCallRequest(SCOPED_WRITER_TOKEN, "entity_overview", Map.of("subject", "Dracul")))
                .andExpect(status().isForbidden());
    }

    @Test
    void dataQualityReportIsForbiddenForScopedReader() throws Exception {
        mockMvc.perform(toolCallRequest(SCOPED_WRITER_TOKEN, "data_quality_report", Map.of()))
                .andExpect(status().isForbidden());
    }

    @Test
    void readingListIsForbiddenForScopedReader() throws Exception {
        mockMvc.perform(toolCallRequest(SCOPED_WRITER_TOKEN, "reading_list", Map.of()))
                .andExpect(status().isForbidden());
    }

    // ── reads: READ_GLOBAL_TOOLS ────────────────────────────────────────────

    @Test
    void searchKgSucceedsForScopedReader() throws Exception {
        JsonNode body = callTool(SCOPED_WRITER_TOKEN, "search_kg", Map.of("query", "Dracul"));
        assertThat(body.path("error").isMissingNode()).isTrue();
    }

    @Test
    void timeMachineSucceedsForScopedReader() throws Exception {
        JsonNode body = callTool(SCOPED_WRITER_TOKEN, "time_machine", Map.of("subject", "Dracul"));
        assertThat(body.path("error").isMissingNode()).isTrue();
    }

    @Test
    void wakeUpSucceedsForScopedReader() throws Exception {
        JsonNode body = callTool(SCOPED_WRITER_TOKEN, "wake_up", Map.of());
        assertThat(body.path("error").isMissingNode()).isTrue();
    }

    // ── regression: unscoped WRITER behaves exactly as today ───────────────

    @Test
    void unscopedWriterAddCellToAnyRealmSucceeds() throws Exception {
        JsonNode body = callTool(UNSCOPED_WRITER_TOKEN, "add_cell", Map.of(
                "content", "unscoped note", "realm", "personal"));
        assertThat(body.path("error").isMissingNode()).isTrue();
        assertThat(body.path("result").path("isError").asBoolean(false)).isFalse();
    }

    @Test
    void unscopedWriterTraverseIsNotForbidden() throws Exception {
        MvcResult result = mockMvc.perform(toolCallRequest(UNSCOPED_WRITER_TOKEN, "traverse", Map.of(
                        "cell_id", UUID.randomUUID().toString())))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isNotEqualTo(403);
    }

    @Test
    void unscopedWriterSearchReturnsAllRealms() throws Exception {
        insertCell(UUID.fromString("00000000-0000-0000-0000-0000000e0001"),
                "unscoped dracul-research cell about widgets", "dracul-research");
        insertCell(UUID.fromString("00000000-0000-0000-0000-0000000e0002"),
                "unscoped personal cell about widgets", "personal");

        JsonNode results = callToolResultArray(UNSCOPED_WRITER_TOKEN, "search", Map.of(
                "query", "widgets", "limit", 10));

        List<String> realms = new java.util.ArrayList<>();
        for (JsonNode row : results) {
            realms.add(row.path("realm").asText());
        }
        assertThat(realms).contains("dracul-research", "personal");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder toolCallRequest(
            String token, String toolName, Map<String, Object> arguments) throws Exception {
        return post("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "id", 1,
                        "method", "tools/call",
                        "params", Map.of("name", toolName, "arguments", arguments)
                )));
    }

    private JsonNode callTool(String token, String toolName, Map<String, Object> arguments) throws Exception {
        MvcResult result = mockMvc.perform(toolCallRequest(token, toolName, arguments))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Parses the tool result's {@code content[0].text} (a JSON array) into a JsonNode. */
    private JsonNode callToolResultArray(String token, String toolName, Map<String, Object> arguments) throws Exception {
        JsonNode body = callTool(token, toolName, arguments);
        String text = body.path("result").path("content").get(0).path("text").asText();
        return objectMapper.readTree(text);
    }

    private void insertCell(UUID id, String content, String realm) {
        dslContext.execute(
                """
                INSERT INTO cells (
                    id, content, realm, signal, topic, importance, summary, status, created_by, created_at, valid_from, valid_until
                ) VALUES (?, ?, ?, 'facts', 'general', 3, ?, 'committed', ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """,
                id, content, realm, content, "scoped-writer",
                OffsetDateTime.parse("2026-04-03T10:00:00Z"),
                OffsetDateTime.parse("2026-04-03T10:00:00Z"), null
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @org.springframework.context.annotation.Primary
        TokenService tokenService() {
            return new com.hivemem.auth.support.FixedTokenService(token -> switch (token) {
                case SCOPED_WRITER_TOKEN -> Optional.of(new AuthPrincipal(
                        "scoped-writer", AuthRole.WRITER, null,
                        List.of("dracul-research", "dracul"),
                        List.of("dracul-research")));
                case UNSCOPED_WRITER_TOKEN -> Optional.of(new AuthPrincipal(
                        "unscoped-writer", AuthRole.WRITER));
                default -> Optional.empty();
            });
        }

        @Bean
        @org.springframework.context.annotation.Primary
        EmbeddingClient embeddingClient() {
            return new FixedEmbeddingClient();
        }
    }
}
