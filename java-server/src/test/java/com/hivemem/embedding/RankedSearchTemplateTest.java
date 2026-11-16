package com.hivemem.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RankedSearchTemplateTest {

    private static final String TEMPLATE = load("db/templates/ranked_search.sql.tmpl");

    private static final String ON_CLAUSE =
            "\n        WHERE length(to_tsvector('german', lex)) > 0 AND length(to_tsvector('english', lex)) > 0";

    @Test
    void templateHoldsTheStopWordPlaceholderOnceAndInline() {
        assertThat(TEMPLATE.split("\\{\\{STOP_WORD_FILTER}}", -1)).hasSize(2);
        assertThat(TEMPLATE).contains("coalesce(query_text, '')))) AS lex{{STOP_WORD_FILTER}}\n");
    }

    @Test
    void offRenderIsByteIdenticalToMigrationV0061() {
        assertThat(rawStatement(RankedSearchTemplate.render(1024))).isEqualTo(rawStatement(migrationV0061()));
    }

    @Test
    void emptyNullAndBlankListsRenderLikeOff() {
        String off = RankedSearchTemplate.render(8);
        assertThat(off).doesNotContain("{{").doesNotContain("length(to_tsvector(");
        assertThat(RankedSearchTemplate.render(8, List.of())).isEqualTo(off);
        assertThat(RankedSearchTemplate.render(8, null)).isEqualTo(off);
        assertThat(RankedSearchTemplate.render(8, List.of(" ", ""))).isEqualTo(off);
    }

    @Test
    void onRenderAddsExactlyOneClauseWithOneConjunctPerConfiguration() {
        String on = RankedSearchTemplate.render(8, List.of("german", "english"));
        assertThat(on).contains("AS lex" + ON_CLAUSE + "\n");
        assertThat(on.replace(ON_CLAUSE, "")).isEqualTo(RankedSearchTemplate.render(8));
        assertThat(RankedSearchTemplate.render(8, List.of("simple")))
                .contains("AS lex\n        WHERE length(to_tsvector('simple', lex)) > 0\n");
    }

    @Test
    void normalizeTrimsDropsBlanksAndCollapsesDuplicates() {
        assertThat(RankedSearchTemplate.normalizeStopWordConfigs(List.of(" german ", "", "english", "german")))
                .containsExactly("german", "english");
        assertThat(RankedSearchTemplate.normalizeStopWordConfigs(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"German", "german;x", "ger man", "\"german\"", "1german", "pg_catalog.german", "german'--"})
    void malformedNamesAreRejectedWithTheSettingName(String name) {
        assertThatThrownBy(() -> RankedSearchTemplate.render(8, List.of("english", name)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hivemem.search.keyword.stop-word-configs")
                .hasMessageContaining(name);
    }

    @Test
    void rendersTemplateWithGivenDimension() {
        String sql = RankedSearchTemplate.render(384);

        assertThat(sql).contains("vector(384)");
        assertThat(sql).doesNotContain("{{DIM}}");
        assertThat(sql).doesNotContain("vector(1024)");
        assertThat(sql).contains("CREATE OR REPLACE FUNCTION ranked_search");
    }

    @Test
    void rendersAllPlaceholderOccurrences() {
        String sql = RankedSearchTemplate.render(768);

        long count = sql.lines().filter(l -> l.contains("vector(768)")).count();
        assertThat(count).isGreaterThanOrEqualTo(2);
    }

    @Test
    void rejectsNonPositiveDimension() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> RankedSearchTemplate.render(0));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> RankedSearchTemplate.render(-1));
    }

    @Test
    void newestRankedSearchMigrationEqualsRenderedTemplate() throws Exception {
        String migration = migrationV0061();

        assertThat(functionStatement(migration)).isEqualTo(functionStatement(RankedSearchTemplate.render(1024)));
    }

    /** The CREATE OR REPLACE FUNCTION ranked_search ... $$; statement, without comments, whitespace collapsed. */
    private static String functionStatement(String sql) {
        int start = sql.indexOf("CREATE OR REPLACE FUNCTION ranked_search");
        assertThat(start).as("function statement present").isGreaterThanOrEqualTo(0);
        int end = sql.lastIndexOf("$$;") + "$$;".length();
        return sql.substring(start, end).replaceAll("--[^\\n]*", "").replaceAll("\\s+", " ").trim();
    }

    private static String migrationV0061() {
        return load("db/migration/V0061__ranked_search_proximity.sql");
    }

    private static String load(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The statement from CREATE OR REPLACE FUNCTION ranked_search to the last $$; with nothing removed. */
    private static String rawStatement(String sql) {
        int start = sql.indexOf("CREATE OR REPLACE FUNCTION ranked_search");
        assertThat(start).as("function statement present").isGreaterThanOrEqualTo(0);
        return sql.substring(start, sql.lastIndexOf("$$;") + "$$;".length());
    }
}
