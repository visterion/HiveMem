package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.RateLimiter;
import com.hivemem.auth.TokenService;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.write.AdminToolService;
import org.jooq.DSLContext;
import org.jooq.Record;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WriteToolsIntegrationTest.TestConfig.class)
@Testcontainers
class WriteToolsIntegrationTest {

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
    private AdminToolService adminToolService;

    @Autowired
    private EmbeddingClient embeddingClient;

    @BeforeEach
    void resetDatabase() {
        rateLimiter.clearAll();
        dslContext.execute("TRUNCATE TABLE agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cells CASCADE");
    }

    @Test
    void writerCanAddCommittedFactAndSeeItInActiveFacts() throws Exception {
        JsonNode content = callToolContent("writer-token", "kg_add", Map.of(
                "subject", "HiveMem",
                "predicate", "runs on",
                "object_", "Java",
                "confidence", 0.75,
                "valid_from", "2026-04-03T12:00:00Z"
        ));
        assertThat(content.path("id").asText()).isNotBlank();
        assertThat(content.path("subject").asText()).isEqualTo("HiveMem");
        assertThat(content.path("predicate").asText()).isEqualTo("runs on");
        assertThat(content.path("object").asText()).isEqualTo("Java");
        assertThat(content.path("status").asText()).isEqualTo("committed");

        Record row = dslContext.fetchOne("""
                SELECT subject, predicate, "object", status, created_by
                FROM active_facts
                WHERE subject = ? AND predicate = ? AND "object" = ?
                """, "HiveMem", "runs on", "Java");
        org.junit.jupiter.api.Assertions.assertNotNull(row);
        org.junit.jupiter.api.Assertions.assertEquals("committed", row.get("status", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("writer-1", row.get("created_by", String.class));
    }

    @Test
    void writerCanAddDrawerWithEmbeddingAndProgressiveLayers() throws Exception {
        JsonNode content = callToolContent("writer-token", "add_cell", Map.ofEntries(
                Map.entry("content", "Semantic oracle drawer"),
                Map.entry("realm", "alpha"),
                Map.entry("signal", "facts"),
                Map.entry("topic", "search"),
                Map.entry("source", "system"),
                Map.entry("tags", List.of("semantic", "oracle")),
                Map.entry("importance", 2),
                Map.entry("summary", "Semantic oracle summary"),
                Map.entry("key_points", List.of("semantic", "oracle")),
                Map.entry("insight", "Used for semantic search"),
                Map.entry("actionability", "reference"),
                Map.entry("valid_from", "2026-04-03T12:00:00Z")
        ));
        assertThat(content.path("status").asText()).isEqualTo("committed");
        assertThat(content.path("realm").asText()).isEqualTo("alpha");
        assertThat(content.path("signal").asText()).isEqualTo("facts");

        Record row = dslContext.fetchOne("""
                SELECT content, realm, signal, topic, source, tags, importance, summary, key_points, insight, actionability, status, created_by, embedding
                FROM cells
                WHERE content = ?
                """, "Semantic oracle drawer");
        org.junit.jupiter.api.Assertions.assertNotNull(row);
        org.junit.jupiter.api.Assertions.assertEquals("alpha", row.get("realm", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("facts", row.get("signal", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("search", row.get("topic", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("system", row.get("source", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("Semantic oracle summary", row.get("summary", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("Used for semantic search", row.get("insight", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("reference", row.get("actionability", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("committed", row.get("status", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("writer-1", row.get("created_by", String.class));
        org.junit.jupiter.api.Assertions.assertNotNull(row.get("embedding"));
    }

    @Test
    void addDrawerWithoutDedupeThresholdAlwaysInserts() throws Exception {
        JsonNode content = callToolContent("writer-token", "add_cell", Map.of(
                "content", "Unique drawer content without dedupe",
                "realm", "alpha",
                "signal", "facts",
                "topic", "search"
        ));
        assertThat(content.path("inserted").asBoolean()).isTrue();
        assertThat(content.path("id").asText()).isNotBlank();
    }

    @Test
    void addDrawerWithDedupeThresholdInsertsWhenNoMatch() throws Exception {
        JsonNode content = callToolContent("writer-token", "add_cell", Map.of(
                "content", "Unique content with no near duplicates xyz987",
                "realm", "alpha",
                "signal", "facts",
                "topic", "search",
                "dedupe_threshold", 0.99
        ));
        assertThat(content.path("inserted").asBoolean()).isTrue();
        assertThat(content.path("id").asText()).isNotBlank();
    }

    @Test
    void addDrawerWithDedupeThresholdSkipsInsertWhenDuplicateExists() throws Exception {
        // First insert
        callToolContent("writer-token", "add_cell", Map.of(
                "content", "Duplicate oracle alpha",
                "realm", "alpha",
                "signal", "facts",
                "topic", "search",
                "summary", "Duplicate oracle alpha"
        ));

        // Second attempt with dedupe_threshold should detect duplicate and skip
        JsonNode content = callToolContent("writer-token", "add_cell", Map.of(
                "content", "Duplicate oracle beta",
                "realm", "alpha",
                "signal", "facts",
                "topic", "search",
                "dedupe_threshold", 0.80
        ));
        assertThat(content.path("inserted").asBoolean()).isFalse();
        assertThat(content.has("id")).isFalse();
        assertThat(content.path("duplicates").isArray()).isTrue();
        assertThat(content.path("duplicates")).isNotEmpty();
        assertThat(content.path("duplicates").get(0).path("summary").asText()).isEqualTo("Duplicate oracle alpha");
        assertThat(content.path("duplicates").get(0).path("similarity").isNumber()).isTrue();
    }

    @Test
    void addDrawerEmbedsExactlyOnceWithDedupeThreshold() throws Exception {
        FixedEmbeddingClient fixedClient = (FixedEmbeddingClient) embeddingClient;
        int countBefore = fixedClient.getEncodeDocumentCallCount();

        callToolContent("writer-token", "add_cell", Map.of(
                "content", "Duplicate oracle alpha",
                "realm", "alpha",
                "signal", "facts",
                "topic", "search",
                "dedupe_threshold", 0.5
        ));

        int countAfter = fixedClient.getEncodeDocumentCallCount();
        // Exactly one encodeDocument call for add_drawer with dedupe_threshold
        assertThat(countAfter - countBefore).isEqualTo(1);
    }

    @Test
    void agentKgAddForcesPendingAndShowsInPendingApprovals() throws Exception {
        JsonNode factContent = callToolContent("agent-token", "kg_add", Map.of(
                "subject", "Agentic fact",
                "predicate", "needs review",
                "object_", "yes",
                "status", "committed"
        ));
        assertThat(factContent.path("status").asText()).isEqualTo("pending");

        JsonNode pending = callToolContent("writer-token", "pending_approvals", Map.of());
        assertThat(pending.get(0).path("type").asText()).isEqualTo("fact");
        assertThat(pending.get(0).path("description").asText()).isEqualTo("Agentic fact -> needs review -> yes");

        Record row = dslContext.fetchOne("""
                SELECT status, created_by
                FROM facts
                WHERE subject = ? AND predicate = ? AND "object" = ?
                """, "Agentic fact", "needs review", "yes");
        org.junit.jupiter.api.Assertions.assertNotNull(row);
        org.junit.jupiter.api.Assertions.assertEquals("pending", row.get("status", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("agent-1", row.get("created_by", String.class));
    }

    @Test
    void kgAddDefaultInsertsEvenWithConflict() throws Exception {
        // Insert first fact
        callToolContent("writer-token", "kg_add", Map.of(
                "subject", "X",
                "predicate", "status",
                "object_", "active"
        ));
        // Insert second fact with same subject+predicate but different object — no on_conflict arg
        JsonNode content = callToolContent("writer-token", "kg_add", Map.of(
                "subject", "X",
                "predicate", "status",
                "object_", "retired"
        ));
        assertThat(content.path("inserted").asBoolean()).isTrue();
        assertThat(content.path("id").asText()).isNotBlank();
    }

    @Test
    void kgAddOnConflictReturnSkipsInsertAndReportsConflicts() throws Exception {
        // Insert first fact
        callToolContent("writer-token", "kg_add", Map.of(
                "subject", "Y",
                "predicate", "status",
                "object_", "active"
        ));
        // Attempt second with on_conflict=return
        JsonNode content = callToolContent("writer-token", "kg_add", Map.of(
                "subject", "Y",
                "predicate", "status",
                "object_", "retired",
                "on_conflict", "return"
        ));
        assertThat(content.path("inserted").asBoolean()).isFalse();
        assertThat(content.has("id")).isFalse();
        assertThat(content.path("conflicts").isArray()).isTrue();
        assertThat(content.path("conflicts")).isNotEmpty();
    }

    @Test
    void kgAddOnConflictRejectThrows() throws Exception {
        // Insert first fact
        callToolContent("writer-token", "kg_add", Map.of(
                "subject", "Z",
                "predicate", "status",
                "object_", "active"
        ));
        // Attempt second with on_conflict=reject — expect error response with conflict message
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":99,
                                  "method":"tools/call",
                                  "params":{
                                    "name":"kg_add",
                                    "arguments":{
                                      "subject":"Z",
                                      "predicate":"status",
                                      "object_":"retired",
                                      "on_conflict":"reject"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.message").value("kg_add rejected: conflicting active fact exists"));
    }

    @Test
    void kgAddOnConflictReturnNoConflictInserts() throws Exception {
        // No prior fact for W — on_conflict=return should just insert
        JsonNode content = callToolContent("writer-token", "kg_add", Map.of(
                "subject", "W",
                "predicate", "status",
                "object_", "active",
                "on_conflict", "return"
        ));
        assertThat(content.path("inserted").asBoolean()).isTrue();
        assertThat(content.path("id").asText()).isNotBlank();
    }

    @Test
    void kgAddInvalidOnConflictRejected() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":100,
                                  "method":"tools/call",
                                  "params":{
                                    "name":"kg_add",
                                    "arguments":{
                                      "subject":"V",
                                      "predicate":"status",
                                      "object_":"active",
                                      "on_conflict":"bogus"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void kgAddOnConflictReturnReportsConflictingActiveFacts() throws Exception {
        insertFact(
                UUID.fromString("00000000-0000-0000-0000-000000000101"),
                null,
                "HiveMem",
                "runs on",
                "PostgreSQL",
                0.9f,
                null,
                "committed",
                "writer-1",
                OffsetDateTime.parse("2026-04-01T10:00:00Z"),
                OffsetDateTime.parse("2026-04-01T10:00:00Z"),
                null
        );
        insertFact(
                UUID.fromString("00000000-0000-0000-0000-000000000102"),
                null,
                "HiveMem",
                "runs on",
                "Java",
                0.8f,
                null,
                "committed",
                "writer-1",
                OffsetDateTime.parse("2026-04-02T10:00:00Z"),
                OffsetDateTime.parse("2026-04-02T10:00:00Z"),
                null
        );
        insertFact(
                UUID.fromString("00000000-0000-0000-0000-000000000103"),
                null,
                "HiveMem",
                "ships with",
                "Java",
                0.7f,
                null,
                "committed",
                "writer-1",
                OffsetDateTime.parse("2026-04-02T11:00:00Z"),
                OffsetDateTime.parse("2026-04-02T11:00:00Z"),
                null
        );

        JsonNode content = callToolContent("writer-token", "kg_add", Map.of(
                "subject", "HiveMem",
                "predicate", "runs on",
                "object_", "Spring Boot",
                "on_conflict", "return"
        ));
        assertThat(content.path("inserted").asBoolean()).isFalse();
        JsonNode conflicts = content.path("conflicts");
        assertThat(conflicts).hasSize(2);
        List<String> existingObjects = new ArrayList<>();
        for (JsonNode row : conflicts) {
            existingObjects.add(row.path("existing_object").asText());
        }
        assertThat(existingObjects).containsExactlyInAnyOrder("PostgreSQL", "Java");
        for (JsonNode row : conflicts) {
            assertThat(row.path("valid_from").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?[+-]\\d{2}:\\d{2}");
        }
    }

    @Test
    void kgInvalidateRemovesFactFromActiveAndSearchPaths() throws Exception {
        UUID factId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        insertFact(
                factId,
                null,
                "Transient fact",
                "state",
                "active",
                1.0f,
                null,
                "committed",
                "writer-1",
                OffsetDateTime.parse("2026-04-01T10:00:00Z"),
                OffsetDateTime.parse("2026-04-01T10:00:00Z"),
                null
        );

        JsonNode invalidateContent = callToolContent("writer-token", "kg_invalidate", Map.of(
                "fact_id", "00000000-0000-0000-0000-000000000201"
        ));
        assertThat(invalidateContent.path("invalidated").asBoolean()).isTrue();

        JsonNode searchContent = callToolContent("writer-token", "search_kg", Map.of(
                "subject", "Transient fact"
        ));
        assertThat(searchContent).isEmpty();

        org.junit.jupiter.api.Assertions.assertNull(dslContext.fetchOne("""
                SELECT id
                FROM active_facts
                WHERE id = ?
                """, factId));
    }

    @Test
    void writerCanReviseCommittedFactAndPreserveFactHistory() throws Exception {
        UUID oldId = UUID.fromString("00000000-0000-0000-0000-000000000401");
        UUID sourceDrawerId = UUID.fromString("00000000-0000-0000-0000-000000000402");
        insertDrawer(sourceDrawerId, null, "Fact source", "alpha", "facts", "planning", "system", 1,
                "Source summary", null, null, "committed", "writer-1",
                OffsetDateTime.parse("2026-04-05T09:59:00Z"),
                OffsetDateTime.parse("2026-04-05T09:59:00Z"),
                null);
        insertFact(
                oldId,
                null,
                "HiveMem",
                "runs on",
                "Java",
                0.85f,
                sourceDrawerId,
                "committed",
                "writer-1",
                OffsetDateTime.parse("2026-04-05T10:00:00Z"),
                OffsetDateTime.parse("2026-04-05T10:00:00Z"),
                null
        );

        JsonNode reviseContent = callToolContent("writer-token", "revise_fact", Map.of(
                "old_id", "00000000-0000-0000-0000-000000000401",
                "new_object", "Spring Boot"
        ));
        assertThat(reviseContent.path("old_id").asText()).isEqualTo(oldId.toString());
        assertThat(reviseContent.path("new_id").asText()).isNotBlank();

        Record oldRow = dslContext.fetchOne("""
                SELECT id, valid_until, subject, predicate, "object", confidence, source_id, status, created_by
                FROM facts
                WHERE id = ?
                """, oldId);
        org.junit.jupiter.api.Assertions.assertNotNull(oldRow);
        org.junit.jupiter.api.Assertions.assertNotNull(oldRow.get("valid_until"));

        Record newRow = dslContext.fetchOne("""
                SELECT id, parent_id, subject, predicate, "object", confidence, source_id, status, created_by, valid_from
                FROM facts
                WHERE parent_id = ? AND "object" = ?
                """, oldId, "Spring Boot");
        org.junit.jupiter.api.Assertions.assertNotNull(newRow);
        org.junit.jupiter.api.Assertions.assertEquals(oldId, newRow.get("parent_id", UUID.class));
        org.junit.jupiter.api.Assertions.assertEquals("HiveMem", newRow.get("subject", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("runs on", newRow.get("predicate", String.class));
        org.junit.jupiter.api.Assertions.assertEquals(0.85d, newRow.get("confidence", Double.class), 0.000001d);
        org.junit.jupiter.api.Assertions.assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000402"),
                newRow.get("source_id", UUID.class));
        org.junit.jupiter.api.Assertions.assertEquals("Spring Boot", newRow.get("object", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("committed", newRow.get("status", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("writer-1", newRow.get("created_by", String.class));

        UUID newId = newRow.get("id", UUID.class);
        JsonNode history = callToolContent("writer-token", "history", Map.of(
                "type", "fact", "id", newId.toString()
        ));
        assertThat(history).hasSize(2);
        assertThat(history.get(0).path("id").asText()).isEqualTo(oldId.toString());
        assertThat(history.get(1).path("id").asText()).isEqualTo(newId.toString());
    }

    @Test
    void agentRevisingFactForcesPendingNewRow() throws Exception {
        UUID oldId = UUID.fromString("00000000-0000-0000-0000-000000000403");
        insertFact(
                oldId,
                null,
                "Agent fact",
                "status",
                "open",
                0.6f,
                null,
                "committed",
                "writer-1",
                OffsetDateTime.parse("2026-04-05T11:00:00Z"),
                OffsetDateTime.parse("2026-04-05T11:00:00Z"),
                null
        );

        JsonNode reviseContent = callToolContent("agent-token", "revise_fact", Map.of(
                "old_id", "00000000-0000-0000-0000-000000000403",
                "new_object", "closed"
        ));
        assertThat(reviseContent.path("old_id").asText()).isEqualTo(oldId.toString());
        assertThat(reviseContent.path("new_id").asText()).isNotBlank();

        Record newRow = dslContext.fetchOne("""
                SELECT parent_id, "object", status, created_by
                FROM facts
                WHERE parent_id = ? AND "object" = ?
                """, oldId, "closed");
        org.junit.jupiter.api.Assertions.assertNotNull(newRow);
        org.junit.jupiter.api.Assertions.assertEquals("pending", newRow.get("status", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("agent-1", newRow.get("created_by", String.class));
    }

    @Test
    void revisingConflictingFactRemovesOldObjectFromContradictionCheck() throws Exception {
        UUID oldId = UUID.fromString("00000000-0000-0000-0000-000000000404");
        insertFact(
                oldId,
                null,
                "HiveMem",
                "runs on",
                "PostgreSQL",
                0.9f,
                null,
                "committed",
                "writer-1",
                OffsetDateTime.parse("2026-04-05T12:00:00Z"),
                OffsetDateTime.parse("2026-04-05T12:00:00Z"),
                null
        );

        callToolContent("writer-token", "revise_fact", Map.of(
                "old_id", "00000000-0000-0000-0000-000000000404",
                "new_object", "Spring Boot"
        ));

        // After revising the old fact, on_conflict=return should find no conflicts
        JsonNode content = callToolContent("writer-token", "kg_add", Map.of(
                "subject", "HiveMem",
                "predicate", "runs on",
                "object_", "Spring Boot",
                "on_conflict", "return"
        ));
        // The revised fact now has object=Spring Boot, and we're adding the same object
        // so no conflict (conflicts only trigger for *different* objects)
        assertThat(content.path("inserted").asBoolean()).isTrue();
    }

    @Test
    void writerCanUpsertIdentityAndPersistTokenCount() throws Exception {
        JsonNode content = callToolContent("writer-token", "update_identity", Map.of(
                "key", "l0_identity",
                "content", "I am HiveMem."
        ));
        assertThat(content.path("key").asText()).isEqualTo("l0_identity");
        assertThat(content.path("token_count").asInt()).isEqualTo(3);

        Record row = dslContext.fetchOne("""
                SELECT content, token_count
                FROM identity
                WHERE key = ?
                """, "l0_identity");
        org.junit.jupiter.api.Assertions.assertNotNull(row);
        org.junit.jupiter.api.Assertions.assertEquals("I am HiveMem.", row.get("content", String.class));
        org.junit.jupiter.api.Assertions.assertEquals(3, row.get("token_count", Integer.class));
    }

    @Test
    void writerCanAddReferenceAndLinkItToDrawer() throws Exception {
        UUID drawerId = UUID.fromString("00000000-0000-0000-0000-000000000501");
        insertDrawer(drawerId, null, "Reference drawer", "eng", "facts", "refs", "system", 2,
                "Reference summary", null, null, "committed", "writer-1",
                OffsetDateTime.parse("2026-04-05T13:00:00Z"),
                OffsetDateTime.parse("2026-04-05T13:00:00Z"),
                null);

        JsonNode refContent = callToolContent("writer-token", "add_reference", Map.of(
                "title", "GraphRAG Survey 2024",
                "url", "https://example.com/graphrag",
                "author", "Zhang et al.",
                "ref_type", "paper",
                "status", "unread",
                "notes", "Worth reading",
                "tags", List.of("graph", "rag"),
                "importance", 2
        ));
        assertThat(refContent.path("title").asText()).isEqualTo("GraphRAG Survey 2024");
        assertThat(refContent.path("status").asText()).isEqualTo("unread");

        String refId = refContent.path("id").asText();
        Record referenceRow = dslContext.fetchOne("""
                SELECT title, url, author, ref_type, status, notes, tags, importance
                FROM references_
                WHERE id = ?
                """, UUID.fromString(refId));
        org.junit.jupiter.api.Assertions.assertNotNull(referenceRow);
        org.junit.jupiter.api.Assertions.assertEquals("GraphRAG Survey 2024", referenceRow.get("title", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("unread", referenceRow.get("status", String.class));

        JsonNode linkContent = callToolContent("writer-token", "link_reference", Map.of(
                "cell_id", "00000000-0000-0000-0000-000000000501",
                "reference_id", refId,
                "relation", "source"
        ));
        assertThat(linkContent.path("cell_id").asText()).isEqualTo(drawerId.toString());
        assertThat(linkContent.path("reference_id").asText()).isEqualTo(refId);
        assertThat(linkContent.path("relation").asText()).isEqualTo("source");

        Record linkRow = dslContext.fetchOne("""
                SELECT cell_id, reference_id, relation
                FROM cell_references
                WHERE cell_id = ? AND reference_id = ?
                """, drawerId, UUID.fromString(refId));
        org.junit.jupiter.api.Assertions.assertNotNull(linkRow);
        org.junit.jupiter.api.Assertions.assertEquals("source", linkRow.get("relation", String.class));
    }

    @Test
    void writerCanRegisterAgentAndWriteDiaryEntries() throws Exception {
        JsonNode agentContent = callToolContent("writer-token", "register_agent", Map.of(
                "name", "classifier",
                "focus", "Classify incoming drawers",
                "schedule", "nightly"
        ));
        assertThat(agentContent.path("name").asText()).isEqualTo("classifier");
        assertThat(agentContent.path("focus").asText()).isEqualTo("Classify incoming drawers");

        Record agentRow = dslContext.fetchOne("""
                SELECT focus, schedule
                FROM agents
                WHERE name = ?
                """, "classifier");
        org.junit.jupiter.api.Assertions.assertNotNull(agentRow);
        org.junit.jupiter.api.Assertions.assertEquals("Classify incoming drawers", agentRow.get("focus", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("nightly", agentRow.get("schedule", String.class));

        JsonNode diaryContent = callToolContent("writer-token", "diary_write", Map.of(
                "agent", "classifier",
                "entry", "Merged duplicates, kept most recent"
        ));
        assertThat(diaryContent.path("agent").asText()).isEqualTo("classifier");
        assertThat(diaryContent.path("id").asText()).isNotBlank();

        Record diaryRow = dslContext.fetchOne("""
                SELECT agent, entry
                FROM agent_diary
                WHERE agent = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, "classifier");
        org.junit.jupiter.api.Assertions.assertNotNull(diaryRow);
        org.junit.jupiter.api.Assertions.assertEquals("Merged duplicates, kept most recent", diaryRow.get("entry", String.class));
    }

    @Test
    void writerCanAppendBlueprintsAndClosePreviousVersion() throws Exception {
        JsonNode v1 = callToolContent("writer-token", "update_blueprint", Map.of(
                "realm", "eng",
                "title", "V1",
                "narrative", "First version",
                "signal_order", List.of("auth", "search")
        ));
        assertThat(v1.path("realm").asText()).isEqualTo("eng");
        assertThat(v1.path("title").asText()).isEqualTo("V1");

        JsonNode v2 = callToolContent("writer-token", "update_blueprint", Map.of(
                "realm", "eng",
                "title", "V2",
                "narrative", "Updated version",
                "signal_order", List.of("auth", "search", "infra")
        ));
        assertThat(v2.path("title").asText()).isEqualTo("V2");

        Record activeRow = dslContext.fetchOne("""
                SELECT title, valid_until, created_by
                FROM blueprints
                WHERE realm = ? AND valid_until IS NULL
                """, "eng");
        org.junit.jupiter.api.Assertions.assertNotNull(activeRow);
        org.junit.jupiter.api.Assertions.assertEquals("V2", activeRow.get("title", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("writer-1", activeRow.get("created_by", String.class));

        org.junit.jupiter.api.Assertions.assertEquals(2L, dslContext.fetchOne("""
                SELECT count(*) AS cnt
                FROM blueprints
                WHERE realm = ?
                """, "eng").get("cnt", Long.class));
    }

    @Test
    void writerCanReviseDrawerAndPreserveDrawerHistory() throws Exception {
        UUID drawerId = UUID.fromString("00000000-0000-0000-0000-000000000550");
        insertDrawer(drawerId, null, "Drawer V1", "eng", "facts", "docs", "system", 3,
                "Summary V1", new String[]{"docs"}, new String[]{"v1"}, "committed", "writer-1",
                OffsetDateTime.parse("2026-04-05T13:30:00Z"),
                OffsetDateTime.parse("2026-04-05T13:30:00Z"),
                null);

        JsonNode reviseContent = callToolContent("writer-token", "revise_cell", Map.of(
                "old_id", "00000000-0000-0000-0000-000000000550",
                "new_content", "Drawer V2",
                "new_summary", "Summary V2"
        ));
        assertThat(reviseContent.path("old_id").asText()).isEqualTo(drawerId.toString());

        String newId = reviseContent.path("new_id").asText();
        Record oldRow = dslContext.fetchOne("""
                SELECT valid_until
                FROM cells
                WHERE id = ?
                """, drawerId);
        org.junit.jupiter.api.Assertions.assertNotNull(oldRow);
        org.junit.jupiter.api.Assertions.assertNotNull(oldRow.get("valid_until"));

        Record newRow = dslContext.fetchOne("""
                SELECT parent_id, content, summary, importance, created_by, status
                FROM cells
                WHERE id = ?
                """, UUID.fromString(newId));
        org.junit.jupiter.api.Assertions.assertNotNull(newRow);
        org.junit.jupiter.api.Assertions.assertEquals(drawerId, newRow.get("parent_id", UUID.class));
        org.junit.jupiter.api.Assertions.assertEquals("Drawer V2", newRow.get("content", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("Summary V2", newRow.get("summary", String.class));
        org.junit.jupiter.api.Assertions.assertEquals(3, newRow.get("importance", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals("writer-1", newRow.get("created_by", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("committed", newRow.get("status", String.class));
    }

    @Test
    void agentAddTunnelForcesPendingAndRemoveTunnelClosesIt() throws Exception {
        UUID fromDrawerId = UUID.fromString("00000000-0000-0000-0000-000000000601");
        UUID toDrawerId = UUID.fromString("00000000-0000-0000-0000-000000000602");
        insertDrawer(fromDrawerId, null, "Tunnel from", "eng", "facts", "refs", "system", 2,
                "From summary", null, null, "committed", "writer-1",
                OffsetDateTime.parse("2026-04-05T14:00:00Z"),
                OffsetDateTime.parse("2026-04-05T14:00:00Z"),
                null);
        insertDrawer(toDrawerId, null, "Tunnel to", "eng", "facts", "refs", "system", 2,
                "To summary", null, null, "committed", "writer-1",
                OffsetDateTime.parse("2026-04-05T14:00:00Z"),
                OffsetDateTime.parse("2026-04-05T14:00:00Z"),
                null);

        JsonNode tunnelContent = callToolContent("agent-token", "add_tunnel", Map.of(
                "from_cell", "00000000-0000-0000-0000-000000000601",
                "to_cell", "00000000-0000-0000-0000-000000000602",
                "relation", "related_to",
                "note", "context link",
                "status", "committed"
        ));
        assertThat(tunnelContent.path("status").asText()).isEqualTo("pending");

        String tunnelId = tunnelContent.path("id").asText();
        Record tunnelRow = dslContext.fetchOne("""
                SELECT from_cell, to_cell, relation, note, status, created_by, valid_until
                FROM tunnels
                WHERE id = ?
                """, UUID.fromString(tunnelId));
        org.junit.jupiter.api.Assertions.assertNotNull(tunnelRow);
        org.junit.jupiter.api.Assertions.assertEquals("pending", tunnelRow.get("status", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("agent-1", tunnelRow.get("created_by", String.class));

        JsonNode removeContent = callToolContent("writer-token", "remove_tunnel", Map.of(
                "tunnel_id", tunnelId
        ));
        assertThat(removeContent.path("removed").asBoolean()).isTrue();

        Record removedRow = dslContext.fetchOne("""
                SELECT valid_until
                FROM tunnels
                WHERE id = ?
                """, UUID.fromString(tunnelId));
        org.junit.jupiter.api.Assertions.assertNotNull(removedRow.get("valid_until"));
    }

    @Test
    void adminCanLogAccessAndCheckHealth() throws Exception {
        UUID drawerId = UUID.fromString("00000000-0000-0000-0000-000000000701");
        insertDrawer(drawerId, null, "Popular drawer", "eng", "facts", "popularity", "system", 4,
                "Popular summary", null, null, "committed", "writer-1",
                OffsetDateTime.parse("2026-04-05T15:00:00Z"),
                OffsetDateTime.parse("2026-04-05T15:00:00Z"),
                null);

        // log_access is no longer a tool; call the service directly to exercise the write path
        Map<String, Object> logResult = adminToolService.logAccess(drawerId, null, "admin");
        assertThat((Boolean) logResult.get("logged")).isTrue();

        // Popularity refresh is now done by the scheduler; call the service directly
        Map<String, Object> refreshResult = adminToolService.refreshPopularity();
        assertThat((Boolean) refreshResult.get("refreshed")).isTrue();
        assertThat(refreshResult.get("cell_count")).isInstanceOf(Number.class);

        JsonNode healthContent = callToolContent("admin-token", "health", Map.of());
        assertThat(healthContent.path("db_connected").asBoolean()).isTrue();
        assertThat(healthContent.path("cells").asInt()).isEqualTo(1);
        assertThat(healthContent.path("facts").asInt()).isEqualTo(0);

        org.junit.jupiter.api.Assertions.assertEquals(1L, dslContext.fetchOne("""
                SELECT count(*) AS cnt
                FROM access_log
                WHERE cell_id = ?
                """, drawerId).get("cnt", Long.class));
        org.junit.jupiter.api.Assertions.assertEquals(1L, dslContext.fetchOne("""
                SELECT access_count
                FROM cell_popularity
                WHERE cell_id = ?
                """, drawerId).get("access_count", Long.class));
    }

    @Test
    void reviseFactRejectsMissingOrUnknownOldId() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":17,
                                  "method":"tools/call",
                                  "params":{
                                    "name":"revise_fact",
                                    "arguments":{"new_object":"Spring Boot"}
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Missing old_id"));

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":18,
                                  "method":"tools/call",
                                  "params":{
                                    "name":"revise_fact",
                                    "arguments":{
                                      "old_id":"00000000-0000-0000-0000-000000000499",
                                      "new_object":"Spring Boot"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Fact 00000000-0000-0000-0000-000000000499 not found or already revised"));
    }

    @Test
    void adminApprovePendingCommitsPendingRowsAcrossTables() throws Exception {
        UUID drawerId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID factId = UUID.fromString("00000000-0000-0000-0000-000000000302");
        UUID fromDrawerId = UUID.fromString("00000000-0000-0000-0000-000000000303");
        UUID toDrawerId = UUID.fromString("00000000-0000-0000-0000-000000000304");
        UUID tunnelId = UUID.fromString("00000000-0000-0000-0000-000000000305");

        insertDrawer(fromDrawerId, null, "From drawer", "alpha", "facts", "planning", "system", 2,
                "From summary", null, null, "committed", "writer-1",
                OffsetDateTime.parse("2026-04-01T09:00:00Z"),
                OffsetDateTime.parse("2026-04-01T09:00:00Z"),
                null);
        insertDrawer(toDrawerId, null, "To drawer", "alpha", "facts", "planning", "system", 2,
                "To summary", null, null, "committed", "writer-1",
                OffsetDateTime.parse("2026-04-01T09:00:00Z"),
                OffsetDateTime.parse("2026-04-01T09:00:00Z"),
                null);
        insertDrawer(drawerId, null, "Pending drawer", "alpha", "facts", "planning", "system", 2,
                "Pending summary", null, null, "pending", "agent-1",
                OffsetDateTime.parse("2026-04-01T09:00:00Z"),
                OffsetDateTime.parse("2026-04-01T09:00:00Z"),
                null);
        insertFact(factId, null, "Pending fact", "needs", "review", 0.5f, null, "pending", "agent-1",
                OffsetDateTime.parse("2026-04-01T09:00:00Z"),
                OffsetDateTime.parse("2026-04-01T09:00:00Z"),
                null);
        insertTunnel(tunnelId, fromDrawerId, toDrawerId, "related_to", "Pending tunnel", "pending", "agent-1",
                OffsetDateTime.parse("2026-04-01T09:00:00Z"),
                OffsetDateTime.parse("2026-04-01T09:00:00Z"),
                null);

        JsonNode approveContent = callToolContent("admin-token", "approve_pending", Map.of(
                "ids", List.of(
                        "00000000-0000-0000-0000-000000000301",
                        "00000000-0000-0000-0000-000000000302",
                        "00000000-0000-0000-0000-000000000305"
                ),
                "decision", "committed"
        ));
        assertThat(approveContent.path("decision").asText()).isEqualTo("committed");
        assertThat(approveContent.path("count").asInt()).isEqualTo(3);

        org.junit.jupiter.api.Assertions.assertEquals("committed", dslContext.fetchOne("""
                SELECT status FROM cells WHERE id = ?
                """, drawerId).get("status", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("committed", dslContext.fetchOne("""
                SELECT status FROM facts WHERE id = ?
                """, factId).get("status", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("committed", dslContext.fetchOne("""
                SELECT status FROM tunnels WHERE id = ?
                """, tunnelId).get("status", String.class));
    }

    @Test
    void nonAdminCannotApprovePending() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0","id":8,"method":"tools/call",
                                  "params":{
                                    "name":"approve_pending",
                                    "arguments":{"ids":[],"decision":"committed"}
                                  }
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(-32003))
                .andExpect(jsonPath("$.error.message").value("Tool not permitted: approve_pending"));
    }

    @Test
    void invalidApproveDecisionReturnsInvalidParams() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":9,
                                  "method":"tools/call",
                                  "params":{
                                    "name":"approve_pending",
                                    "arguments":{"ids":[],"decision":"maybe"}
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Invalid decision 'maybe'. Must be 'committed' or 'rejected'."));
    }

    @Test
    void malformedOrBlankRequiredArgsAreRejected() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":10,
                                  "method":"tools/call",
                                  "params":{
                                    "name":"kg_add",
                                    "arguments":{
                                      "predicate":"runs on",
                                      "object_":"Java"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Missing subject"));

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":11,
                                  "method":"tools/call",
                                  "params":{
                                    "name":"kg_add",
                                    "arguments":{
                                      "subject":"   ",
                                      "predicate":"runs on",
                                      "object_":"Java",
                                      "on_conflict":"return"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Missing subject"));
    }

    @Test
    void writerCanReclassifyCellInPlace() throws Exception {
        UUID cellId = UUID.fromString("00000000-0000-0000-0000-000000000800");
        insertDrawer(cellId, null, "Reclass content", "old-realm", "facts", "old-topic", "system", 2,
                "Summary", new String[]{"t"}, new String[]{"kp"}, "committed", "writer-1",
                OffsetDateTime.parse("2026-04-10T09:00:00Z"),
                OffsetDateTime.parse("2026-04-10T09:00:00Z"),
                null);

        JsonNode content = callToolContent("writer-token", "reclassify", Map.of(
                "cell_ids", List.of(cellId.toString()),
                "realm", "New Realm",
                "topic", "New Topic",
                "signal", "discoveries"
        ));
        assertThat(content.path("updated").asInt()).isEqualTo(1);
        assertThat(content.path("matched").asInt()).isEqualTo(1);

        Record row = dslContext.fetchOne("""
                SELECT realm, topic, signal, content, summary, valid_until, parent_id, status,
                       (SELECT count(*) FROM cells) AS total
                FROM cells
                WHERE id = ?
                """, cellId);
        org.junit.jupiter.api.Assertions.assertEquals("new-realm", row.get("realm", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("new-topic", row.get("topic", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("discoveries", row.get("signal", String.class));
        // content / summary untouched
        org.junit.jupiter.api.Assertions.assertEquals("Reclass content", row.get("content", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("Summary", row.get("summary", String.class));
        // in-place: no new row, no valid_until, no parent
        org.junit.jupiter.api.Assertions.assertNull(row.get("valid_until"));
        org.junit.jupiter.api.Assertions.assertNull(row.get("parent_id"));
        org.junit.jupiter.api.Assertions.assertEquals("committed", row.get("status", String.class));
        org.junit.jupiter.api.Assertions.assertEquals(1L, row.get("total", Long.class));
    }

    @Test
    void reclassifyCellPartialUpdateLeavesOtherFieldsUnchanged() throws Exception {
        UUID cellId = UUID.fromString("00000000-0000-0000-0000-000000000801");
        insertDrawer(cellId, null, "Partial", "orig-realm", "facts", "orig-topic", "system", 2,
                "Summary", null, null, "committed", "writer-1",
                OffsetDateTime.parse("2026-04-10T10:00:00Z"),
                OffsetDateTime.parse("2026-04-10T10:00:00Z"),
                null);

        JsonNode content = callToolContent("writer-token", "reclassify", Map.of(
                "cell_ids", List.of(cellId.toString()),
                "realm", "moved-realm"
        ));
        assertThat(content.path("updated").asInt()).isEqualTo(1);
        assertThat(content.path("matched").asInt()).isEqualTo(1);

        Record row = dslContext.fetchOne(
                "SELECT realm, topic, signal FROM cells WHERE id = ?", cellId);
        org.junit.jupiter.api.Assertions.assertEquals("moved-realm", row.get("realm", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("orig-topic", row.get("topic", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("facts", row.get("signal", String.class));
    }

    @Test
    void reclassifySignalOnlyLeavesTopicUnchangedAndViceVersa() throws Exception {
        // Regression: guards a signal/topic arg-order swap in the reclassify → bulkReclassify
        // → reclassifyCell service call chain (bulkReclassify(realm, signal, topic) vs
        // reclassifyCell(realm, topic, signal)).
        UUID cellId = UUID.fromString("00000000-0000-0000-0000-000000000820");
        insertDrawer(cellId, null, "SigTopic", "r", "facts", "orig-topic", "system", 1,
                "s", null, null, "committed", "writer-1",
                OffsetDateTime.parse("2026-04-10T17:00:00Z"),
                OffsetDateTime.parse("2026-04-10T17:00:00Z"),
                null);

        callToolContent("writer-token", "reclassify", Map.of(
                "cell_ids", List.of(cellId.toString()),
                "signal", "events"
        ));
        Record afterSignal = dslContext.fetchOne(
                "SELECT signal, topic FROM cells WHERE id = ?", cellId);
        org.junit.jupiter.api.Assertions.assertEquals("events", afterSignal.get("signal", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("orig-topic", afterSignal.get("topic", String.class));

        callToolContent("writer-token", "reclassify", Map.of(
                "cell_ids", List.of(cellId.toString()),
                "topic", "some-topic"
        ));
        Record afterTopic = dslContext.fetchOne(
                "SELECT signal, topic FROM cells WHERE id = ?", cellId);
        org.junit.jupiter.api.Assertions.assertEquals("some-topic", afterTopic.get("topic", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("events", afterTopic.get("signal", String.class));
    }

    @Test
    void reclassifyCellRejectsUnknownId() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0","id":40,"method":"tools/call",
                                  "params":{
                                    "name":"reclassify",
                                    "arguments":{
                                      "cell_ids":["00000000-0000-0000-0000-000000000899"],
                                      "realm":"x"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("cell not found"));
    }

    @Test
    void reclassifyCellRejectsClosedVersion() throws Exception {
        UUID cellId = UUID.fromString("00000000-0000-0000-0000-000000000802");
        insertDrawer(cellId, null, "Closed", "r", "facts", "t", "system", 1,
                "s", null, null, "committed", "writer-1",
                OffsetDateTime.parse("2026-04-10T11:00:00Z"),
                OffsetDateTime.parse("2026-04-10T11:00:00Z"),
                OffsetDateTime.parse("2026-04-10T12:00:00Z"));

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0","id":41,"method":"tools/call",
                                  "params":{
                                    "name":"reclassify",
                                    "arguments":{
                                      "cell_ids":["00000000-0000-0000-0000-000000000802"],
                                      "realm":"x"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value(
                        "cell version is not current — target the live version"));
    }

    @Test
    void reclassifyCellRejectsRejectedStatus() throws Exception {
        UUID cellId = UUID.fromString("00000000-0000-0000-0000-000000000803");
        insertDrawer(cellId, null, "Rejected", "r", "facts", "t", "system", 1,
                "s", null, null, "rejected", "writer-1",
                OffsetDateTime.parse("2026-04-10T13:00:00Z"),
                OffsetDateTime.parse("2026-04-10T13:00:00Z"),
                null);

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0","id":42,"method":"tools/call",
                                  "params":{
                                    "name":"reclassify",
                                    "arguments":{
                                      "cell_ids":["00000000-0000-0000-0000-000000000803"],
                                      "realm":"x"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("cannot reclassify rejected cell"));
    }

    @Test
    void reclassifyCellRequiresAtLeastOneClassificationField() throws Exception {
        UUID cellId = UUID.fromString("00000000-0000-0000-0000-000000000804");
        insertDrawer(cellId, null, "NoFields", "r", "facts", "t", "system", 1,
                "s", null, null, "committed", "writer-1",
                OffsetDateTime.parse("2026-04-10T14:00:00Z"),
                OffsetDateTime.parse("2026-04-10T14:00:00Z"),
                null);

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0","id":43,"method":"tools/call",
                                  "params":{
                                    "name":"reclassify",
                                    "arguments":{
                                      "cell_ids":["00000000-0000-0000-0000-000000000804"]
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value(
                        "at least one of realm/signal/topic required"));
    }

    @Test
    void reclassifyCellRejectsInvalidSignal() throws Exception {
        UUID cellId = UUID.fromString("00000000-0000-0000-0000-000000000805");
        insertDrawer(cellId, null, "BadSig", "r", "facts", "t", "system", 1,
                "s", null, null, "committed", "writer-1",
                OffsetDateTime.parse("2026-04-10T15:00:00Z"),
                OffsetDateTime.parse("2026-04-10T15:00:00Z"),
                null);

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0","id":44,"method":"tools/call",
                                  "params":{
                                    "name":"reclassify",
                                    "arguments":{
                                      "cell_ids":["00000000-0000-0000-0000-000000000805"],
                                      "signal":"nonsense"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value(
                        "signal must be one of facts/events/discoveries/preferences/advice"));
    }

    @Test
    void reclassifyCellPreservesEmbeddingTunnelsFactsReferences() throws Exception {
        UUID cellId = UUID.fromString("00000000-0000-0000-0000-000000000810");
        UUID otherCellId = UUID.fromString("00000000-0000-0000-0000-000000000811");
        UUID factId = UUID.fromString("00000000-0000-0000-0000-000000000812");
        UUID tunnelId = UUID.fromString("00000000-0000-0000-0000-000000000813");

        insertDrawer(cellId, null, "Linked", "r", "facts", "t", "system", 1,
                "s", null, null, "committed", "writer-1",
                OffsetDateTime.parse("2026-04-10T16:00:00Z"),
                OffsetDateTime.parse("2026-04-10T16:00:00Z"),
                null);
        insertDrawer(otherCellId, null, "Target", "r", "facts", "t", "system", 1,
                "s", null, null, "committed", "writer-1",
                OffsetDateTime.parse("2026-04-10T16:00:00Z"),
                OffsetDateTime.parse("2026-04-10T16:00:00Z"),
                null);
        insertFact(factId, null, "subj", "pred", "obj", 1.0f, cellId, "committed", "writer-1",
                OffsetDateTime.parse("2026-04-10T16:00:00Z"),
                OffsetDateTime.parse("2026-04-10T16:00:00Z"),
                null);
        insertTunnel(tunnelId, cellId, otherCellId, "related_to", "n", "committed", "writer-1",
                OffsetDateTime.parse("2026-04-10T16:00:00Z"),
                OffsetDateTime.parse("2026-04-10T16:00:00Z"),
                null);

        // capture embedding snapshot as text
        String embeddingBefore = dslContext.fetchOne(
                "SELECT embedding::text AS e FROM cells WHERE id = ?", cellId)
                .get("e", String.class);

        callToolContent("writer-token", "reclassify", Map.of(
                "cell_ids", List.of(cellId.toString()),
                "realm", "new-r"
        ));

        String embeddingAfter = dslContext.fetchOne(
                "SELECT embedding::text AS e FROM cells WHERE id = ?", cellId)
                .get("e", String.class);
        org.junit.jupiter.api.Assertions.assertEquals(embeddingBefore, embeddingAfter);

        org.junit.jupiter.api.Assertions.assertEquals(cellId,
                dslContext.fetchOne("SELECT source_id FROM facts WHERE id = ?", factId)
                        .get("source_id", UUID.class));
        org.junit.jupiter.api.Assertions.assertEquals(cellId,
                dslContext.fetchOne("SELECT from_cell FROM tunnels WHERE id = ?", tunnelId)
                        .get("from_cell", UUID.class));
    }

    @Test
    void rejectCellMarksCommittedCellRejectedAndHidesFromSearch() throws Exception {
        JsonNode added = callToolContent("writer-token", "add_cell", Map.of(
                "content", "Zebraflux quintessential reject marker phrase",
                "realm", "alpha",
                "signal", "facts",
                "topic", "search",
                "summary", "Zebraflux quintessential reject marker phrase"
        ));
        String cellId = added.path("id").asText();
        assertThat(cellId).isNotBlank();

        // The committed cell is searchable before rejection.
        JsonNode before = callToolContent("writer-token", "search", Map.of("query", "Zebraflux"));
        boolean foundBefore = false;
        for (JsonNode row : before) {
            if (cellId.equals(row.path("id").asText())) {
                foundBefore = true;
            }
        }
        assertThat(foundBefore).as("cell should be searchable before rejection").isTrue();

        JsonNode rejected = callToolContent("writer-token", "reject_cell", Map.of("cell_id", cellId));
        assertThat(rejected.path("id").asText()).isEqualTo(cellId);
        assertThat(rejected.path("status").asText()).isEqualTo("rejected");

        org.junit.jupiter.api.Assertions.assertEquals("rejected", dslContext.fetchOne(
                "SELECT status FROM cells WHERE id = ?", UUID.fromString(cellId)).get("status", String.class));

        // After rejection the cell no longer appears in search (search is over committed cells).
        JsonNode after = callToolContent("writer-token", "search", Map.of("query", "Zebraflux"));
        for (JsonNode row : after) {
            org.junit.jupiter.api.Assertions.assertNotEquals(cellId, row.path("id").asText());
        }
    }

    @Test
    void rejectCellIsIdempotentWhenAlreadyRejected() throws Exception {
        JsonNode added = callToolContent("writer-token", "add_cell", Map.of(
                "content", "Idempotent reject candidate",
                "realm", "alpha",
                "signal", "facts",
                "topic", "search"
        ));
        String cellId = added.path("id").asText();

        JsonNode first = callToolContent("writer-token", "reject_cell", Map.of("cell_id", cellId));
        assertThat(first.path("status").asText()).isEqualTo("rejected");

        JsonNode second = callToolContent("writer-token", "reject_cell", Map.of("cell_id", cellId));
        assertThat(second.path("id").asText()).isEqualTo(cellId);
        assertThat(second.path("status").asText()).isEqualTo("rejected");
    }

    @Test
    void rejectCellRejectsUnknownId() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0","id":50,"method":"tools/call",
                                  "params":{
                                    "name":"reject_cell",
                                    "arguments":{
                                      "cell_id":"00000000-0000-0000-0000-000000000899"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("cell not found"));
    }

    @Test
    void rejectCellRejectsNonLiveVersion() throws Exception {
        UUID cellId = UUID.fromString("00000000-0000-0000-0000-000000000850");
        insertDrawer(cellId, null, "Reject non-live", "r", "facts", "t", "system", 1,
                "s", null, null, "committed", "writer-1",
                OffsetDateTime.parse("2026-04-11T09:00:00Z"),
                OffsetDateTime.parse("2026-04-11T09:00:00Z"),
                null);

        // Revising closes the old version (sets valid_until), producing a new live revision.
        callToolContent("writer-token", "revise_cell", Map.of(
                "old_id", cellId.toString(),
                "new_content", "Reject non-live v2",
                "new_summary", "v2"
        ));

        // Rejecting the OLD, now-closed version must fail.
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0","id":51,"method":"tools/call",
                                  "params":{
                                    "name":"reject_cell",
                                    "arguments":{
                                      "cell_id":"00000000-0000-0000-0000-000000000850"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value(
                        "cell version is not current — target the live version"));
    }

    private void insertFact(
            UUID id,
            UUID parentId,
            String subject,
            String predicate,
            String object,
            float confidence,
            UUID sourceId,
            String status,
            String createdBy,
            OffsetDateTime validFrom,
            OffsetDateTime createdAt,
            OffsetDateTime validUntil
    ) {
        dslContext.execute("""
                INSERT INTO facts (id, parent_id, subject, predicate, "object", confidence, source_id,
                                   status, created_by, valid_from, created_at, valid_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """, id, parentId, subject, predicate, object, confidence, sourceId, status, createdBy, validFrom, createdAt, validUntil);
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
            String[] tags,
            String[] keyPoints,
            String status,
            String createdBy,
            OffsetDateTime validFrom,
            OffsetDateTime createdAt,
            OffsetDateTime validUntil
    ) {
        dslContext.execute("""
                INSERT INTO cells (id, parent_id, content, realm, signal, topic, source, importance,
                                     summary, tags, key_points, status, created_by, valid_from, created_at, valid_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """, id, parentId, content, realm, signal, topic, source, importance, summary, tags, keyPoints, status, createdBy, validFrom, createdAt, validUntil);
    }

    private void insertTunnel(
            UUID id,
            UUID fromDrawer,
            UUID toDrawer,
            String relation,
            String note,
            String status,
            String createdBy,
            OffsetDateTime validFrom,
            OffsetDateTime createdAt,
            OffsetDateTime validUntil
    ) {
        dslContext.execute("""
                INSERT INTO tunnels (id, from_cell, to_cell, relation, note, status, created_by, valid_from, created_at, valid_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """, id, fromDrawer, toDrawer, relation, note, status, createdBy, validFrom, createdAt, validUntil);
    }

    private JsonNode callToolContent(String token, String toolName, Map<String, Object> arguments) throws Exception {
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

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        TokenService tokenService() {
            return new com.hivemem.auth.support.FixedTokenService(token -> switch (token) {
                case "writer-token" -> Optional.of(new AuthPrincipal("writer-1", AuthRole.WRITER));
                case "agent-token" -> Optional.of(new AuthPrincipal("agent-1", AuthRole.AGENT));
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
