package com.hivemem.search;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the BM25 keyword signal of ranked_search: rare terms and short documents win, the best
 * lexical match of a query scores exactly 1.0, and the corpus statistics only ever see the cells
 * that pass the function's filters. Ordering tests use weight_keyword = 1 and every other weight 0
 * so that no other signal decides the order.
 */
@Testcontainers
class RankedSearchBm25Test {

    private static final int DIMS = 8;

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(List.of("apparmor=unconfined"))));

    private DSLContext dsl;

    @BeforeEach
    void setUp() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        dsl.execute("TRUNCATE TABLE cell_references, references_, facts, tunnels, cells CASCADE");
        new EmbeddingStateRepository(dsl, ds).replaceRankedSearchFunction(DIMS);
    }

    @Test
    void shortDocumentWithRareWordRanksAboveLongOne() {
        // Background cells make "alpha" and "beta" common, so only the rare word carries weight.
        for (int i = 0; i < 30; i++) {
            insertCell("a", "background note " + i + " about alpha and beta", null, "committed", false);
        }
        String shortText = "zebra alpha beta";
        UUID a = insertCell("a", shortText, null, "committed", false);
        UUID b = insertCell("a", shortText + " " + "filler ".repeat(2000), null, "committed", false);
        UUID c = insertCell("a", "alpha", null, "committed", false);

        List<Row> rows = search("zebra alpha beta", "a", null);
        List<Row> order = onlyIds(rows, a, b, c);

        assertThat(order).extracting(r -> r.id).containsExactly(a, b, c);
        Map<UUID, Float> kw = byId(rows);
        assertThat(kw.get(a)).isEqualTo(1.0f);
        assertThat(kw.get(b)).isGreaterThan(0f).isLessThan(1f);
        assertThat(kw.get(c)).isLessThan(kw.get(b));
    }

    @Test
    void bestLexicalMatchScoresOne() {
        for (int i = 0; i < 5; i++) {
            insertCell("a", "note " + i + " mentions kiwi " + "pad ".repeat(i * 10), null, "committed", false);
        }
        insertCell("a", "kiwi kiwi kiwi mango", null, "committed", false);
        insertCell("a", "mango only here", null, "committed", false);

        List<Row> rows = search("kiwi mango", "a", null);

        assertThat(rows).hasSizeGreaterThan(3);
        assertThat(rows.stream().filter(r -> r.kw == 1.0f).count()).isEqualTo(1);
        assertThat(rows.get(0).kw).isEqualTo(1.0f);
        assertThat(rows).allSatisfy(r -> assertThat(r.kw).isBetween(0f, 1f));
    }

    @Test
    void emptyNullAndPunctuationQueryGiveNoKeywordScore() {
        insertCell("a", "some ordinary text", null, "committed", false);
        insertCellWithEmbedding("a", "an unrelated embedded note");

        for (String query : new String[]{null, "", "?!\u2026"}) {
            // Without an embedding there is nothing to return, and no error.
            assertThat(search(query, "a", null)).isEmpty();
            // With one, the embedded cell comes back through sem > 0.3 and has no keyword score.
            List<Row> rows = searchWithEmbedding(query);
            assertThat(rows).isNotEmpty();
            assertThat(rows).allSatisfy(r -> assertThat(r.kw).isEqualTo(0f));
        }
    }

    @Test
    void queryWithUnknownTermsOnly() {
        insertCell("a", "some ordinary text", null, "committed", false);
        insertCellWithEmbedding("a", "an unrelated embedded note");

        assertThat(search("qqqqzzzz wwwwxxxx", "a", null)).isEmpty();
        List<Row> rows = searchWithEmbedding("qqqqzzzz wwwwxxxx");
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(r -> assertThat(r.kw).isEqualTo(0f));
    }

    @Test
    void summaryAndContentBothCount() {
        for (int i = 0; i < 6; i++) {
            insertCell("a", "unrelated background " + i, null, "committed", false);
        }
        int k = 40;
        UUID x = insertCell("a", "needle " + "x".repeat(k), null, "committed", false);
        // The summary's "needle" has 6 bytes, so the content is 6 bytes shorter: equal total length.
        UUID y = insertCell("a", "needle " + "x".repeat(k - 6), "needle", "committed", false);

        List<Row> rows = search("needle", "a", null);

        assertThat(onlyIds(rows, x, y)).extracting(r -> r.id).containsExactly(y, x);
        // Strict: counting only content positions would tie the two cells.
        assertThat(byId(rows).get(y)).isGreaterThan(byId(rows).get(x));
    }

    @Test
    void filteredOutCellDoesNotChangeScores() {
        for (int i = 0; i < 4; i++) {
            insertCell("a", "plum note " + i + " " + "pad ".repeat(i), null, "committed", false);
        }
        insertCell("a", "plum plum cherry", null, "committed", false);

        List<Row> before = search("plum cherry", "a", null);
        assertThat(before).isNotEmpty();

        // Realm filter.
        for (int i = 0; i < 5; i++) {
            insertCell("b", "plum cherry plum cherry stuffed " + i, null, "committed", false);
        }
        assertThat(search("plum cherry", "a", null)).isEqualTo(before);

        // Status filter (default: committed only).
        insertCell("a", "plum cherry plum cherry pending", null, "pending", false);
        assertThat(search("plum cherry", "a", null)).isEqualTo(before);

        // Validity filter.
        insertCell("a", "plum cherry plum cherry expired", null, "committed", true);
        assertThat(search("plum cherry", "a", null)).isEqualTo(before);
    }

    @Test
    void excludedCellDoesNotChangeScoresForTagsTopicSignalAndRealms() {
        for (int i = 0; i < 4; i++) {
            insertFull("a", "facts", "keep", new String[]{"x"}, "grape note " + i + " " + "pad ".repeat(i));
        }
        insertFull("a", "facts", "keep", new String[]{"x"}, "grape grape melon");
        insertFull(null, "facts", "keep", new String[]{"x"}, "grape melon without realm");

        String[] filters = {
                "p_tags => ARRAY['x']",
                "p_topic => 'keep'",
                "p_signal => 'facts'",
                "p_realms => ARRAY['a', 'none']"
        };
        // One excluded cell per filter, each stuffed with the query terms.
        Runnable[] excluders = {
                () -> insertFull("a", "facts", "keep", new String[]{"y"}, "grape melon grape melon tags"),
                () -> insertFull("a", "facts", "other", new String[]{"x"}, "grape melon grape melon topic"),
                () -> insertFull("a", "events", "keep", new String[]{"x"}, "grape melon grape melon signal"),
                () -> insertFull("b", "facts", "keep", new String[]{"x"}, "grape melon grape melon realms")
        };
        for (int i = 0; i < filters.length; i++) {
            List<Row> before = searchWith("grape melon", false, filters[i]);
            assertThat(before).as(filters[i]).isNotEmpty();
            for (int j = 0; j < 5; j++) {
                excluders[i].run();
            }
            assertThat(searchWith("grape melon", false, filters[i])).as(filters[i]).isEqualTo(before);
        }
    }

    @Test
    void hyphenatedWordAndUrlInQuery() {
        for (int i = 0; i < 5; i++) {
            insertCell("a", "plain background note " + i, null, "committed", false);
        }
        UUID full = insertCell("a", "a state-of-the-art tool at https://example.com/page works", null, "committed", false);
        UUID partial = insertCell("a", "the art of a tool", null, "committed", false);
        UUID without = insertCell("a", "an ordinary tool at a shop", null, "committed", false);

        List<Row> rows = search("state-of-the-art https://example.com/page", "a", null);

        assertThat(rows.get(0).id).isEqualTo(full);
        Map<UUID, Float> kw = byId(rows);
        assertThat(kw).containsKeys(full, partial);
        assertThat(kw.get(full)).isGreaterThan(kw.get(partial));
        assertThat(kw).doesNotContainKey(without);
    }

    @Test
    void tiesAreBrokenById() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(insertCell("a", "identical lemon text", null, "committed", false));
        }
        insertCell("a", "something else entirely", null, "committed", false);

        List<Row> rows = search("lemon", "a", null);

        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(r -> assertThat(r.kw).isEqualTo(rows.get(0).kw));
        // Postgres orders uuid by unsigned bytes, which is the order of the canonical hex string
        // (UUID.compareTo compares signed longs and would disagree).
        assertThat(rows).extracting(r -> r.id.toString()).isSorted();
    }

    @Test
    void keywordOnlyFallbackOrdersByBm25WhenOtherWeightsAreZero() {
        for (int i = 0; i < 5; i++) {
            insertCell("a", "note " + i + " pear", null, "committed", false);
        }
        UUID best = insertCell("a", "pear fig", null, "committed", false);
        UUID worse = insertCell("a", "pear " + "pad ".repeat(100), null, "committed", false);

        List<Row> rows = search("pear fig", "a", null);

        assertThat(rows.get(0).id).isEqualTo(best);
        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i).kw).isLessThanOrEqualTo(rows.get(i - 1).kw);
        }
        assertThat(byId(rows).get(worse)).isLessThan(1f);
    }

    @Test
    void realmWithOnlyEmptyCellsReturnsNoRowAndNoError() {
        // A zero-byte cell can never match a term; the avgdl = 0 guard in the SQL is defensive.
        insertCell("empty", "", "", "committed", false);
        insertCell("empty", "", null, "committed", false);

        assertThat(search("anything", "empty", null)).isEmpty();
        assertThat(search(null, "empty", null)).isEmpty();
    }

    // ---- helpers ----

    private record Row(UUID id, float kw) {
    }

    private static final String EMBEDDING = "[1,0,0,0,0,0,0,0]";

    private List<Row> search(String query, String realm, String status) {
        return searchWith(query, false, "p_realm => ?, p_status => ?", realm, status);
    }

    /** Search with the query embedding set; semantic weight stays 0, so only sem > 0.3 admits rows. */
    private List<Row> searchWithEmbedding(String query) {
        return searchWith(query, true, "p_realm => 'a'");
    }

    private List<Row> searchWith(String query, boolean withEmbedding, String extraArgs, Object... extraBinds) {
        Object[] binds = new Object[extraBinds.length + 1];
        binds[0] = query;
        System.arraycopy(extraBinds, 0, binds, 1, extraBinds.length);
        Result<Record> result = dsl.fetch(
                "SELECT id, score_keyword FROM ranked_search("
                        + "query_embedding => " + (withEmbedding ? "'" + EMBEDDING + "'::vector" : "NULL::vector")
                        + ", query_text => ?, p_limit => 100, "
                        + "p_weight_semantic => 0, p_weight_keyword => 1, p_weight_recency => 0, "
                        + "p_weight_importance => 0, p_weight_popularity => 0, p_weight_graph_proximity => 0, "
                        + extraArgs + ")",
                binds);
        List<Row> rows = new ArrayList<>();
        for (Record r : result) {
            rows.add(new Row((UUID) r.get("id"), ((Number) r.get("score_keyword")).floatValue()));
        }
        return rows;
    }

    private static List<Row> onlyIds(List<Row> rows, UUID... ids) {
        List<UUID> wanted = List.of(ids);
        return rows.stream().filter(r -> wanted.contains(r.id)).toList();
    }

    private static Map<UUID, Float> byId(List<Row> rows) {
        Map<UUID, Float> map = new HashMap<>();
        rows.forEach(r -> map.put(r.id, r.kw));
        return map;
    }

    private UUID insertCellWithEmbedding(String realm, String content) {
        UUID id = insertCell(realm, content, null, "committed", false);
        dsl.execute("UPDATE cells SET embedding = ?::vector WHERE id = ?", EMBEDDING, id);
        return id;
    }

    private UUID insertFull(String realm, String signal, String topic, String[] tags, String content) {
        UUID id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, realm, signal, topic, tags, importance, status, created_by,
                                   created_at, valid_from)
                VALUES (?, ?, ?, ?, ?, ?::text[], 3, 'committed', 'writer-1',
                        '2026-04-03T10:00:00Z'::timestamptz, '2026-04-03T10:00:00Z'::timestamptz)
                """, id, content, realm, signal, topic, tags);
        return id;
    }

    private UUID insertCell(String realm, String content, String summary, String status, boolean expired) {
        UUID id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, summary, realm, signal, topic, importance, status, created_by,
                                   created_at, valid_from, valid_until)
                VALUES (?, ?, ?, ?, 'facts', 't', 3, ?, 'writer-1',
                        '2026-04-03T10:00:00Z'::timestamptz, '2026-04-03T10:00:00Z'::timestamptz,
                        CASE WHEN ? THEN '2026-04-04T10:00:00Z'::timestamptz ELSE NULL END)
                """, id, content, summary, realm, status, expired);
        return id;
    }
}
