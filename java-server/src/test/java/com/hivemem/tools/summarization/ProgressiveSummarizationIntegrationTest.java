package com.hivemem.tools.summarization;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.cells.CellReadRepository;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.search.KgSearchRepository;
import com.hivemem.search.ConfidenceThresholds;
import com.hivemem.search.SearchWeightsProperties;
import com.hivemem.tools.read.CellFieldSelection;
import com.hivemem.tools.read.ReadToolService;
import com.hivemem.write.AdminToolRepository;
import com.hivemem.write.AdminToolService;
import com.hivemem.write.WriteToolRepository;
import com.hivemem.sync.InstanceConfig;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PushDispatcher;
import com.hivemem.write.WriteToolService;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for progressive summarization fields.
 *
 * <p>Covers persistence of all four fields, optional fields, actionability
 * constraint validation, duplicate checking with threshold sensitivity,
 * and field preservation across drawer revisions.
 */
@SpringBootTest(
        classes = ProgressiveSummarizationIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Testcontainers
@SuppressWarnings("unchecked")
class ProgressiveSummarizationIntegrationTest {

    private static final AuthPrincipal WRITER = new AuthPrincipal("writer-1", AuthRole.WRITER);
    private static final OffsetDateTime BASE_TIME = OffsetDateTime.parse("2026-04-15T10:00:00Z");

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
    private WriteToolService writeToolService;

    @Autowired
    private ReadToolService readToolService;

    @Autowired
    private DSLContext dslContext;

    @BeforeEach
    void resetDatabase() {
        dslContext.execute("TRUNCATE TABLE agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cells CASCADE");
    }

    private Map<String, Object> getCellFull(UUID cellId) {
        return readToolService.getCell(WRITER, cellId, CellFieldSelection.forGetCell(List.of(
                "content", "summary", "key_points", "insight", "tags",
                "importance", "source", "actionability", "status",
                "parent_id", "created_by", "created_at", "valid_from", "valid_until"
        )));
    }

    // -----------------------------------------------------------------------
    // 1. All progressive fields are persisted and returned via getDrawer
    // -----------------------------------------------------------------------

    @Test
    void allLayersPersistedAndReturnedViaGetDrawer() {
        Map<String, Object> created = writeToolService.addCell(
                WRITER,
                "We decided to migrate BOGIS from Camunda 7 to Temporal. Better DX and native Go support.",
                "engineering",
                "facts",
                "bogis",
                "claude-code",
                List.of("migration", "temporal"),
                1,
                "BOGIS migrating from Camunda 7 to Temporal",
                List.of("Camunda 7 to Temporal migration", "Better DX", "Native Go support"),
                "This unblocks the Go rewrite of the orchestration layer",
                "actionable",
                "committed",
                BASE_TIME,
                null
        );

        String drawerId = (String) created.get("id");
        assertThat(drawerId).isNotNull();

        Map<String, Object> drawer = getCellFull(UUID.fromString(drawerId));
        assertThat(drawer).isNotNull();
        assertThat(drawer.get("content")).isEqualTo(
                "We decided to migrate BOGIS from Camunda 7 to Temporal. Better DX and native Go support.");
        assertThat(drawer.get("summary")).isEqualTo("BOGIS migrating from Camunda 7 to Temporal");
        assertThat((List<String>) drawer.get("key_points"))
                .containsExactly("Camunda 7 to Temporal migration", "Better DX", "Native Go support");
        assertThat(drawer.get("insight")).isEqualTo("This unblocks the Go rewrite of the orchestration layer");
        assertThat(drawer.get("actionability")).isEqualTo("actionable");
    }

    // -----------------------------------------------------------------------
    // 2. Drawer with only content (summary/key_points/insight null) is valid
    // -----------------------------------------------------------------------

    @Test
    void drawerWithOnlyL0IsValidAndStored() {
        Map<String, Object> created = writeToolService.addCell(
                WRITER,
                "Minimal drawer without progressive layers",
                "test",
                "facts",
                "test",
                null,
                List.of(),
                null,
                null,           // no summary
                List.of(),      // empty key_points
                null,           // no insight
                null,           // no actionability
                "committed",
                BASE_TIME,
                null
        );

        Map<String, Object> drawer = getCellFull(
                UUID.fromString((String) created.get("id")));
        assertThat(drawer).isNotNull();
        assertThat(drawer.get("content")).isEqualTo("Minimal drawer without progressive layers");
        assertThat(drawer.get("summary")).isNull();
        assertThat((List<?>) drawer.get("key_points")).isEmpty();
        assertThat(drawer.get("insight")).isNull();
        assertThat(drawer.get("actionability")).isNull();
    }

    // -----------------------------------------------------------------------
    // 3. Actionability constraint — valid values
    //
    // NOTE: The Java migration (V0002) defines actionability as TEXT with
    // no CHECK constraint. The Python schema enforces
    //   CHECK (actionability IN ('actionable','reference','someday','archive')).
    // Until a migration adds the constraint on the Java track, any string is
    // accepted. These tests document what SHOULD be valid values and verify
    // they round-trip correctly.
    // -----------------------------------------------------------------------

    @Test
    void actionabilityActionableIsAccepted() {
        Map<String, Object> created = writeToolService.addCell(
                WRITER,
                "Actionable drawer",
                "test", "facts", "test",
                null, List.of(), null, "summary", List.of(), null,
                "actionable",
                "committed", BASE_TIME, null
        );
        Map<String, Object> drawer = readToolService.getCell(WRITER,
                UUID.fromString((String) created.get("id")));
        assertThat(drawer.get("actionability")).isEqualTo("actionable");
    }

    @Test
    void actionabilityReferenceIsAccepted() {
        Map<String, Object> created = writeToolService.addCell(
                WRITER,
                "Reference drawer",
                "test", "facts", "test",
                null, List.of(), null, "summary", List.of(), null,
                "reference",
                "committed", BASE_TIME, null
        );
        Map<String, Object> drawer = readToolService.getCell(WRITER,
                UUID.fromString((String) created.get("id")));
        assertThat(drawer.get("actionability")).isEqualTo("reference");
    }

    @Test
    void actionabilitySomedayIsAccepted() {
        Map<String, Object> created = writeToolService.addCell(
                WRITER,
                "Someday drawer",
                "test", "facts", "test",
                null, List.of(), null, "summary", List.of(), null,
                "someday",
                "committed", BASE_TIME, null
        );
        Map<String, Object> drawer = readToolService.getCell(WRITER,
                UUID.fromString((String) created.get("id")));
        assertThat(drawer.get("actionability")).isEqualTo("someday");
    }

    @Test
    void actionabilityArchiveIsAccepted() {
        Map<String, Object> created = writeToolService.addCell(
                WRITER,
                "Archive drawer",
                "test", "facts", "test",
                null, List.of(), null, "summary", List.of(), null,
                "archive",
                "committed", BASE_TIME, null
        );
        Map<String, Object> drawer = readToolService.getCell(WRITER,
                UUID.fromString((String) created.get("id")));
        assertThat(drawer.get("actionability")).isEqualTo("archive");
    }

    /**
     * Enforced by migration V0007__actionability_check.sql which adds
     * CHECK (actionability IN ('actionable','reference','someday','archive')).
     * Matches the Python schema (migrations/0001_initial.sql).
     */
    @Test
    void invalidActionabilityIsRejectedByCheckConstraint() {
        assertThatThrownBy(() -> writeToolService.addCell(
                WRITER,
                "Bad actionability drawer",
                "test", "facts", "test",
                null, List.of(), null, "summary", List.of(), null,
                "invalid_value",
                "committed", BASE_TIME, null
        )).hasMessageContaining("cells_actionability_check");
    }

    @Test
    void actionabilityNullIsAccepted() {
        Map<String, Object> created = writeToolService.addCell(
                WRITER,
                "Null actionability drawer",
                "test", "facts", "test",
                null, List.of(), null, "summary", List.of(), null,
                null,
                "committed", BASE_TIME, null
        );
        Map<String, Object> drawer = readToolService.getCell(WRITER,
                UUID.fromString((String) created.get("id")));
        assertThat(drawer.get("actionability")).isNull();
    }

    // -----------------------------------------------------------------------
    // 4. Duplicate checking with threshold sensitivity
    //
    // FixedEmbeddingClient maps "duplicate oracle" to (0.9, 0.9, 0.0, ...).
    // Two "duplicate oracle" texts produce identical vectors -> cosine
    // similarity = 1.0. A low threshold catches them; a high (>1.0) does not.
    // -----------------------------------------------------------------------

    @Test
    void addDrawerWithDedupeThresholdSkipsInsertWhenDuplicateExists() {
        writeToolService.addCell(
                WRITER,
                "Duplicate oracle alpha",
                "test", "facts", "dup",
                null, List.of(), null,
                "Duplicate oracle alpha",
                List.of(), null, null,
                "committed", BASE_TIME, null
        );

        // Same "duplicate oracle" prefix -> identical embedding -> similarity ~1.0
        // A low threshold (0.5) should detect the duplicate and skip insert.
        Map<String, Object> result = writeToolService.addCell(
                WRITER,
                "Duplicate oracle beta",
                "test", "facts", "dup",
                null, List.of(), null, null,
                List.of(), null, null,
                "committed", BASE_TIME.plusSeconds(1), 0.5);
        assertThat(result.get("inserted")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dupes = (List<Map<String, Object>>) result.get("duplicates");
        assertThat(dupes).isNotEmpty();
        assertThat((Double) dupes.get(0).get("similarity")).isGreaterThan(0.5);
    }

    @Test
    void addDrawerWithDedupeThresholdInsertsWhenNoMatch() {
        writeToolService.addCell(
                WRITER,
                "Duplicate oracle alpha",
                "test", "facts", "dup",
                null, List.of(), null,
                "Duplicate oracle alpha",
                List.of(), null, null,
                "committed", BASE_TIME, null
        );

        // FixedEmbeddingClient: "duplicate oracle alpha" and "duplicate oracle beta"
        // both map to the same vector (0.9, 0.9, 0.0, ...) -> similarity 1.0.
        // A threshold of 0.99 should still catch the duplicate.
        Map<String, Object> highThresholdResult = writeToolService.addCell(
                WRITER,
                "Duplicate oracle beta",
                "test", "facts", "dup",
                null, List.of(), null, null,
                List.of(), null, null,
                "committed", BASE_TIME.plusSeconds(1), 0.99);
        assertThat(highThresholdResult.get("inserted")).isEqualTo(false);

        // Completely different content should not match at a strict threshold.
        // Note: FixedEmbeddingClient is word-hash based, so unrelated content still has
        // low-but-nonzero cosine similarity (~0.56). A strict threshold (0.9) correctly
        // allows the insert.
        Map<String, Object> noMatchResult = writeToolService.addCell(
                WRITER,
                "Cooking Italian pasta recipes for dinner tonight",
                "test", "facts", "dup",
                null, List.of(), null, null,
                List.of(), null, null,
                "committed", BASE_TIME.plusSeconds(2), 0.9);
        assertThat(noMatchResult.get("inserted")).isEqualTo(true);
        assertThat(noMatchResult.get("id")).isNotNull();
    }

    @Test
    void addDrawerWithoutDedupeThresholdAlwaysInserts() {
        writeToolService.addCell(
                WRITER,
                "PostgreSQL vector search with pgvector",
                "eng", "facts", "db",
                null, List.of(), null, "pgvector search",
                List.of(), null, null,
                "committed", BASE_TIME, null
        );

        // Without dedupe_threshold, always inserts even for similar content.
        Map<String, Object> result = writeToolService.addCell(
                WRITER,
                "PostgreSQL vector search with pgvector",
                "eng", "facts", "db",
                null, List.of(), null, "pgvector search",
                List.of(), null, null,
                "committed", BASE_TIME.plusSeconds(1), null);
        assertThat(result.get("inserted")).isEqualTo(true);
        assertThat(result.get("id")).isNotNull();
    }

    // -----------------------------------------------------------------------
    // 5. Revise drawer preserves content, summary, key_points, and insight when only updating content
    // -----------------------------------------------------------------------

    @Test
    void reviseDrawerPreservesAllLayersWhenOnlyContentChanges() {
        Map<String, Object> original = writeToolService.addCell(
                WRITER,
                "Original content about auth migration",
                "eng", "facts", "auth",
                "system",
                List.of("auth", "migration"),
                1,
                "Auth migration v1",
                List.of("Migrate from Camunda", "Use Temporal", "Q3 deadline"),
                "This unblocks the Go rewrite",
                "actionable",
                "committed",
                BASE_TIME,
                null
        );
        UUID originalId = UUID.fromString((String) original.get("id"));

        Map<String, Object> revision = writeToolService.reviseCell(
                WRITER, originalId, "Updated content about auth migration complete", null);

        UUID newId = UUID.fromString((String) revision.get("new_id"));
        Map<String, Object> revised = getCellFull(newId);

        assertThat(revised).isNotNull();
        // content updated
        assertThat(revised.get("content")).isEqualTo("Updated content about auth migration complete");
        // summary preserved (newSummary was null)
        assertThat(revised.get("summary")).isEqualTo("Auth migration v1");
        // key_points preserved
        assertThat((List<String>) revised.get("key_points"))
                .containsExactly("Migrate from Camunda", "Use Temporal", "Q3 deadline");
        // insight preserved
        assertThat(revised.get("insight")).isEqualTo("This unblocks the Go rewrite");
        // Actionability preserved
        assertThat(revised.get("actionability")).isEqualTo("actionable");
        // Importance preserved
        assertThat(revised.get("importance")).isEqualTo(1);
    }

    @Test
    void reviseDrawerUpdatesSummaryWhilePreservingKeyPointsAndInsight() {
        Map<String, Object> original = writeToolService.addCell(
                WRITER,
                "Content about database optimization",
                "eng", "facts", "db",
                "system",
                List.of("db"),
                2,
                "DB optimization v1",
                List.of("Indexing strategy", "Query tuning"),
                "HNSW index is the bottleneck",
                "reference",
                "committed",
                BASE_TIME,
                null
        );
        UUID originalId = UUID.fromString((String) original.get("id"));

        Map<String, Object> revision = writeToolService.reviseCell(
                WRITER, originalId, "Revised DB optimization content", "DB optimization v2");

        UUID newId = UUID.fromString((String) revision.get("new_id"));
        Map<String, Object> revised = getCellFull(newId);

        assertThat(revised).isNotNull();
        assertThat(revised.get("content")).isEqualTo("Revised DB optimization content");
        assertThat(revised.get("summary")).isEqualTo("DB optimization v2");
        // key_points and insight carried over from original
        assertThat((List<String>) revised.get("key_points"))
                .containsExactly("Indexing strategy", "Query tuning");
        assertThat(revised.get("insight")).isEqualTo("HNSW index is the bottleneck");
        assertThat(revised.get("actionability")).isEqualTo("reference");
    }

    @Test
    void reviseDrawerCreatesNewRowAndClosesOld() {
        Map<String, Object> original = writeToolService.addCell(
                WRITER,
                "Version 1 content",
                "eng", "facts", "docs",
                "system",
                List.of(),
                3,
                "Summary v1",
                List.of("point-a"),
                "insight-v1",
                "someday",
                "committed",
                BASE_TIME,
                null
        );
        UUID originalId = UUID.fromString((String) original.get("id"));

        Map<String, Object> revision = writeToolService.reviseCell(
                WRITER, originalId, "Version 2 content", null);

        UUID newId = UUID.fromString((String) revision.get("new_id"));
        assertThat(newId).isNotEqualTo(originalId);

        // Old row is closed (valid_until is set)
        Map<String, Object> oldDrawer = getCellFull(originalId);
        assertThat(oldDrawer).isNotNull();
        assertThat(oldDrawer.get("valid_until")).isNotNull();

        // New row points to old via parent_id
        Map<String, Object> newDrawer = getCellFull(newId);
        assertThat(newDrawer).isNotNull();
        assertThat(newDrawer.get("parent_id")).isEqualTo(originalId.toString());
        assertThat(newDrawer.get("valid_until")).isNull();
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    void drawerWithEmptyKeyPointsAndNullInsightRoundTrips() {
        Map<String, Object> created = writeToolService.addCell(
                WRITER,
                "Edge case drawer with empty arrays",
                "test", "facts", "edge",
                null, List.of(), null,
                "Has a summary",
                List.of(),    // empty key_points
                null,         // null insight
                null,         // null actionability
                "committed",
                BASE_TIME,
                null
        );

        Map<String, Object> drawer = readToolService.getCell(WRITER,
                UUID.fromString((String) created.get("id")));
        assertThat(drawer.get("summary")).isEqualTo("Has a summary");
        assertThat((List<?>) drawer.get("key_points")).isEmpty();
        assertThat(drawer.get("insight")).isNull();
        assertThat(drawer.get("actionability")).isNull();
    }

    @Test
    void multipleDrawersWithDifferentLayerCombinations() {
        // content only
        Map<String, Object> l0Only = writeToolService.addCell(
                WRITER, "Content only", "test", "facts", "layers",
                null, List.of(), null, null, List.of(), null, null,
                "committed", BASE_TIME, null);

        // content + summary
        Map<String, Object> l0l1 = writeToolService.addCell(
                WRITER, "Content plus summary", "test", "facts", "layers",
                null, List.of(), null, "Has summary only", List.of(), null, null,
                "committed", BASE_TIME.plusSeconds(1), null);

        // content + summary + key_points
        Map<String, Object> l0l1l2 = writeToolService.addCell(
                WRITER, "Content plus summary plus key points", "test", "facts", "layers",
                null, List.of(), null, "Summary present",
                List.of("point-1", "point-2"), null, null,
                "committed", BASE_TIME.plusSeconds(2), null);

        // content + summary + key_points + insight (all progressive fields)
        Map<String, Object> allLayers = writeToolService.addCell(
                WRITER, "All layers present", "test", "facts", "layers",
                null, List.of(), null, "Full summary",
                List.of("key-a", "key-b", "key-c"), "Deep insight", "actionable",
                "committed", BASE_TIME.plusSeconds(3), null);

        // Verify each round-trips correctly
        Map<String, Object> d0 = readToolService.getCell(WRITER,UUID.fromString((String) l0Only.get("id")));
        assertThat(d0.get("summary")).isNull();
        assertThat((List<?>) d0.get("key_points")).isEmpty();
        assertThat(d0.get("insight")).isNull();

        Map<String, Object> d1 = readToolService.getCell(WRITER,UUID.fromString((String) l0l1.get("id")));
        assertThat(d1.get("summary")).isEqualTo("Has summary only");
        assertThat((List<?>) d1.get("key_points")).isEmpty();
        assertThat(d1.get("insight")).isNull();

        Map<String, Object> d2 = readToolService.getCell(WRITER,UUID.fromString((String) l0l1l2.get("id")));
        assertThat(d2.get("summary")).isEqualTo("Summary present");
        assertThat((List<String>) d2.get("key_points")).containsExactly("point-1", "point-2");
        assertThat(d2.get("insight")).isNull();

        Map<String, Object> d3 = readToolService.getCell(WRITER,UUID.fromString((String) allLayers.get("id")));
        assertThat(d3.get("summary")).isEqualTo("Full summary");
        assertThat((List<String>) d3.get("key_points")).containsExactly("key-a", "key-b", "key-c");
        assertThat(d3.get("insight")).isEqualTo("Deep insight");
        assertThat(d3.get("actionability")).isEqualTo("actionable");
    }

    // -----------------------------------------------------------------------
    // Test application and config
    // -----------------------------------------------------------------------

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            WriteToolService.class,
            WriteToolRepository.class,
            ReadToolService.class,
            SearchWeightsProperties.class,
            ConfidenceThresholds.class,
            CellReadRepository.class,
            CellSearchRepository.class,
            KgSearchRepository.class,
            AdminToolRepository.class,
            OpLogWriter.class,
            InstanceConfig.class,
            TestConfig.class
    })
    static class TestApplication {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        EmbeddingClient embeddingClient() {
            return new FixedEmbeddingClient();
        }

        @Bean
        AdminToolService adminToolService(AdminToolRepository adminToolRepository) {
            // EmbeddingMigrationService is not loaded in this slim context;
            // provide AdminToolService with a no-op migration stub since
            // ReadToolService needs it only to call logAccess.
            return new AdminToolService(adminToolRepository, new com.hivemem.embedding.EmbeddingMigrationService(
                    new FixedEmbeddingClient(),
                    null
            ) {
                @Override
                public void run(org.springframework.boot.ApplicationArguments args) {
                    // no-op: skip migration in unit test context
                }
            });
        }

        @Bean
        PushDispatcher pushDispatcher() {
            return org.mockito.Mockito.mock(PushDispatcher.class);
        }
    }
}
