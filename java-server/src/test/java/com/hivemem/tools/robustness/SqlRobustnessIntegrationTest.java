package com.hivemem.tools.robustness;

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
import com.hivemem.tools.read.ReadToolService;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = SqlRobustnessIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Testcontainers
class SqlRobustnessIntegrationTest {

    private static final AuthPrincipal WRITER = new AuthPrincipal("writer-1", AuthRole.WRITER);
    private static final AuthPrincipal ADMIN = new AuthPrincipal("admin-1", AuthRole.ADMIN);
    private static final OffsetDateTime BASE_TIME = OffsetDateTime.parse("2026-04-14T09:00:00Z");

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

    @Test
    void approvePendingBatchHandlesAllIdsInSingleStatement() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<String, Object> result = writeToolService.addCell(
                    WRITER,
                    "Pending " + i,
                    "test",
                    "facts",
                    "batch",
                    "system",
                    List.of(),
                    1,
                    null,
                    List.of(),
                    null,
                    null,
                    "pending",
                    BASE_TIME.plusSeconds(i),
                    null
            );
            ids.add(UUID.fromString((String) result.get("id")));
        }

        Map<String, Object> approveResult = writeToolService.approvePending(ids, "committed");
        assertThat(((Number) approveResult.get("count")).intValue()).isEqualTo(5);

        for (UUID id : ids) {
            Record row = dslContext.fetchOne("SELECT status FROM cells WHERE id = ?", id);
            assertThat(row).isNotNull();
            assertThat(row.get("status", String.class)).isEqualTo("committed");
        }
    }

    @Test
    void searchKgRespectsLimit() {
        for (int i = 0; i < 10; i++) {
            writeToolService.kgAdd(
                    WRITER,
                    "Entity" + i,
                    "has",
                    "value",
                    1.0d,
                    null,
                    "committed",
                    BASE_TIME.plusSeconds(i),
                    null
            );
        }

        List<Map<String, Object>> results = readToolService.searchKg("Entity", null, null, 3);
        assertThat(results).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void timeMachineRespectsLimit() {
        for (int i = 0; i < 10; i++) {
            writeToolService.kgAdd(
                    WRITER,
                    "TimeMachine",
                    "fact" + i,
                    "val",
                    1.0d,
                    null,
                    "committed",
                    BASE_TIME.plusSeconds(i),
                    null
            );
        }

        List<Map<String, Object>> results = readToolService.timeMachine("TimeMachine", null, null, 3);
        assertThat(results).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void traverseDoesNotBlowUpOnDiamond() {
        // Build diamond: A -> B, A -> C, B -> D, C -> D
        Map<String, Object> dA = writeToolService.addCell(
                WRITER, "Diamond A", "test", "facts", null, "system",
                List.of(), 1, null, List.of(), null, null, "committed", BASE_TIME, null);
        Map<String, Object> dB = writeToolService.addCell(
                WRITER, "Diamond B", "test", "facts", null, "system",
                List.of(), 1, null, List.of(), null, null, "committed", BASE_TIME.plusSeconds(1), null);
        Map<String, Object> dC = writeToolService.addCell(
                WRITER, "Diamond C", "test", "facts", null, "system",
                List.of(), 1, null, List.of(), null, null, "committed", BASE_TIME.plusSeconds(2), null);
        Map<String, Object> dD = writeToolService.addCell(
                WRITER, "Diamond D", "test", "facts", null, "system",
                List.of(), 1, null, List.of(), null, null, "committed", BASE_TIME.plusSeconds(3), null);

        UUID idA = UUID.fromString((String) dA.get("id"));
        UUID idB = UUID.fromString((String) dB.get("id"));
        UUID idC = UUID.fromString((String) dC.get("id"));
        UUID idD = UUID.fromString((String) dD.get("id"));

        writeToolService.addTunnel(WRITER, idA, idB, "related_to", null, "committed");
        writeToolService.addTunnel(WRITER, idA, idC, "related_to", null, "committed");
        writeToolService.addTunnel(WRITER, idB, idD, "related_to", null, "committed");
        writeToolService.addTunnel(WRITER, idC, idD, "related_to", null, "committed");

        List<Map<String, Object>> results = readToolService.traverse(idA, 3, null);

        Set<UUID> found = new HashSet<>();
        for (Map<String, Object> row : results) {
            found.add(UUID.fromString((String) row.get("from_cell")));
            found.add(UUID.fromString((String) row.get("to_cell")));
        }
        assertThat(found).contains(idA, idB, idC, idD);
        // UNION deduplicates -- bounded: 4 tunnels x 2 directions x up to 3 depths
        assertThat(results).hasSizeLessThanOrEqualTo(16);
    }

    @Test
    void statusReturnsConsolidatedCounts() {
        Map<String, Object> result = readToolService.status();

        assertThat(result).containsKeys("cells", "facts", "tunnels", "pending", "realms");
        assertThat(result.get("cells")).isInstanceOf(Number.class);
        assertThat(result.get("facts")).isInstanceOf(Number.class);
        assertThat(result.get("tunnels")).isInstanceOf(Number.class);
        assertThat(result.get("pending")).isInstanceOf(Number.class);
    }

    @Test
    void updateBlueprintIsAtomic() {
        Map<String, Object> r1 = writeToolService.updateBlueprint(
                WRITER, "test-wing", "Map v1", "First version", List.of(), List.of());
        Map<String, Object> r2 = writeToolService.updateBlueprint(
                WRITER, "test-wing", "Map v2", "Second version", List.of(), List.of());

        UUID id1 = UUID.fromString((String) r1.get("id"));
        UUID id2 = UUID.fromString((String) r2.get("id"));

        // Old blueprint should be closed
        Record oldRow = dslContext.fetchOne("SELECT valid_until FROM blueprints WHERE id = ?", id1);
        assertThat(oldRow).isNotNull();
        assertThat(oldRow.get("valid_until")).isNotNull();

        // New blueprint should be active
        Record newRow = dslContext.fetchOne("SELECT valid_until FROM blueprints WHERE id = ?", id2);
        assertThat(newRow).isNotNull();
        assertThat(newRow.get("valid_until")).isNull();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            WriteToolService.class,
            WriteToolRepository.class,
            ReadToolService.class,
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
