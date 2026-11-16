package com.hivemem.embedding;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

final class RankedSearchTemplate {

    private static final String RESOURCE_PATH = "db/templates/ranked_search.sql.tmpl";
    private static final String PLACEHOLDER = "{{DIM}}";
    private static final String STOP_WORD_PLACEHOLDER = "{{STOP_WORD_FILTER}}";
    private static final Pattern CONFIG_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");

    static final String STOP_WORD_SETTING = "hivemem.search.keyword.stop-word-configs";

    private RankedSearchTemplate() {
    }

    static String render(int dimension) {
        return render(dimension, List.of());
    }

    static String render(int dimension, List<String> stopWordConfigs) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive, got " + dimension);
        }
        String template = load();
        return template
                .replace(PLACEHOLDER, Integer.toString(dimension))
                .replace(STOP_WORD_PLACEHOLDER, stopWordClause(normalizeStopWordConfigs(stopWordConfigs)));
    }

    static List<String> normalizeStopWordConfigs(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String entry : raw) {
            String name = entry == null ? "" : entry.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (!CONFIG_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(STOP_WORD_SETTING
                        + " must hold lower-case text search configuration names ([a-z][a-z0-9_]*), got '" + name + "'");
            }
            names.add(name);
        }
        return List.copyOf(new ArrayList<>(names));
    }

    private static String stopWordClause(List<String> configs) {
        if (configs.isEmpty()) {
            return "";
        }
        List<String> conjuncts = configs.stream()
                .map(name -> "length(to_tsvector('" + name + "', lex)) > 0")
                .toList();
        return "\n        WHERE " + String.join(" AND ", conjuncts);
    }

    private static String load() {
        try (InputStream in = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + RESOURCE_PATH, e);
        }
    }
}
