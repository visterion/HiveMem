package com.hivemem.search;

import com.hivemem.embedding.EmbeddingStateRepository;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins the proximity component of the keyword signal of ranked_search: among the 100 best BM25
 * cells, cells whose rare query terms (document frequency / n <= 0.5) stand within 30 token
 * positions of each other are ranked up. Ordering tests use weight_keyword = 1 and every other
 * weight 0. Expected scores are computed independently in Java: a BM25 re-implementation over the
 * fixture texts, plus the coverage the test states for each cell by construction.
 */
@Testcontainers
class RankedSearchProximityTest {

    private static final int DIMS = 8;

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(List.of("apparmor=unconfined"))));

    private DSLContext dsl;
    /** The committed, valid cells of realm "a" inserted through add(...): id -> {summary, content}. */
    private final Map<UUID, String[]> docs = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        dsl.execute("TRUNCATE TABLE cell_references, references_, facts, tunnels, cells CASCADE");
        new EmbeddingStateRepository(dsl, ds).replaceRankedSearchFunction(DIMS);
        docs.clear();
    }

    @Test
    void closeTermsBeatFarTerms() {
        addFillers(30);
        // A is longer, so it has the lower BM25; its two rare terms are adjacent.
        UUID a = add("zork quib" + pad(300));
        // B is short, so it has the higher BM25; its rare terms are 41 positions apart.
        UUID b = add("zork" + pad(40) + " quib");

        List<Row> rows = search("zork quib");

        assertThat(bm25("zork", "quib").get(b)).isGreaterThan(bm25("zork", "quib").get(a));
        assertThat(onlyIds(rows, a, b)).extracting(r -> r.id).containsExactly(a, b);
        assertThat(byId(rows).get(a)).isEqualTo(1.0f);
        // B is a candidate too and holds one of the two equally rare terms: coverage 0.5.
        assertScores(rows, 2, Map.of(a, 1.0, b, 0.5), "zork", "quib");
    }

    @Test
    void singleRareTermKeepsRoundOneScores() {
        // "alpha" is in every cell (df/n = 1) and so not rare; "zork" is the only rare term.
        for (int i = 0; i < 20; i++) {
            add("alpha pad note " + i + pad(i));
        }
        add("alpha zork" + pad(50));
        add("zork alpha");
        add("alpha" + pad(10) + " zork");

        List<Row> rows = search("zork alpha");

        assertThat(rows).hasSizeGreaterThan(20);
        assertScores(rows, 23, Map.of(), "zork", "alpha");
    }

    @Test
    void noRareTermKeepsRoundOneScores() {
        // Both query terms are in every cell.
        for (int i = 0; i < 12; i++) {
            add("alpha beta note " + i + pad(i * 7));
        }
        add("alpha" + pad(60) + " beta");
        add("alpha beta");

        List<Row> rows = search("alpha beta");

        assertThat(rows).hasSize(14);
        assertScores(rows, 14, Map.of(), "alpha", "beta");
    }

    @Test
    void clampedPositionsGiveNoBonus() {
        addFillers(30);
        // Both rare terms stand after position 16,383 of a very long cell: the tsvector stores both
        // at 16,383, which would look like perfect proximity if the window used them.
        UUID longCell = add("pad ".repeat(16_500) + "zork quib");
        // A shorter competitor with the higher BM25 whose terms are far apart.
        UUID shortCell = add("zork" + pad(500) + " quib");

        List<Row> rows = search("zork quib");

        assertThat(onlyIds(rows, shortCell, longCell)).extracting(r -> r.id)
                .containsExactly(shortCell, longCell);
        // The long cell gets no bonus: its score is its BM25 share (coverage 0).
        assertScores(rows, 2, Map.of(shortCell, 0.5, longCell, 0.0), "zork", "quib");
    }

    @Test
    void windowIsSkippedAboveSixtyFourLexemes() {
        addFillers(30);
        UUID adjacent = add("zork quib" + pad(300));
        UUID far = add("zork" + pad(40) + " quib");

        // 65 distinct lexemes (two real ones plus 63 unknown): no window, round-one order.
        String q65 = "zork quib" + unknownWords(63);
        List<Row> rows65 = search(q65);
        assertThat(onlyIds(rows65, adjacent, far)).extracting(r -> r.id).containsExactly(far, adjacent);
        assertThat(byId(rows65).get(far)).isEqualTo(1.0f);
        assertScores(rows65, 2, Map.of(), "zork", "quib");

        // 64 distinct lexemes: the window applies.
        String q64 = "zork quib" + unknownWords(62);
        List<Row> rows64 = search(q64);
        assertThat(onlyIds(rows64, adjacent, far)).extracting(r -> r.id).containsExactly(adjacent, far);
        assertThat(byId(rows64).get(adjacent)).isEqualTo(1.0f);
    }

    @Test
    void summaryProximityGivesNoBonus() {
        addFillers(30);
        // S: the rare terms are adjacent only in the summary; in the content they are 41 apart.
        UUID s = add("zork quib", "zork" + pad(40) + " quib");
        // C: the same terms adjacent in the content.
        UUID c = add(null, "zork quib" + pad(40));

        List<Row> rows = search("zork quib");

        // Precondition that makes the test able to fail: with the summary counted, S (tf 2 per term)
        // would hold full coverage and outrank C.
        assertThat(bm25("zork", "quib").get(s)).isGreaterThan(bm25("zork", "quib").get(c));
        Map<UUID, Float> kw = byId(rows);
        assertThat(kw.get(c)).isGreaterThan(kw.get(s));
        assertThat(kw.get(c)).isEqualTo(1.0f);
        // S holds only one of the two equally rare terms inside any content window: coverage 0.5.
        assertScores(rows, 2, Map.of(c, 1.0, s, 0.5), "zork", "quib");
    }

    @Test
    void exactlyOneRowScoresOne() {
        addFillers(25);
        add("zork quib" + pad(20));
        add("zork" + pad(45) + " quib");
        add("quib" + pad(10));
        add("zork zork zork");

        List<Row> rows = search("zork quib");

        assertThat(rows.stream().filter(r -> r.kw == 1.0f).count()).isEqualTo(1);
        assertThat(rows).allSatisfy(r -> assertThat(r.kw).isBetween(0f, 1f));
    }

    @Test
    void equalBm25AndCoverageOrderedById() {
        addFillers(25);
        for (int i = 0; i < 3; i++) {
            add("zork quib identical text");
        }
        add("zork" + pad(45) + " quib");

        List<Row> rows = search("zork quib");

        List<Row> identical = rows.stream().filter(r -> docs.get(r.id)[1].equals("zork quib identical text")).toList();
        assertThat(identical).hasSize(3);
        assertThat(identical).allSatisfy(r -> assertThat(r.kw).isEqualTo(identical.get(0).kw));
        assertThat(rows).extracting(r -> r.id.toString()).startsWith(
                identical.stream().map(r -> r.id.toString()).sorted().toArray(String[]::new));
    }

    @Test
    void excludedCellChangesNeitherRareSetNorScores() {
        // Each filter gets its own fixture; the excluded cells hold both rare terms in numbers that
        // would push each term over df/n = 0.5 (switching the window off) if they were counted.
        // Cell scope = {realm, signal, topic, tag, status}; kept cells are {a, facts, keep, x, committed}.
        String[] filters = {
                "p_realm => 'a'", "p_tags => ARRAY['x']", "p_status => 'committed'",
                "p_signal => 'facts'", "p_topic => 'keep'", "p_realms => ARRAY['a', 'none']"};
        String[][] excluded = {
                {"b", "facts", "keep", "x", "committed"},
                {"a", "facts", "keep", "y", "committed"},
                {"a", "facts", "keep", "x", "pending"},
                {"a", "events", "keep", "x", "committed"},
                {"a", "facts", "other", "x", "committed"},
                {"b", "facts", "keep", "x", "committed"}};
        for (int scenario = 0; scenario < filters.length; scenario++) {
            dsl.execute("TRUNCATE TABLE cell_references, references_, facts, tunnels, cells CASCADE");
            for (int i = 0; i < 10; i++) {
                insertScoped(new String[]{"a", "facts", "keep", "x", "committed"}, "pad note " + i);
            }
            UUID adjacent = insertScoped(new String[]{"a", "facts", "keep", "x", "committed"}, "zork quib" + pad(200));
            UUID far = insertScoped(new String[]{"a", "facts", "keep", "x", "committed"}, "zork" + pad(40) + " quib");
            // A realm-less cell must be admitted by the 'none' branch of p_realms; it also holds the
            // adjacent terms, so losing it would change the result.
            if (scenario == 5) {
                insertScoped(new String[]{null, "facts", "keep", "x", "committed"}, "zork quib" + pad(400));
            }

            List<Row> before = searchWith("zork quib", filters[scenario]);
            assertThat(before).as(filters[scenario]).hasSize(scenario == 5 ? 3 : 2);
            assertThat(before.get(0).id).isEqualTo(adjacent);
            assertThat(before.get(0).kw).isEqualTo(1.0f);
            assertThat(before.stream().map(Row::id)).contains(far);

            for (int i = 0; i < 15; i++) {
                insertScoped(excluded[scenario], "zork quib zork quib stuffed " + i);
            }
            assertThat(searchWith("zork quib", filters[scenario])).as(filters[scenario]).isEqualTo(before);
        }
    }

    @Test
    void compoundAndPlainRareTerm() {
        addFillers(30);
        // "state-of-the-art" yields the compound and its parts at neighbouring positions.
        UUID adjacent = add("a state-of-the-art zork engine" + pad(30));
        UUID far = add("zork" + pad(50) + " state-of-the-art");

        List<Row> rows = search("state-of-the-art zork");

        assertThat(onlyIds(rows, adjacent, far)).extracting(r -> r.id).containsExactly(adjacent, far);
        assertThat(rows).allSatisfy(r -> assertThat(r.kw).isBetween(0f, 1f));
        assertThat(rows.get(0).kw).isEqualTo(1.0f);
    }

    @Test
    void rareTermWithMoreThan255Occurrences() {
        addFillers(30);
        // Only the first 255 positions of "zork" exist in the tsvector; "quib" follows at position 301.
        UUID many = add("zork ".repeat(300) + "quib");
        UUID far = add("zork" + pad(40) + " quib");

        List<Row> rows = search("zork quib");

        // The tf of "zork" saturates at 255 counted occurrences, which still beats the single
        // occurrence of the short cell, so the long cell leads on BM25. The tsvector keeps positions
        // 1..255 of "zork"; "quib" is at 301, more than 30 positions after the last of them, so
        // neither cell has a window with both terms: both get coverage 0.5 (one of two equally
        // rare terms).
        assertThat(onlyIds(rows, many, far)).extracting(r -> r.id).containsExactly(many, far);
        assertThat(rows).allSatisfy(r -> assertThat(r.kw).isBetween(0f, 1f));
        assertThat(rows.stream().filter(r -> r.kw == 1.0f).count()).isEqualTo(1);
        assertScores(rows, 2, Map.of(many, 0.5, far, 0.5), "zork", "quib");
    }

    @Test
    void positionHeavyCandidatesKeepExactCoverage() {
        // Ten rare terms of strictly decreasing rarity (term k is also in k extra one-term cells), each
        // about 250 times per cell: about 2,500 rare-term positions in each of the four cells below.
        // The occurrences of one term stand in runs (the same term next to itself costs no coverage);
        // runs of different terms are 41 positions apart, so only the deliberate adjacencies count.
        String[] t = {"rarea", "rareb", "rarec", "rared", "raree", "raref", "rareg", "rareh", "rarei", "rarej"};
        addFillers(30);
        StringBuilder all = new StringBuilder(t[0]);
        for (int k = 1; k < t.length; k++) {
            all.append(' ').append(t[k]);
        }
        String ten = all.toString();
        Set<Integer> five = Set.of(1, 3, 5, 7, 9);

        // A: the ten terms once next to each other, everything else in runs: full coverage.
        UUID a = add(ten + pad(40) + runs(t, Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)));
        // B: no two different terms within 30 positions: the best window holds the single rarest term.
        UUID b = add(runs(t, Set.of()));
        // C: five of the ten terms next to each other once, otherwise isolated.
        UUID c = add(String.join(" ", five.stream().sorted().map(k -> t[k]).toList())
                + pad(40) + runs(t, five));
        // D: like B, plus one adjacent pair of the two rarest terms beyond position 16,383, where the
        // tsvector stores every position as 16,383: that pair must not count.
        UUID d = add(runs(t, Set.of()) + pad(14_000) + " " + t[0] + " " + t[1]);
        List<UUID> extras = new ArrayList<>();
        for (int k = 0; k < t.length; k++) {
            for (int i = 0; i < k; i++) {
                extras.add(add(t[k] + " pad note"));
            }
        }

        List<Row> rows = search(String.join(" ", t));

        double n = docs.size();
        double[] idf = new double[t.length];
        double sum = 0;
        for (int k = 0; k < t.length; k++) {
            String term = t[k];
            long df = docs.values().stream().filter(doc -> tf(doc, term) > 0).count();
            assertThat(df / n).as("term %s stays rare", term).isLessThanOrEqualTo(0.5);
            idf[k] = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            sum += idf[k];
        }
        double fiveSum = five.stream().mapToDouble(k -> idf[k]).sum();
        Map<UUID, Double> coverage = new HashMap<>();
        coverage.put(a, 1.0);
        coverage.put(b, idf[0] / sum);
        coverage.put(c, fiveSum / sum);
        coverage.put(d, idf[0] / sum);
        int e = 0;
        for (int k = 0; k < t.length; k++) {
            for (int i = 0; i < k; i++) {
                coverage.put(extras.get(e++), idf[k] / sum);
            }
        }
        // The expectations differ from one another, so a window that merged terms, dropped a run or
        // counted the clamped pair would change a score.
        assertThat(idf[0]).isGreaterThan(idf[1]).isGreaterThan(idf[9]);
        assertScores(rows, 4 + extras.size(), coverage, t);
    }

    // ---- expectations ----

    /**
     * Asserts every returned row's score_keyword against lex / max(lex), with lex = bm25 +
     * 4 * coverage * max(bm25) for the cells named in {@code coverage} (the candidates the window
     * applies to) and plain bm25 for the others; an empty map means the window does not apply.
     */
    private void assertScores(List<Row> rows, int expectedRows, Map<UUID, Double> coverage, String... terms) {
        assertThat(rows).as("returned rows").hasSize(expectedRows);
        int checked = 0;
        Map<UUID, Double> bm25 = bm25(terms);
        double mx = bm25.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        Map<UUID, Double> lex = new HashMap<>();
        bm25.forEach((id, score) -> lex.put(id, score + 4 * coverage.getOrDefault(id, 0.0) * mx));
        double best = lex.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        for (Row r : rows) {
            assertThat(bm25).as("row is a fixture cell matching a term").containsKey(r.id);
            checked++;
            assertThat((double) r.kw).as(docs.get(r.id)[1].substring(0, Math.min(30, docs.get(r.id)[1].length())))
                    .isNotNaN().isCloseTo(lex.get(r.id) / best, within(1e-4));
        }
        assertThat(checked).as("checked rows").isEqualTo(expectedRows);
    }

    /** BM25 (k1 1.5, b 0.75) of every fixture cell that holds a term; length is the byte length. */
    private Map<UUID, Double> bm25(String... terms) {
        double n = docs.size();
        double avgdl = docs.values().stream().mapToDouble(d -> bytes(d)).average().orElse(1);
        Map<UUID, Double> out = new HashMap<>();
        for (String term : terms) {
            long df = docs.values().stream().filter(d -> tf(d, term) > 0).count();
            if (df == 0) {
                continue;
            }
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            docs.forEach((id, d) -> {
                double tf = Math.min(tf(d, term), 255);
                if (tf > 0) {
                    out.merge(id, idf * tf * 2.5 / (tf + 1.5 * (0.25 + 0.75 * bytes(d) / avgdl)), Double::sum);
                }
            });
        }
        return out;
    }

    private static double bytes(String[] d) {
        return ((d[0] == null ? "" : d[0]) + (d[1] == null ? "" : d[1])).getBytes(StandardCharsets.UTF_8).length;
    }

    private static long tf(String[] d, String term) {
        long count = 0;
        for (String text : d) {
            if (text == null) {
                continue;
            }
            for (String token : text.toLowerCase().split("\\s+")) {
                if (token.equals(term)) {
                    count++;
                }
            }
        }
        return count;
    }

    // ---- helpers ----

    private record Row(UUID id, float kw) {
    }

    private static String pad(int words) {
        return " pad".repeat(words);
    }

    /** One run of 250 occurrences per term (249 for the terms in {@code shorter}), 40 filler words after each. */
    private static String runs(String[] terms, Set<Integer> shorter) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < terms.length; k++) {
            sb.append((" " + terms[k]).repeat(shorter.contains(k) ? 249 : 250)).append(pad(40));
        }
        return sb.toString();
    }

    private static String unknownWords(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            sb.append(" unknownword").append(i);
        }
        return sb.toString();
    }

    private void addFillers(int count) {
        for (int i = 0; i < count; i++) {
            add("pad note " + i + pad(i % 5));
        }
    }

    private UUID add(String content) {
        return add(null, content);
    }

    private UUID add(String summary, String content) {
        UUID id = insertCell("a", summary, content, "committed", "x");
        docs.put(id, new String[]{summary, content});
        return id;
    }

    private UUID insertScoped(String[] scope, String content) {
        return insertCell(scope[0], null, content, scope[4], scope[1], scope[2], scope[3]);
    }

    private UUID insertCell(String realm, String summary, String content, String status, String tag) {
        return insertCell(realm, summary, content, status, "facts", "t", tag);
    }

    private UUID insertCell(String realm, String summary, String content, String status,
                            String signal, String topic, String tag) {
        UUID id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, summary, realm, signal, topic, tags, importance, status, created_by,
                                   created_at, valid_from)
                VALUES (?, ?, ?, ?, ?, ?, ARRAY[?]::text[], 3, ?, 'writer-1',
                        '2026-04-03T10:00:00Z'::timestamptz, '2026-04-03T10:00:00Z'::timestamptz)
                """, id, content, summary, realm, signal, topic, tag, status);
        return id;
    }

    private List<Row> search(String query) {
        return searchWith(query, "p_realm => 'a'");
    }

    private List<Row> searchWith(String query, String extraArgs) {
        List<Row> rows = new ArrayList<>();
        for (Record r : dsl.fetch(
                "SELECT id, score_keyword FROM ranked_search("
                        + "query_embedding => NULL::vector, query_text => ?, p_limit => 200, "
                        + "p_weight_semantic => 0, p_weight_keyword => 1, p_weight_recency => 0, "
                        + "p_weight_importance => 0, p_weight_popularity => 0, p_weight_graph_proximity => 0, "
                        + extraArgs + ")", query)) {
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
}
