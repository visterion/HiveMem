package com.hivemem.search;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.cells.CellReadRepository;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.EmbeddingStateRepository;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.search.FacetRepository;
import com.hivemem.tools.read.ReadToolService;
import com.hivemem.search.DocumentListRepository;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code ranked_search} surfaces a {@code score_graph_proximity}
 * column and that the new sixth signal contributes positively to a cell's total
 * score when it is reachable from a top-ranked anchor via a tunnel.
 */
@SpringBootTest(
        classes = RankedSearchGraphSignalTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Testcontainers
class RankedSearchGraphSignalTest {

    private static final AuthPrincipal WRITER = new AuthPrincipal("writer-1", AuthRole.WRITER);
    private static final OffsetDateTime BASE_TIME = OffsetDateTime.parse("2026-04-15T09:00:00Z");
    private static final int DIMS = 1024;

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

    @Autowired
    private EmbeddingStateRepository embeddingStateRepository;

    @BeforeEach
    void resetDatabase() {
        dslContext.execute("TRUNCATE TABLE agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cells CASCADE");
        // The minimal Spring context here doesn't run EmbeddingMigrationService, so
        // ranked_search (dropped in V0017) must be installed by the test itself.
        embeddingStateRepository.replaceRankedSearchFunction(DIMS);
    }

    @Test
    void connectedCellOutranksUnconnectedCellViaGraphProximity() {
        // The anchor + 24 filler cells all contain "semantic", so the
        // FixedEmbeddingClient assigns them the [1,0,0]-padded embedding. They
        // saturate the top-25 anchors CTE when ranked_search is called with the
        // [1,0,0] query embedding. `connected` and `unconnected` use different
        // content so their hash-based embeddings keep them out of the anchor set
        // (otherwise graph_proximity_scores would exclude `connected` because it
        // would itself be an anchor). Both pass the sem>0.3 OR kw>0 filter via
        // a keyword match against query_text="alpha".
        UUID anchor = createCell("semantic anchor cell");
        for (int i = 0; i < 24; i++) {
            createCell("semantic filler cell " + i);
        }
        UUID connected = createCell("alpha bridge target");
        UUID unconnected = createCell("alpha lonely target");

        writeToolService.addTunnel(WRITER, anchor, connected, "builds_on", null, "committed");

        List<Float> queryEmbedding = paddedEmbedding(1.0f, 0.0f, 0.0f);

        Result<Record> result = dslContext.fetch(
                "SELECT id, score_graph_proximity, score_total "
                        + "FROM ranked_search(?::vector, ?, NULL, NULL, NULL, 100, "
                        + "0.30::real, 0.15::real, 0.15::real, 0.15::real, 0.15::real, 0.10::real)",
                queryEmbedding.toArray(new Float[0]),
                "alpha"
        );

        Map<UUID, Double> totals = new HashMap<>();
        Map<UUID, Double> graph = new HashMap<>();
        for (Record row : result) {
            UUID id = (UUID) row.get("id");
            totals.put(id, ((Number) row.get("score_total")).doubleValue());
            graph.put(id, ((Number) row.get("score_graph_proximity")).doubleValue());
        }

        assertThat(totals).containsKeys(connected, unconnected);
        assertThat(graph.get(connected)).isGreaterThan(0.0);
        assertThat(graph.get(unconnected)).isEqualTo(0.0);
        assertThat(totals.get(connected)).isGreaterThan(totals.get(unconnected));
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

    private static List<Float> paddedEmbedding(float d0, float d1, float d2) {
        List<Float> vec = new ArrayList<>(Collections.nCopies(DIMS, 0.0f));
        vec.set(0, d0);
        vec.set(1, d1);
        vec.set(2, d2);
        return vec;
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
            EmbeddingStateRepository.class,
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
