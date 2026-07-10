package com.hivemem.search;

import com.hivemem.cells.CellReadRepository;
import com.hivemem.embedding.EmbeddingStateRepository;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the importance ranking signal follows the product's 5-star convention:
 * importance 5 (most important) maps to score 1.0 and outranks importance 1 (0.2),
 * that keyword-only ranking works with a NULL query embedding (the embedding-outage
 * fallback path), and that the reading list surfaces the most important entries first.
 */
@Testcontainers
class RankedSearchImportanceTest {

    private static final int DIMS = 8;

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
        dsl.execute("TRUNCATE TABLE cell_references, references_, facts, tunnels, cells CASCADE");
        // ranked_search was dropped in V0017 and is installed at boot from the template;
        // this minimal test installs it directly.
        new EmbeddingStateRepository(dsl, ds).replaceRankedSearchFunction(DIMS);
    }

    @Test
    void fiveStarCellOutranksOneStarCellOnImportance() {
        UUID fiveStar = insertCell("alpha note about deployment", 5);
        UUID oneStar = insertCell("alpha note about deployment", 1);

        Result<Record> result = rankedSearch("alpha");

        assertThat(result).hasSize(2);
        Map<UUID, Double> importanceScores = scores(result, "score_importance");
        Map<UUID, Double> totals = scores(result, "score_total");
        assertThat(importanceScores.get(fiveStar)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(importanceScores.get(oneStar)).isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(totals.get(fiveStar)).isGreaterThan(totals.get(oneStar));
        assertThat((UUID) result.get(0).get("id")).isEqualTo(fiveStar);
    }

    @Test
    void keywordOnlyRankingWorksWithoutQueryEmbedding() {
        // The embedding-outage fallback passes a NULL query embedding; ranked_search
        // must still return keyword matches.
        UUID match = insertCell("bravo unique keyword content", 3);
        insertCell("completely unrelated text", 3);

        Result<Record> result = rankedSearch("bravo");

        assertThat(result).hasSize(1);
        assertThat((UUID) result.get(0).get("id")).isEqualTo(match);
    }

    @Test
    void readingListOrdersMostImportantFirst() {
        dsl.execute("INSERT INTO references_ (title, status, importance) VALUES ('minor', 'unread', 1)");
        dsl.execute("INSERT INTO references_ (title, status, importance) VALUES ('major', 'unread', 5)");

        List<Map<String, Object>> rows = new CellReadRepository(dsl).readingList(null, 10);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("title")).isEqualTo("major");
        assertThat(rows.get(1).get("title")).isEqualTo("minor");
    }

    // ---- helpers ----

    private Result<Record> rankedSearch(String query) {
        return dsl.fetch(
                "SELECT id, score_importance, score_total "
                        + "FROM ranked_search(NULL::vector, ?, NULL, NULL, NULL, 10, "
                        + "0.30::real, 0.15::real, 0.15::real, 0.15::real, 0.15::real, 0.10::real) "
                        + "ORDER BY score_total DESC",
                query);
    }

    private UUID insertCell(String content, int importance) {
        UUID id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, realm, signal, topic, importance, status, created_by, created_at, valid_from)
                VALUES (?, ?, 'test', 'facts', 't', ?, 'committed', 'writer-1',
                        '2026-04-03T10:00:00Z'::timestamptz, '2026-04-03T10:00:00Z'::timestamptz)
                """, id, content, importance);
        return id;
    }

    private static Map<UUID, Double> scores(Result<Record> result, String column) {
        Map<UUID, Double> map = new java.util.HashMap<>();
        for (Record row : result) {
            map.put((UUID) row.get("id"), ((Number) row.get(column)).doubleValue());
        }
        return map;
    }
}
