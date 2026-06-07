package com.hivemem.config;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.search.CellSearchRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(SearchFilterTest.TestConfig.class)
class SearchFilterTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        EmbeddingClient testEmbeddingClient() {
            return new FixedEmbeddingClient();
        }
    }

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
    CellSearchRepository repo;

    @Autowired
    DSLContext dslContext;

    static final UUID ID_A = UUID.fromString("00000000-0000-0000-0000-000000000aa1");
    static final UUID ID_B = UUID.fromString("00000000-0000-0000-0000-000000000bb2");
    static final UUID ID_C = UUID.fromString("00000000-0000-0000-0000-000000000cc3");

    @BeforeEach
    void seed() {
        dslContext.execute("DELETE FROM cells WHERE realm = 'docs' AND topic = 't'");
        seed(ID_A, "alpha contract document", new String[]{"contract"}, "committed");
        seed(ID_B, "alpha invoice document", new String[]{"invoice"}, "committed");
        seed(ID_C, "alpha contract paid document", new String[]{"contract", "paid"}, "pending");
    }

    private void seed(UUID id, String content, String[] tags, String status) {
        dslContext.execute(
                "INSERT INTO cells (id, content, realm, signal, topic, tags, status, valid_from, created_at) " +
                "VALUES (?, ?, 'docs', 'facts', 't', ?, ?, now(), now())",
                id, content, tags, status);
    }

    @Test
    void tagFilterReturnsOnlyMatchingCommittedCells() {
        List<CellSearchRepository.RankedRow> results = repo.rankedSearch(
                null, "alpha", "docs", null, null, 50,
                0.3, 0.15, 0.15, 0.15, 0.15, 0.10,
                List.of("contract"), null);

        Set<UUID> ids = results.stream().map(CellSearchRepository.RankedRow::id).collect(Collectors.toSet());
        assertThat(ids).containsExactly(ID_A);
    }

    @Test
    void statusFilterReturnsPendingOnly() {
        List<CellSearchRepository.RankedRow> results = repo.rankedSearch(
                null, "alpha", "docs", null, null, 50,
                0.3, 0.15, 0.15, 0.15, 0.15, 0.10,
                null, "pending");

        Set<UUID> ids = results.stream().map(CellSearchRepository.RankedRow::id).collect(Collectors.toSet());
        assertThat(ids).containsExactly(ID_C);
    }

    @Test
    void defaultStatusReturnsCommittedOnly() {
        List<CellSearchRepository.RankedRow> results = repo.rankedSearch(
                null, "alpha", "docs", null, null, 50,
                0.3, 0.15, 0.15, 0.15, 0.15, 0.10,
                null, null);

        Set<UUID> ids = results.stream().map(CellSearchRepository.RankedRow::id).collect(Collectors.toSet());
        assertThat(ids).containsExactlyInAnyOrder(ID_A, ID_B);
        assertThat(ids).doesNotContain(ID_C);
    }
}
