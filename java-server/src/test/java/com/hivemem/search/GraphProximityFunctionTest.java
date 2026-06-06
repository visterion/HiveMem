package com.hivemem.search;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.cells.CellReadRepository;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.tools.read.ReadToolService;
import com.hivemem.write.AdminToolRepository;
import com.hivemem.write.AdminToolService;
import com.hivemem.write.WriteToolRepository;
import com.hivemem.write.WriteToolService;
import com.hivemem.sync.InstanceConfig;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PushDispatcher;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for the {@code graph_proximity_scores} SQL function added in V0018.
 *
 * <p>The function returns one row per neighbour of any anchor, with an aggregated
 * score = max over paths of (relation_weight * 1/depth). Anchors themselves are
 * excluded from the result set.
 */
@SpringBootTest(
        classes = GraphProximityFunctionTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Testcontainers
class GraphProximityFunctionTest {

    private static final AuthPrincipal WRITER = new AuthPrincipal("writer-1", AuthRole.WRITER);
    private static final OffsetDateTime BASE_TIME = OffsetDateTime.parse("2026-04-15T09:00:00Z");

    private static final String DEFAULT_WEIGHTS_JSON = "{"
            + "\"builds_on\":1.0,"
            + "\"refines\":0.8,"
            + "\"related_to\":0.6,"
            + "\"contradicts\":0.4"
            + "}";

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
    private DSLContext dslContext;

    @BeforeEach
    void resetDatabase() {
        dslContext.execute("TRUNCATE TABLE agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cells CASCADE");
    }

    @Test
    void directNeighbourScoresOneAndAnchorIsExcluded() {
        UUID idA = createCell("Anchor A");
        UUID idB = createCell("Neighbour B");
        UUID idC = createCell("Unrelated C");

        addTunnel(idA, idB, "builds_on");

        Map<UUID, Float> scores = callGraphProximity(List.of(idA), DEFAULT_WEIGHTS_JSON, 2);

        // B is a direct neighbour via builds_on (weight=1.0) at depth 1 -> score = 1.0 * 1/1 = 1.0
        assertThat(scores).containsKey(idB);
        assertThat(scores.get(idB)).isCloseTo(1.0f, within(1e-4f));
        // C has no edges from A
        assertThat(scores).doesNotContainKey(idC);
        // Anchor itself is excluded
        assertThat(scores).doesNotContainKey(idA);
    }

    @Test
    void depthTwoChainAppliesRelationWeightAndDepthDecay() {
        // A -builds_on-> B -related_to-> C
        UUID idA = createCell("Chain A");
        UUID idB = createCell("Chain B");
        UUID idC = createCell("Chain C");

        addTunnel(idA, idB, "builds_on");
        addTunnel(idB, idC, "related_to");

        Map<UUID, Float> scores = callGraphProximity(List.of(idA), DEFAULT_WEIGHTS_JSON, 2);

        // B: depth 1 via builds_on (1.0) -> 1.0 * 1/1 = 1.0
        assertThat(scores).containsKey(idB);
        assertThat(scores.get(idB)).isCloseTo(1.0f, within(1e-4f));

        // C: depth 2; path_score = 1.0 (seed) * 1.0 (builds_on) * 1/1
        //                       then * 0.6 (related_to) * 1/2 = 0.3
        assertThat(scores).containsKey(idC);
        assertThat(scores.get(idC)).isCloseTo(0.3f, within(1e-4f));

        // Anchor excluded
        assertThat(scores).doesNotContainKey(idA);
    }

    // ---- helpers ----

    private UUID createCell(String content) {
        Map<String, Object> result = writeToolService.addCell(
                WRITER,
                content,
                "test",
                "facts",
                null,
                "system",
                List.of(),
                1,
                null,
                List.of(),
                null,
                null,
                "committed",
                BASE_TIME,
                null
        );
        return UUID.fromString((String) result.get("id"));
    }

    private void addTunnel(UUID from, UUID to, String relation) {
        writeToolService.addTunnel(WRITER, from, to, relation, null, "committed");
    }

    private Map<UUID, Float> callGraphProximity(List<UUID> anchors, String weightsJson, int maxDepth) {
        UUID[] anchorArray = anchors.toArray(new UUID[0]);
        Result<Record> result = dslContext.resultQuery(
                "SELECT cell_id, score FROM graph_proximity_scores(?::uuid[], ?::jsonb, ?)",
                anchorArray,
                weightsJson,
                maxDepth
        ).fetch();
        Map<UUID, Float> scores = new HashMap<>();
        for (Record row : result) {
            UUID id = (UUID) row.get("cell_id");
            Number score = (Number) row.get("score");
            scores.put(id, score.floatValue());
        }
        return scores;
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
