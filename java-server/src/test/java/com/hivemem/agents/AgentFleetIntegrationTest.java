package com.hivemem.agents;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.cells.CellReadRepository;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.search.CellSelectorRepository;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.search.KgSearchRepository;
import com.hivemem.search.ConfidenceThresholds;
import com.hivemem.search.SearchWeightsProperties;
import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.search.DataQualityRepository;
import com.hivemem.search.FacetRepository;
import com.hivemem.tools.read.ReadToolService;
import com.hivemem.search.DocumentListRepository;
import com.hivemem.search.MediaListRepository;
import com.hivemem.write.WriteToolRepository;
import com.hivemem.write.AdminToolRepository;
import com.hivemem.write.AdminToolService;
import com.hivemem.sync.InstanceConfig;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PushDispatcher;
import com.hivemem.kg.KgEntityRepository;
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

/**
 * Integration tests for the agent fleet + approval workflow.
 * Ports Python test_agent_fleet.py scenarios that go beyond what
 * WriteToolsIntegrationTest already covers.
 */
@SpringBootTest(
        classes = AgentFleetIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Testcontainers
class AgentFleetIntegrationTest {

    private static final AuthPrincipal AGENT = new AuthPrincipal("agent-1", AuthRole.AGENT);
    private static final AuthPrincipal WRITER = new AuthPrincipal("writer-1", AuthRole.WRITER);
    private static final AuthPrincipal ADMIN = new AuthPrincipal("admin-1", AuthRole.ADMIN);
    private static final OffsetDateTime BASE_TIME = OffsetDateTime.parse("2026-04-15T09:00:00Z");

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

    // -----------------------------------------------------------------------
    // 1. Register agent -- idempotent (upsert, not duplicate)
    // -----------------------------------------------------------------------

    @Test
    void registerAgentIsIdempotentAndUpdatesOnConflict() {
        writeToolService.registerAgent("classifier", "Classify drawers", null, "nightly", null, List.of());
        Map<String, Object> updated = writeToolService.registerAgent("classifier", "Classify drawers v2", null, "hourly", null, List.of("search"));

        assertThat(updated.get("name")).isEqualTo("classifier");
        assertThat(updated.get("focus")).isEqualTo("Classify drawers v2");

        long agentCount = dslContext.fetchOne(
                "SELECT count(*) AS cnt FROM agents WHERE name = ?", "classifier"
        ).get("cnt", Long.class);
        assertThat(agentCount).isEqualTo(1L);

        String schedule = dslContext.fetchOne(
                "SELECT schedule FROM agents WHERE name = ?", "classifier"
        ).get("schedule", String.class);
        assertThat(schedule).isEqualTo("hourly");
    }

    // -----------------------------------------------------------------------
    // 2. List agents -- register 3, assert 3 returned with correct fields
    // -----------------------------------------------------------------------

    @Test
    void listAgentsReturnsAllRegisteredAgentsWithCorrectFields() {
        writeToolService.registerAgent("classifier", "Classify drawers", null, "nightly", null, List.of());
        writeToolService.registerAgent("curator", "Curate knowledge", null, "daily", null, List.of());
        writeToolService.registerAgent("summarizer", "Summarize content", null, null, null, List.of());

        List<Map<String, Object>> agents = readToolService.listAgents();

        assertThat(agents).hasSize(3);
        assertThat(agents)
                .extracting(a -> a.get("name"))
                .containsExactlyInAnyOrder("classifier", "curator", "summarizer");
        assertThat(agents)
                .extracting(a -> a.get("focus"))
                .containsExactlyInAnyOrder("Classify drawers", "Curate knowledge", "Summarize content");
        // Every agent should have a created_at timestamp
        assertThat(agents)
                .allSatisfy(a -> assertThat(a.get("created_at")).isNotNull());
    }

    // -----------------------------------------------------------------------
    // 3. Agent rejected decision -- fact gets status=rejected, excluded from active_facts
    // -----------------------------------------------------------------------

    @Test
    void rejectedAgentFactIsExcludedFromActiveFacts() {
        Map<String, Object> fact = writeToolService.kgAdd(
                AGENT, "HiveMem", "uses", "pgvector", 0.9, null, "committed", BASE_TIME, null
        );
        String factId = (String) fact.get("id");

        // Agent forces pending regardless of requested status
        assertThat(fact.get("status")).isEqualTo("pending");

        // Not visible in active facts while pending
        List<Map<String, Object>> activeBeforeReject = readToolService.searchKg(null, "HiveMem", null, null, 100);
        assertThat(activeBeforeReject).isEmpty();

        // Admin rejects
        writeToolService.approvePending(List.of(UUID.fromString(factId)), "rejected");

        // Still not in active facts
        List<Map<String, Object>> activeAfterReject = readToolService.searchKg(null, "HiveMem", null, null, 100);
        assertThat(activeAfterReject).isEmpty();

        // But the row exists in facts with status=rejected
        String status = dslContext.fetchOne(
                "SELECT status FROM facts WHERE id = ?", UUID.fromString(factId)
        ).get("status", String.class);
        assertThat(status).isEqualTo("rejected");
    }

    // -----------------------------------------------------------------------
    // 4. Diary read returns entries in reverse-chronological order
    // -----------------------------------------------------------------------

    @Test
    void diaryReadReturnsEntriesInReverseChronologicalOrder() {
        writeToolService.registerAgent("curator", "Curate knowledge", null, null, null, List.of());
        writeToolService.diaryWrite("curator", "First entry");
        // Small delay via explicit insert with timestamps to guarantee order
        dslContext.execute("""
                INSERT INTO agent_diary (agent, entry, created_at)
                VALUES ('curator', 'Second entry', now() + interval '1 second')
                """);
        dslContext.execute("""
                INSERT INTO agent_diary (agent, entry, created_at)
                VALUES ('curator', 'Third entry', now() + interval '2 seconds')
                """);

        List<Map<String, Object>> entries = readToolService.diaryRead("curator", 10);

        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).get("entry")).isEqualTo("Third entry");
        assertThat(entries.get(1).get("entry")).isEqualTo("Second entry");
        assertThat(entries.get(2).get("entry")).isEqualTo("First entry");
    }

    // -----------------------------------------------------------------------
    // 5. Diary read filters by agent_id
    // -----------------------------------------------------------------------

    @Test
    void diaryReadFiltersByAgent() {
        writeToolService.registerAgent("curator", "Curate knowledge", null, null, null, List.of());
        writeToolService.registerAgent("classifier", "Classify drawers", null, null, null, List.of());

        writeToolService.diaryWrite("curator", "Curator entry 1");
        writeToolService.diaryWrite("curator", "Curator entry 2");
        writeToolService.diaryWrite("classifier", "Classifier entry 1");

        List<Map<String, Object>> curatorEntries = readToolService.diaryRead("curator", 10);
        assertThat(curatorEntries).hasSize(2);
        assertThat(curatorEntries)
                .allSatisfy(e -> assertThat(e.get("agent")).isEqualTo("curator"));

        List<Map<String, Object>> classifierEntries = readToolService.diaryRead("classifier", 10);
        assertThat(classifierEntries).hasSize(1);
        assertThat(classifierEntries.get(0).get("entry")).isEqualTo("Classifier entry 1");
    }

    // -----------------------------------------------------------------------
    // 6. Diary read respects limit
    // -----------------------------------------------------------------------

    @Test
    void diaryReadRespectsLimit() {
        writeToolService.registerAgent("curator", "Curate knowledge", null, null, null, List.of());
        writeToolService.diaryWrite("curator", "Entry 1");
        writeToolService.diaryWrite("curator", "Entry 2");
        writeToolService.diaryWrite("curator", "Entry 3");
        writeToolService.diaryWrite("curator", "Entry 4");
        writeToolService.diaryWrite("curator", "Entry 5");

        List<Map<String, Object>> entries = readToolService.diaryRead("curator", 2);

        assertThat(entries).hasSize(2);
    }

    // -----------------------------------------------------------------------
    // 7. Mixed approve/reject batch -- 2 pending drawers + 2 pending facts,
    //    admin approves 2, rejects 2, verify status transitions
    // -----------------------------------------------------------------------

    @Test
    void mixedApproveRejectBatchTransitionsCorrectly() {
        // Create 2 pending drawers from agent
        Map<String, Object> drawer1 = writeToolService.addCell(
                AGENT, "Agent suggestion 1", "eng", "facts", "test", "system",
                List.of(), 1, "Summary 1", List.of(), null, null, "committed", BASE_TIME, null
        );
        Map<String, Object> drawer2 = writeToolService.addCell(
                AGENT, "Agent suggestion 2", "eng", "facts", "test", "system",
                List.of(), 1, "Summary 2", List.of(), null, null, "committed", BASE_TIME.plusSeconds(1), null
        );

        // Create 2 pending facts from agent
        Map<String, Object> fact1 = writeToolService.kgAdd(
                AGENT, "Entity1", "has", "value1", 0.8, null, "committed", BASE_TIME.plusSeconds(2), null
        );
        Map<String, Object> fact2 = writeToolService.kgAdd(
                AGENT, "Entity2", "has", "value2", 0.7, null, "committed", BASE_TIME.plusSeconds(3), null
        );

        UUID drawer1Id = UUID.fromString((String) drawer1.get("id"));
        UUID drawer2Id = UUID.fromString((String) drawer2.get("id"));
        UUID fact1Id = UUID.fromString((String) fact1.get("id"));
        UUID fact2Id = UUID.fromString((String) fact2.get("id"));

        // All should be pending (agent forces pending)
        assertThat(drawer1.get("status")).isEqualTo("pending");
        assertThat(drawer2.get("status")).isEqualTo("pending");
        assertThat(fact1.get("status")).isEqualTo("pending");
        assertThat(fact2.get("status")).isEqualTo("pending");

        // Approve drawer1 + fact1
        Map<String, Object> approveResult = writeToolService.approvePending(
                List.of(drawer1Id, fact1Id), "committed"
        );
        assertThat(((Number) approveResult.get("count")).intValue()).isEqualTo(2);

        // Reject drawer2 + fact2
        Map<String, Object> rejectResult = writeToolService.approvePending(
                List.of(drawer2Id, fact2Id), "rejected"
        );
        assertThat(((Number) rejectResult.get("count")).intValue()).isEqualTo(2);

        // Verify drawer1 committed, visible in active_cells
        assertThat(dslContext.fetchOne("SELECT status FROM cells WHERE id = ?", drawer1Id)
                .get("status", String.class)).isEqualTo("committed");
        assertThat(dslContext.fetchOne("SELECT count(*) AS cnt FROM active_cells WHERE id = ?", drawer1Id)
                .get("cnt", Long.class)).isEqualTo(1L);

        // Verify drawer2 rejected, NOT in active_cells
        assertThat(dslContext.fetchOne("SELECT status FROM cells WHERE id = ?", drawer2Id)
                .get("status", String.class)).isEqualTo("rejected");
        assertThat(dslContext.fetchOne("SELECT count(*) AS cnt FROM active_cells WHERE id = ?", drawer2Id)
                .get("cnt", Long.class)).isEqualTo(0L);

        // Verify fact1 committed, visible in active_facts
        assertThat(dslContext.fetchOne("SELECT status FROM facts WHERE id = ?", fact1Id)
                .get("status", String.class)).isEqualTo("committed");
        assertThat(dslContext.fetchOne("SELECT count(*) AS cnt FROM active_facts WHERE id = ?", fact1Id)
                .get("cnt", Long.class)).isEqualTo(1L);

        // Verify fact2 rejected, NOT in active_facts
        assertThat(dslContext.fetchOne("SELECT status FROM facts WHERE id = ?", fact2Id)
                .get("status", String.class)).isEqualTo("rejected");
        assertThat(dslContext.fetchOne("SELECT count(*) AS cnt FROM active_facts WHERE id = ?", fact2Id)
                .get("cnt", Long.class)).isEqualTo(0L);
    }

    // -----------------------------------------------------------------------
    // Test application and configuration
    // -----------------------------------------------------------------------

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            WriteToolService.class,
            KgEntityRepository.class,
            CellSelectorRepository.class,
            WriteToolRepository.class,
            ReadToolService.class,
            DocumentListRepository.class,
            MediaListRepository.class,
            FacetRepository.class,
            DataQualityRepository.class,
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
