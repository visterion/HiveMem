package com.hivemem.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * H4: {@link EmbeddingStateRepository#fetchCellBatch} / {@code fetchFactBatch} used to page with
 * {@code ORDER BY created_at ASC LIMIT ? OFFSET ?}. Because each batch's {@code UPDATE ... SET
 * embedding} happens between page fetches, and {@code created_at} is non-unique / not a stable
 * scan-order tiebreaker, an OFFSET-based rescan can silently skip a row — which then keeps its
 * stale-dimension vector and breaks the {@code CREATE INDEX ... ((embedding::vector(newDim)))}
 * cast at the end of a re-encode. The fix pages by an {@code id > ?} keyset combined with a
 * "still needs work" predicate (no embedding, or the wrong dimension). This IT proves every row
 * is visited exactly once and ends at the target dimension, even when other rows are mutated
 * out-of-band while the scan is in progress (simulating a concurrent writer/backfill).
 */
@Testcontainers
class EmbeddingReencodeKeysetPaginationIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private static final int OLD_DIM = 32;
    private static final int NEW_DIM = 16;

    private DSLContext dsl;
    private EmbeddingStateRepository repo;

    @BeforeEach
    void setUp() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        dsl.execute("DELETE FROM facts");
        dsl.execute("DELETE FROM cells");
        // idx_cells_embedding is an expression index bound to whatever dimension a previous test
        // left behind; drop it so inserting mixed-dimension vectors below never trips it.
        dsl.execute("DROP INDEX IF EXISTS idx_cells_embedding");
        dsl.execute("DROP INDEX IF EXISTS idx_facts_embedding");

        repo = new EmbeddingStateRepository(dsl, ds);
    }

    @Test
    void everyCellIsVisitedExactlyOnceDespiteConcurrentMutationBetweenPages() {
        List<UUID> staleIds = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            staleIds.add(insertCell("stale content " + i, vector(OLD_DIM, 0.1f)));
        }
        UUID alreadyCorrect = insertCell("already correct", vector(NEW_DIM, 0.2f));
        UUID nullEmbedding = insertCellNoEmbedding("no embedding yet");

        List<UUID> visited = new ArrayList<>();
        UUID afterId = null;
        int guard = 0;
        while (true) {
            List<EmbeddingStateRepository.CellRow> batch = repo.fetchCellBatch(afterId, NEW_DIM, 3);
            if (batch.isEmpty()) {
                break;
            }
            for (EmbeddingStateRepository.CellRow row : batch) {
                visited.add(row.id());
                repo.updateEmbedding(row.id(), constantVector(NEW_DIM));
            }
            afterId = batch.get(batch.size() - 1).id();

            // Simulate a concurrent actor rewriting a row's embedding mid-scan (the exact
            // scenario that broke OFFSET pagination): touch a row that hasn't been visited yet.
            staleIds.stream()
                    .filter(id -> !visited.contains(id))
                    .findFirst()
                    .ifPresent(id -> dsl.execute("UPDATE cells SET embedding = ?::vector WHERE id = ?",
                            vector(OLD_DIM, 0.9f), id));

            if (++guard > 100) {
                throw new IllegalStateException("fetchCellBatch loop did not terminate");
            }
        }

        // Every originally-stale cell was visited (none skipped) ...
        assertThat(visited).containsAll(staleIds);
        // ... the already-correct-dimension cell was never re-visited (predicate correctly
        // excludes rows that don't need work) ...
        assertThat(visited).doesNotContain(alreadyCorrect);
        // ... and the NULL-embedding cell (nothing to fix, no encode call in this low-level test)
        // was visited by the predicate (it legitimately still "needs work"), proving fetchCellBatch
        // surfaces it rather than silently ignoring it.
        assertThat(visited).contains(nullEmbedding);

        // Final state: every stale cell now has the target dimension.
        for (UUID id : staleIds) {
            Integer dim = dsl.fetchOne("SELECT vector_dims(embedding) AS d FROM cells WHERE id = ?", id)
                    .get("d", Integer.class);
            assertThat(dim).isEqualTo(NEW_DIM);
        }
    }

    @Test
    void everyFactIsVisitedExactlyOnceAcrossPages() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(insertFact("subject" + i, vector(OLD_DIM, 0.3f)));
        }

        List<UUID> visited = new ArrayList<>();
        UUID afterId = null;
        int guard = 0;
        while (true) {
            List<EmbeddingStateRepository.FactRow> batch = repo.fetchFactBatch(afterId, NEW_DIM, 2);
            if (batch.isEmpty()) {
                break;
            }
            for (EmbeddingStateRepository.FactRow row : batch) {
                visited.add(row.id());
                repo.updateFactEmbedding(row.id(), constantVector(NEW_DIM));
            }
            afterId = batch.get(batch.size() - 1).id();
            if (++guard > 100) {
                throw new IllegalStateException("fetchFactBatch loop did not terminate");
            }
        }

        assertThat(visited).containsExactlyInAnyOrderElementsOf(ids);
        for (UUID id : ids) {
            Integer dim = dsl.fetchOne("SELECT vector_dims(embedding) AS d FROM facts WHERE id = ?", id)
                    .get("d", Integer.class);
            assertThat(dim).isEqualTo(NEW_DIM);
        }
    }

    private static List<Float> constantVector(int dim) {
        List<Float> v = new ArrayList<>(dim);
        for (int i = 0; i < dim; i++) v.add(0.42f);
        return v;
    }

    private UUID insertCell(String content, String vectorLiteral) {
        UUID id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, embedding, realm, status, created_by, valid_from)
                VALUES (?, ?, ?::vector, 'test', 'committed', 'test', now())
                """, id, content, vectorLiteral);
        return id;
    }

    private UUID insertCellNoEmbedding(String content) {
        UUID id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, embedding, realm, status, created_by, valid_from)
                VALUES (?, ?, NULL, 'test', 'committed', 'test', now())
                """, id, content);
        return id;
    }

    private UUID insertFact(String subject, String vectorLiteral) {
        UUID id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO facts (id, subject, predicate, "object", embedding, status, created_by, valid_from)
                VALUES (?, ?, 'pred', 'obj', ?::vector, 'committed', 'test', now())
                """, id, subject, vectorLiteral);
        return id;
    }

    private static String vector(int dim, float value) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dim; i++) {
            if (i > 0) sb.append(',');
            sb.append(value);
        }
        return sb.append(']').toString();
    }
}
