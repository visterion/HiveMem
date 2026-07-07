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
            conditions.add("subject ILIKE ?");
            params.add('%' + subject + '%');
        }
        if (predicate != null && !predicate.isBlank()) {
            conditions.add("predicate ILIKE ?");
            params.add('%' + predicate + '%');
        }
        if (object_ != null && !object_.isBlank()) {
            conditions.add("\"object\" ILIKE ?");
            params.add('%' + object_ + '%');
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

    public List<Map<String, Object>> semanticSearch(List<Float> queryVector, String subject,
            String predicate, String object_, int limit) {
        List<Object> params = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        conditions.add("embedding IS NOT NULL");
        if (subject != null && !subject.isBlank()) {
            conditions.add("subject ILIKE ?");
            params.add('%' + subject + '%');
        }
        if (predicate != null && !predicate.isBlank()) {
            conditions.add("predicate ILIKE ?");
            params.add('%' + predicate + '%');
        }
        if (object_ != null && !object_.isBlank()) {
            conditions.add("\"object\" ILIKE ?");
            params.add('%' + object_ + '%');
        }
        Float[] vec = queryVector.toArray(Float[]::new);

        String sql = "SELECT id, subject, predicate, object, confidence, valid_from, valid_until, "
                + "(1 - (embedding <=> ?::vector))::float8 AS score "
                + "FROM active_facts WHERE " + String.join(" AND ", conditions)
                + " ORDER BY embedding <=> ?::vector LIMIT ?";

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

    private static double numberValue(Record row, String field) {
        Number value = row.get(field, Number.class);
        return value == null ? 0.0d : value.doubleValue();
    }

    private static String timestampValue(Record row, String field) {
        OffsetDateTime value = row.get(field, OffsetDateTime.class);
        return value == null ? null : DateTimeFormatter.ISO_INSTANT.format(value.toInstant());
    }
}
