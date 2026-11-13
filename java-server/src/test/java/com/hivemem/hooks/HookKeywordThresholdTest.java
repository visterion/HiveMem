package com.hivemem.hooks;

import com.hivemem.auth.RateLimiter;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.search.CellSearchRepository.RankedRow;
import com.hivemem.search.SearchWeights;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what the hook's real relevance threshold (the default 0.65, not the 0.0 that
 * {@link HooksIntegrationTest} runs with) does under the BM25 keyword signal.
 *
 * <p>The old keyword signal ({@code ts_rank_cd(…, 32)}, i.e. rank/(rank+1), without IDF or length
 * normalisation) was roughly 0.5 for this short cell (about 0.9 for typical, longer cells) and
 * for nearly every cell that shared any word with the prompt, and because the {@code simple}
 * configuration has no stop words that is nearly every cell. At the hook's keyword weight of 0.10
 * it added up to about 0.09 to almost every total, and the threshold was set with that offset in
 * place; for this short cell it was just enough to lift its total over the threshold. The BM25
 * signal is normalised so that the best lexical match of a query scores 1.0, while a cell that
 * shares only common words with the prompt scores close to 0 and so loses most of that offset
 * (up to about 0.09 of its total). A cell that shares no word at all scored 0 under the old
 * signal too and is unchanged. The low score of the common-word cell comes from the idf of the
 * common words and, since the proximity window, from the missing proximity bonus: it holds none
 * of the rare words of the prompt, so no window of rare words lifts it.
 *
 * <p>All cells have the same cosine similarity to the prompt (0.75). Their other signals are
 * identical too (importance 3, created now, no popularity, no graph neighbours), so the only
 * difference between the totals is the keyword signal.
 *
 * <p>What the two-cell test pins: the no-overlap cell is dropped before and after the change, and
 * the change it pins is that the overlap cell now crosses the threshold with the full keyword
 * weight. The regression itself is pinned by the common-word test: a cell sharing only common
 * words used to receive a keyword score near the top of the range and a total above the
 * threshold's neighbourhood, and now stays below it.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(HookKeywordThresholdTest.TestConfig.class)
@Testcontainers
class HookKeywordThresholdTest {

    private static final int DIMS = 1024;
    private static final double SIMILARITY = 0.75;
    private static final String PROMPT = "semantic: how does the zephyrquokka relay handle failover";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem")
            .withUsername("hivemem")
            .withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null
                            ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig())
                            .withSecurityOpts(List.of("apparmor=unconfined"))));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private DSLContext dsl;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private HookContextService hook;

    @Autowired
    private HookProperties props;

    @Autowired
    private CellSearchRepository searchRepository;

    @Autowired
    private EmbeddingClient embeddingClient;

    @BeforeEach
    void resetDatabase() {
        rateLimiter.clearAll();
        dsl.execute("TRUNCATE TABLE agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cells CASCADE");
    }

    @Test
    void lexicalOverlapLiftsACandidateOverTheDefaultThreshold() {
        assertThat(props.getRelevanceThreshold()).isEqualTo(0.65);

        UUID overlap = insertCell("A zephyrquokka relay fails over to a standby node within seconds");
        UUID noOverlap = insertCell("Rotation schedule for quarterly office plant watering");

        List<RankedRow> rows = searchRepository.rankedSearch(
                embeddingClient.encodeQuery(PROMPT), PROMPT, null, null, null, 10,
                weights().semantic(), weights().keyword(), weights().recency(),
                weights().importance(), weights().popularity(), weights().graphProximity(),
                null, null, null);
        RankedRow withOverlap = row(rows, overlap);
        RankedRow without = row(rows, noOverlap);

        // Same semantic similarity, so the keyword signal is the only difference.
        assertThat(withOverlap.scoreSemantic()).isCloseTo(SIMILARITY, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(without.scoreSemantic()).isCloseTo(SIMILARITY, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(withOverlap.scoreKeyword()).isEqualTo(1.0);
        assertThat(without.scoreKeyword()).isEqualTo(0.0);
        assertThat(withOverlap.scoreTotal()).isGreaterThan(without.scoreTotal());
        assertThat(withOverlap.scoreTotal() - without.scoreTotal())
                .isCloseTo(weights().keyword(), org.assertj.core.data.Offset.offset(1e-4));

        // The boundary: the cell without overlap falls below the threshold, the other stays above.
        assertThat(without.scoreTotal()).isLessThan(props.getRelevanceThreshold());
        assertThat(withOverlap.scoreTotal()).isGreaterThanOrEqualTo(props.getRelevanceThreshold());

        ContextResult result = hook.contextFor(
                new HookContextRequest("UserPromptSubmit", PROMPT, "threshold-1", null));
        assertThat(result.citedSources()).extracting(SourceAttribution::cellId)
                .containsExactly(overlap);
        assertThat(result.formattedContext()).contains(overlap.toString().substring(0, 8));
        assertThat(result.formattedContext()).doesNotContain(noOverlap.toString().substring(0, 8));
    }

    @Test
    void cellSharingOnlyCommonWordsIsNotInjected() {
        UUID overlap = insertCell("A zephyrquokka relay fails over to a standby node within seconds");
        // Shares only the common words "how", "does" and "the" with the prompt. Under the earlier
        // signal such a cell received a keyword score of roughly 0.5 to 0.9 and therefore a total
        // up to about 0.09 higher; with BM25 the words are common in the corpus (low idf), and
        // the cell holds no rare query word, so it earns no proximity bonus: it scores close to 0.
        UUID common = insertCell("How does the plant watering rota work");
        // Fillers carrying the same common words keep their idf low; their embedding is orthogonal
        // to the prompt, so their semantic score is 0 and they stay far below the threshold.
        for (int i = 0; i < 20; i++) {
            insertCell("how does the filler note " + i + " go on the shelf", 0.0, 0.0, 1.0);
        }

        List<RankedRow> rows = searchRepository.rankedSearch(
                embeddingClient.encodeQuery(PROMPT), PROMPT, null, null, null, 10,
                weights().semantic(), weights().keyword(), weights().recency(),
                weights().importance(), weights().popularity(), weights().graphProximity(),
                null, null, null);
        RankedRow commonRow = row(rows, common);

        assertThat(commonRow.scoreSemantic()).isCloseTo(SIMILARITY, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(commonRow.scoreKeyword()).isGreaterThan(0.0).isLessThan(0.02);
        assertThat(commonRow.scoreTotal()).isLessThan(props.getRelevanceThreshold());
        assertThat(row(rows, overlap).scoreKeyword()).isEqualTo(1.0);

        ContextResult result = hook.contextFor(
                new HookContextRequest("UserPromptSubmit", PROMPT, "threshold-2", null));
        assertThat(result.citedSources()).extracting(SourceAttribution::cellId)
                .containsExactly(overlap);
    }

    private SearchWeights weights() {
        return props.getWeights().toSearchWeights();
    }

    private static RankedRow row(List<RankedRow> rows, UUID id) {
        return rows.stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("cell missing from the ranked rows: " + id));
    }

    /** Inserts a committed cell whose embedding has cosine similarity {@link #SIMILARITY} to the prompt vector. */
    private UUID insertCell(String content) {
        return insertCell(content, SIMILARITY, Math.sqrt(1 - SIMILARITY * SIMILARITY), 0.0);
    }

    private UUID insertCell(String content, double d0, double d1, double d2) {
        List<Float> v = new ArrayList<>(Collections.nCopies(DIMS, 0.0f));
        v.set(0, (float) d0);
        v.set(1, (float) d1);
        v.set(2, (float) d2);
        String vector = v.toString().replace(" ", "");
        UUID id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, realm, signal, topic, importance, status, created_by,
                                   created_at, valid_from, embedding)
                VALUES (?, ?, 'eng', 'facts', 'ops', 3, 'committed', 'writer-1', now(), now(), ?::vector)
                """, id, content, vector);
        return id;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @org.springframework.context.annotation.Primary
        EmbeddingClient embeddingClient() {
            // "semantic" in the prompt maps to the unit vector [1, 0, 0, ...].
            return new FixedEmbeddingClient();
        }
    }
}
