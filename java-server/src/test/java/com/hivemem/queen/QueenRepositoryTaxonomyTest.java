package com.hivemem.queen;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Mirrors the Testcontainers Postgres integration setup used by QueenRepositoryInboxTest
// (and the other *IntegrationTest/*IT classes in this codebase): an autowired
// DSLContext `db` against a Flyway-migrated schema. There is no shared abstract
// base class in this project -- each IT test duplicates this boilerplate.
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(QueenRepositoryTaxonomyTest.TestConfig.class)
class QueenRepositoryTaxonomyTest {

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

    // Isolate from other tests sharing the same container (matches the pattern used
    // by QueenRepositoryInboxTest) -- without it, rows from one test method/class
    // leak into another and break the exact assertions below.
    @BeforeEach
    void cleanCellsAndOpsLog() {
        db.execute("DELETE FROM cells WHERE realm IN ('work', 'inbox')");
        db.execute("DELETE FROM ops_log");
    }

    @Test
    void listTaxonomyAggregatesPerRealmTopicAndExcludesInbox() {
        QueenRepository repo = new QueenRepository(db);
        insert("work", "steuer"); insert("work", "steuer"); insert("work", "reisen");
        insert("inbox", "whatever");
        List<Map<String, Object>> rows = repo.listTaxonomy();
        assertThat(rows).noneMatch(r -> "inbox".equals(r.get("realm")));
        assertThat(rows).anySatisfy(r -> {
            if ("work".equals(r.get("realm")) && "steuer".equals(r.get("topic")))
                assertThat(((Number) r.get("cell_count")).longValue()).isEqualTo(2L);
        });
    }

    @Test
    void findArchivistLogReturnsOnlyArchivistOps() {
        QueenRepository repo = new QueenRepository(db);
        UUID inst = UUID.randomUUID();
        db.execute("INSERT INTO ops_log (instance_id, op_id, op_type, payload) VALUES (?, ?, 'reclassify_cell', ?::jsonb)",
                inst, UUID.randomUUID(), "{\"agent_id\":\"inbox-archivist\",\"cell_id\":\"c1\",\"new_realm\":\"work\"}");
        db.execute("INSERT INTO ops_log (instance_id, op_id, op_type, payload) VALUES (?, ?, 'reclassify_cell', ?::jsonb)",
                inst, UUID.randomUUID(), "{\"agent_id\":\"queen\",\"cell_id\":\"c2\"}");
        List<Map<String, Object>> rows = repo.findArchivistLog(50);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("payload").toString()).contains("inbox-archivist");
    }

    private void insert(String realm, String topic) {
        db.execute("INSERT INTO cells (id, content, realm, signal, topic, status, tags, created_at) "
                + "VALUES (?, 'x', ?, 'facts', ?, 'committed', '{}', now())", UUID.randomUUID(), realm, topic);
    }
}
