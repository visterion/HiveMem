package com.hivemem.tools.search;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.cells.CellReadRepository;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.EmbeddingStateRepository;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.search.CellSelectorRepository;
import com.hivemem.search.ConfidenceThresholds;
import com.hivemem.search.DocumentListRepository;
import com.hivemem.search.FacetRepository;
import com.hivemem.search.KgSearchRepository;
import com.hivemem.search.MediaListRepository;
import com.hivemem.search.SearchWeightsProperties;
import com.hivemem.tools.read.ReadToolService;
import com.hivemem.write.AdminToolRepository;
import com.hivemem.write.AdminToolService;
import com.hivemem.write.WriteToolRepository;
import com.hivemem.write.WriteToolService;
import com.hivemem.sync.InstanceConfig;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PushDispatcher;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link CellSearchRepository#rankedSearch} accepts a trailing
 * {@code realmIn} parameter that filters by realm, with the sentinel entry
 * {@code "none"} matching cells whose realm is NULL (never a literal realm
 * named "none").
 */
@SpringBootTest(
        classes = RankedSearchRealmInTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Testcontainers
class RankedSearchRealmInTest {

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
    private CellSearchRepository cellSearchRepository;

    @Autowired
    private DSLContext dslContext;

    @Autowired
    private EmbeddingStateRepository embeddingStateRepository;

    private UUID cellA;
    private UUID cellB;
    private UUID cellC;
    private UUID cellNullRealm;

    @BeforeEach
    void resetDatabase() {
        dslContext.execute("TRUNCATE TABLE agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cells CASCADE");
        embeddingStateRepository.replaceRankedSearchFunction(DIMS);

        cellA = createCell("keyword match cell in realm a", "a");
        cellB = createCell("keyword match cell in realm b", "b");
        cellC = createCell("keyword match cell in realm c", "c");
        cellNullRealm = createCell("keyword match cell in no realm", null);
    }

    @Test
    void realmInFiltersToGivenRealms() {
        List<CellSearchRepository.RankedRow> rows = search(List.of("a", "b"));
        assertThat(ids(rows)).containsExactlyInAnyOrder(cellA, cellB);
    }

    @Test
    void realmInNoneSentinelMatchesOnlyNullRealm() {
        List<CellSearchRepository.RankedRow> rows = search(List.of("none"));
        assertThat(ids(rows)).containsExactly(cellNullRealm);
    }

    @Test
    void realmInCombinesNamedRealmsAndNoneSentinel() {
        List<CellSearchRepository.RankedRow> rows = search(List.of("a", "none"));
        assertThat(ids(rows)).containsExactlyInAnyOrder(cellA, cellNullRealm);
    }

    @Test
    void realmInNullMeansNoFilter() {
        List<CellSearchRepository.RankedRow> rows = search(null);
        assertThat(ids(rows)).containsExactlyInAnyOrder(cellA, cellB, cellC, cellNullRealm);
    }

    // ---- helpers ----

    private List<CellSearchRepository.RankedRow> search(List<String> realmIn) {
        List<Float> queryEmbedding = paddedEmbedding(1.0f, 0.0f, 0.0f);
        return cellSearchRepository.rankedSearch(
                queryEmbedding,
                "keyword",
                null,
                null,
                null,
                100,
                0.30,
                0.15,
                0.15,
                0.15,
                0.15,
                0.10,
                null,
                null,
                realmIn
        );
    }

    private static Set<UUID> ids(List<CellSearchRepository.RankedRow> rows) {
        return rows.stream().map(CellSearchRepository.RankedRow::id).collect(Collectors.toSet());
    }

    private UUID createCell(String content, String realm) {
        Map<String, Object> result = writeToolService.addCell(
                WRITER,
                content,
                realm,
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

    @Configuration
    @EnableAutoConfiguration
    @Import({
            WriteToolService.class,
            CellSelectorRepository.class,
            WriteToolRepository.class,
            ReadToolService.class,
            DocumentListRepository.class,
            MediaListRepository.class,
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
