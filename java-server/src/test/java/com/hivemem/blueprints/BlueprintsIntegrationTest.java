package com.hivemem.blueprints;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.cells.CellReadRepository;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.search.KgSearchRepository;
import com.hivemem.search.ConfidenceThresholds;
import com.hivemem.search.SearchWeightsProperties;
import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.search.FacetRepository;
import com.hivemem.tools.read.ReadToolService;
import com.hivemem.search.DocumentListRepository;
import com.hivemem.write.WriteToolRepository;
import com.hivemem.write.AdminToolRepository;
import com.hivemem.write.AdminToolService;
import com.hivemem.sync.InstanceConfig;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PushDispatcher;
import com.hivemem.write.WriteToolService;
import org.jooq.DSLContext;
import org.jooq.Record;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dedicated integration tests for Blueprints (Maps of Content).
 * Ported from Python tests/test_maps.py (5 tests).
 * Note: tests/test_blueprints.py does not exist in the Python codebase.
 *
 * <p>The existing single blueprint test lives in
 * WriteToolsIntegrationTest.writerCanAppendBlueprintsAndClosePreviousVersion.
 * This class covers the remaining scenarios.
 */
@SpringBootTest(
        classes = BlueprintsIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Testcontainers
class BlueprintsIntegrationTest {

    private static final AuthPrincipal WRITER = new AuthPrincipal("writer-1", AuthRole.WRITER);

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

    // --- 1. getBlueprint returns latest active version ---

    @Test
    void getBlueprintReturnsLatestActiveVersionForWing() {
        writeToolService.updateBlueprint(WRITER, "engineering", "Engineering: Active Decisions",
                "Current focus is on auth migration and HiveMem development.",
                List.of("auth", "hivemem", "infra"), List.of());

        List<Map<String, Object>> blueprints = readToolService.getBlueprint("engineering");

        assertThat(blueprints).hasSize(1);
        assertThat(blueprints.get(0)).containsEntry("realm", "engineering");
        assertThat(blueprints.get(0)).containsEntry("title", "Engineering: Active Decisions");
        assertThat(blueprints.get(0)).containsEntry("narrative",
                "Current focus is on auth migration and HiveMem development.");
    }

    // --- 2. getBlueprint returns empty for unknown wing ---

    @Test
    void getBlueprintReturnsEmptyForUnknownWing() {
        List<Map<String, Object>> blueprints = readToolService.getBlueprint("nonexistent-wing");

        assertThat(blueprints).isEmpty();
    }

    // --- 3. Blueprint with signals list ---

    @Test
    void blueprintStoresAndReturnsSignalOrder() {
        writeToolService.updateBlueprint(WRITER, "eng", "Ordered",
                "With signals", List.of("auth", "search", "infra"), List.of());

        List<Map<String, Object>> blueprints = readToolService.getBlueprint("eng");

        assertThat(blueprints).hasSize(1);
        assertThat(blueprints.get(0).get("signal_order")).isEqualTo(List.of("auth", "search", "infra"));
    }

    // --- 4. Blueprint with key_cells list ---
    // Not ported: blueprintWithCellRefs — original Python test suite did not cover
    // cell_refs separately (only signal_order was tested). The Java schema supports
    // key_cells but it is exercised here only implicitly via updateBlueprint calls
    // above (empty list).

    // --- 5. History chain: 3 updates, only latest active ---

    @Test
    void historyChainThreeUpdatesOnlyLatestActive() {
        writeToolService.updateBlueprint(WRITER, "eng", "V1", "First version",
                List.of("auth"), List.of());
        writeToolService.updateBlueprint(WRITER, "eng", "V2", "Second version",
                List.of("auth", "search"), List.of());
        writeToolService.updateBlueprint(WRITER, "eng", "V3", "Third version",
                List.of("auth", "search", "infra"), List.of());

        // Only V3 should be returned by getBlueprint (active_blueprints view)
        List<Map<String, Object>> activeBlueprints = readToolService.getBlueprint("eng");
        assertThat(activeBlueprints).hasSize(1);
        assertThat(activeBlueprints.get(0)).containsEntry("title", "V3");

        // All 3 versions exist in the blueprints table
        List<Record> allVersions = dslContext.fetch("""
                SELECT title, valid_until
                FROM blueprints
                WHERE realm = 'eng'
                ORDER BY valid_from
                """);
        assertThat(allVersions).hasSize(3);

        // V1 and V2 should have valid_until set (closed)
        assertThat(allVersions.get(0).get("title", String.class)).isEqualTo("V1");
        assertThat(allVersions.get(0).get("valid_until")).isNotNull();

        assertThat(allVersions.get(1).get("title", String.class)).isEqualTo("V2");
        assertThat(allVersions.get(1).get("valid_until")).isNotNull();

        // V3 should have valid_until null (active)
        assertThat(allVersions.get(2).get("title", String.class)).isEqualTo("V3");
        assertThat(allVersions.get(2).get("valid_until")).isNull();
    }

    // --- 6. Append-only: updateBlueprint always inserts, never UPDATEs content ---

    @Test
    void appendOnlyUpdateInsertsNewRowAndClosesPrevious() {
        writeToolService.updateBlueprint(WRITER, "eng", "V1", "First version",
                List.of(), List.of());
        writeToolService.updateBlueprint(WRITER, "eng", "V2", "Updated version",
                List.of(), List.of());

        // Only V2 in active view
        List<Map<String, Object>> active = readToolService.getBlueprint("eng");
        assertThat(active).hasSize(1);
        assertThat(active.get(0)).containsEntry("title", "V2");

        // V1 still exists with valid_until set
        List<Record> allRows = dslContext.fetch("""
                SELECT title, valid_until
                FROM blueprints
                WHERE realm = 'eng'
                ORDER BY valid_from
                """);
        assertThat(allRows).hasSize(2);
        assertThat(allRows.get(0).get("title", String.class)).isEqualTo("V1");
        assertThat(allRows.get(0).get("valid_until")).isNotNull();
        assertThat(allRows.get(1).get("title", String.class)).isEqualTo("V2");
        assertThat(allRows.get(1).get("valid_until")).isNull();
    }

    // --- 7. Per-wing isolation ---

    @Test
    void blueprintPerWingIsolation() {
        writeToolService.updateBlueprint(WRITER, "wing-a", "A-V1", "Wing A first",
                List.of("hall-a1"), List.of());
        writeToolService.updateBlueprint(WRITER, "wing-a", "A-V2", "Wing A second",
                List.of("hall-a1", "hall-a2"), List.of());
        writeToolService.updateBlueprint(WRITER, "wing-b", "B-V1", "Wing B first",
                List.of("hall-b1"), List.of());

        // Wing A: only A-V2 active, A-V1 closed
        List<Map<String, Object>> wingA = readToolService.getBlueprint("wing-a");
        assertThat(wingA).hasSize(1);
        assertThat(wingA.get(0)).containsEntry("title", "A-V2");
        assertThat(countRows("SELECT count(*) AS cnt FROM blueprints WHERE realm = ?", "wing-a"))
                .isEqualTo(2L);

        // Wing B: only B-V1 active, no history
        List<Map<String, Object>> wingB = readToolService.getBlueprint("wing-b");
        assertThat(wingB).hasSize(1);
        assertThat(wingB.get(0)).containsEntry("title", "B-V1");
        assertThat(countRows("SELECT count(*) AS cnt FROM blueprints WHERE realm = ?", "wing-b"))
                .isEqualTo(1L);

        // Updating wing B does not affect wing A
        writeToolService.updateBlueprint(WRITER, "wing-b", "B-V2", "Wing B second",
                List.of("hall-b1", "hall-b2"), List.of());
        List<Map<String, Object>> wingAAfter = readToolService.getBlueprint("wing-a");
        assertThat(wingAAfter).hasSize(1);
        assertThat(wingAAfter.get(0)).containsEntry("title", "A-V2");
    }

    // --- 8. getBlueprint with no wing returns all active blueprints ---

    @Test
    void getBlueprintWithoutWingReturnsAllActive() {
        writeToolService.updateBlueprint(WRITER, "eng", "Eng Map", "Engineering",
                List.of(), List.of());
        writeToolService.updateBlueprint(WRITER, "personal", "Personal Map", "Personal stuff",
                List.of(), List.of());

        List<Map<String, Object>> all = readToolService.getBlueprint(null);

        assertThat(all).hasSize(2);
        List<String> wings = all.stream().map(m -> (String) m.get("realm")).toList();
        assertThat(wings).containsExactlyInAnyOrder("eng", "personal");
    }

    private long countRows(String sql, Object... bindings) {
        return dslContext.fetchOne(sql, bindings).get("cnt", Long.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            WriteToolService.class,
            WriteToolRepository.class,
            ReadToolService.class,
            DocumentListRepository.class,
            FacetRepository.class,
            AttachmentRepository.class,
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
            return new AdminToolService(adminToolRepository, new com.hivemem.embedding.EmbeddingMigrationService(
                    new FixedEmbeddingClient(), null) {
                @Override
                public void run(org.springframework.boot.ApplicationArguments args) {}
            });
        }

        @Bean
        PushDispatcher pushDispatcher() {
            return org.mockito.Mockito.mock(PushDispatcher.class);
        }
    }
}
