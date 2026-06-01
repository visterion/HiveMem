package com.hivemem.queen;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(QueenRepositoryIT.TestConfig.class)
class QueenRepositoryIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        EmbeddingClient testEmbeddingClient() { return new FixedEmbeddingClient(); }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
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

    @Autowired DSLContext db;
    @Autowired QueenRepository repo;

    private UUID insertCell(String topic) {
        return db.fetchOne("""
                INSERT INTO cells (realm, signal, topic, content, status, created_by)
                VALUES ('work', 'facts', ?, 'content for ' || ?, 'committed', 'test')
                RETURNING id
                """, topic, topic).get("id", UUID.class);
    }

    @Test
    void findsCellsWithNoTunnels() {
        UUID lonely = insertCell("lonely-" + UUID.randomUUID());
        UUID a = insertCell("a-" + UUID.randomUUID());
        UUID b = insertCell("b-" + UUID.randomUUID());
        db.execute("INSERT INTO tunnels (from_cell, to_cell, relation, status, created_by) " +
                "VALUES (?, ?, 'related_to', 'committed', 'test')", a, b);

        List<UUID> isolated = repo.findIsolatedCellIds(100);
        assertThat(isolated).contains(lonely);
        assertThat(isolated).doesNotContain(a, b);
    }

    @Test
    void pendingProposalAlsoCountsAsLinked() {
        UUID c = insertCell("c-" + UUID.randomUUID());
        UUID d = insertCell("d-" + UUID.randomUUID());
        db.execute("INSERT INTO tunnels (from_cell, to_cell, relation, status, created_by) " +
                "VALUES (?, ?, 'related_to', 'pending', 'queen')", c, d);

        List<UUID> isolated = repo.findIsolatedCellIds(100);
        assertThat(isolated).doesNotContain(c, d);
    }

    @Test
    void tunnelExistsDetectsDuplicate() {
        UUID e = insertCell("e-" + UUID.randomUUID());
        UUID f = insertCell("f-" + UUID.randomUUID());
        db.execute("INSERT INTO tunnels (from_cell, to_cell, relation, status, created_by) " +
                "VALUES (?, ?, 'builds_on', 'pending', 'queen')", e, f);

        assertThat(repo.tunnelExists(e, f, "builds_on")).isTrue();
        assertThat(repo.tunnelExists(e, f, "related_to")).isFalse();
    }
}
