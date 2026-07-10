package com.hivemem.search;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class KgSearchRepository {

    private final DSLContext dslContext;

    public KgSearchRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    public List<Map<String, Object>> search(String subject, String predicate, String object_, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, subject, predicate, object, confidence, valid_from, valid_until
                FROM active_facts
                """);
        List<Object> params = new ArrayList<>();
        List<String> conditions = new ArrayList<>();

        if (subject != null && !subject.isBlank()) {
            conditions.add("subject ILIKE ? ESCAPE '\\'");
            params.add('%' + escapeLikePattern(subject) + '%');
        }
        if (predicate != null && !predicate.isBlank()) {
            conditions.add("predicate ILIKE ? ESCAPE '\\'");
            params.add('%' + escapeLikePattern(predicate) + '%');
        }
        if (object_ != null && !object_.isBlank()) {
            conditions.add("\"object\" ILIKE ? ESCAPE '\\'");
            params.add('%' + escapeLikePattern(object_) + '%');
        }

        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }

        sql.append(" ORDER BY created_at DESC LIMIT ?");
        params.add(limit);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch(sql.toString(), params.toArray())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", row.get("id", java.util.UUID.class).toString());
            result.put("subject", row.get("subject", String.class));
            result.put("predicate", row.get("predicate", String.class));
            result.put("object", row.get("object", String.class));
            result.put("confidence", numberValue(row, "confidence"));
            result.put("valid_from", timestampValue(row, "valid_from"));
            result.put("valid_until", timestampValue(row, "valid_until"));
            results.add(result);
        }
        return results;
    }

    /**
     * ILIKE fallback for {@code search_kg} when a free-text {@code query} is given but the
     * embedding sidecar is unavailable and no explicit subject/predicate/object filter was
     * passed. Matches the query text against subject OR object so results still relate to
     * the query, instead of returning the newest facts in the whole KG unfiltered.
     */
    public List<Map<String, Object>> searchText(String queryText, int limit) {
        String pattern = '%' + escapeLikePattern(queryText) + '%';
        String sql = """
                SELECT id, subject, predicate, object, confidence, valid_from, valid_until
                FROM active_facts
                WHERE subject ILIKE ? ESCAPE '\\' OR "object" ILIKE ? ESCAPE '\\'
                ORDER BY created_at DESC
                LIMIT ?
                """;
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch(sql, pattern, pattern, limit)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", row.get("id", java.util.UUID.class).toString());
            result.put("subject", row.get("subject", String.class));
            result.put("predicate", row.get("predicate", String.class));
            result.put("object", row.get("object", String.class));
            result.put("confidence", numberValue(row, "confidence"));
            result.put("valid_from", timestampValue(row, "valid_from"));
            result.put("valid_until", timestampValue(row, "valid_until"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> semanticSearch(List<Float> queryVector, String subject,
            String predicate, String object_, int limit, int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive, got " + dimension);
        }
        List<Object> params = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        conditions.add("embedding IS NOT NULL");
        if (subject != null && !subject.isBlank()) {
            conditions.add("subject ILIKE ? ESCAPE '\\'");
            params.add('%' + escapeLikePattern(subject) + '%');
        }
        if (predicate != null && !predicate.isBlank()) {
            conditions.add("predicate ILIKE ? ESCAPE '\\'");
            params.add('%' + escapeLikePattern(predicate) + '%');
        }
        if (object_ != null && !object_.isBlank()) {
            conditions.add("\"object\" ILIKE ? ESCAPE '\\'");
            params.add('%' + escapeLikePattern(object_) + '%');
        }
        Float[] vec = queryVector.toArray(Float[]::new);

        // Cast the column to the active embedding dimension in both the score expression and
        // the ORDER BY so the planner can use the HNSW index on (embedding::vector(dim)); a bare
        // `embedding <=> ?` on an untyped vector column bypasses the index entirely.
        String vectorExpr = "(embedding::vector(" + dimension + "))";
        String sql = "SELECT id, subject, predicate, object, confidence, valid_from, valid_until, "
                + "(1 - (" + vectorExpr + " <=> ?::vector))::float8 AS score "
                + "FROM active_facts WHERE " + String.join(" AND ", conditions)
                + " ORDER BY " + vectorExpr + " <=> ?::vector LIMIT ?";

        List<Object> allParams = new ArrayList<>();
        allParams.add(vec);
        allParams.addAll(params);
        allParams.add(vec);
        allParams.add(limit);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch(sql, allParams.toArray())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", row.get("id", java.util.UUID.class).toString());
            result.put("subject", row.get("subject", String.class));
            result.put("predicate", row.get("predicate", String.class));
            result.put("object", row.get("object", String.class));
            result.put("confidence", numberValue(row, "confidence"));
            result.put("valid_from", timestampValue(row, "valid_from"));
            result.put("valid_until", timestampValue(row, "valid_until"));
            result.put("score", numberValue(row, "score"));
            results.add(result);
        }
        return results;
    }

    /** Escapes LIKE/ILIKE wildcards so user input matches literally (pair with {@code ESCAPE '\'}). */
    private static String escapeLikePattern(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static double numberValue(Record row, String field) {
        Number value = row.get(field, Number.class);
        return value == null ? 0.0d : value.doubleValue();
    }

    private static String timestampValue(Record row, String field) {
        OffsetDateTime value = row.get(field, OffsetDateTime.class);
        return value == null ? null : DateTimeFormatter.ISO_INSTANT.format(value.toInstant());
    }
}
