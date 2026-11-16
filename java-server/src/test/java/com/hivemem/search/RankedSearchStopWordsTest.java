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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the optional stop-word filter of the keyword signal of ranked_search against a real PostgreSQL.
 * Keyword-only ranking: weight_keyword = 1, every other weight 0.
 */
@Testcontainers
class RankedSearchStopWordsTest {

    private static final int DIMS = 8;

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(List.of("apparmor=unconfined"))));

    private DSLContext dsl;
    private DataSource ds;
    private UUID content;
    private UUID stopEn;
    private UUID stopDe;

    @BeforeEach
    void setUp() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        dsl.execute("TRUNCATE TABLE cell_references, references_, facts, tunnels, cells CASCADE");
        new EmbeddingStateRepository(dsl, ds).replaceRankedSearchFunction(DIMS);
        content = insertCell("a", "zebra crossing report");
        stopEn = insertCell("a", "the the the of and");
        stopDe = insertCell("a", "der die das und");
        for (int i = 0; i < 10; i++) {
            insertCell("a", "filler note " + i);
        }
    }

    private void on() {
        new EmbeddingStateRepository(dsl, ds, new SearchKeywordProperties(List.of("german", "english")))
                .replaceRankedSearchFunction(DIMS);
    }

    private void off() {
        new EmbeddingStateRepository(dsl, ds).replaceRankedSearchFunction(DIMS);
    }

    private String functionDefinition() {
        return dsl.fetchSingle("SELECT pg_get_functiondef(p.oid) AS d FROM pg_proc p WHERE p.proname = 'ranked_search'")
                .get("d", String.class);
    }


    @Test
    void offKeepsStopWordsInTheQuery() {
        off();
        Map<UUID, Float> kw = byId(search("the zebra der"));
        assertThat(kw.get(stopEn)).isGreaterThan(0f);
        assertThat(kw.get(stopDe)).isGreaterThan(0f);
    }

    @Test
    void onDropsGermanAndEnglishStopWords() {
        on();
        Map<UUID, Float> kw = byId(search("the zebra der"));
        assertThat(kw.get(content)).isEqualTo(1.0f);
        assertThat(kw.getOrDefault(stopEn, 0f)).isZero();
        assertThat(kw.getOrDefault(stopDe, 0f)).isZero();
    }

    @Test
    void onKeepsAWordThatIsAStopWordInNeitherLanguage() {
        on();
        assertThat(byId(search("crossing")).get(content)).isEqualTo(1.0f);
    }

    @Test
    void onQueryOfStopWordsOnlyAndNullQueryScoreNothingWithoutError() {
        on();
        insertCellWithEmbedding("a", "an unrelated embedded note");
        List<Row> rows = searchWithEmbedding("the der und of");
        assertThat(rows).isNotEmpty().allSatisfy(r -> assertThat(r.kw()).isZero());
        assertThat(searchWithEmbedding(null)).isNotEmpty().allSatisfy(r -> assertThat(r.kw()).isZero());
        assertThat(search("the der und of")).isEmpty();
        assertThat(search(null)).isEmpty();
    }

    @Test
    void simpleAloneChangesNothing() {
        off();
        List<Row> before = search("the zebra der");
        new EmbeddingStateRepository(dsl, ds, new SearchKeywordProperties(List.of("simple")))
                .replaceRankedSearchFunction(DIMS);
        assertThat(search("the zebra der")).isEqualTo(before);
    }

    @Test
    void unknownConfigurationIsReportedAndLeavesTheInstalledFunctionInPlace() {
        off();
        var repo = new EmbeddingStateRepository(dsl, ds, new SearchKeywordProperties(List.of("german", "klingon")));
        assertThat(repo.unknownStopWordConfigs()).containsExactly("klingon");
        assertThatThrownBy(() -> repo.replaceRankedSearchFunction(DIMS)).isInstanceOf(RuntimeException.class);
        assertThat(byId(search("zebra")).get(content)).isEqualTo(1.0f);
        assertThat(functionDefinition()).doesNotContain("length(to_tsvector(");
    }

    @Test
    void knownConfigurationsAreNotReportedAndNothingConfiguredAsksNothing() {
        assertThat(new EmbeddingStateRepository(dsl, ds, new SearchKeywordProperties(List.of("german", "english")))
                .unknownStopWordConfigs()).isEmpty();
        assertThat(new EmbeddingStateRepository(dsl, ds).unknownStopWordConfigs()).isEmpty();
    }

    @Test
    void malformedNameFailsWhenTheRepositoryIsBuilt() {
        assertThatThrownBy(() -> new EmbeddingStateRepository(dsl, ds, new SearchKeywordProperties(List.of("German"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hivemem.search.keyword.stop-word-configs");
    }

    @Test
    void onQuoteAndBackslashInTheQueryAreHarmless() {
        UUID quoted = insertCell("a", "o'brien wrote c:\\temp notes");
        on();
        assertThat(byId(search("the o'brien c:\\temp")).get(quoted)).isGreaterThan(0f);
    }

    @Test
    void onHyphenatedCompoundWithStopWordsStillMatches() {
        UUID compound = insertCell("a", "a state-of-the-art design");
        on();
        assertThat(byId(search("state-of-the-art")).get(compound)).isGreaterThan(0f);
    }

    @Test
    void onLongQueryThatShrinksUnderTheWindowBoundRuns() {
        insertCell("a", "w01 w02 invented pair");
        on();
        StringBuilder q = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            q.append(String.format("w%02d ", i));
        }
        for (String w : new String[]{"the", "of", "and", "a", "an", "to", "in", "is", "it", "for",
                "on", "with", "as", "by", "at", "be", "this", "that", "was", "are"}) {
            q.append(w).append(' ');
        }
        assertThat(search(q.toString().trim())).isNotEmpty();
    }

    @Test
    void switchingOffAgainRemovesTheFilter() {
        on();
        assertThat(functionDefinition()).contains("length(to_tsvector('german', lex)) > 0");
        off();
        assertThat(functionDefinition()).doesNotContain("length(to_tsvector(");
    }

    // ---- helpers ----

    private record Row(UUID id, float kw) {
    }

    private static final String EMBEDDING = "[1,0,0,0,0,0,0,0]";

    private List<Row> search(String query) {
        return searchWith(query, false, "p_realm => 'a'");
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

    private static Map<UUID, Float> byId(List<Row> rows) {
        Map<UUID, Float> map = new HashMap<>();
        rows.forEach(r -> map.put(r.id, r.kw));
        return map;
    }

    private UUID insertCellWithEmbedding(String realm, String content) {
        UUID id = insertCell(realm, content);
        dsl.execute("UPDATE cells SET embedding = ?::vector WHERE id = ?", EMBEDDING, id);
        return id;
    }

    private UUID insertCell(String realm, String text) {
        UUID id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, realm, signal, topic, importance, status, created_by,
                                   created_at, valid_from)
                VALUES (?, ?, ?, 'facts', 't', 3, 'committed', 'writer-1',
                        '2026-04-03T10:00:00Z'::timestamptz, '2026-04-03T10:00:00Z'::timestamptz)
                """, id, text, realm);
        return id;
    }
}
