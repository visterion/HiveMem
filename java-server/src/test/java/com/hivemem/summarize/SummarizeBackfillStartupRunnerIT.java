package com.hivemem.summarize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class SummarizeBackfillStartupRunnerIT {

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
        dsl = DSL.using(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()),
                SQLDialect.POSTGRES);
        dsl.execute("DELETE FROM cells");
    }

    private UUID seed(String content, String embedding, String summary) {
        UUID id = UUID.randomUUID();
        // Production always inserts cells with a non-null tags array (empty by default);
        // seed the same way so the runner's `'needs_summary' != ALL(tags)` matches real rows.
        dsl.execute("INSERT INTO cells (id, content, embedding, summary, status, tags, valid_from) "
                + "VALUES (?, ?, ?::vector, ?, 'committed', '{}'::text[], now())",
                id, content, embedding, summary);
        return id;
    }

    /** Seed with an explicit status and optional soft-delete (valid_until set). */
    private UUID seedRaw(String content, String status, boolean softDeleted) {
        UUID id = UUID.randomUUID();
        dsl.execute("INSERT INTO cells (id, content, embedding, status, tags, valid_from, valid_until) "
                + "VALUES (?, ?, NULL, ?, '{}'::text[], now(), " + (softDeleted ? "now()" : "NULL") + ")",
                id, content, status);
        return id;
    }

    private boolean hasNeedsSummary(UUID id) {
        return dsl.fetchOne("SELECT 'needs_summary' = ANY(tags) AS f FROM cells WHERE id = ?", id)
                .get("f", Boolean.class);
    }

    private int needsSummaryCount(UUID id) {
        return dsl.fetchOne(
                "SELECT cardinality(array_positions(tags, 'needs_summary')) AS n FROM cells WHERE id = ?", id)
                .get("n", Integer.class);
    }

    @Test
    void tagsNullEmbeddingLongCellsAndLeavesOthers() {
        SummarizerProperties props = new SummarizerProperties(); // threshold defaults to 500
        UUID longNull = seed("x".repeat(600), null, null);          // SHOULD be tagged
        UUID longNullWithSummary = seed("y".repeat(600), null, "s"); // SHOULD be tagged (embedding still null)
        UUID shortNull = seed("z".repeat(100), null, null);         // SHOULD NOT (too short)
        UUID longEmbedded = seed("w".repeat(600), "[1,0,0]", "s");  // SHOULD NOT (already embedded)

        new SummarizeBackfillStartupRunner(dsl, props).run(null);

        assertTrue(hasNeedsSummary(longNull), "long NULL-embedding cell must be tagged");
        assertTrue(hasNeedsSummary(longNullWithSummary), "long NULL-embedding cell must be tagged even with a summary");
        assertFalse(hasNeedsSummary(shortNull), "short cell must not be tagged");
        assertFalse(hasNeedsSummary(longEmbedded), "already-embedded cell must not be tagged");
    }

    @Test
    void skipsPendingAndSoftDeletedCells() {
        SummarizerProperties props = new SummarizerProperties();
        UUID pending = seedRaw("p".repeat(600), "pending", false);        // not committed
        UUID softDeleted = seedRaw("d".repeat(600), "committed", true);   // valid_until set

        new SummarizeBackfillStartupRunner(dsl, props).run(null);

        assertFalse(hasNeedsSummary(pending), "pending cell must not be tagged");
        assertFalse(hasNeedsSummary(softDeleted), "soft-deleted cell must not be tagged");
    }

    @Test
    void doesNotDuplicateTagOnSecondRun() {
        SummarizerProperties props = new SummarizerProperties();
        UUID cell = seed("a".repeat(600), null, null);
        var runner = new SummarizeBackfillStartupRunner(dsl, props);

        runner.run(null); // tags once
        runner.run(null); // second pass must be idempotent (guarded by `!= ALL(tags)`)

        assertEquals(1, needsSummaryCount(cell), "needs_summary must appear exactly once after two runs");
    }
}
