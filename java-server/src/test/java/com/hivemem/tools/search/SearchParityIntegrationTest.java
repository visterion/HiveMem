package com.hivemem.tools.search;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.RateLimiter;
import com.hivemem.auth.TokenService;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.search.CellSearchRepository;
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
import static org.assertj.core.api.Assertions.within;
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

    @Autowired
    private CellSearchRepository cellSearchRepository;

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
    void popularityNormalizesAgainstFixedReferenceInsteadOfObservedMaximum() throws Exception {
        // Regression guard: score_popularity used to be divided by the observed
        // MAX(recent_access_count), so a cell with as few as 7 accesses got the
        // full 1.0 and the full p_weight_popularity=0.15. It is now divided by a
        // fixed reference of 25, so evidence stays proportionate to itself.
        UUID sevenHitsCellId = UUID.fromString("00000000-0000-0000-0000-000000000861");
        UUID twoHitsCellId = UUID.fromString("00000000-0000-0000-0000-000000000862");
        UUID fortyHitsCellId = UUID.fromString("00000000-0000-0000-0000-000000000863");
        UUID untouchedCellId = UUID.fromString("00000000-0000-0000-0000-000000000864");
        OffsetDateTime ts = OffsetDateTime.parse("2026-04-03T13:00:00Z");

        insertDrawer(sevenHitsCellId, "Popularity reference probe seven", "eng", "facts", "pop", 3,
                "seven hits", "committed", ts);
        insertDrawer(twoHitsCellId, "Popularity reference probe two", "eng", "facts", "pop", 3,
                "two hits", "committed", ts);
        insertDrawer(fortyHitsCellId, "Popularity reference probe forty", "eng", "facts", "pop", 3,
                "forty hits", "committed", ts);
        insertDrawer(untouchedCellId, "Popularity reference probe untouched", "eng", "facts", "pop", 3,
                "untouched", "committed", ts);

        for (int i = 0; i < 7; i++) adminToolService.logAccess(sevenHitsCellId, null, "admin");
        for (int i = 0; i < 2; i++) adminToolService.logAccess(twoHitsCellId, null, "admin");
        for (int i = 0; i < 40; i++) adminToolService.logAccess(fortyHitsCellId, null, "admin");
        adminToolService.refreshPopularity();

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "popularity reference probe",
                "limit", 10,
                "include", List.of("scores")
        ));

        // 7 accesses (of 25) yield 0.28. Under the old formula this fixture's observed
        // maximum was 40 (fortyHitsCellId below), so the old value here would have been
        // 7/40 = 0.175 -- not 1.0. The point isn't that the old value was 1.0 in every
        // fixture, it's that it was contingent on whatever else happened to be in the
        // corpus at all; the new value never is.
        assertThat(scoreOf(results, sevenHitsCellId)).isCloseTo(0.28f, within(1e-4f));
        // 2 accesses yield 0.08, not 0.2857.
        assertThat(scoreOf(results, twoHitsCellId)).isCloseTo(0.08f, within(1e-4f));
        // 40 accesses saturate at the 1.0 cap, not 1.6.
        assertThat(scoreOf(results, fortyHitsCellId)).isCloseTo(1.0f, within(1e-4f));
        // No row in cell_popularity at all yields 0.0.
        assertThat(scoreOf(results, untouchedCellId)).isCloseTo(0.0f, within(1e-4f));
    }

    @Test
    void popularityIsZeroWhenCellPopularityViewIsCompletelyEmpty() throws Exception {
        // With max_pop removed, nothing protects the division from an empty
        // cell_popularity view except the COALESCE(cp.recent_access_count, 0)
        // on the LEFT JOIN. This confirms the function still returns rows, and
        // score_popularity is 0 everywhere, when the view has zero rows at all
        // (not just zero rows for one cell).
        UUID cellId = UUID.fromString("00000000-0000-0000-0000-000000000865");
        insertDrawer(cellId, "Popularity empty view probe", "eng", "facts", "pop", 3,
                "empty view", "committed", OffsetDateTime.parse("2026-04-03T13:00:00Z"));

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "popularity empty view probe",
                "limit", 10,
                "include", List.of("scores")
        ));

        assertThat(results).isNotEmpty();
        assertThat(scoreOf(results, cellId)).isCloseTo(0.0f, within(1e-4f));
    }

    @Test
    void popularityGapInScoreTotalShrinksWithFixedReference() throws Exception {
        // The actual regression case: two cells with identical content (so sem,
        // kw, rec, imp are equal), differing only by access count. Under the old
        // observed-max normalization the score_total gap between 7 and 2 hits
        // was 0.107 (default p_weight_popularity=0.15); with the fixed reference
        // of 25 it must fall to 0.030.
        UUID sevenHitsCellId = UUID.fromString("00000000-0000-0000-0000-000000000871");
        UUID twoHitsCellId = UUID.fromString("00000000-0000-0000-0000-000000000872");
        OffsetDateTime ts = OffsetDateTime.parse("2026-04-03T13:00:00Z");

        insertDrawer(sevenHitsCellId, "Popularity gap probe identical content", "eng", "facts", "pop", 3,
                "gap probe", "committed", ts);
        insertDrawer(twoHitsCellId, "Popularity gap probe identical content", "eng", "facts", "pop", 3,
                "gap probe", "committed", ts);

        for (int i = 0; i < 7; i++) adminToolService.logAccess(sevenHitsCellId, null, "admin");
        for (int i = 0; i < 2; i++) adminToolService.logAccess(twoHitsCellId, null, "admin");
        adminToolService.refreshPopularity();

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "popularity gap probe identical content",
                "limit", 10,
                "include", List.of("scores")
        ));

        float gap = totalOf(results, sevenHitsCellId) - totalOf(results, twoHitsCellId);
        assertThat(gap).isCloseTo(0.030f, within(1e-3f));
    }

    @Test
    void confidenceLevelHighSetDiffersBetweenOldStyleAndNewStylePopularityDistribution() throws Exception {
        // Spec §3.1's sixth case, as an actual before/after ranking comparison rather
        // than a standalone unit check on ConfidenceLevel.classify: ConfidenceLevel is
        // relative to the result-set distribution and therefore invariant to any affine
        // (scale+shift) transform applied uniformly to every score, so a test that just
        // shows "scores shrink a bit" would prove nothing. What has to be shown is that
        // the SAME cell set, ranked twice through the deployed ranked_search function,
        // produces a DIFFERENT HIGH membership.
        //
        // Both distributions are reachable through the single deployed (new-formula)
        // function purely by varying access counts:
        //   - "old-style": give the outlier cell 25 accesses -> pop = 25/25 = 1.0, the
        //     same value the OLD max-normalization formula produced for a cell that IS
        //     the observed maximum (e.g. this repo's real corpus, where 7 accesses WAS
        //     the observed maximum -> old pop = 7/7 = 1.0).
        //   - "new-style": give the same cell 7 accesses -> pop = 7/25 = 0.28, the value
        //     the NEW fixed-reference formula actually produces for that same historical
        //     access count.
        // Ranking the same 5-cell set once under each condition and diffing the HIGH
        // sets is the property that would fail on a revert of the template/migration
        // change and passes with it in place.
        UUID popularCellId = UUID.fromString("00000000-0000-0000-0000-000000000881");
        UUID bgImportance5 = UUID.fromString("00000000-0000-0000-0000-000000000882");
        UUID bgImportance4 = UUID.fromString("00000000-0000-0000-0000-000000000883");
        UUID bgImportance3 = UUID.fromString("00000000-0000-0000-0000-000000000884");
        UUID bgImportance2 = UUID.fromString("00000000-0000-0000-0000-000000000885");
        OffsetDateTime ts = OffsetDateTime.parse("2026-04-03T13:00:00Z");
        // Identical content across all five cells keeps score_semantic (0, no embedding
        // set), score_keyword and score_recency equal for all of them; only
        // score_importance and score_popularity differ, isolating the popularity effect
        // exactly as the spec's sixth case requires.
        String content = "Widget deployment procedure notes shared across the corpus";

        insertDrawer(popularCellId, content, "eng", "facts", "pop", 1,
                "popular cell, weakest content signal", "committed", ts);
        insertDrawer(bgImportance5, content, "eng", "facts", "pop", 5, "background five", "committed", ts);
        insertDrawer(bgImportance4, content, "eng", "facts", "pop", 4, "background four", "committed", ts);
        insertDrawer(bgImportance3, content, "eng", "facts", "pop", 3, "background three", "committed", ts);
        insertDrawer(bgImportance2, content, "eng", "facts", "pop", 2, "background two", "committed", ts);

        for (int i = 0; i < 25; i++) adminToolService.logAccess(popularCellId, null, "admin");
        adminToolService.refreshPopularity();

        JsonNode oldStyleResults = callTool("writer-token", "search", Map.of(
                "query", "widget deployment procedure",
                "limit", 10,
                "include", List.of("scores")
        ));
        assertThat(scoreOf(oldStyleResults, popularCellId)).isCloseTo(1.0f, within(1e-4f));
        assertThat(confidenceOf(oldStyleResults, popularCellId)).isEqualTo("HIGH");
        java.util.Set<String> highOld = highIds(oldStyleResults);

        dslContext.execute("DELETE FROM access_log WHERE cell_id = ?", popularCellId);
        for (int i = 0; i < 7; i++) adminToolService.logAccess(popularCellId, null, "admin");
        adminToolService.refreshPopularity();

        JsonNode newStyleResults = callTool("writer-token", "search", Map.of(
                "query", "widget deployment procedure",
                "limit", 10,
                "include", List.of("scores")
        ));
        assertThat(scoreOf(newStyleResults, popularCellId)).isCloseTo(0.28f, within(1e-4f));
        assertThat(confidenceOf(newStyleResults, popularCellId)).isNotEqualTo("HIGH");
        java.util.Set<String> highNew = highIds(newStyleResults);

        assertThat(highOld).contains(popularCellId.toString());
        assertThat(highNew).doesNotContain(popularCellId.toString());
        assertThat(highOld).isNotEqualTo(highNew);
    }

    private String confidenceOf(JsonNode results, UUID cellId) {
        for (JsonNode row : results) {
            if (row.path("id").asText().equals(cellId.toString())) {
                return row.path("confidence_level").asText();
            }
        }
        throw new AssertionError("cell not found in results: " + cellId);
    }

    private java.util.Set<String> highIds(JsonNode results) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (JsonNode row : results) {
            if ("HIGH".equals(row.path("confidence_level").asText())) {
                ids.add(row.path("id").asText());
            }
        }
        return ids;
    }

    private float scoreOf(JsonNode results, UUID cellId) {
        for (JsonNode row : results) {
            if (row.path("id").asText().equals(cellId.toString())) {
                return (float) row.path("score_popularity").asDouble();
            }
        }
        throw new AssertionError("cell not found in results: " + cellId);
    }

    private float totalOf(JsonNode results, UUID cellId) {
        for (JsonNode row : results) {
            if (row.path("id").asText().equals(cellId.toString())) {
                return (float) row.path("score_total").asDouble();
            }
        }
        throw new AssertionError("cell not found in results: " + cellId);
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

    @Test
    void searchStatusAllReturnsRejectedAndCommitted() throws Exception {
        insertDrawer(UUID.fromString("00000000-0000-0000-0000-000000000911"),
                "vector rejected cell", "eng", "facts", "st", 2, "rejected summary",
                "rejected", OffsetDateTime.parse("2026-04-03T10:00:00Z"));
        insertDrawer(UUID.fromString("00000000-0000-0000-0000-000000000912"),
                "vector committed cell", "eng", "facts", "st", 2, "committed summary",
                "committed", OffsetDateTime.parse("2026-04-03T11:00:00Z"));

        JsonNode all = callTool("writer-token", "search", Map.of(
                "query", "vector cell", "status", "all", "limit", 10));
        JsonNode committedOnly = callTool("writer-token", "search", Map.of(
                "query", "vector cell", "limit", 10));

        assertThat(textValues(all, "id")).contains("00000000-0000-0000-0000-000000000911",
                "00000000-0000-0000-0000-000000000912");
        assertThat(textValues(committedOnly, "id"))
                .doesNotContain("00000000-0000-0000-0000-000000000911");
    }

    @Test
    void browseStatusAllReturnsRejectedAndCommitted() throws Exception {
        insertDrawer(UUID.fromString("00000000-0000-0000-0000-000000000913"),
                "browse rejected cell", "eng", "facts", "st", 2, "rejected summary",
                "rejected", OffsetDateTime.parse("2026-04-03T10:00:00Z"));

        JsonNode all = callTool("writer-token", "search", Map.of(
                "realm", "eng", "status", "all", "limit", 50));   // no query -> browse path
        assertThat(textValues(all, "id")).contains("00000000-0000-0000-0000-000000000913");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task 15 hotfix: filter-only browse when query is blank/absent (realm drilldown).
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void blankQueryWithRealmFilterBrowsesNewestFirstIncludingKeyPointsAndInsight() throws Exception {
        UUID older = UUID.fromString("00000000-0000-0000-0000-000000000c01");
        UUID newer = UUID.fromString("00000000-0000-0000-0000-000000000c02");
        UUID otherRealm = UUID.fromString("00000000-0000-0000-0000-000000000c03");

        insertDrawer(older, "Older engineering cell", "engineering", "facts", "infra", 2,
                "older summary", "committed", OffsetDateTime.parse("2026-04-01T10:00:00Z"));
        insertKeyPointsAndInsight(older, new String[] {"older point"}, "older insight");
        insertDrawer(newer, "Newer engineering cell", "engineering", "facts", "infra", 2,
                "newer summary", "committed", OffsetDateTime.parse("2026-04-05T10:00:00Z"));
        insertKeyPointsAndInsight(newer, new String[] {"newer point"}, "newer insight");
        insertDrawer(otherRealm, "Personal cell", "personal", "facts", "infra", 2,
                "personal summary", "committed", OffsetDateTime.parse("2026-04-06T10:00:00Z"));

        JsonNode results = callTool("writer-token", "search", Map.of(
                "query", "",
                "realm", "engineering",
                "include", List.of("key_points", "insight", "content")
        ));

        assertThat(results).hasSize(2);
        // newest-first
        assertThat(results.get(0).path("id").asText()).isEqualTo(newer.toString());
        assertThat(results.get(1).path("id").asText()).isEqualTo(older.toString());
        assertThat(results.get(0).path("insight").asText()).isEqualTo("newer insight");
        assertThat(results.get(0).path("key_points").get(0).asText()).isEqualTo("newer point");
        assertThat(results.get(0).path("content").asText()).isEqualTo("Newer engineering cell");
        // no ranking scores on the browse path
        assertThat(results.get(0).has("score_total")).isFalse();
        assertThat(results.get(0).has("confidence_level")).isFalse();
        assertThat(results.get(0).has("score_semantic")).isFalse();
    }

    @Test
    void absentQueryWithTagsFilterBrowsesMatchingTagsOnly() throws Exception {
        UUID tagged = UUID.fromString("00000000-0000-0000-0000-000000000c11");
        UUID untagged = UUID.fromString("00000000-0000-0000-0000-000000000c12");

        insertDrawer(tagged, "Tagged cell", "eng", "facts", "infra", 2,
                "tagged summary", "committed", OffsetDateTime.parse("2026-04-01T10:00:00Z"));
        dslContext.execute("UPDATE cells SET tags = ?::text[] WHERE id = ?",
                new String[] {"docker"}, tagged);
        insertDrawer(untagged, "Untagged cell", "eng", "facts", "infra", 2,
                "untagged summary", "committed", OffsetDateTime.parse("2026-04-02T10:00:00Z"));

        JsonNode results = callTool("writer-token", "search", Map.of(
                "tags", List.of("docker")
        ));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).path("id").asText()).isEqualTo(tagged.toString());
    }

    @Test
    void blankQueryWithoutAnyFilterStillFailsWithMissingQuery() throws Exception {
        MvcResult result = mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jsonrpc", "2.0",
                                "id", 1,
                                "method", "tools/call",
                                "params", Map.of(
                                        "name", "search",
                                        "arguments", Map.of("query", "")
                                )
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(body.path("error").path("message").asText()).isEqualTo("Missing query");
    }

    @Test
    void noQueryNoFilterAtAllStillFailsWithMissingQuery() throws Exception {
        MvcResult result = mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jsonrpc", "2.0",
                                "id", 1,
                                "method", "tools/call",
                                "params", Map.of(
                                        "name", "search",
                                        "arguments", Map.of()
                                )
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(body.path("error").path("message").asText()).isEqualTo("Missing query");
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
    void rankedSearchIsNoLongerInlinedOnceItSetsHnswEfSearch() {
        // V0014 originally rewrote ranked_search as LANGUAGE SQL so the outer planner could inline
        // the body instead of treating it as an opaque function call. Design §3.6a's chunk_ann
        // measurement forced a trade-off against that: LIMIT 400 alone pushes the HNSW startup
        // cost estimate past the seq-scan crossover at the default ef_search=40 (measured 53.9 ms
        // vs 4.9 ms), so the function now sets hnsw.ef_search=500 as a function-level GUC. A
        // LANGUAGE SQL function with a non-empty proconfig is no longer eligible for
        // inline_set_returning_function -- measured and accepted in the design (9.9 ms inlined vs
        // 9.2 ms with SET on real data, the single caller CellSearchRepository never joins the
        // result into anything else). This test now documents and guards THAT fact: the plan for
        // the outer call must show the opaque "Function Scan on ranked_search", not an expanded
        // inner plan -- the inverse of what V0014's original version of this test asserted.
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
                .contains("Function Scan on ranked_search");
    }

    @Test
    void rankedSearchUsesHnswIndexWhenSelectingCandidates() {
        // Design §3.6a: once ranked_search sets hnsw.ef_search, it is no longer inlined (see
        // rankedSearchIsNoLongerInlinedOnceItSetsHnswEfSearch above), so an outer EXPLAIN on the
        // wrapper call can no longer show the inner "Index Scan using idx_cells_embedding" line --
        // Postgres treats a non-inlined LANGUAGE SQL function as an opaque Function Scan and does
        // not expose its internal plan. Index usage is instead observed the way Postgres itself
        // tracks it: pg_stat_user_indexes.idx_scan on idx_cells_embedding, which increments once
        // per statement that scans it, function-internal or not. pg_stat_force_next_flush() (PG15+)
        // makes the counter visible immediately instead of waiting for the stats collector's
        // periodic flush.
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
        // Force the planner to consider the HNSW index: at only 500 rows a seq scan is otherwise
        // cheap enough that the planner may prefer it, which would make this test pass or fail on
        // the planner's cost model rather than on whether ranked_search's internal query can use
        // the index at all. This is session-level SET, not SET LOCAL, because these jOOQ
        // .execute() calls are separate autocommit statements on a pooled connection -- a
        // transaction-scoped SET LOCAL would not survive between them. Session-level SET DOES
        // leak onto the pooled connection once it's returned to the pool (TRUNCATE in
        // @BeforeEach resets table *data*, not session GUCs), so it is explicitly RESET in a
        // finally block below before the connection can be reused by another test.
        dslContext.execute("SET enable_seqscan = off");
        dslContext.execute("SET enable_bitmapscan = off");
        dslContext.execute("SET enable_sort = off");
        try {
            dslContext.execute("SELECT pg_stat_force_next_flush()");
            Long idxScansBefore = dslContext.fetchOne("""
                    SELECT idx_scan FROM pg_stat_user_indexes WHERE indexrelname = 'idx_cells_embedding'
                    """).get("idx_scan", Long.class);

            List<Float> queryVec = client.encodeQuery("Sample cell content number 42");
            dslContext.fetch(
                    """
                    SELECT id, score_total FROM ranked_search(?::vector, ?, NULL, NULL, NULL, 10,
                                                               0.35::real, 0.15::real, 0.20::real,
                                                               0.15::real, 0.15::real)
                    """,
                    queryVec.toArray(Float[]::new),
                    "Sample content"
            );
            dslContext.execute("SELECT pg_stat_force_next_flush()");
            Long idxScansAfter = dslContext.fetchOne("""
                    SELECT idx_scan FROM pg_stat_user_indexes WHERE indexrelname = 'idx_cells_embedding'
                    """).get("idx_scan", Long.class);

            // A seq scan on cells (the cast in the function not matching the index expression, or
            // the GUC not taking effect) would leave idx_cells_embedding's scan count unchanged.
            assertThat(idxScansAfter)
                    .as("ranked_search must use idx_cells_embedding (before=%d, after=%d)",
                            idxScansBefore, idxScansAfter)
                    .isGreaterThan(idxScansBefore);
        } finally {
            dslContext.execute("RESET enable_seqscan");
            dslContext.execute("RESET enable_bitmapscan");
            dslContext.execute("RESET enable_sort");
        }
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
        // vector as the query, so the semantic component is a constant 0.20
        // (similarity 1.0 at weight 0.20) regardless of the wall clock. Together with the
        // constant importance/recency/popularity terms of the seeded cells the top hit stays
        // above the 0.20 floor, and the keyword leg adds to it on top; this keeps the top
        // hit's "not NONE" guarantee time-independent.
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
    void semanticOnlyOldCellFallsBelowTheFloor() throws Exception {
        // The query vector is axis 0 ("semantic oracle"). The probe cell shares no word with the
        // query, has similarity exactly 0.5, is years old (recency ~0), importance 3 (0.6) and no
        // popularity or graph signal: new balanced 0.20*0.5 + 0.15*0.6 = 0.19 < floor 0.20,
        // old weights 0.30*0.5 + 0.15*0.6 = 0.24 >= floor.
        UUID probe = UUID.fromString("00000000-0000-0000-0000-000000000f01");
        OffsetDateTime old = OffsetDateTime.parse("2020-01-01T10:00:00Z");
        OffsetDateTime recent = OffsetDateTime.now().minusDays(1);
        insertCellWithEmbedding(probe, "unrelated gardening compost notes", "eng", "facts", "floor", null, 3,
                "gardening", "committed", old, null, halfSimilarityVector());
        insertCellWithEmbedding(UUID.fromString("00000000-0000-0000-0000-000000000f02"),
                "semantic oracle handbook chapter one", "eng", "facts", "floor", null, 3,
                "handbook one", "committed", recent, null, unitVector(1));
        insertCellWithEmbedding(UUID.fromString("00000000-0000-0000-0000-000000000f03"),
                "semantic oracle handbook chapter two", "eng", "facts", "floor", null, 3,
                "handbook two", "committed", recent, null, unitVector(1));

        JsonNode defaults = callTool("writer-token", "search", Map.of(
                "query", "semantic oracle", "limit", 10, "include", List.of("scores")));
        assertThat(defaults).hasSizeGreaterThanOrEqualTo(3);
        float newTotal = totalOf(defaults, probe);
        assertThat(newTotal).as("probe score_total with the default weights").isLessThan(0.20f);
        assertThat(confidenceOf(defaults, probe)).isEqualTo("NONE");

        JsonNode oldWeights = callTool("writer-token", "search", Map.of(
                "query", "semantic oracle", "limit", 10, "include", List.of("scores"),
                "weight_semantic", 0.30, "weight_keyword", 0.15, "weight_recency", 0.15,
                "weight_importance", 0.15, "weight_popularity", 0.15, "weight_graph_proximity", 0.10));
        float oldTotal = totalOf(oldWeights, probe);
        assertThat(oldTotal).as("probe score_total with the old weights").isGreaterThanOrEqualTo(0.20f);
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

    // ─────────────────────────────────────────────────────────────────────────
    // Task 3: ranked_search ranks by best matching chunk (design §3.6, §5.3).
    // These tests call CellSearchRepository.rankedSearch directly rather than going through the
    // MCP "search" tool: exposing match_page_from/_to/_excerpt to MCP callers is Task 4's scope,
    // not this one's. Query and cell/chunk vectors are constructed as exact orthonormal-basis
    // combinations (not run through FixedEmbeddingClient's text heuristics) so cosine similarity
    // values are exact, not merely "high" or "low" -- required for the tie and GREATEST
    // never-decreases assertions below.
    // ─────────────────────────────────────────────────────────────────────────

    private static final int DIM = 1024;
    // A query text that matches no seeded content's tsvector, so score_keyword stays 0 for every
    // row in these tests and only score_semantic (sem) drives WHERE s.sem > 0.3 OR s.kw > 0.
    private static final String NO_KEYWORD_MATCH_QUERY = "zzzznokeywordmatchzzzz";

    private static List<Float> unitVector(int axis) {
        List<Float> v = new ArrayList<>(java.util.Collections.nCopies(DIM, 0.0f));
        v.set(axis, 1.0f);
        return v;
    }

    /** cos_sim with {@link #unitVector}(0) is exactly 0.5: axis0 weight cos(60deg), axis2 weight
     *  sin(60deg), both components of a unit vector. */
    private static List<Float> halfSimilarityVector() {
        List<Float> v = new ArrayList<>(java.util.Collections.nCopies(DIM, 0.0f));
        v.set(0, 0.5f);
        v.set(2, (float) Math.sqrt(1 - 0.25));
        return v;
    }

    private void insertCellWithEmbedding(
            UUID id, String content, String realm, String signal, String topic, String[] tags,
            Integer importance, String summary, String status, OffsetDateTime createdAt,
            OffsetDateTime validUntil, List<Float> embedding) {
        Float[] embeddingArray = embedding == null ? null : embedding.toArray(Float[]::new);
        dslContext.execute("""
                INSERT INTO cells (
                    id, content, embedding, realm, signal, topic, tags, importance, summary, status,
                    created_by, created_at, valid_from, valid_until
                ) VALUES (?, ?, ?::vector, ?, ?, ?, ?::text[], ?, ?, ?, 'writer-1',
                          ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """,
                id, content, embeddingArray, realm, signal, topic, tags, importance, summary, status,
                createdAt, createdAt, validUntil);
    }

    private void insertChunk(UUID cellId, int ordinal, Integer pageFrom, Integer pageTo,
            String content, List<Float> embedding) {
        Float[] embeddingArray = embedding.toArray(Float[]::new);
        dslContext.execute("""
                INSERT INTO cell_chunks (cell_id, ordinal, page_from, page_to, content, embedding, cell_content_hash)
                VALUES (?, ?, ?, ?, ?, ?::vector, md5(?))
                """, cellId, ordinal, pageFrom, pageTo, content, embeddingArray, content);
    }

    /** A cell reachable only through its chunk (embedding IS NULL), with one strong chunk (cos_sim
     *  1.0 with {@link #unitVector}(0)) at pages 5-6. Used by the filter-parity tests: whatever
     *  filter value is passed in must be honored on this chunk-only path exactly as it would be on
     *  the cell-vector path (design §3.6b, §5.3). */
    private UUID insertChunkOnlyCell(String realm, String signal, String topic, String[] tags,
            String status, OffsetDateTime validUntil) {
        UUID id = UUID.randomUUID();
        insertCellWithEmbedding(id, "chunk-only cell content", realm, signal, topic, tags, 3,
                "chunk-only summary", status, OffsetDateTime.parse("2026-04-03T10:00:00Z"), validUntil, null);
        insertChunk(id, 0, 5, 6, "chunk-only cell strong chunk body", unitVector(0));
        return id;
    }

    private List<CellSearchRepository.RankedRow> searchByVectorOnly(
            List<Float> queryEmbedding, String realm, String signal, String topic,
            List<String> tags, String status, List<String> realmIn) {
        return cellSearchRepository.rankedSearch(
                queryEmbedding, NO_KEYWORD_MATCH_QUERY, realm, signal, topic, 20,
                1.0, 0.0, 0.0, 0.0, 0.0, 0.0, tags, status, realmIn);
    }

    private Optional<CellSearchRepository.RankedRow> rowFor(
            List<CellSearchRepository.RankedRow> rows, UUID id) {
        return rows.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    @Test
    void chunkOnlyCellIsFoundAndCarriesMatchFields() throws Exception {
        UUID id = insertChunkOnlyCell("chunkrealm", "facts", "chunktopic", null, "committed", null);

        List<CellSearchRepository.RankedRow> results =
                searchByVectorOnly(unitVector(0), null, null, null, null, null, null);

        Optional<CellSearchRepository.RankedRow> found = rowFor(results, id);
        assertThat(found).as("cell reachable only via its chunk must still be found").isPresent();
        assertThat(found.get().scoreSemantic()).isCloseTo(1.0, within(1e-4));
        assertThat(found.get().matchPageFrom()).isEqualTo(5);
        assertThat(found.get().matchPageTo()).isEqualTo(6);
        assertThat(found.get().matchExcerpt()).isEqualTo("chunk-only cell strong chunk body");
    }

    /**
     * Mutation-proof coverage for the 301-not-300 rule (design §3.6a, plan's "SIX THINGS THAT
     * MUST BE RIGHT" #3): a chunk of exactly 300 characters must come back whole, with no
     * ellipsis. This is the boundary that {@code left(ch.content, 301)} exists to preserve --
     * mutating the SQL literal to 300 truncates this chunk to 299 chars + "…", which the
     * assertion below catches. Every other chunk fixture in this class is well under 300 chars,
     * so without this test the boundary has zero coverage.
     */
    @Test
    void matchExcerptOfExactly300CharsComesBackWholeWithNoEllipsis() throws Exception {
        UUID id = UUID.randomUUID();
        insertCellWithEmbedding(id, "cell with exactly-300-char chunk", "eng", "facts", "t", null, 3,
                "summary", "committed", OffsetDateTime.parse("2026-04-03T10:00:00Z"), null, null);
        String exactly300 = "x".repeat(300);
        insertChunk(id, 0, 1, 2, exactly300, unitVector(0));

        CellSearchRepository.RankedRow row = rowFor(
                searchByVectorOnly(unitVector(0), null, null, null, null, null, null), id)
                .orElseThrow();

        assertThat(row.matchExcerpt()).hasSize(300).isEqualTo(exactly300).doesNotContain("…");
    }

    /**
     * Companion to the 300-char test above: a chunk of 400 characters must come back truncated to
     * exactly 300 characters plus the ellipsis. Fails if the SQL literal is mutated from 301 to
     * 300 (off-by-one in the truncation point) or if {@code CellSearchRepository.toExcerpt}'s
     * {@code > 300} comparison is flipped to {@code >=} or {@code >= 301}.
     */
    @Test
    void matchExcerptOf400CharsIsTruncatedTo300CharsPlusEllipsis() throws Exception {
        UUID id = UUID.randomUUID();
        insertCellWithEmbedding(id, "cell with 400-char chunk", "eng", "facts", "t", null, 3,
                "summary", "committed", OffsetDateTime.parse("2026-04-03T10:00:00Z"), null, null);
        String exactly400 = "y".repeat(400);
        insertChunk(id, 0, 1, 2, exactly400, unitVector(0));

        CellSearchRepository.RankedRow row = rowFor(
                searchByVectorOnly(unitVector(0), null, null, null, null, null, null), id)
                .orElseThrow();

        assertThat(row.matchExcerpt())
                .hasSize(301)
                .isEqualTo(exactly400.substring(0, 300) + "…");
    }

    @Test
    void matchIsNullWhenCellVectorScoresStrictlyBetterThanItsBestChunk() throws Exception {
        UUID id = UUID.randomUUID();
        insertCellWithEmbedding(id, "cell with own strong embedding", "eng", "facts", "t", null, 3,
                "summary", "committed", OffsetDateTime.parse("2026-04-03T10:00:00Z"), null, unitVector(0));
        // Orthogonal to the query axis -> cos_sim 0, strictly weaker than the cell's own 1.0.
        insertChunk(id, 0, 1, 2, "weak chunk", unitVector(1));

        List<CellSearchRepository.RankedRow> results =
                searchByVectorOnly(unitVector(0), null, null, null, null, null, null);

        CellSearchRepository.RankedRow row = rowFor(results, id).orElseThrow();
        assertThat(row.scoreSemantic()).isCloseTo(1.0, within(1e-4));
        assertThat(row.matchPageFrom()).isNull();
        assertThat(row.matchPageTo()).isNull();
        assertThat(row.matchExcerpt()).isNull();
    }

    @Test
    void tiedChunkAndCellVectorScoresSetMatchFields() throws Exception {
        // §3.6d: at an exact tie the chunk counts as the provider.
        UUID id = UUID.randomUUID();
        insertCellWithEmbedding(id, "cell with tying embedding", "eng", "facts", "t", null, 3,
                "summary", "committed", OffsetDateTime.parse("2026-04-03T10:00:00Z"), null, unitVector(0));
        insertChunk(id, 0, 3, 4, "tying chunk body", unitVector(0));

        List<CellSearchRepository.RankedRow> results =
                searchByVectorOnly(unitVector(0), null, null, null, null, null, null);

        CellSearchRepository.RankedRow row = rowFor(results, id).orElseThrow();
        assertThat(row.scoreSemantic()).isCloseTo(1.0, within(1e-4));
        assertThat(row.matchPageFrom()).isEqualTo(3);
        assertThat(row.matchPageTo()).isEqualTo(4);
        assertThat(row.matchExcerpt()).isEqualTo("tying chunk body");
    }

    @Test
    void greatestNeverLowersSemAsChunksAreAddedToTheSameCell() throws Exception {
        UUID id = UUID.randomUUID();
        // Cell's own vector scores exactly 0.5 against the query.
        insertCellWithEmbedding(id, "cell with half-similarity embedding", "eng", "facts", "t", null, 3,
                "summary", "committed", OffsetDateTime.parse("2026-04-03T10:00:00Z"), null, halfSimilarityVector());

        double semBeforeAnyChunk = rowFor(
                searchByVectorOnly(unitVector(0), null, null, null, null, null, null), id)
                .orElseThrow().scoreSemantic();
        assertThat(semBeforeAnyChunk).isCloseTo(0.5, within(1e-4));

        // A weaker chunk (cos_sim 0) must not lower sem below the cell vector's own 0.5.
        insertChunk(id, 0, null, null, "weaker chunk", unitVector(1));
        double semAfterWeakChunk = rowFor(
                searchByVectorOnly(unitVector(0), null, null, null, null, null, null), id)
                .orElseThrow().scoreSemantic();
        assertThat(semAfterWeakChunk).isCloseTo(0.5, within(1e-4));

        // A stronger chunk (cos_sim 1.0) must raise sem above the cell vector's own 0.5.
        insertChunk(id, 1, null, null, "stronger chunk", unitVector(0));
        double semAfterStrongChunk = rowFor(
                searchByVectorOnly(unitVector(0), null, null, null, null, null, null), id)
                .orElseThrow().scoreSemantic();
        assertThat(semAfterStrongChunk).isCloseTo(1.0, within(1e-4));
    }

    @Test
    void cellWithoutAnyChunksRanksExactlyAsItDidBeforeChunking() throws Exception {
        UUID id = UUID.randomUUID();
        insertCellWithEmbedding(id, "cell with no chunks at all", "eng", "facts", "t", null, 3,
                "summary", "committed", OffsetDateTime.parse("2026-04-03T10:00:00Z"), null, unitVector(0));

        CellSearchRepository.RankedRow row = rowFor(
                searchByVectorOnly(unitVector(0), null, null, null, null, null, null), id)
                .orElseThrow();

        assertThat(row.scoreSemantic()).isCloseTo(1.0, within(1e-4));
        assertThat(row.matchPageFrom()).isNull();
        assertThat(row.matchPageTo()).isNull();
        assertThat(row.matchExcerpt()).isNull();
    }

    @Test
    void chunkOnlyCellBecomesAGraphAnchorAndBoostsATunneledNeighbor() throws Exception {
        // §3.6f: anchors must no longer require c.embedding IS NOT NULL, or a cell reachable only
        // through its chunk can never seed graph_proximity_scores. Proven here by tunneling a
        // second cell -- which has NO embedding and NO chunk of its own, so it can only enter
        // `candidates` via a keyword match on a distinct query term -- to the chunk-only cell and
        // observing a non-zero graph score, which is only possible if the chunk-only cell was
        // selected as an anchor (it is the only cell in this fixture that could seed the walk).
        String keywordQuery = "tunnelneighborkeyword";
        UUID anchorCandidateId = insertChunkOnlyCell("eng", "facts", "graphtopic", null, "committed", null);
        UUID neighborId = UUID.randomUUID();
        insertCellWithEmbedding(neighborId, "content containing tunnelneighborkeyword and nothing else relevant",
                "eng", "facts", "graphtopic", null, 3, "neighbor summary", "committed",
                OffsetDateTime.parse("2026-04-03T10:00:00Z"), null, null);
        dslContext.execute("""
                INSERT INTO tunnels (from_cell, to_cell, relation, status, created_by)
                VALUES (?, ?, 'related_to', 'committed', 'writer-1')
                """, anchorCandidateId, neighborId);

        List<CellSearchRepository.RankedRow> results = cellSearchRepository.rankedSearch(
                unitVector(0), keywordQuery, null, null, null, 20,
                0.30, 0.15, 0.0, 0.0, 0.0, 0.70, null, null, null);

        // The neighbor has no embedding and no chunk, so its non-zero graph_proximity score can
        // only come from the tunnel to the anchor -- which requires the chunk-only cell (whose
        // only semantic signal is its chunk, not c.embedding) to have been selected as an anchor.
        CellSearchRepository.RankedRow neighborRow = rowFor(results, neighborId)
                .orElseThrow(() -> new AssertionError("neighbor must be found via its own keyword match"));
        assertThat(neighborRow.scoreGraphProximity()).isGreaterThan(0.0);
    }

    // ─── Filter parity between the chunk-only path and the cell-vector path (design §3.6b) ───

    @Test
    void filterParityRealm() throws Exception {
        UUID id = insertChunkOnlyCell("realmA", "facts", "t", null, "committed", null);

        assertThat(rowFor(searchByVectorOnly(unitVector(0), "realmB", null, null, null, null, null), id))
                .as("wrong realm must exclude the chunk-only cell").isEmpty();
        assertThat(rowFor(searchByVectorOnly(unitVector(0), "realmA", null, null, null, null, null), id))
                .as("matching realm must include the chunk-only cell").isPresent();
    }

    @Test
    void filterParityRealmsIn() throws Exception {
        UUID id = insertChunkOnlyCell("realmA", "facts", "t", null, "committed", null);

        assertThat(rowFor(searchByVectorOnly(unitVector(0), null, null, null, null, null, List.of("realmB")), id))
                .as("p_realms not containing the cell's realm must exclude it").isEmpty();
        assertThat(rowFor(searchByVectorOnly(unitVector(0), null, null, null, null, null, List.of("realmA")), id))
                .as("p_realms containing the cell's realm must include it").isPresent();
    }

    /** The p_realm='none' branch (c.realm IS NULL) is a separate predicate arm from the plain
     *  equality branch tested by {@link #filterParityRealm}, and is the branch most likely to
     *  drift between the ann/chunk_best-join arm and the cell-vector arm since it's easy to copy
     *  the equality half of the OR and miss the NULL half. */
    @Test
    void filterParityRealmNone() throws Exception {
        UUID id = insertChunkOnlyCell(null, "facts", "t", null, "committed", null);

        assertThat(rowFor(searchByVectorOnly(unitVector(0), "someRealm", null, null, null, null, null), id))
                .as("a non-'none' realm filter must exclude a realm-less chunk-only cell").isEmpty();
        assertThat(rowFor(searchByVectorOnly(unitVector(0), "none", null, null, null, null, null), id))
                .as("p_realm='none' must include a realm-less chunk-only cell").isPresent();
    }

    /** Companion to {@link #filterParityRealmNone} for the p_realms array form: 'none' as an
     *  array element (matched against c.realm IS NULL), not a plain equality element. */
    @Test
    void filterParityRealmsInNone() throws Exception {
        UUID id = insertChunkOnlyCell(null, "facts", "t", null, "committed", null);

        assertThat(rowFor(searchByVectorOnly(unitVector(0), null, null, null, null, null, List.of("someRealm")), id))
                .as("p_realms without 'none' must exclude a realm-less chunk-only cell").isEmpty();
        assertThat(rowFor(searchByVectorOnly(unitVector(0), null, null, null, null, null, List.of("none")), id))
                .as("p_realms containing 'none' must include a realm-less chunk-only cell").isPresent();
    }

    @Test
    void filterParitySignal() throws Exception {
        UUID id = insertChunkOnlyCell("eng", "discoveries", "t", null, "committed", null);

        assertThat(rowFor(searchByVectorOnly(unitVector(0), null, "facts", null, null, null, null), id))
                .as("wrong signal must exclude the chunk-only cell").isEmpty();
        assertThat(rowFor(searchByVectorOnly(unitVector(0), null, "discoveries", null, null, null, null), id))
                .as("matching signal must include the chunk-only cell").isPresent();
    }

    @Test
    void filterParityTopic() throws Exception {
        UUID id = insertChunkOnlyCell("eng", "facts", "topicA", null, "committed", null);

        assertThat(rowFor(searchByVectorOnly(unitVector(0), null, null, "topicB", null, null, null), id))
                .as("wrong topic must exclude the chunk-only cell").isEmpty();
        assertThat(rowFor(searchByVectorOnly(unitVector(0), null, null, "topicA", null, null, null), id))
                .as("matching topic must include the chunk-only cell").isPresent();
    }

    @Test
    void filterParityTags() throws Exception {
        UUID id = insertChunkOnlyCell("eng", "facts", "t", new String[] {"tagX"}, "committed", null);

        assertThat(rowFor(searchByVectorOnly(unitVector(0), null, null, null, List.of("tagY"), null, null), id))
                .as("non-overlapping tags must exclude the chunk-only cell").isEmpty();
        assertThat(rowFor(searchByVectorOnly(unitVector(0), null, null, null, List.of("tagX"), null, null), id))
                .as("overlapping tags must include the chunk-only cell").isPresent();
    }

    @Test
    void filterParityStatus() throws Exception {
        UUID id = insertChunkOnlyCell("eng", "facts", "t", null, "pending", null);

        assertThat(rowFor(searchByVectorOnly(unitVector(0), null, null, null, null, null, null), id))
                .as("default status filter (committed) must exclude a pending chunk-only cell").isEmpty();
        assertThat(rowFor(searchByVectorOnly(unitVector(0), null, null, null, null, "all", null), id))
                .as("status=all must include a pending chunk-only cell").isPresent();
    }

    @Test
    void filterParityValidUntil() throws Exception {
        UUID expiredId = insertChunkOnlyCell("eng", "facts", "t", null, "committed",
                OffsetDateTime.parse("2020-01-01T00:00:00Z"));
        UUID activeId = insertChunkOnlyCell("eng", "facts", "t", null, "committed", null);

        List<CellSearchRepository.RankedRow> results =
                searchByVectorOnly(unitVector(0), null, null, null, null, null, null);

        assertThat(rowFor(results, expiredId)).as("expired chunk-only cell must be excluded").isEmpty();
        assertThat(rowFor(results, activeId)).as("active chunk-only cell must be included").isPresent();
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
