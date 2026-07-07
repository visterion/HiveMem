package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.RateLimiter;
import com.hivemem.auth.TokenService;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.write.WriteToolService;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
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
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ReadToolIntegrationTest.TestConfig.class)
@Testcontainers
class ReadToolIntegrationTest {

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

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReadToolService readToolService;

    @Autowired
    private WriteToolService writeToolService;

    @BeforeEach
    void resetDatabase() {
        rateLimiter.clearAll();
        dslContext.execute("TRUNCATE TABLE agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cell_attachments, attachments, cells CASCADE");
    }

    @Test
    void toolsListExposesImplementedReadHandlers() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[0].name").value("status"))
                .andExpect(jsonPath("$.result.tools[1].name").value("search"))
                .andExpect(jsonPath("$.result.tools[2].name").value("search_kg"))
                .andExpect(jsonPath("$.result.tools[3].name").value("get_cell"))
                .andExpect(jsonPath("$.result.tools[4].name").value("list"))
                .andExpect(jsonPath("$.result.tools[5].name").value("traverse"))
                .andExpect(jsonPath("$.result.tools[6].name").value("quick_facts"))
                .andExpect(jsonPath("$.result.tools[7].name").value("time_machine"))
                .andExpect(jsonPath("$.result.tools[8].name").value("history"))
                .andExpect(jsonPath("$.result.tools[9].name").value("facet_count"))
                .andExpect(jsonPath("$.result.tools[10].name").value("pending_approvals"))
                .andExpect(jsonPath("$.result.tools[11].name").value("reading_list"))
                .andExpect(jsonPath("$.result.tools[12].name").value("list_agents"))
                .andExpect(jsonPath("$.result.tools[13].name").value("diary_read"))
                .andExpect(jsonPath("$.result.tools[14].name").value("get_blueprint"))
                .andExpect(jsonPath("$.result.tools[15].name").value("list_documents"))
                .andExpect(jsonPath("$.result.tools[16].name").value("list_saved_searches"))
                .andExpect(jsonPath("$.result.tools[17].name").value("wake_up"));
    }

    @Test
    void statusToolReturnsCountsAndWingsFromSql() throws Exception {
        seedStatusRows();

        JsonNode content = callToolContent("status", Map.of());
        assertThat(content.path("cells").asInt()).isEqualTo(2);
        assertThat(content.path("facts").asInt()).isEqualTo(2);
        assertThat(content.path("tunnels").asInt()).isEqualTo(1);
        assertThat(content.path("pending").asInt()).isEqualTo(3);
        assertThat(content.path("last_activity").asText()).isEqualTo("2026-04-03T12:00:00Z");
        assertThat(content.path("realms").get(0).asText()).isEqualTo("alpha");
        assertThat(content.path("realms").get(1).asText()).isEqualTo("beta");
    }

    @Test
    void searchToolReturnsRankedDrawerResults() throws Exception {
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000501"),
                null,
                "Semantic oracle drawer",
                "alpha",
                "facts",
                "oracle",
                "system",
                1,
                "Semantic oracle summary",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-03T10:00:00Z"),
                OffsetDateTime.parse("2026-04-03T10:00:00Z"),
                null
        );
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000502"),
                null,
                "Keyword oracle drawer",
                "alpha",
                "facts",
                "oracle",
                "system",
                5,
                "Keyword oracle summary",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-03T10:05:00Z"),
                OffsetDateTime.parse("2026-04-03T10:05:00Z"),
                null
        );

        JsonNode results = callToolContent("search", Map.of("query", "semantic oracle", "limit", 10));
        assertThat(results.get(0).path("id").asText()).isEqualTo("00000000-0000-0000-0000-000000000501");
        assertThat(results.get(0).path("score_total").isNumber()).isTrue();
        assertThat(results.get(0).path("score_semantic").isNumber()).isTrue();
        assertThat(results.get(0).path("score_keyword").isNumber()).isTrue();
        // Keyword matching uses OR-of-lexemes semantics: both cells contain "oracle",
        // so both pass the kw > 0 hard filter. The "semantic oracle" cell matches both
        // query lexemes (higher ts_rank_cd) and ranks first; the "keyword oracle" cell
        // matches only "oracle" but still scores keyword > 0.
        assertThat(results).hasSize(2);

        // Query "oracle" matches both cells; weighting favours importance, so
        // the importance=1 cell ranks above the importance=5 cell.
        JsonNode weightedResults = callToolContent("search", Map.of(
                "query", "oracle",
                "limit", 10,
                "weight_semantic", 0.05,
                "weight_keyword", 0.05,
                "weight_recency", 0.05,
                "weight_importance", 0.75,
                "weight_popularity", 0.1
        ));
        assertThat(weightedResults).hasSize(2);
        assertThat(weightedResults.get(0).path("id").asText()).isEqualTo("00000000-0000-0000-0000-000000000501");
    }

    @Test
    void keywordScoreIsNormalizedByRankOverRankPlusOneWithoutSaturating() throws Exception {
        // Flag 32 (rank/(rank+1)) replaces the old LEAST(ts_rank_cd, 1.0) clamp.
        // Cell A repeats all three query lexemes many times so raw ts_rank_cd
        // comfortably exceeds 1.0 (the old clamp would force it down to exactly
        // 1.0); cell B contains a single occurrence of a single lexeme. Under
        // the old clamp both could collide at 1.0 despite very different match
        // richness; under flag 32 they must land at distinct, sub-1.0 scores.
        UUID richId = UUID.fromString("00000000-0000-0000-0000-000000000601");
        UUID sparseId = UUID.fromString("00000000-0000-0000-0000-000000000602");

        insertDrawer(
                richId,
                null,
                "alpha bravo charlie alpha bravo charlie alpha bravo charlie alpha bravo charlie",
                "alpha",
                "facts",
                "rank",
                "system",
                1,
                "alpha bravo charlie rich summary",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-03T10:00:00Z"),
                OffsetDateTime.parse("2026-04-03T10:00:00Z"),
                null
        );
        insertDrawer(
                sparseId,
                null,
                "alpha only, nothing else relevant here",
                "alpha",
                "facts",
                "rank",
                "system",
                1,
                "alpha sparse summary",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-03T10:05:00Z"),
                OffsetDateTime.parse("2026-04-03T10:05:00Z"),
                null
        );

        JsonNode results = callToolContent("search", Map.of("query", "alpha bravo charlie", "limit", 10));
        assertThat(results).hasSize(2);

        Map<String, Double> scoresById = new java.util.HashMap<>();
        for (JsonNode row : results) {
            scoresById.put(row.path("id").asText(), row.path("score_keyword").asDouble());
        }

        double richScore = scoresById.get(richId.toString());
        double sparseScore = scoresById.get(sparseId.toString());

        assertThat(richScore).isLessThan(1.0d);
        assertThat(sparseScore).isLessThan(1.0d);
        assertThat(sparseScore).isGreaterThan(0.0d);
        assertThat(richScore).isGreaterThan(sparseScore);
    }

    @Test
    void searchKgToolReturnsCommittedFactsOnly() throws Exception {
        seedStatusRows();

        JsonNode content = callToolContent("search_kg", Map.of("subject", "HiveMem", "limit", 10));
        assertThat(content.get(0).path("subject").asText()).isEqualTo("HiveMem");
        assertThat(content.get(0).path("predicate").asText()).isEqualTo("runs on");
        assertThat(content.get(0).path("object").asText()).isEqualTo("PostgreSQL");
        assertThat(content.get(1).path("object").asText()).isEqualTo("Java");
        assertThat(content).hasSize(2);
    }

    @Test
    void searchKgToolRejectsInvalidLimit() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":31,
                                  "method":"tools/call",
                                  "params":{
                                    "name":"search_kg",
                                    "arguments":{"subject":"HiveMem","limit":1000}
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Invalid limit"));
    }

    @Test
    void searchKgIlikePathUnchanged() throws Exception {
        seedStatusRows();

        JsonNode content = callToolContent("search_kg", Map.of("subject", "HiveMem", "limit", 10));
        assertThat(content.get(0).path("subject").asText()).isEqualTo("HiveMem");
        assertThat(content.get(0).path("predicate").asText()).isEqualTo("runs on");
        assertThat(content.get(0).path("object").asText()).isEqualTo("PostgreSQL");
        assertThat(content.get(1).path("object").asText()).isEqualTo("Java");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).has("score")).isFalse();
    }

    @Test
    void searchKgSemanticQueryFindsFactWithoutSubstringMatch() throws Exception {
        callToolContent("kg_add", Map.of(
                "subject", "Widget",
                "predicate", "manufactured by",
                "object_", "AcmeCorp"
        ));
        callToolContent("kg_add", Map.of(
                "subject", "Gadget",
                "predicate", "sold by",
                "object_", "OtherCorp"
        ));

        JsonNode content = callToolContent("search_kg", Map.of(
                "query", "Widget manufactured by AcmeCorp",
                "limit", 10
        ));

        assertThat(content).isNotEmpty();
        assertThat(content.get(0).path("subject").asText()).isEqualTo("Widget");
        assertThat(content.get(0).has("score")).isTrue();
        assertThat(content.get(0).path("score").asDouble()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void getDrawerToolReturnsStoredDrawerPayload() throws Exception {
        UUID drawerId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        insertDrawer(
                drawerId,
                null,
                "The JVM migration plan",
                "alpha",
                "facts",
                "jvm",
                "system",
                4,
                "Java migration slice",
                "reference",
                "archive",
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                null
        );

        JsonNode content = callToolContent("get_cell", Map.of(
                "cell_id", "00000000-0000-0000-0000-000000000111",
                "include", List.of("content", "tags", "key_points")
        ));
        assertThat(content.path("id").asText()).isEqualTo("00000000-0000-0000-0000-000000000111");
        assertThat(content.path("content").asText()).isEqualTo("The JVM migration plan");
        assertThat(content.path("tags").isArray()).isTrue();
        assertThat(content.path("tags")).isEmpty();
        assertThat(content.path("key_points").isArray()).isTrue();
        assertThat(content.path("key_points")).isEmpty();
        assertThat(content.has("parent_id")).isFalse();
        assertThat(content.has("created_by")).isFalse();
    }

    @Test
    void getCellDefaultIncludesActionabilityAndStatus() throws Exception {
        UUID cellId = UUID.fromString("00000000-0000-0000-0000-000000000112");
        insertDrawer(
                cellId,
                null,
                "Cell with workflow metadata",
                "alpha", "facts", "jvm", "system",
                3, "workflow cell", "reference",
                "actionable", "pending", "writer",
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                null
        );

        JsonNode content = callToolContent("get_cell", Map.of(
                "cell_id", cellId.toString()
        ));

        assertThat(content.path("actionability").asText()).isEqualTo("actionable");
        assertThat(content.path("status").asText()).isEqualTo("pending");
        assertThat(content.has("parent_id")).isFalse();
        assertThat(content.has("created_by")).isFalse();
    }

    @Test
    void getCellReturnsParentIdAndCreatedByWhenRequested() throws Exception {
        UUID cellId = UUID.fromString("00000000-0000-0000-0000-000000000113");
        insertDrawer(
                cellId,
                null,
                "Cell with attribution",
                "alpha", "facts", "jvm", "system",
                3, "attributed cell", "reference",
                "archive", "committed", "writer",
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                null
        );

        JsonNode content = callToolContent("get_cell", Map.of(
                "cell_id", cellId.toString(),
                "include", List.of("parent_id", "created_by")
        ));

        assertThat(content.path("parent_id").isNull()).isTrue();
        assertThat(content.path("created_by").asText()).isEqualTo("writer");
    }

    @Test
    void getCellReturnsLinkedAttachmentsWithPublicFieldsOnly() throws Exception {
        UUID cellId = UUID.fromString("00000000-0000-0000-0000-000000000114");
        insertDrawer(
                cellId, null, "[page=1] scanned invoice text",
                "documents", "facts", "invoices", "system",
                3, "HUK invoice", null,
                "archive", "committed", "writer",
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                null
        );
        UUID attachmentId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        insertAttachment(attachmentId, cellId, "application/pdf", "huk-rechnung.pdf", 84213L);

        JsonNode content = callToolContent("get_cell", Map.of("cell_id", cellId.toString()));

        assertThat(content.path("attachments").isArray()).isTrue();
        assertThat(content.path("attachments")).hasSize(1);
        JsonNode a = content.path("attachments").get(0);
        assertThat(a.path("id").asText()).isEqualTo(attachmentId.toString());
        assertThat(a.path("mime_type").asText()).isEqualTo("application/pdf");
        assertThat(a.path("original_filename").asText()).isEqualTo("huk-rechnung.pdf");
        assertThat(a.path("size_bytes").asLong()).isEqualTo(84213L);
        assertThat(a.has("s3_key_original")).isFalse();
        assertThat(a.has("file_hash")).isFalse();
        assertThat(a.has("s3_key_thumbnail")).isFalse();
        assertThat(a.has("uploaded_by")).isFalse();
        assertThat(a.has("created_at")).isFalse();
    }

    @Test
    void getCellReturnsEmptyAttachmentsWhenNoneLinked() throws Exception {
        UUID cellId = UUID.fromString("00000000-0000-0000-0000-000000000115");
        insertDrawer(
                cellId, null, "plain text cell",
                "alpha", "facts", "notes", "system",
                1, "no attachment", null,
                "archive", "committed", "writer",
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                null
        );

        JsonNode content = callToolContent("get_cell", Map.of("cell_id", cellId.toString()));

        assertThat(content.path("attachments").isArray()).isTrue();
        assertThat(content.path("attachments")).isEmpty();
    }

    @Test
    void getCellWithConfidenceIncludeReturnsAvgActiveFacts() throws Exception {
        UUID cellId = UUID.fromString("00000000-0000-0000-0000-000000000116");
        insertDrawer(
                cellId, null, "Doc with two facts",
                "documents", "facts", "invoices", "system",
                3, "confidence test cell", null,
                "archive", "committed", "writer",
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                null
        );
        // fact 1: confidence 0.8, active (no valid_until)
        insertFact(UUID.fromString("00000000-0000-0000-0000-000000000f01"), null,
                "doc", "has", "vendor_a", 0.8f, cellId,
                "committed", "system",
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                OffsetDateTime.parse("2026-04-03T12:00:00Z"), null);
        // fact 2: confidence 0.6, active
        insertFact(UUID.fromString("00000000-0000-0000-0000-000000000f02"), null,
                "doc", "has", "vendor_b", 0.6f, cellId,
                "committed", "system",
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                OffsetDateTime.parse("2026-04-03T12:00:00Z"), null);

        JsonNode content = callToolContent("get_cell", Map.of(
                "cell_id", cellId.toString(),
                "include", List.of("confidence")
        ));
        assertThat(content.path("id").asText()).isEqualTo(cellId.toString());
        assertThat(content.has("confidence")).isTrue();
        double conf = content.path("confidence").asDouble();
        assertThat(conf).isCloseTo(0.7, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void getCellWithConfidenceIncludeReturnsNullWhenNoActiveFacts() throws Exception {
        UUID cellId = UUID.fromString("00000000-0000-0000-0000-000000000117");
        insertDrawer(
                cellId, null, "Doc with no facts",
                "documents", "facts", "invoices", "system",
                1, "no-facts cell", null,
                "archive", "committed", "writer",
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                null
        );

        JsonNode content = callToolContent("get_cell", Map.of(
                "cell_id", cellId.toString(),
                "include", List.of("confidence")
        ));
        assertThat(content.path("id").asText()).isEqualTo(cellId.toString());
        assertThat(content.has("confidence")).isTrue();
        assertThat(content.path("confidence").isNull()).isTrue();
    }

    @Test
    void getCellWithoutConfidenceIncludeDoesNotExposeConfidenceField() throws Exception {
        UUID cellId = UUID.fromString("00000000-0000-0000-0000-000000000118");
        insertDrawer(
                cellId, null, "Doc - confidence not requested",
                "documents", "facts", "invoices", "system",
                1, "hidden confidence cell", null,
                "archive", "committed", "writer",
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                null
        );

        // Default get_cell (no explicit include) should NOT contain confidence
        JsonNode content = callToolContent("get_cell", Map.of("cell_id", cellId.toString()));
        assertThat(content.has("confidence")).isFalse();
    }

    @Test
    void getDrawerToolReturnsNullWhenDrawerDoesNotExist() throws Exception {
        JsonNode content = callToolContent("get_cell", Map.of("cell_id", "00000000-0000-0000-0000-000000000999"));
        assertThat(content.isNull()).isTrue();
    }

    @Test
    void getDrawerLogsAccessAutomatically() {
        UUID drawerId = UUID.fromString(
            (String) writeToolService.addCell(
                new AuthPrincipal("fixture-writer", AuthRole.WRITER),
                "test drawer for auto-log",
                "testing", "facts", "base",
                null, null, null, null, null, null, null, null, null, null
            ).get("id"));

        long beforeCount = dslContext.fetchCount(
            DSL.table("access_log"),
            DSL.field("cell_id").eq(drawerId)
        );

        readToolService.getCell(
            new AuthPrincipal("test-writer", AuthRole.WRITER),
            drawerId
        );

        long afterCount = dslContext.fetchCount(
            DSL.table("access_log"),
            DSL.field("cell_id").eq(drawerId)
        );
        org.junit.jupiter.api.Assertions.assertEquals(beforeCount + 1, afterCount);

        String accessedBy = dslContext
            .select(DSL.field("accessed_by", String.class))
            .from("access_log")
            .where(DSL.field("cell_id").eq(drawerId))
            .orderBy(DSL.field("accessed_at").desc())
            .limit(1)
            .fetchOne(DSL.field("accessed_by", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("test-writer", accessedBy);
    }

    @Test
    void listToolNoParamsReturnsRealmsWithCellCount() throws Exception {
        seedStatusRows();

        JsonNode content = callToolContent("list", Map.of());
        assertThat(content.get(0).path("value").asText()).isEqualTo("alpha");
        assertThat(content.get(0).path("label").asText()).isEqualTo("alpha");
        assertThat(content.get(0).path("cell_count").asInt()).isEqualTo(1);
        assertThat(content.get(1).path("value").asText()).isEqualTo("beta");
        assertThat(content.get(1).path("cell_count").asInt()).isEqualTo(1);
        assertThat(content).hasSize(2);
    }

    @Test
    void listToolWithRealmReturnsSignalsWithCellCount() throws Exception {
        seedStatusRows();
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                null,
                "Alpha strategy drawer",
                "alpha",
                "events",
                "notes",
                "system",
                2,
                "Strategy summary",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-03T11:00:00Z"),
                OffsetDateTime.parse("2026-04-03T11:00:00Z"),
                null
        );

        JsonNode content = callToolContent("list", Map.of("realm", "alpha"));
        assertThat(content.get(0).path("value").asText()).isEqualTo("events");
        assertThat(content.get(0).path("label").asText()).isEqualTo("events");
        assertThat(content.get(0).path("cell_count").asInt()).isEqualTo(1);
        assertThat(content.get(1).path("value").asText()).isEqualTo("facts");
        assertThat(content.get(1).path("cell_count").asInt()).isEqualTo(1);
        assertThat(content).hasSize(2);
    }

    @Test
    void listToolWithRealmAndSignalReturnsTopicsWithCellCount() throws Exception {
        seedStatusRows();
        insertDrawer(
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                null,
                "Alpha facts second topic",
                "alpha",
                "facts",
                "archive",
                "system",
                2,
                "Archive summary",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-03T11:00:00Z"),
                OffsetDateTime.parse("2026-04-03T11:00:00Z"),
                null
        );

        JsonNode content = callToolContent("list", Map.of("realm", "alpha", "signal", "facts"));
        assertThat(content.get(0).path("value").asText()).isEqualTo("archive");
        assertThat(content.get(0).path("label").asText()).isEqualTo("archive");
        assertThat(content.get(0).path("cell_count").asInt()).isEqualTo(1);
        assertThat(content.get(1).path("value").asText()).isEqualTo("milestones");
        assertThat(content.get(1).path("cell_count").asInt()).isEqualTo(1);
        assertThat(content).hasSize(2);
    }

    @Test
    void listToolWithRealmSignalTopicReturnsCellMetadata() throws Exception {
        seedStatusRows();

        JsonNode content = callToolContent("list", Map.of("realm", "alpha", "signal", "facts", "topic", "milestones"));
        assertThat(content).hasSize(1);
        assertThat(content.get(0).path("id").asText()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(content.get(0).path("summary").asText()).isEqualTo("Alpha summary");
        assertThat(content.get(0).path("importance").asInt()).isEqualTo(3);
        assertThat(content.get(0).path("created_at").asText()).isNotBlank();
    }

    @Test
    void listToolRejectsSignalWithoutRealm() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":60,
                                  "method":"tools/call",
                                  "params":{"name":"list","arguments":{"signal":"facts"}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("signal requires realm"));
    }

    @Test
    void listToolRejectsTopicWithoutSignal() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":61,
                                  "method":"tools/call",
                                  "params":{"name":"list","arguments":{"realm":"alpha","topic":"milestones"}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("topic requires realm and signal"));
    }

    @Test
    void traverseToolReturnsBidirectionalDepthLimitedEdges() throws Exception {
        seedStatusRows();
        UUID drawerThree = UUID.fromString("00000000-0000-0000-0000-000000000004");
        insertDrawer(
                drawerThree,
                null,
                "Third committed drawer",
                "alpha",
                "facts",
                "notes",
                "system",
                1,
                "Ops summary",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-04T10:00:00Z"),
                OffsetDateTime.parse("2026-04-04T10:00:00Z"),
                null
        );
        insertTunnel(
                UUID.fromString("00000000-0000-0000-0000-000000000203"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                drawerThree,
                "related_to",
                "Second to third link",
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-04T11:00:00Z"),
                OffsetDateTime.parse("2026-04-04T11:00:00Z"),
                null
        );

        JsonNode content = callToolContent("traverse", Map.of("cell_id", "00000000-0000-0000-0000-000000000002", "max_depth", 1));
        JsonNode edges = content.path("edges");
        assertThat(edges.get(0).path("from_cell").asText()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(edges.get(0).path("to_cell").asText()).isEqualTo("00000000-0000-0000-0000-000000000002");
        assertThat(edges.get(0).path("relation").asText()).isEqualTo("related_to");
        assertThat(edges.get(0).path("depth").asInt()).isEqualTo(1);
        assertThat(edges.get(1).path("from_cell").asText()).isEqualTo("00000000-0000-0000-0000-000000000002");
        assertThat(edges.get(1).path("to_cell").asText()).isEqualTo("00000000-0000-0000-0000-000000000004");
        assertThat(edges).hasSize(2);
        assertThat(content.path("node_count").asInt()).isEqualTo(3);
        assertThat(content.path("truncated").asBoolean()).isFalse();
    }

    @Test
    void traverseToolTruncatesWhenMaxNodesExceeded() throws Exception {
        UUID root = UUID.fromString("00000000-0000-0000-0000-000000000700");
        insertDrawer(
                root, null, "Chain node 0", "alpha", "facts", "chain", "system", 1,
                "Chain summary 0", null, null, "committed", "writer",
                OffsetDateTime.parse("2026-05-01T10:00:00Z"),
                OffsetDateTime.parse("2026-05-01T10:00:00Z"),
                null
        );
        UUID previous = root;
        for (int i = 1; i <= 11; i++) {
            UUID current = UUID.fromString(String.format("00000000-0000-0000-0000-0000000007%02d", i));
            insertDrawer(
                    current, null, "Chain node " + i, "alpha", "facts", "chain", "system", 1,
                    "Chain summary " + i, null, null, "committed", "writer",
                    OffsetDateTime.parse("2026-05-01T10:00:00Z").plusMinutes(i),
                    OffsetDateTime.parse("2026-05-01T10:00:00Z").plusMinutes(i),
                    null
            );
            insertTunnel(
                    UUID.fromString(String.format("00000000-0000-0000-0000-0000000008%02d", i)),
                    previous,
                    current,
                    "related_to",
                    "Chain link " + i,
                    "committed",
                    "writer",
                    OffsetDateTime.parse("2026-05-01T11:00:00Z").plusMinutes(i),
                    OffsetDateTime.parse("2026-05-01T11:00:00Z").plusMinutes(i),
                    null
            );
            previous = current;
        }

        JsonNode content = callToolContent("traverse", Map.of(
                "cell_id", root.toString(), "max_depth", 50, "max_nodes", 5));
        assertThat(content.path("truncated").asBoolean()).isTrue();
        assertThat(content.path("node_count").asInt()).isLessThanOrEqualTo(5);
        assertThat(content.path("edges")).isNotEmpty();

        // Every returned edge's endpoints must be within the counted node set.
        Set<String> countedNodes = new HashSet<>();
        countedNodes.add(root.toString());
        for (JsonNode edge : content.path("edges")) {
            countedNodes.add(edge.path("from_cell").asText());
            countedNodes.add(edge.path("to_cell").asText());
        }
        assertThat(countedNodes).hasSizeLessThanOrEqualTo(5);
        assertThat(countedNodes).hasSize(content.path("node_count").asInt());
    }

    @Test
    void traverseToolAdmitsInSetEdgesAfterNodeCapOverflow() throws Exception {
        // Triangle A-B, A-C, B-C plus a tail A-T1, T1-T2. UUIDs are chosen so that at
        // depth 2 the overflowing tail edge T1->T2 (from_cell ...0901) sorts BEFORE the
        // in-set triangle edge B->C (from_cell ...0903). With max_nodes=4 the tail edge
        // must be skipped (truncated=true) while B->C, whose endpoints are both already
        // counted, must still be admitted.
        UUID cellA = UUID.fromString("00000000-0000-0000-0000-000000000900");
        UUID cellT1 = UUID.fromString("00000000-0000-0000-0000-000000000901");
        UUID cellT2 = UUID.fromString("00000000-0000-0000-0000-000000000902");
        UUID cellB = UUID.fromString("00000000-0000-0000-0000-000000000903");
        UUID cellC = UUID.fromString("00000000-0000-0000-0000-000000000904");
        int i = 0;
        for (UUID cell : List.of(cellA, cellT1, cellT2, cellB, cellC)) {
            insertDrawer(
                    cell, null, "Triangle node " + i, "alpha", "facts", "triangle", "system", 1,
                    "Triangle summary " + i, null, null, "committed", "writer",
                    OffsetDateTime.parse("2026-05-02T10:00:00Z").plusMinutes(i),
                    OffsetDateTime.parse("2026-05-02T10:00:00Z").plusMinutes(i),
                    null
            );
            i++;
        }
        UUID[][] tunnels = {
                {UUID.fromString("00000000-0000-0000-0000-000000000911"), cellA, cellB},
                {UUID.fromString("00000000-0000-0000-0000-000000000912"), cellA, cellC},
                {UUID.fromString("00000000-0000-0000-0000-000000000913"), cellA, cellT1},
                {UUID.fromString("00000000-0000-0000-0000-000000000914"), cellB, cellC},
                {UUID.fromString("00000000-0000-0000-0000-000000000915"), cellT1, cellT2},
        };
        int j = 0;
        for (UUID[] tunnel : tunnels) {
            insertTunnel(
                    tunnel[0], tunnel[1], tunnel[2], "related_to", "Triangle link " + j,
                    "committed", "writer",
                    OffsetDateTime.parse("2026-05-02T11:00:00Z").plusMinutes(j),
                    OffsetDateTime.parse("2026-05-02T11:00:00Z").plusMinutes(j),
                    null
            );
            j++;
        }

        JsonNode content = callToolContent("traverse", Map.of(
                "cell_id", cellA.toString(), "max_depth", 3, "max_nodes", 4));

        assertThat(content.path("truncated").asBoolean()).isTrue();
        assertThat(content.path("node_count").asInt()).isEqualTo(4);

        boolean hasTriangleClosingEdge = false;
        Set<String> countedNodes = new HashSet<>();
        countedNodes.add(cellA.toString());
        for (JsonNode edge : content.path("edges")) {
            String from = edge.path("from_cell").asText();
            String to = edge.path("to_cell").asText();
            countedNodes.add(from);
            countedNodes.add(to);
            if (from.equals(cellB.toString()) && to.equals(cellC.toString())) {
                hasTriangleClosingEdge = true;
            }
        }
        // The in-set edge B->C sorts after the overflowing tail edge but must be admitted.
        assertThat(hasTriangleClosingEdge).isTrue();
        // Endpoint containment: no edge may reference a node beyond the counted set.
        assertThat(countedNodes).hasSize(content.path("node_count").asInt());
        assertThat(countedNodes).containsExactlyInAnyOrder(
                cellA.toString(), cellB.toString(), cellC.toString(), cellT1.toString());
        assertThat(countedNodes).doesNotContain(cellT2.toString());
    }

    @Test
    void quickFactsToolReturnsSubjectAndObjectMatches() throws Exception {
        seedStatusRows();
        insertFact(
                UUID.fromString("00000000-0000-0000-0000-000000000104"),
                null,
                "Viktor",
                "created",
                "HiveMem",
                0.88f,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-06T12:00:00Z"),
                OffsetDateTime.parse("2026-04-06T12:00:00Z"),
                null
        );

        JsonNode content = callToolContent("quick_facts", Map.of("entity", "HiveMem"));
        assertThat(content).hasSize(3);
        assertThat(content.get(0).path("subject").asText()).isEqualTo("Viktor");
        assertThat(content.get(0).path("object").asText()).isEqualTo("HiveMem");
        assertThat(content.get(1).path("object").asText()).isEqualTo("PostgreSQL");
        assertThat(content.get(2).path("object").asText()).isEqualTo("Java");
    }

    @Test
    void entityOverviewReturnsCellsFactsAndTunnelsForKnownSubject() throws Exception {
        UUID serverCell = UUID.fromString("00000000-0000-0000-0000-000000000601");
        UUID relatedCell = UUID.fromString("00000000-0000-0000-0000-000000000602");
        insertDrawer(
                serverCell,
                null,
                "hivemem-server runs the production stack",
                "hivemem",
                "facts",
                "infra",
                "system",
                3,
                "hivemem-server summary",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-10T10:00:00Z"),
                OffsetDateTime.parse("2026-04-10T10:00:00Z"),
                null
        );
        insertDrawer(
                relatedCell,
                null,
                "Companion note about hivemem-server topology",
                "hivemem",
                "facts",
                "infra",
                "system",
                2,
                "companion summary",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-10T10:05:00Z"),
                OffsetDateTime.parse("2026-04-10T10:05:00Z"),
                null
        );
        insertTunnel(
                UUID.fromString("00000000-0000-0000-0000-000000000603"),
                serverCell,
                relatedCell,
                "related_to",
                "Server to companion link",
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-10T10:10:00Z"),
                OffsetDateTime.parse("2026-04-10T10:10:00Z"),
                null
        );
        insertFact(
                UUID.fromString("00000000-0000-0000-0000-000000000604"),
                null,
                "hivemem-server",
                "runs_on",
                "lxc-145",
                0.95f,
                serverCell,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-10T10:15:00Z"),
                OffsetDateTime.parse("2026-04-10T10:15:00Z"),
                null
        );

        JsonNode content = callToolContent("entity_overview", Map.of("subject", "hivemem-server"));

        assertThat(content.path("cells").isArray()).isTrue();
        assertThat(content.path("cells")).isNotEmpty();
        assertThat(content.path("facts")).isNotEmpty();
        boolean hasExactFact = false;
        for (JsonNode fact : content.path("facts")) {
            if (fact.path("subject").asText().equals("hivemem-server")
                    && fact.path("predicate").asText().equals("runs_on")
                    && fact.path("object").asText().equals("lxc-145")) {
                hasExactFact = true;
            }
        }
        assertThat(hasExactFact).isTrue();
        assertThat(content.path("tunnels")).isNotEmpty();
    }

    @Test
    void entityOverviewReturnsEmptyArraysForUnknownSubject() throws Exception {
        JsonNode content = callToolContent("entity_overview", Map.of("subject", "no-such-entity-xyz"));

        assertThat(content.path("cells")).isEmpty();
        assertThat(content.path("facts")).isEmpty();
        assertThat(content.path("tunnels")).isEmpty();
    }

    @Test
    void timeMachineToolReturnsCurrentAndHistoricalSnapshots() throws Exception {
        seedAliceHistoryRows();

        JsonNode current = callToolContent("time_machine", Map.of("subject", "Alice", "limit", 10));
        assertThat(current).hasSize(2);
        assertThat(current.get(0).path("object").asText()).isEqualTo("New City");
        assertThat(current.get(1).path("object").asText()).isEqualTo("Acme");

        JsonNode historical = callToolContent("time_machine", Map.of(
                "subject", "Alice",
                "as_of", "2025-09-01T00:00:00Z",
                "limit", 10
        ));
        assertThat(historical).hasSize(2);
        assertThat(historical.get(0).path("object").asText()).isEqualTo("Old City");
        assertThat(historical.get(1).path("object").asText()).isEqualTo("Acme");
    }

    @Test
    void timeMachineBiTemporalRespectsIngestionCutoff() throws Exception {
        // Bi-temporal scenario: a fact is revised later with a backdated valid_from.
        // Same event-time query ("what was true at T_eff") must yield different results
        // depending on the knowledge cutoff ("what did we know at T_know").
        //
        //   Event time (valid_from):   T_eff = 2025-06-01
        //   Original ingested at:      2025-01-01 (known early)
        //   Revision ingested at:      2026-02-01 (learned later, backdated)
        //
        //   Knowledge cutoff T_mid = 2025-08-01  -> only the original is visible
        //   Knowledge cutoff T_late = 2026-03-01 -> both original and revision visible
        OffsetDateTime origIngested = OffsetDateTime.parse("2025-01-01T00:00:00Z");
        OffsetDateTime effectiveFrom = OffsetDateTime.parse("2025-06-01T00:00:00Z");
        OffsetDateTime revisionIngested = OffsetDateTime.parse("2026-02-01T00:00:00Z");
        UUID originalId = UUID.fromString("00000000-0000-0000-0000-000000000701");
        UUID revisedId = UUID.fromString("00000000-0000-0000-0000-000000000702");

        // Original fact: ingested early, effective from T_eff, superseded at revisionIngested.
        dslContext.execute(
                """
                INSERT INTO facts (id, parent_id, subject, predicate, object, confidence,
                                   source_id, status, created_by, created_at, valid_from, valid_until, ingested_at)
                VALUES (?, NULL, ?, ?, ?, ?, NULL, 'committed', 'writer',
                        ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """,
                originalId, "Bob", "lives_in", "Munich", 1.0f,
                origIngested, effectiveFrom, revisionIngested, origIngested
        );
        // Revision: ingested late, backdated effective to T_eff, still active.
        dslContext.execute(
                """
                INSERT INTO facts (id, parent_id, subject, predicate, object, confidence,
                                   source_id, status, created_by, created_at, valid_from, valid_until, ingested_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL, 'committed', 'writer',
                        ?::timestamptz, ?::timestamptz, NULL, ?::timestamptz)
                """,
                revisedId, originalId, "Bob", "lives_in", "Berlin", 1.0f,
                revisionIngested, effectiveFrom, revisionIngested
        );

        // Knowledge cutoff BETWEEN the two ingestion times: only original is visible.
        JsonNode midKnowledge = callToolContent("time_machine", Map.of(
                "subject", "Bob",
                "as_of", "2025-06-01T00:00:00Z",
                "as_of_ingestion", "2025-08-01T00:00:00Z",
                "limit", 10
        ));
        assertThat(midKnowledge).hasSize(1);
        assertThat(midKnowledge.get(0).path("object").asText()).isEqualTo("Munich");
        assertThat(midKnowledge.get(0).path("ingested_at").asText()).isNotBlank();

        // Knowledge cutoff AFTER the revision: both visible for the same effective time.
        JsonNode lateKnowledge = callToolContent("time_machine", Map.of(
                "subject", "Bob",
                "as_of", "2025-06-01T00:00:00Z",
                "as_of_ingestion", "2026-03-01T00:00:00Z",
                "limit", 10
        ));
        assertThat(lateKnowledge).hasSize(2);
    }

    @Test
    void historyIncludesIngestedAt() throws Exception {
        UUID originalId = UUID.fromString("00000000-0000-0000-0000-000000000801");
        UUID revisedId = UUID.fromString("00000000-0000-0000-0000-000000000802");
        insertDrawer(
                originalId, null, "Initial", "alpha", "facts", "bitemp", "system", 3,
                "V1", null, null, "committed", "writer",
                OffsetDateTime.parse("2025-01-01T00:00:00Z"),
                OffsetDateTime.parse("2025-01-01T00:00:00Z"),
                null
        );
        insertDrawer(
                revisedId, originalId, "Revised", "alpha", "facts", "bitemp", "system", 3,
                "V2", null, null, "committed", "writer",
                OffsetDateTime.parse("2025-03-01T00:00:00Z"),
                OffsetDateTime.parse("2025-02-01T00:00:00Z"),
                null
        );

        JsonNode content = callToolContent("history", Map.of(
                "type", "cell",
                "id", "00000000-0000-0000-0000-000000000802"
        ));
        assertThat(content).hasSize(2);
        assertThat(content.get(0).path("ingested_at").asText()).isNotBlank();
        assertThat(content.get(1).path("ingested_at").asText()).isNotBlank();
    }

    @Test
    void historyWithDrawerTypeReturnsOldestVersionFirst() throws Exception {
        UUID originalId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID revisedId = UUID.fromString("00000000-0000-0000-0000-000000000302");
        insertDrawer(
                originalId,
                null,
                "Original drawer content",
                "alpha",
                "facts",
                "history",
                "system",
                3,
                "Drawer V1",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2025-01-01T00:00:00Z"),
                OffsetDateTime.parse("2025-01-01T00:00:00Z"),
                null
        );
        insertDrawer(
                revisedId,
                originalId,
                "Revised drawer content",
                "alpha",
                "facts",
                "history",
                "system",
                3,
                "Drawer V2",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2025-02-01T00:00:00Z"),
                OffsetDateTime.parse("2025-02-01T00:00:00Z"),
                null
        );

        JsonNode content = callToolContent("history", Map.of("type", "cell", "id", "00000000-0000-0000-0000-000000000302"));
        assertThat(content).hasSize(2);
        assertThat(content.get(0).path("summary").asText()).isEqualTo("Drawer V1");
        assertThat(content.get(1).path("summary").asText()).isEqualTo("Drawer V2");
        assertThat(content.get(0).path("parent_id").isNull()).isTrue();
        assertThat(content.get(1).path("parent_id").asText()).isEqualTo("00000000-0000-0000-0000-000000000301");
    }

    @Test
    void historyWithFactTypeReturnsOldestVersionFirst() throws Exception {
        UUID originalId = UUID.fromString("00000000-0000-0000-0000-000000000401");
        UUID revisedId = UUID.fromString("00000000-0000-0000-0000-000000000402");
        insertFact(
                originalId,
                null,
                "BOGIS",
                "uses",
                "Camunda7",
                1.0f,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2025-01-01T00:00:00Z"),
                OffsetDateTime.parse("2025-01-01T00:00:00Z"),
                null
        );
        insertFact(
                revisedId,
                originalId,
                "BOGIS",
                "uses",
                "Temporal",
                1.0f,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2025-02-01T00:00:00Z"),
                OffsetDateTime.parse("2025-02-01T00:00:00Z"),
                null
        );

        JsonNode content = callToolContent("history", Map.of("type", "fact", "id", "00000000-0000-0000-0000-000000000402"));
        assertThat(content).hasSize(2);
        assertThat(content.get(0).path("object").asText()).isEqualTo("Camunda7");
        assertThat(content.get(1).path("object").asText()).isEqualTo("Temporal");
        assertThat(content.get(0).path("parent_id").isNull()).isTrue();
        assertThat(content.get(1).path("parent_id").asText()).isEqualTo("00000000-0000-0000-0000-000000000401");
    }

    @Test
    void historyRejectsInvalidType() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":50,
                                  "method":"tools/call",
                                  "params":{
                                    "name":"history",
                                    "arguments":{"type":"bogus","id":"00000000-0000-0000-0000-000000000001"}
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("type")));
    }

    @Test
    void pendingApprovalsToolReturnsPendingRowsFromView() throws Exception {
        seedStatusRows();

        JsonNode content = callToolContent("pending_approvals", Map.of());
        assertThat(content).hasSize(3);
        assertThat(content.get(0).path("type").asText()).isEqualTo("cell");
        assertThat(content.get(0).path("description").asText()).isEqualTo("Pending summary");
        assertThat(content.get(1).path("type").asText()).isEqualTo("fact");
        assertThat(content.get(1).path("description").asText()).isEqualTo("HiveMem -> runs on -> Python");
        assertThat(content.get(2).path("type").asText()).isEqualTo("tunnel");
        assertThat(content.get(2).path("description").asText()).isEqualTo("00000000-0000-0000-0000-000000000002 -[refines]-> 00000000-0000-0000-0000-000000000001");
    }

    @Test
    void readingListToolReturnsUnreadAndReadingReferencesWithLinkedDrawerCounts() throws Exception {
        seedStatusRows();
        UUID unreadReference = UUID.fromString("00000000-0000-0000-0000-000000000601");
        UUID readingReference = UUID.fromString("00000000-0000-0000-0000-000000000602");
        UUID archivedReference = UUID.fromString("00000000-0000-0000-0000-000000000603");
        insertReference(
                unreadReference,
                "PostgreSQL guide",
                "https://example.com/postgres",
                "Ada",
                "book",
                "unread",
                null,
                new String[]{"db"},
                1,
                OffsetDateTime.parse("2026-04-06T10:00:00Z")
        );
        insertReference(
                readingReference,
                "Java migration notes",
                "https://example.com/java",
                "Bob",
                "article",
                "reading",
                null,
                new String[]{"java"},
                3,
                OffsetDateTime.parse("2026-04-06T11:00:00Z")
        );
        insertReference(
                archivedReference,
                "Archived note",
                null,
                null,
                "article",
                "archived",
                null,
                null,
                5,
                OffsetDateTime.parse("2026-04-06T12:00:00Z")
        );
        linkReference(
                UUID.fromString("00000000-0000-0000-0000-000000000701"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                unreadReference,
                "source",
                OffsetDateTime.parse("2026-04-06T13:00:00Z")
        );
        linkReference(
                UUID.fromString("00000000-0000-0000-0000-000000000702"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                readingReference,
                "extends",
                OffsetDateTime.parse("2026-04-06T13:30:00Z")
        );
        linkReference(
                UUID.fromString("00000000-0000-0000-0000-000000000703"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                readingReference,
                "source",
                OffsetDateTime.parse("2026-04-06T13:40:00Z")
        );

        JsonNode content = callToolContent("reading_list", Map.of());
        assertThat(content).hasSize(2);
        assertThat(content.get(0).path("title").asText()).isEqualTo("PostgreSQL guide");
        assertThat(content.get(0).path("linked_cells").asInt()).isEqualTo(1);
        assertThat(content.get(0).path("status").asText()).isEqualTo("unread");
        assertThat(content.get(1).path("title").asText()).isEqualTo("Java migration notes");
        assertThat(content.get(1).path("linked_cells").asInt()).isEqualTo(2);
        assertThat(content.get(1).path("status").asText()).isEqualTo("reading");

        JsonNode filtered = callToolContent("reading_list", Map.of("ref_type", "article"));
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).path("title").asText()).isEqualTo("Java migration notes");
        assertThat(filtered.get(0).path("ref_type").asText()).isEqualTo("article");
    }

    @Test
    void listAgentsDiaryReadGetBlueprintAndWakeUpReturnRowsFromSql() throws Exception {
        insertAgent(
                "alpha-agent",
                "Migrate the Java server",
                "daily",
                OffsetDateTime.parse("2026-04-07T09:00:00Z")
        );
        insertAgent(
                "beta-agent",
                "Keep docs updated",
                null,
                OffsetDateTime.parse("2026-04-07T10:00:00Z")
        );
        insertDiaryEntry(
                UUID.fromString("00000000-0000-0000-0000-000000000801"),
                "alpha-agent",
                "First diary entry",
                OffsetDateTime.parse("2026-04-07T11:00:00Z")
        );
        insertDiaryEntry(
                UUID.fromString("00000000-0000-0000-0000-000000000802"),
                "alpha-agent",
                "Second diary entry",
                OffsetDateTime.parse("2026-04-07T12:00:00Z")
        );
        UUID blueprintOne = UUID.fromString("00000000-0000-0000-0000-000000000901");
        UUID blueprintTwo = UUID.fromString("00000000-0000-0000-0000-000000000902");
        UUID blueprintThree = UUID.fromString("00000000-0000-0000-0000-000000000903");
        insertBlueprint(
                blueprintOne,
                "alpha",
                "Alpha blueprint",
                "Narrative v1",
                new String[]{"planning", "delivery"},
                new UUID[]{UUID.fromString("00000000-0000-0000-0000-000000000001")},
                "writer",
                OffsetDateTime.parse("2026-04-07T13:00:00Z"),
                OffsetDateTime.parse("2026-04-07T13:00:00Z"),
                null
        );
        insertBlueprint(
                blueprintTwo,
                "alpha",
                "Alpha blueprint",
                "Narrative v2",
                new String[]{"planning", "delivery", "archive"},
                new UUID[]{UUID.fromString("00000000-0000-0000-0000-000000000002")},
                "writer",
                OffsetDateTime.parse("2026-04-08T13:00:00Z"),
                OffsetDateTime.parse("2026-04-08T13:00:00Z"),
                null
        );
        insertBlueprint(
                blueprintThree,
                "beta",
                "Beta blueprint",
                "Narrative beta",
                new String[]{"delivery"},
                new UUID[]{UUID.fromString("00000000-0000-0000-0000-000000000002")},
                "writer",
                OffsetDateTime.parse("2026-04-08T14:00:00Z"),
                OffsetDateTime.parse("2026-04-08T14:00:00Z"),
                null
        );
        insertIdentity("l0_identity", "You are Alice.", 3, OffsetDateTime.parse("2026-04-07T14:00:00Z"));
        insertIdentity("l1_critical", "Remember the migration plan.", 4, OffsetDateTime.parse("2026-04-07T14:05:00Z"));

        JsonNode agents = callToolContent("list_agents", Map.of());
        assertThat(agents).hasSize(2);
        assertThat(agents.get(0).path("name").asText()).isEqualTo("alpha-agent");
        assertThat(agents.get(1).path("name").asText()).isEqualTo("beta-agent");

        JsonNode diary = callToolContent("diary_read", Map.of("agent", "alpha-agent"));
        assertThat(diary).hasSize(2);
        assertThat(diary.get(0).path("entry").asText()).isEqualTo("Second diary entry");
        assertThat(diary.get(1).path("entry").asText()).isEqualTo("First diary entry");

        JsonNode diaryLimited = callToolContent("diary_read", Map.of("agent", "alpha-agent", "last_n", 1));
        assertThat(diaryLimited).hasSize(1);
        assertThat(diaryLimited.get(0).path("entry").asText()).isEqualTo("Second diary entry");

        JsonNode blueprint = callToolContent("get_blueprint", Map.of("realm", "alpha"));
        assertThat(blueprint).hasSize(2);
        assertThat(blueprint.get(0).path("id").asText()).isEqualTo(blueprintTwo.toString());
        assertThat(blueprint.get(0).path("signal_order").get(2).asText()).isEqualTo("archive");
        assertThat(blueprint.get(1).path("id").asText()).isEqualTo(blueprintOne.toString());
        assertThat(blueprint.get(1).path("key_cells").get(0).asText()).isEqualTo("00000000-0000-0000-0000-000000000001");

        JsonNode allBlueprints = callToolContent("get_blueprint", Map.of());
        assertThat(allBlueprints).hasSize(3);
        assertThat(allBlueprints.get(0).path("id").asText()).isEqualTo(blueprintTwo.toString());
        assertThat(allBlueprints.get(1).path("id").asText()).isEqualTo(blueprintOne.toString());
        assertThat(allBlueprints.get(2).path("id").asText()).isEqualTo(blueprintThree.toString());
        assertThat(allBlueprints.get(2).path("realm").asText()).isEqualTo("beta");

        JsonNode wakeUp = callToolContent("wake_up", Map.of());
        assertThat(wakeUp.path("identity").asText()).isNotEmpty();
        assertThat(wakeUp.path("role").asText()).isNotEmpty();
        JsonNode context = wakeUp.path("context");
        assertThat(context.path("l0_identity").path("content").asText()).isEqualTo("You are Alice.");
        assertThat(context.path("l0_identity").path("token_count").asInt()).isEqualTo(3);
        assertThat(context.path("l1_critical").path("content").asText()).isEqualTo("Remember the migration plan.");
        assertThat(context.path("l1_critical").path("token_count").asInt()).isEqualTo(4);
    }

    @Test
    void wakeUpReturnsArbitraryIdentityKeys() throws Exception {
        insertIdentity("who-am-i", "Vu, Product Owner", 4,
                OffsetDateTime.parse("2026-04-24T15:58:06Z"));
        insertIdentity("current-focus", "MTV refactor, auth cleanup", 6,
                OffsetDateTime.parse("2026-04-24T15:58:08Z"));
        insertIdentity("my_custom_slot_42", "free-form content", 3,
                OffsetDateTime.parse("2026-04-24T15:58:10Z"));

        JsonNode wakeUp = callToolContent("wake_up", Map.of());
        JsonNode context = wakeUp.path("context");

        assertThat(context.path("who-am-i").path("content").asText())
                .isEqualTo("Vu, Product Owner");
        assertThat(context.path("who-am-i").path("token_count").asInt()).isEqualTo(4);
        assertThat(context.path("current-focus").path("content").asText())
                .isEqualTo("MTV refactor, auth cleanup");
        assertThat(context.path("my_custom_slot_42").path("content").asText())
                .isEqualTo("free-form content");
        assertThat(context.path("l0_identity").isMissingNode()).isTrue();
    }

    @Test
    void readingListToolRejectsInvalidLimit() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":20,
                                  "method":"tools/call",
                                  "params":{"name":"reading_list","arguments":{"limit":0}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Invalid limit"));

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":201,
                                  "method":"tools/call",
                                  "params":{"name":"reading_list","arguments":{"limit":101}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Invalid limit"));

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":202,
                                  "method":"tools/call",
                                  "params":{"name":"reading_list","arguments":{"limit":1.9}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Invalid limit"));
    }

    @Test
    void diaryReadToolRejectsInvalidLimit() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":21,
                                  "method":"tools/call",
                                  "params":{"name":"diary_read","arguments":{"agent":"alpha-agent","last_n":101}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Invalid last_n"));

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":211,
                                  "method":"tools/call",
                                  "params":{"name":"diary_read","arguments":{"agent":"alpha-agent","last_n":1.5}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Invalid last_n"));
    }

    @Test
    void diaryReadToolRejectsMissingAgent() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":22,
                                  "method":"tools/call",
                                  "params":{"name":"diary_read","arguments":{}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Missing agent"));

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":221,
                                  "method":"tools/call",
                                  "params":{"name":"diary_read","arguments":{"agent":123}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Missing agent"));
    }

    @Test
    void searchRejectsAttachmentsIncludeField() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> CellFieldSelection.forSearch(java.util.List.of("attachments")));
    }

    private void seedStatusRows() {
        UUID committedDrawer = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondCommittedDrawer = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID pendingDrawer = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID committedFact = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID secondCommittedFact = UUID.fromString("00000000-0000-0000-0000-000000000103");
        UUID pendingFact = UUID.fromString("00000000-0000-0000-0000-000000000102");
        UUID committedTunnel = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID pendingTunnel = UUID.fromString("00000000-0000-0000-0000-000000000202");

        insertDrawer(
                committedDrawer,
                null,
                "First committed drawer",
                "alpha",
                "facts",
                "milestones",
                "system",
                3,
                "Alpha summary",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-01T10:00:00Z"),
                OffsetDateTime.parse("2026-04-01T10:00:00Z"),
                null
        );
        insertDrawer(
                secondCommittedDrawer,
                committedDrawer,
                "Second committed drawer",
                "beta",
                "events",
                "milestones",
                "system",
                2,
                "Beta summary",
                null,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-02T10:00:00Z"),
                OffsetDateTime.parse("2026-04-02T10:00:00Z"),
                null
        );
        insertDrawer(
                pendingDrawer,
                null,
                "Pending drawer",
                "gamma",
                "facts",
                "pending-items",
                "system",
                1,
                "Pending summary",
                null,
                null,
                "pending",
                "writer",
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                OffsetDateTime.parse("2026-04-03T12:00:00Z"),
                null
        );

        insertFact(
                committedFact,
                null,
                "HiveMem",
                "runs on",
                "PostgreSQL",
                0.91f,
                committedDrawer,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-01T11:00:00Z"),
                OffsetDateTime.parse("2026-04-01T11:00:00Z"),
                null
        );
        insertFact(
                secondCommittedFact,
                null,
                "HiveMem",
                "runs on",
                "Java",
                0.73f,
                secondCommittedDrawer,
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-01T10:30:00Z"),
                OffsetDateTime.parse("2026-04-01T10:30:00Z"),
                null
        );
        insertFact(
                pendingFact,
                null,
                "HiveMem",
                "runs on",
                "Python",
                0.44f,
                pendingDrawer,
                "pending",
                "writer",
                OffsetDateTime.parse("2026-04-04T11:00:00Z"),
                OffsetDateTime.parse("2026-04-04T11:00:00Z"),
                null
        );
        insertTunnel(
                committedTunnel,
                committedDrawer,
                secondCommittedDrawer,
                "related_to",
                "Committed link",
                "committed",
                "writer",
                OffsetDateTime.parse("2026-04-02T11:00:00Z"),
                OffsetDateTime.parse("2026-04-02T11:00:00Z"),
                null
        );
        insertTunnel(
                pendingTunnel,
                secondCommittedDrawer,
                committedDrawer,
                "refines",
                "Pending link",
                "pending",
                "writer",
                OffsetDateTime.parse("2026-04-05T11:00:00Z"),
                OffsetDateTime.parse("2026-04-05T11:00:00Z"),
                null
        );
    }

    private void seedAliceHistoryRows() {
        insertFact(
                UUID.fromString("00000000-0000-0000-0000-000000000501"),
                null,
                "Alice",
                "works_at",
                "Acme",
                1.0f,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2025-01-01T00:00:00Z"),
                OffsetDateTime.parse("2025-01-01T00:00:00Z"),
                null
        );
        insertFact(
                UUID.fromString("00000000-0000-0000-0000-000000000502"),
                null,
                "Alice",
                "lives_in",
                "Old City",
                0.9f,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2025-06-01T00:00:00Z"),
                OffsetDateTime.parse("2025-06-01T00:00:00Z"),
                OffsetDateTime.parse("2025-12-31T00:00:00Z")
        );
        insertFact(
                UUID.fromString("00000000-0000-0000-0000-000000000503"),
                null,
                "Alice",
                "lives_in",
                "New City",
                1.0f,
                null,
                "committed",
                "writer",
                OffsetDateTime.parse("2025-12-31T00:00:00Z"),
                OffsetDateTime.parse("2025-12-31T00:00:00Z"),
                null
        );
    }

    private void insertDrawer(
            UUID id,
            UUID parentId,
            String content,
            String realm,
            String signal,
            String topic,
            String source,
            Integer importance,
            String summary,
            String insight,
            String actionability,
            String status,
            String createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil
    ) {
        dslContext.execute(
                """
                INSERT INTO cells (
                    id, parent_id, content, realm, signal, topic, source, importance, summary,
                    insight, actionability, status, created_by, created_at, valid_from, valid_until
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """,
                id, parentId, content, realm, signal, topic, source, importance, summary,
                insight, actionability, status, createdBy, createdAt, validFrom, validUntil
        );
    }

    private void insertFact(
            UUID id,
            UUID parentId,
            String subject,
            String predicate,
            String object,
            Float confidence,
            UUID sourceId,
            String status,
            String createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil
    ) {
        dslContext.execute(
                """
                INSERT INTO facts (
                    id, parent_id, subject, predicate, object, confidence, source_id,
                    status, created_by, created_at, valid_from, valid_until
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """,
                id, parentId, subject, predicate, object, confidence, sourceId,
                status, createdBy, createdAt, validFrom, validUntil
        );
    }

    private void insertTunnel(
            UUID id,
            UUID fromDrawer,
            UUID toDrawer,
            String relation,
            String note,
            String status,
            String createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil
    ) {
        dslContext.execute(
                """
                INSERT INTO tunnels (
                    id, from_cell, to_cell, relation, note, status, created_by, created_at, valid_from, valid_until
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """,
                id, fromDrawer, toDrawer, relation, note, status, createdBy, createdAt, validFrom, validUntil
        );
    }

    private void insertReference(
            UUID id,
            String title,
            String url,
            String author,
            String refType,
            String status,
            String notes,
            String[] tags,
            Integer importance,
            OffsetDateTime createdAt
    ) {
        dslContext.execute(
                """
                INSERT INTO references_ (
                    id, title, url, author, ref_type, status, notes, tags, importance, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz)
                """,
                id, title, url, author, refType, status, notes, tags, importance, createdAt
        );
    }

    private void linkReference(
            UUID id,
            UUID drawerId,
            UUID referenceId,
            String relation,
            OffsetDateTime createdAt
    ) {
        dslContext.execute(
                """
                INSERT INTO cell_references (
                    id, cell_id, reference_id, relation, created_at
                ) VALUES (?, ?, ?, ?, ?::timestamptz)
                """,
                id, drawerId, referenceId, relation, createdAt
        );
    }

    private void insertAttachment(
            UUID attachmentId,
            UUID cellId,
            String mimeType,
            String originalFilename,
            long sizeBytes
    ) {
        dslContext.execute(
                """
                INSERT INTO attachments (
                    id, file_hash, mime_type, original_filename, size_bytes,
                    s3_key_original, s3_key_thumbnail, uploaded_by, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, now())
                """,
                attachmentId, "hash-" + attachmentId, mimeType, originalFilename, sizeBytes,
                "attachments/" + attachmentId + ".pdf", "writer"
        );
        dslContext.execute(
                """
                INSERT INTO cell_attachments (id, cell_id, attachment_id, extraction_source, created_at)
                VALUES (?, ?, ?, true, now())
                """,
                UUID.randomUUID(), cellId, attachmentId
        );
    }

    private void insertAgent(
            String name,
            String focus,
            String schedule,
            OffsetDateTime createdAt
    ) {
        dslContext.execute(
                """
                INSERT INTO agents (
                    name, focus, schedule, created_at
                ) VALUES (?, ?, ?, ?::timestamptz)
                """,
                name, focus, schedule, createdAt
        );
    }

    private void insertDiaryEntry(
            UUID id,
            String agent,
            String entry,
            OffsetDateTime createdAt
    ) {
        dslContext.execute(
                """
                INSERT INTO agent_diary (
                    id, agent, entry, created_at
                ) VALUES (?, ?, ?, ?::timestamptz)
                """,
                id, agent, entry, createdAt
        );
    }

    private void insertBlueprint(
            UUID id,
            String realm,
            String title,
            String narrative,
            String[] signalOrder,
            UUID[] keyCells,
            String createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil
    ) {
        dslContext.execute(
                """
                INSERT INTO blueprints (
                    id, realm, title, narrative, signal_order, key_cells, created_by, created_at, valid_from, valid_until
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """,
                id, realm, title, narrative, signalOrder, keyCells, createdBy, createdAt, validFrom, validUntil
        );
    }

    private void insertIdentity(
            String key,
            String content,
            Integer tokenCount,
            OffsetDateTime updatedAt
    ) {
        dslContext.execute(
                """
                INSERT INTO identity (
                    key, content, token_count, updated_at
                ) VALUES (?, ?, ?, ?::timestamptz)
                ON CONFLICT (key) DO UPDATE SET content = excluded.content, token_count = excluded.token_count, updated_at = excluded.updated_at
                """,
                key, content, tokenCount, updatedAt
        );
    }

    private JsonNode callToolContent(String toolName, Map<String, Object> arguments) throws Exception {
        MvcResult result = mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
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

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        TokenService tokenService() {
            return new com.hivemem.auth.support.FixedTokenService(token -> switch (token) {
                case "good-token" -> Optional.of(new AuthPrincipal("token-1", AuthRole.WRITER));
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
