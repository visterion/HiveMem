package com.hivemem.sync;

import tools.jackson.databind.ObjectMapper;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(OpLogBackfillRunnerIntegrationTest.TestConfig.class)
class OpLogBackfillRunnerIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean @Primary
        EmbeddingClient testEmbeddingClient() { return new FixedEmbeddingClient(); }

        @Bean
        ObjectMapper objectMapper() { return new ObjectMapper(); }
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

    @Autowired DSLContext dsl;
    @Autowired OpLogBackfillRunner runner;

    @org.junit.jupiter.api.AfterEach
    void cleanupOpLog() {
        dsl.execute("DELETE FROM ops_log");
    }

    @Test
    void backfillEmitsOpsForExistingCellsAndIsIdempotent() {
        // Seed a cell directly via SQL (bypassing the service so no op-log entry yet).
        dsl.execute("DELETE FROM ops_log");
        UUID cellId = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO cells (id, content, realm, signal, topic, created_by, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                cellId, "seed-content", "engineering", "facts", "t", "admin", "committed");

        long before = dsl.fetchOne("SELECT count(*) AS c FROM ops_log").get("c", Long.class);
        assertThat(before).isZero();

        runner.runBackfill();

        long afterFirst = dsl.fetchOne("SELECT count(*) AS c FROM ops_log").get("c", Long.class);
        assertThat(afterFirst).isGreaterThanOrEqualTo(1L);

        runner.runBackfill();
        long afterSecond = dsl.fetchOne("SELECT count(*) AS c FROM ops_log").get("c", Long.class);
        assertThat(afterSecond).isEqualTo(afterFirst);
    }

    @Test
    void backfillSerializesTextArraysAsJsonArrays() {
        dsl.execute("DELETE FROM ops_log");
        UUID cellId = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO cells (id, content, realm, signal, topic, tags, key_points, created_by, status) "
                + "VALUES (?, ?, ?, ?, ?, ?::text[], ?::text[], ?, ?)",
                cellId, "content", "engineering", "facts", "t",
                "{tag1,tag2}", "{kp1}",
                "admin", "committed");

        runner.runBackfill();

        var row = dsl.fetchOne(
                "SELECT payload::text AS p FROM ops_log "
                + "WHERE op_type = 'add_cell' AND payload->>'cell_id' = ?",
                cellId.toString());
        assertThat(row).isNotNull();
        String compact = row.get("p", String.class).replaceAll("\\s+", "");
        assertThat(compact).contains("\"tags\":[\"tag1\",\"tag2\"]");
        assertThat(compact).contains("\"key_points\":[\"kp1\"]");
        // column names are mapped to the op-payload key contract OpReplayer expects
        assertThat(compact).contains("\"agent_id\":\"admin\"");
        dsl.execute("DELETE FROM cells WHERE id = ?", cellId);
    }

    @Test
    void backfillSkipsClosedRevisions() {
        dsl.execute("DELETE FROM ops_log");
        UUID closedId = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO cells (id, content, realm, signal, topic, created_by, status, valid_until) "
                + "VALUES (?, 'old revision', 'eng', 'facts', 't', 'admin', 'committed', now())",
                closedId);

        runner.runBackfill();

        var row = dsl.fetchOne(
                "SELECT 1 FROM ops_log WHERE op_type = 'add_cell' AND payload->>'cell_id' = ?",
                closedId.toString());
        assertThat(row).isNull();
        dsl.execute("DELETE FROM cells WHERE id = ?", closedId);
    }

    @Test
    void backfillSerializesJsonbAsEmbeddedJsonObject() {
        dsl.execute("DELETE FROM ops_log");
        dsl.execute("""
                INSERT INTO agents (name, focus, autonomy, tools)
                VALUES (?, ?, ?::jsonb, ?::text[])
                ON CONFLICT (name) DO UPDATE SET focus = EXCLUDED.focus,
                    autonomy = EXCLUDED.autonomy, tools = EXCLUDED.tools
                """,
                "backfill-test-agent", "test-focus",
                "{\"default\":\"suggest_only\"}", "{tool1,tool2}");

        runner.runBackfill();

        var row = dsl.fetchOne(
                "SELECT payload::text AS p FROM ops_log "
                + "WHERE op_type = 'register_agent' AND payload->>'name' = ?",
                "backfill-test-agent");
        assertThat(row).isNotNull();
        String compact = row.get("p", String.class).replaceAll("\\s+", "");
        assertThat(compact).contains("\"autonomy\":{\"default\":\"suggest_only\"}");
        assertThat(compact).contains("\"tools\":[\"tool1\",\"tool2\"]");
        dsl.execute("DELETE FROM agents WHERE name = ?", "backfill-test-agent");
    }
}
