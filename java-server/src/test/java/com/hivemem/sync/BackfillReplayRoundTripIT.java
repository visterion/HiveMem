package com.hivemem.sync;

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

/**
 * C5 regression: the op-log backfill of pre-existing data must produce payloads OpReplayer can
 * actually replay. Before the column-name → payload-key mapping, backfilled ops carried raw
 * column names ({@code id}, {@code from_cell}, {@code created_by}) that the replayer reads as
 * NULL — a newly connected peer replayed none of the existing data and the loss was silent.
 * This test round-trips: seed rows → backfill → wipe → replay the backfilled ops → assert the
 * rows are fully reconstructed with their original ids.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(BackfillReplayRoundTripIT.TestConfig.class)
class BackfillReplayRoundTripIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean @Primary
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

    @Autowired DSLContext dsl;
    @Autowired OpLogBackfillRunner backfillRunner;
    @Autowired SyncOpsRepository syncOpsRepository;
    @Autowired OpReplayer replayer;

    @Test
    void backfilledOpsReplayIntoAnEmptyPeer() {
        // --- seed pre-existing data directly via SQL (as if written before op-logging existed) ---
        dsl.execute("DELETE FROM ops_log");
        dsl.execute("DELETE FROM applied_ops");
        dsl.execute("DELETE FROM tunnels");
        dsl.execute("DELETE FROM cell_references");
        dsl.execute("DELETE FROM facts");
        dsl.execute("DELETE FROM references_");
        dsl.execute("DELETE FROM cells");

        UUID cellA = UUID.randomUUID();
        UUID cellB = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, realm, signal, topic, tags, importance, created_by, status)
                VALUES (?::uuid, 'pre-existing knowledge', 'eng', 'facts', 'roundtrip',
                        ARRAY['seed-tag'], 4, 'seed-agent', 'committed')
                """, cellA);
        dsl.execute("""
                INSERT INTO cells (id, content, realm, signal, topic, created_by, status)
                VALUES (?::uuid, 'second cell', 'eng', 'events', 'roundtrip', 'seed-agent', 'committed')
                """, cellB);

        UUID tunnelId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO tunnels (id, from_cell, to_cell, relation, note, status, created_by)
                VALUES (?::uuid, ?::uuid, ?::uuid, 'related_to', 'seed note', 'committed', 'seed-agent')
                """, tunnelId, cellA, cellB);

        UUID factId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO facts (id, subject, predicate, "object", confidence, source_id, status, created_by)
                VALUES (?::uuid, 'roundtrip', 'works', 'yes', 0.9, ?::uuid, 'committed', 'seed-agent')
                """, factId, cellA);

        UUID refId = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO references_ (id, title, url, status)
                VALUES (?::uuid, 'Seed Reference', 'https://example.com', 'read')
                """, refId);

        // Closed revisions must NOT be part of the initial-sync snapshot.
        UUID closedCell = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, realm, signal, topic, created_by, status, valid_until)
                VALUES (?::uuid, 'closed revision', 'eng', 'facts', 'roundtrip', 'seed-agent', 'committed', now())
                """, closedCell);

        // --- backfill the op log, capture the ops a fresh peer would pull ---
        backfillRunner.runBackfill();
        List<OpDto> ops = syncOpsRepository.findOpsAfter(0);
        assertThat(ops).isNotEmpty();
        assertThat(ops).noneMatch(op -> closedCell.toString().equals(
                op.payload().path("cell_id").asText(null)));

        // --- wipe the data (simulate the empty peer) and replay ---
        dsl.execute("DELETE FROM tunnels");
        dsl.execute("DELETE FROM facts");
        dsl.execute("DELETE FROM references_");
        dsl.execute("DELETE FROM cells");

        UUID sourcePeer = UUID.randomUUID();
        OpReplayer.BatchResult result = replayer.replayAll(sourcePeer, ops);

        assertThat(result.failed()).isZero();
        assertThat(result.replayed()).isGreaterThanOrEqualTo(5); // 2 cells + tunnel + fact + reference

        // --- every seeded row is reconstructed under its ORIGINAL id ---
        var cellRow = dsl.fetchOne(
                "SELECT content, realm, signal, topic, tags, importance, created_by, status "
                + "FROM cells WHERE id = ?", cellA);
        assertThat(cellRow).isNotNull();
        assertThat(cellRow.get("content", String.class)).isEqualTo("pre-existing knowledge");
        assertThat(cellRow.get("realm", String.class)).isEqualTo("eng");
        assertThat(cellRow.get("signal", String.class)).isEqualTo("facts");
        assertThat(cellRow.get("topic", String.class)).isEqualTo("roundtrip");
        assertThat(cellRow.get("tags", String[].class)).contains("seed-tag");
        assertThat(cellRow.get("importance", Integer.class)).isEqualTo(4);
        assertThat(cellRow.get("created_by", String.class)).isEqualTo("seed-agent");
        assertThat(cellRow.get("status", String.class)).isEqualTo("committed");

        var tunnelRow = dsl.fetchOne(
                "SELECT from_cell, to_cell, relation, note FROM tunnels WHERE id = ?", tunnelId);
        assertThat(tunnelRow).isNotNull();
        assertThat(tunnelRow.get("from_cell", UUID.class)).isEqualTo(cellA);
        assertThat(tunnelRow.get("to_cell", UUID.class)).isEqualTo(cellB);
        assertThat(tunnelRow.get("relation", String.class)).isEqualTo("related_to");
        assertThat(tunnelRow.get("note", String.class)).isEqualTo("seed note");

        var factRow = dsl.fetchOne(
                "SELECT subject, predicate, \"object\", confidence, source_id, created_by "
                + "FROM facts WHERE id = ?", factId);
        assertThat(factRow).isNotNull();
        assertThat(factRow.get("subject", String.class)).isEqualTo("roundtrip");
        assertThat(factRow.get("predicate", String.class)).isEqualTo("works");
        assertThat(factRow.get("object", String.class)).isEqualTo("yes");
        assertThat(factRow.get("confidence", Double.class)).isCloseTo(0.9, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(factRow.get("source_id", UUID.class)).isEqualTo(cellA);
        assertThat(factRow.get("created_by", String.class)).isEqualTo("seed-agent");

        var refRow = dsl.fetchOne("SELECT title, url FROM references_ WHERE id = ?", refId);
        assertThat(refRow).isNotNull();
        assertThat(refRow.get("title", String.class)).isEqualTo("Seed Reference");
        assertThat(refRow.get("url", String.class)).isEqualTo("https://example.com");

        // closed revision stays gone
        assertThat(dsl.fetchOne("SELECT 1 FROM cells WHERE id = ?", closedCell)).isNull();
    }
}
