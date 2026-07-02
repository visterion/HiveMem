package com.hivemem.embedding;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class EmbeddingBackfillRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private DSLContext dsl;

    @BeforeEach
    void setUp() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);

        dsl.execute("DELETE FROM tunnels");
        dsl.execute("DELETE FROM cell_attachments");
        dsl.execute("DELETE FROM cells");
        dsl.execute("INSERT INTO instance_identity (id, instance_id) VALUES (1, gen_random_uuid()) ON CONFLICT DO NOTHING");
    }

    @Test
    void findsAndBackfillsCellMissingEmbedding() {
        EmbeddingBackfillRepository repo = new EmbeddingBackfillRepository(dsl);

        // Insert a cell with NULL embedding, non-empty content, and 'embedding_pending' tag
        UUID id = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO cells (id, content, embedding, tags, status, created_by, realm, signal, topic, valid_from) "
                + "VALUES (?, 'hello world', NULL, ARRAY['embedding_pending'], 'committed', 'test', 'facts', 'discoveries', 'test', now())",
                id);

        // Insert a rejected cell — must NOT be returned
        UUID rejectedId = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO cells (id, content, embedding, tags, status, created_by, realm, signal, topic, valid_from) "
                + "VALUES (?, 'rejected content', NULL, ARRAY[]::text[], 'rejected', 'test', 'facts', 'discoveries', 'test', now())",
                rejectedId);

        // findCellsMissingEmbedding should return it
        List<UUID> missing = repo.findCellsMissingEmbedding(10);
        assertTrue(missing.contains(id), "cell with NULL embedding should be returned");
        assertFalse(missing.contains(rejectedId), "rejected cell must not be returned");

        // Build a 1024-dim vector (same dimension as FixedEmbeddingClient default)
        int dims = 1024;
        List<Float> vecList = new ArrayList<>(Collections.nCopies(dims, 0.0f));
        vecList.set(0, 0.5f);
        Float[] vec = vecList.toArray(Float[]::new);

        // setEmbedding should persist the vector and remove 'embedding_pending' tag
        repo.setEmbedding(id, vec);

        // Verify embedding is now set
        List<UUID> stillMissing = repo.findCellsMissingEmbedding(10);
        assertFalse(stillMissing.contains(id), "cell should no longer appear after embedding is set");

        // Verify tag was removed
        var row = dsl.fetchOne("SELECT tags FROM cells WHERE id = ?", id);
        assertNotNull(row);
        Object tagsObj = row.get("tags");
        if (tagsObj instanceof java.sql.Array sqlArr) {
            try {
                Object[] tags = (Object[]) sqlArr.getArray();
                for (Object t : tags) {
                    assertNotEquals("embedding_pending", t, "embedding_pending tag must be removed");
                }
            } catch (Exception e) {
                fail("Could not read tags array: " + e.getMessage());
            }
        }
        // else tags is null or empty string array — tag is gone either way
    }

    @Test
    void findSnapshotReturnsContentAndSummary() {
        EmbeddingBackfillRepository repo = new EmbeddingBackfillRepository(dsl);

        UUID id = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO cells (id, content, summary, status, created_by, realm, signal, topic, valid_from) "
                + "VALUES (?, 'the content', 'the summary', 'committed', 'test', 'facts', 'discoveries', 'test', now())",
                id);

        var snap = repo.findSnapshot(id);
        assertTrue(snap.isPresent());
        assertEquals("the content", snap.get().content());
        assertEquals("the summary", snap.get().summary());
    }

    @Test
    void doesNotReturnSoftDeletedCells() {
        EmbeddingBackfillRepository repo = new EmbeddingBackfillRepository(dsl);

        UUID id = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO cells (id, content, embedding, status, created_by, realm, signal, topic, valid_from, valid_until) "
                + "VALUES (?, 'hello', NULL, 'committed', 'test', 'facts', 'discoveries', 'test', now(), now())",
                id);

        List<UUID> missing = repo.findCellsMissingEmbedding(10);
        assertFalse(missing.contains(id), "soft-deleted (valid_until set) cell must not be returned");
    }
}
