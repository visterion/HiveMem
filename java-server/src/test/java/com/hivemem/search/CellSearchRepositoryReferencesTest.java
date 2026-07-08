package com.hivemem.search;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.cells.CellReadRepository;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.EmbeddingStateRepository;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.attachment.AttachmentRepository;
import com.hivemem.search.DataQualityRepository;
import com.hivemem.search.FacetRepository;
import com.hivemem.tools.read.ReadToolService;
import com.hivemem.search.DocumentListRepository;
import com.hivemem.search.MediaListRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = CellSearchRepositoryReferencesTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Testcontainers
class CellSearchRepositoryReferencesTest {

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
    private CellSearchRepository repo;

    @Autowired
    private WriteToolService writeToolService;

    @Autowired
    private DSLContext dslContext;

    private static final AuthPrincipal WRITER = new AuthPrincipal("test", AuthRole.WRITER);

    @BeforeEach
    void reset() {
        dslContext.execute("TRUNCATE TABLE agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cells CASCADE");
    }

    @Test
    void returnsReferenceForLinkedCell() {
        Map<String, Object> cell = writeToolService.addCell(WRITER,
                "content", "realm", "facts", "topic", "system",
                List.of(), 3, "summary", List.of(), null, null, "committed", null, null);
        UUID cellId = UUID.fromString((String) cell.get("id"));

        Map<String, Object> ref = writeToolService.addReference(
                "Test Article", "https://example.com", null, "article", "read", null, List.of(), null);
        UUID refId = UUID.fromString((String) ref.get("id"));

        writeToolService.linkReference(cellId, refId, "source");

        Map<UUID, List<CellSearchRepository.RefRow>> result =
                repo.findReferencesForCells(List.of(cellId));

        assertThat(result).containsKey(cellId);
        List<CellSearchRepository.RefRow> refs = result.get(cellId);
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).title()).isEqualTo("Test Article");
        assertThat(refs.get(0).url()).isEqualTo("https://example.com");
    }

    @Test
    void returnsEmptyListForCellWithNoReferences() {
        Map<String, Object> cell = writeToolService.addCell(WRITER,
                "content", "realm", "facts", "topic", "system",
                List.of(), 3, "summary", List.of(), null, null, "committed", null, null);
        UUID cellId = UUID.fromString((String) cell.get("id"));

        Map<UUID, List<CellSearchRepository.RefRow>> result =
                repo.findReferencesForCells(List.of(cellId));

        assertThat(result).containsKey(cellId);
        assertThat(result.get(cellId)).isEmpty();
    }

    @Test
    void returnsEmptyMapForEmptyInput() {
        assertThat(repo.findReferencesForCells(List.of())).isEmpty();
    }

    @Test
    void multipleCellsReturnCorrectMapping() {
        Map<String, Object> c1 = writeToolService.addCell(WRITER,
                "c1", "r", "facts", "t1", "system", List.of(), 3, "s1", List.of(), null, null, "committed", null, null);
        Map<String, Object> c2 = writeToolService.addCell(WRITER,
                "c2", "r", "facts", "t2", "system", List.of(), 3, "s2", List.of(), null, null, "committed", null, null);
        UUID id1 = UUID.fromString((String) c1.get("id"));
        UUID id2 = UUID.fromString((String) c2.get("id"));

        Map<String, Object> ref = writeToolService.addReference(
                "Shared Ref", "https://shared.com", null, null, "read", null, List.of(), null);
        UUID refId = UUID.fromString((String) ref.get("id"));
        writeToolService.linkReference(id1, refId, "source");

        Map<UUID, List<CellSearchRepository.RefRow>> result =
                repo.findReferencesForCells(List.of(id1, id2));

        assertThat(result.get(id1)).hasSize(1);
        assertThat(result.get(id1).get(0).title()).isEqualTo("Shared Ref");
        assertThat(result.get(id2)).isEmpty();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            WriteToolService.class,
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
