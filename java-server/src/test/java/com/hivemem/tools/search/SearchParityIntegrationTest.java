package com.hivemem.tools.search;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.RateLimiter;
import com.hivemem.auth.TokenService;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.write.AdminToolService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SearchParityIntegrationTest.TestConfig.class)
@Testcontainers
class SearchParityIntegrationTest {

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

    @Autowired
    private AdminToolService adminToolService;

    @BeforeEach
    void resetDatabase() {
        rateLimiter.clearAll();
        dslContext.execute("TRUNCATE TABLE access_log, agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cells CASCADE");
        dslContext.execute("REFRESH MATERIALIZED VIEW cell_popularity");
    }

    @Test
    void rankedSearchReturnsAllScoreComponents() throws Exception {
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000801"),
                "PostgreSQL vector search with pgvector",
                "eng",
                "facts",
                "db",
                2,
                "pgvector search",
                "committed",
                OffsetDateTime.parse("2026-04-03T10:00:00Z")
        );

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "vector search",
                "limit", 10,
                "include", List.of("scores")
        ));

        JsonNode first = results.get(0);
        assertThat(first.path("score_semantic").isNumber()).isTrue();
        assertThat(first.path("score_keyword").isNumber()).isTrue();
        assertThat(first.path("score_recency").isNumber()).isTrue();
        assertThat(first.path("score_importance").isNumber()).isTrue();
        assertThat(first.path("score_popularity").isNumber()).isTrue();
        assertThat(first.path("score_total").isNumber()).isTrue();
        assertThat(first.path("score_total").asDouble()).isGreaterThan(0.0d);
    }

    @Test
    void defaultSearchOmitsPerSignalScoresButKeepsTotalAndConfidence() throws Exception {
        // backlog #12: by default the response no longer carries the five per-signal
        // sub-scores; score_total and confidence_level must still always be present.
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000d01"),
                "PostgreSQL vector search with pgvector",
                "eng", "facts", "db", 2, "pgvector search", "committed",
                OffsetDateTime.parse("2026-04-03T10:00:00Z")
        );

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "vector search",
                "limit", 10
        ));

        JsonNode first = results.get(0);
        assertThat(first.has("score_total")).isTrue();
        assertThat(first.path("score_total").isNumber()).isTrue();
        assertThat(first.has("confidence_level")).isTrue();
        assertThat(first.has("score_semantic")).isFalse();
        assertThat(first.has("score_keyword")).isFalse();
        assertThat(first.has("score_recency")).isFalse();
        assertThat(first.has("score_importance")).isFalse();
        assertThat(first.has("score_popularity")).isFalse();
    }

    @Test
    void includeScoresRestoresPerSignalScores() throws Exception {
        // backlog #12: include:["scores"] re-adds the five per-signal sub-scores
        // while score_total + confidence_level remain present.
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000d02"),
                "PostgreSQL vector search with pgvector",
                "eng", "facts", "db", 2, "pgvector search", "committed",
                OffsetDateTime.parse("2026-04-03T10:00:00Z")
        );

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "vector search",
                "limit", 10,
                "include", List.of("scores")
        ));

        JsonNode first = results.get(0);
        assertThat(first.path("score_semantic").isNumber()).isTrue();
        assertThat(first.path("score_keyword").isNumber()).isTrue();
        assertThat(first.path("score_recency").isNumber()).isTrue();
        assertThat(first.path("score_importance").isNumber()).isTrue();
        assertThat(first.path("score_popularity").isNumber()).isTrue();
        assertThat(first.path("score_total").isNumber()).isTrue();
        assertThat(first.has("confidence_level")).isTrue();
    }

    @Test
    void includeScoresWithContentYieldsBoth() throws Exception {
        // backlog #12: "scores" is a pseudo-token that must not be forwarded to
        // CellFieldSelection.forSearch; mixed with a real cell field like content
        // it yields both the content field and the per-signal score keys.
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000d03"),
                "PostgreSQL vector search with pgvector",
                "eng", "facts", "db", 2, "pgvector search", "committed",
                OffsetDateTime.parse("2026-04-03T10:00:00Z")
        );

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "vector search",
                "limit", 10,
                "include", List.of("scores", "content")
        ));

        JsonNode first = results.get(0);
        assertThat(first.has("content")).isTrue();
        assertThat(first.path("content").asText()).contains("PostgreSQL vector search");
        assertThat(first.path("score_semantic").isNumber()).isTrue();
        assertThat(first.path("score_total").isNumber()).isTrue();
        assertThat(first.has("confidence_level")).isTrue();
    }

    @Test
    void rankedSearchHonorsWingFilter() throws Exception {
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000811"),
                "Engineering topic",
                "eng",
                "facts",
                "planning",
                3,
                "Engineering topic",
                "committed",
                OffsetDateTime.parse("2026-04-03T11:00:00Z")
        );
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000812"),
                "Personal topic",
                "personal",
                "facts",
                "planning",
                3,
                "Personal topic",
                "committed",
                OffsetDateTime.parse("2026-04-03T11:00:00Z")
        );

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "topic",
                "realm", "eng"
        ));

        assertThat(results).hasSize(1);
        assertThat(textValues(results, "realm")).containsExactly("eng");
    }

    @Test
    void softDeprecatedFlatRealmParamIsStillHonored() throws Exception {
        // backlog #10: the flat realm/signal/topic/tags/status params were removed
        // from the advertised inputSchema() but are still parsed in call(). This
        // guards that an existing caller passing the unadvertised flat 'realm'
        // param keeps getting realm-restricted results.
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000851"),
                "Soft deprecated engineering note",
                "eng",
                "facts",
                "planning",
                3,
                "Engineering note",
                "committed",
                OffsetDateTime.parse("2026-04-03T11:30:00Z")
        );
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000852"),
                "Soft deprecated personal note",
                "personal",
                "facts",
                "planning",
                3,
                "Personal note",
                "committed",
                OffsetDateTime.parse("2026-04-03T11:30:00Z")
        );

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "note",
                "realm", "eng"
        ));

        assertThat(results).hasSize(1);
        assertThat(textValues(results, "realm")).containsExactly("eng");
    }

    @Test
    void flatFilterParamCombinedWithWhereIsRejected() throws Exception {
        // backlog #10: mutual-exclusivity between the (soft-deprecated) flat filter
        // params and the 'where' object is still enforced in call(). The thrown
        // IllegalArgumentException surfaces as a JSON-RPC invalidParams error
        // (HTTP 400) via McpController.
        MvcResult result = mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jsonrpc", "2.0",
                                "id", 1,
                                "method", "tools/call",
                                "params", Map.of(
                                        "name", "search",
                                        "arguments", Map.of(
                                                "query", "note",
                                                "realm", "eng",
                                                "where", Map.of("signal", "facts")
                                        )
                                )
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("result").isMissingNode() || body.path("result").isNull()).isTrue();
        assertThat(body.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(body.path("error").path("message").asText())
                .isEqualTo("where is mutually exclusive with flat filter params");
    }

    @Test
    void rankedSearchHonorsHallFilter() throws Exception {
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000821"),
                "Search discovery note",
                "eng",
                "discoveries",
                "facts",
                2,
                "Search discovery",
                "committed",
                OffsetDateTime.parse("2026-04-03T12:00:00Z")
        );
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000822"),
                "Search fact note",
                "eng",
                "facts",
                "facts",
                2,
                "Search fact",
                "committed",
                OffsetDateTime.parse("2026-04-03T12:00:00Z")
        );

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "search",
                "signal", "discoveries"
        ));

        assertThat(results).hasSize(1);
        assertThat(textValues(results, "signal")).containsExactly("discoveries");
    }

    @Test
    void popularityAffectsRankingDeterministically() throws Exception {
        UUID popularDrawerId = UUID.fromString("00000000-0000-0000-0000-000000000831");
        UUID regularDrawerId = UUID.fromString("00000000-0000-0000-0000-000000000832");

        insertDrawer(
                popularDrawerId,
                "Docker knowledge alpha",
                "eng",
                "facts",
                "infra",
                2,
                "Docker knowledge alpha",
                "committed",
                OffsetDateTime.parse("2026-04-03T13:00:00Z")
        );
        insertDrawer(
                regularDrawerId,
                "Docker knowledge beta",
                "eng",
                "facts",
                "infra",
                2,
                "Docker knowledge beta",
                "committed",
                OffsetDateTime.parse("2026-04-03T13:00:00Z")
        );

        for (int i = 0; i < 5; i++) {
            adminToolService.logAccess(popularDrawerId, null, "admin");
        }
        adminToolService.refreshPopularity();

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "docker knowledge",
                "weight_semantic", 0.0d,
                "weight_keyword", 0.0d,
                "weight_recency", 0.0d,
                "weight_importance", 0.0d,
                "weight_popularity", 1.0d,
                "include", List.of("scores")
        ));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).path("id").asText()).isEqualTo(popularDrawerId.toString());
        assertThat(results.get(0).path("score_popularity").asDouble())
                .isGreaterThan(results.get(1).path("score_popularity").asDouble());
        assertThat(results.get(0).path("score_total").asDouble())
                .isEqualTo(results.get(0).path("score_popularity").asDouble());
    }

    @Test
    void pendingDrawersAreExcludedFromRankedSearch() throws Exception {
        UUID committedDrawerId = UUID.fromString("00000000-0000-0000-0000-000000000841");
        UUID pendingDrawerId = UUID.fromString("00000000-0000-0000-0000-000000000842");

        insertDrawer(
                committedDrawerId,
                "Topic drawer committed",
                "eng",
                "facts",
                "planning",
                2,
                "Committed topic",
                "committed",
                OffsetDateTime.parse("2026-04-03T14:00:00Z")
        );
        insertDrawer(
                pendingDrawerId,
                "Topic drawer pending",
                "eng",
                "facts",
                "planning",
                2,
                "Pending topic",
                "pending",
                OffsetDateTime.parse("2026-04-03T14:00:00Z")
        );

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "topic drawer"
        ));

        assertThat(textValues(results, "id")).contains(committedDrawerId.toString());
        assertThat(textValues(results, "id")).doesNotContain(pendingDrawerId.toString());
    }

    private JsonNode callTool(String token, String toolName, Map<String, Object> arguments) throws Exception {
        MvcResult result = mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jsonrpc", "2.0",
                                "id", 1,
                                "method", "tools/call",
                                "params", Map.of(
                                        "name", toolName,
                                        "arguments", arguments
                                )
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String textContent = body.path("result").path("content").get(0).path("text").asText();
        return objectMapper.readTree(textContent);
    }

    private List<String> textValues(JsonNode results, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode row : results) {
            values.add(row.path(field).asText());
        }
        return values;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tests added with V0012: SQL ranked_search now drives ReadToolService.search.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void hardFilterExcludesCellsWithNoSemanticOrKeywordMatch() throws Exception {
        // Cell with no embedding and content that does not match the query at all.
        // Old in-memory ranking always returned every candidate; the SQL function
        // applies a hard filter (sem > 0.3 OR kw > 0), so this row must be absent.
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000901"),
                "completely unrelated banana split",
                "eng", "facts", "misc", 3, "unrelated", "committed",
                OffsetDateTime.parse("2026-04-03T10:00:00Z")
        );

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "kubernetes ingress",
                "limit", 10
        ));

        assertThat(textValues(results, "id"))
                .doesNotContain("00000000-0000-0000-0000-000000000901");
    }

    @Test
    void deterministicTiebreakOrdersEqualScoresByIdAsc() throws Exception {
        // Two cells with identical content, importance, and timestamps will produce
        // identical scores. V0012 added an ORDER BY id ASC tiebreak so the order is
        // stable across runs.
        UUID lower = UUID.fromString("00000000-0000-0000-0000-000000000aa1");
        UUID higher = UUID.fromString("00000000-0000-0000-0000-000000000aa2");
        OffsetDateTime ts = OffsetDateTime.parse("2026-04-03T10:00:00Z");
        insertDrawer(higher, "tiebreak probe text", "eng", "facts", "ord", 3, "probe", "committed", ts);
        insertDrawer(lower, "tiebreak probe text", "eng", "facts", "ord", 3, "probe", "committed", ts);

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "tiebreak probe",
                "limit", 10
        ));

        List<String> ids = textValues(results, "id");
        assertThat(ids).startsWith(lower.toString(), higher.toString());
    }

    @Test
    void rankedSearchIsInlinedByPlanner() {
        // V0014 rewrote ranked_search as LANGUAGE SQL so the outer planner can
        // inline the body instead of treating it as an opaque function call.
        // Inlining shows up as a real plan for the inner SELECT (Seq/Index Scan
        // on cells, a Sort, etc.) rather than a "Function Scan on ranked_search".
        // Without inlining, a cross-boundary HNSW/ANN optimization is impossible.
        FixedEmbeddingClient client = new FixedEmbeddingClient();
        List<Float> queryVec = client.encodeQuery("probe");
        String explainPlan = String.join("\n",
                dslContext.fetch(
                        """
                        EXPLAIN (FORMAT TEXT)
                        SELECT id FROM ranked_search(?::vector, ?, NULL, NULL, NULL, 10,
                                                     0.35::real, 0.15::real, 0.20::real,
                                                     0.15::real, 0.15::real)
                        """,
                        queryVec.toArray(Float[]::new),
                        "probe"
                ).stream().map(r -> r.get(0, String.class)).toList());

        assertThat(explainPlan)
                .as("Plan was:\n%s", explainPlan)
                .doesNotContain("Function Scan on ranked_search");
    }

    @Test
    void rankedSearchUsesHnswIndexWhenSelectingCandidates() {
        // Seed enough cells (and disable seqscan below) so the planner will
        // prefer the HNSW expression index for the ANN prefilter.
        FixedEmbeddingClient client = new FixedEmbeddingClient();
        for (int i = 0; i < 500; i++) {
            UUID id = UUID.fromString(String.format("00000000-0000-0000-0000-%012d", 700000 + i));
            String content = "Sample cell content number " + i;
            List<Float> embedding = client.encodeDocument(content);
            dslContext.execute(
                    """
                    INSERT INTO cells (
                        id, content, embedding, realm, signal, topic, importance,
                        summary, status, created_by, created_at, valid_from
                    ) VALUES (?, ?, ?::vector, 'eng', 'facts', 'perf', 3, ?, 'committed', 'writer-1',
                             '2026-04-03T10:00:00Z'::timestamptz, '2026-04-03T10:00:00Z'::timestamptz)
                    """,
                    id, content, embedding.toArray(Float[]::new), "summary " + i);
        }

        // Rebuild the HNSW expression index on the current dim. Production does
        // this via EmbeddingMigrationService on startup; here we do it directly
        // because the test container starts with an empty table.
        dslContext.execute("DROP INDEX IF EXISTS idx_cells_embedding");
        dslContext.execute(
                "CREATE INDEX idx_cells_embedding ON cells USING hnsw ((embedding::vector(1024)) vector_cosine_ops)");
        dslContext.execute("ANALYZE cells");
        // Force the planner to consider the HNSW index for the EXPLAIN below.
        // SET LOCAL would not persist across jOOQ execute calls; session SET
        // is fine because @BeforeEach truncates state for the next test.
        dslContext.execute("SET enable_seqscan = off");
        dslContext.execute("SET enable_bitmapscan = off");
        dslContext.execute("SET enable_sort = off");
        // Let HNSW return enough candidates for our LIMIT 200 prefilter.
        dslContext.execute("SET hnsw.ef_search = 200");

        List<Float> queryVec = client.encodeQuery("Sample cell content number 42");
        String explainPlan = String.join("\n",
                dslContext.fetch(
                        """
                        EXPLAIN (FORMAT TEXT)
                        SELECT id, score_total FROM ranked_search(?::vector, ?, NULL, NULL, NULL, 10,
                                                                   0.35::real, 0.15::real, 0.20::real,
                                                                   0.15::real, 0.15::real)
                        """,
                        queryVec.toArray(Float[]::new),
                        "Sample content"
                ).stream().map(r -> r.get(0, String.class)).toList());

        // The plan should reference the HNSW expression index. Seq scan on cells
        // means the cast in the function does not match the index expression,
        // which defeats the whole point of having it.
        assertThat(explainPlan)
                .as("ranked_search must use idx_cells_embedding. Plan was:\n%s", explainPlan)
                .contains("idx_cells_embedding");
    }

    @Test
    void germanContentIsMatchableAfterDictionarySwitch() throws Exception {
        // V0013 switched the tsv dictionary from 'english' to 'simple' so German
        // and English content tokenize equally (no English stemming/stopwords).
        // The German word "Schlüsseldienst" (locksmith) would not have indexed
        // sensibly under 'english'; under 'simple' it lowercases and matches.
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000cc1"),
                "Notiz: Schlüsseldienst gerufen wegen ausgesperrter Mitarbeiterin",
                "personal", "events", "haushalt", 3,
                "Schlüsseldienst Einsatz", "committed",
                OffsetDateTime.parse("2026-04-03T10:00:00Z")
        );

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "Schlüsseldienst",
                "limit", 10
        ));

        assertThat(textValues(results, "id"))
                .contains("00000000-0000-0000-0000-000000000cc1");
    }

    @Test
    void rankedSearchReturnsConfidenceLevel() throws Exception {
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000802"),
                "PostgreSQL confidence level probe content",
                "eng",
                "facts",
                "db",
                2,
                "confidence level probe",
                "committed",
                OffsetDateTime.parse("2026-04-03T10:00:00Z")
        );

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "confidence level probe",
                "limit", 10
        ));

        assertThat(results).isNotEmpty();
        JsonNode first = results.get(0);
        assertThat(first.has("confidence_level")).isTrue();
        assertThat(first.path("confidence_level").asText())
                .isIn("HIGH", "MEDIUM", "LOW", "NONE");
        // score_total must still be present (backward compat)
        assertThat(first.path("score_total").isNumber()).isTrue();
    }

    @Test
    void confidenceLevelIsRelativeToResultSetNotAlwaysNone() throws Exception {
        // Regression guard for backlog #2: real score_total values cluster low
        // (~0.4), so the OLD absolute-threshold classifier (HIGH>=0.80 /
        // MEDIUM>=0.65 / LOW>=0.55) labeled EVERY result NONE — the label was
        // useless. The relative-to-result-set classifier must instead band
        // results against their own distribution above an absolute floor (0.20).
        //
        // Seed several semantically-matching cells with a DETERMINISTIC spread of
        // score_total: identical content/embedding/recency, only importance
        // varies -> only score_importance varies -> distinct score_total. The
        // FixedEmbeddingClient maps any content containing "semantic" to the same
        // vector as the query, so the semantic component is a constant 0.30
        // baseline (well above the 0.20 floor) regardless of the wall clock —
        // this keeps the top hit's "not NONE" guarantee time-independent.
        FixedEmbeddingClient client = new FixedEmbeddingClient();
        String content = "semantic probe spread relative confidence cell";
        List<Float> embedding = client.encodeDocument(content);
        OffsetDateTime ts = OffsetDateTime.parse("2026-04-03T10:00:00Z");
        int[] importances = {1, 2, 4, 5};
        for (int i = 0; i < importances.length; i++) {
            UUID id = UUID.fromString(String.format("00000000-0000-0000-0000-%012d", 990000 + i));
            dslContext.execute(
                    """
                    INSERT INTO cells (
                        id, content, embedding, realm, signal, topic, importance,
                        summary, status, created_by, created_at, valid_from
                    ) VALUES (?, ?, ?::vector, 'eng', 'facts', 'conf', ?, ?, 'committed', 'writer-1',
                             ?::timestamptz, ?::timestamptz)
                    """,
                    id, content, embedding.toArray(Float[]::new), importances[i],
                    "confidence spread " + i, ts, ts);
        }

        // Default weights (no weight/profile overrides) — exactly how a normal
        // search runs in production.
        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "semantic probe spread relative confidence",
                "limit", 10
        ));

        assertThat(results).hasSizeGreaterThanOrEqualTo(2);

        // Core regression guard: the top hit must NOT be NONE. Under the old
        // absolute-cutoff code a ~0.5 top score_total classified as NONE; the
        // relative classifier bands it above NONE. This assertion would FAIL
        // against the pre-fix code.
        assertThat(results.get(0).path("confidence_level").asText())
                .as("top hit confidence_level")
                .isNotEqualTo("NONE");

        // Distinct score_total values must produce more than one confidence band,
        // proving relative banding rather than uniform labeling.
        List<String> levels = textValues(results, "confidence_level");
        assertThat(levels.stream().distinct().count())
                .as("distinct confidence levels across result-set: %s", levels)
                .isGreaterThanOrEqualTo(2L);
    }

    @Test
    void validUntilIsExposedWhenIncluded() throws Exception {
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000bb1"),
                "valid until probe content", "eng", "facts", "tmp", 3,
                "valid until probe", "committed",
                OffsetDateTime.parse("2026-04-03T10:00:00Z")
        );

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "valid until probe",
                "include", List.of("summary", "valid_from", "valid_until")
        ));

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).has("valid_until")).isTrue();
        assertThat(results.get(0).path("valid_until").isNull()).isTrue();
    }

    @Test
    void searchReturnsKeyPointsAndInsightWhenIncluded() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000902");
        insertDrawer(id, "Vector search layers cell", "eng", "facts", "layers",
                2, "layer summary", "committed", OffsetDateTime.parse("2026-04-03T10:00:00Z"));
        insertKeyPointsAndInsight(id, new String[] {"point one", "point two"}, "the insight");

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "vector search layers",
                "include", List.of("key_points", "insight", "summary")));

        JsonNode first = results.get(0);
        assertThat(first.path("insight").asText()).isEqualTo("the insight");
        assertThat(first.path("key_points").isArray()).isTrue();
        assertThat(first.path("key_points").get(0).asText()).isEqualTo("point one");
    }

    /** {@link #insertDrawer} doesn't know about {@code key_points}/{@code insight} —
     *  patch them onto an already-inserted cell for tests that need those columns. */
    private void insertKeyPointsAndInsight(UUID id, String[] keyPoints, String insight) {
        dslContext.execute(
                "UPDATE cells SET key_points = ?::text[], insight = ? WHERE id = ?",
                keyPoints, insight, id);
    }

    private void insertDrawer(
            UUID id,
            String content,
            String realm,
            String signal,
            String topic,
            Integer importance,
            String summary,
            String status,
            OffsetDateTime createdAt
    ) {
        dslContext.execute(
                """
                INSERT INTO cells (
                    id, content, realm, signal, topic, importance, summary, status, created_by, created_at, valid_from, valid_until
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """,
                id, content, realm, signal, topic, importance, summary, status, "writer-1", createdAt, createdAt, null
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @org.springframework.context.annotation.Primary
        TokenService tokenService() {
            return new com.hivemem.auth.support.FixedTokenService(token -> switch (token) {
                case "writer-token" -> Optional.of(new AuthPrincipal("writer-1", AuthRole.WRITER));
                case "admin-token" -> Optional.of(new AuthPrincipal("admin-1", AuthRole.ADMIN));
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
