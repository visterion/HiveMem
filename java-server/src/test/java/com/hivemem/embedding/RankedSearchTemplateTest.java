package com.hivemem.embedding;

import org.junit.jupiter.api.Test;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RankedSearchTemplateTest {

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
    void migrationV0060EqualsRenderedTemplate() throws Exception {
        String migration;
        try (var in = new ClassPathResource("db/migration/V0060__ranked_search_bm25.sql").getInputStream()) {
            migration = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }

        assertThat(functionStatement(migration)).isEqualTo(functionStatement(RankedSearchTemplate.render(1024)));
    }

    /** The CREATE OR REPLACE FUNCTION ranked_search ... $$; statement, without comments, whitespace collapsed. */
    private static String functionStatement(String sql) {
        int start = sql.indexOf("CREATE OR REPLACE FUNCTION ranked_search");
        assertThat(start).as("function statement present").isGreaterThanOrEqualTo(0);
        int end = sql.lastIndexOf("$$;") + "$$;".length();
        return sql.substring(start, end).replaceAll("--[^\\n]*", "").replaceAll("\\s+", " ").trim();
    }
}
