package com.hivemem.search;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Queries backing the {@code data_quality_report} read tool: unclassified cells (missing
 * realm/signal/topic), disconnected cells (no tunnels and no facts), and near-duplicate cell
 * pairs by embedding cosine similarity.
 */
@Repository
public class DataQualityRepository {

    private static final int SAMPLE_LIMIT = 10;
    private static final int MAX_SUBJECTS_FOR_PAIRS = 50;

    private final DSLContext dslContext;

    public DataQualityRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    public Map<String, Object> missingRealm() {
        return unclassified("realm IS NULL");
    }

    public Map<String, Object> missingSignal() {
        return unclassified("signal IS NULL");
    }

    public Map<String, Object> missingTopic() {
        return unclassified("topic IS NULL");
    }

    private Map<String, Object> unclassified(String condition) {
        long count = countCells(condition);
        List<Map<String, Object>> sample = new ArrayList<>();
        for (Record row : dslContext.fetch(
                "SELECT id, realm, signal, topic, summary FROM cells WHERE valid_until IS NULL AND "
                        + condition + " ORDER BY created_at DESC LIMIT ?",
                SAMPLE_LIMIT)) {
            sample.add(cellRow(row));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", count);
        result.put("sample", sample);
        return result;
    }

    private long countCells(String condition) {
        Record row = dslContext.fetchOne(
                "SELECT count(*) AS n FROM cells WHERE valid_until IS NULL AND " + condition);
        return row == null ? 0L : row.get("n", Long.class);
    }

    public Map<String, Object> disconnected() {
        long count;
        Record countRow = dslContext.fetchOne("""
                SELECT count(*) AS n FROM cells c
                WHERE c.valid_until IS NULL
                  AND NOT EXISTS (SELECT 1 FROM tunnels t
                                  WHERE (t.from_cell = c.id OR t.to_cell = c.id) AND t.valid_until IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM facts f
                                  WHERE f.source_id = c.id AND f.valid_until IS NULL)
                """);
        count = countRow == null ? 0L : countRow.get("n", Long.class);

        List<Map<String, Object>> sample = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT c.id, c.realm, c.signal, c.topic, c.summary FROM cells c
                WHERE c.valid_until IS NULL
                  AND NOT EXISTS (SELECT 1 FROM tunnels t
                                  WHERE (t.from_cell = c.id OR t.to_cell = c.id) AND t.valid_until IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM facts f
                                  WHERE f.source_id = c.id AND f.valid_until IS NULL)
                ORDER BY c.created_at DESC LIMIT ?
                """, SAMPLE_LIMIT)) {
            sample.add(cellRow(row));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", count);
        result.put("sample", sample);
        return result;
    }

    public List<Map<String, Object>> duplicatePairs(int dimension, double threshold, int limit) {
        if (dimension <= 0) {
            return List.of();
        }
        String vectorExpr = "(a.embedding::vector(" + dimension + "))";
        String vectorExprB = "(b.embedding::vector(" + dimension + "))";
        String sql = "SELECT a.id AS id_a, a.summary AS summary_a, b.id AS id_b, b.summary AS summary_b, "
                + "(1 - (" + vectorExpr + " <=> " + vectorExprB + "))::real AS similarity "
                + "FROM cells a JOIN cells b ON a.id < b.id "
                + "WHERE a.valid_until IS NULL AND b.valid_until IS NULL "
                + "AND a.embedding IS NOT NULL AND b.embedding IS NOT NULL "
                + "AND (1 - (" + vectorExpr + " <=> " + vectorExprB + ")) >= ? "
                + "ORDER BY similarity DESC, id_a, id_b LIMIT ?";

        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch(sql, threshold, limit)) {
            Map<String, Object> cellA = new LinkedHashMap<>();
            cellA.put("id", row.get("id_a", UUID.class).toString());
            cellA.put("summary", row.get("summary_a", String.class));

            Map<String, Object> cellB = new LinkedHashMap<>();
            cellB.put("id", row.get("id_b", UUID.class).toString());
            cellB.put("summary", row.get("summary_b", String.class));

            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("cell_a", cellA);
            pair.put("cell_b", cellB);
            pair.put("similarity", row.get("similarity", Float.class));
            results.add(pair);
        }
        return results;
    }

    /**
     * Predicates with more than one distinct active subject (fragmentation candidates), each with
     * its subject set and any subject pairs whose pg_trgm similarity meets the threshold.
     */
    public List<Map<String, Object>> potentialConflicts(double similarityThreshold, int limit) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record predRow : dslContext.fetch("""
                SELECT predicate, count(DISTINCT subject) AS subject_count,
                       array_agg(DISTINCT subject ORDER BY subject) AS subjects
                FROM active_facts
                GROUP BY predicate
                HAVING count(DISTINCT subject) > 1
                ORDER BY count(DISTINCT subject) DESC, predicate
                LIMIT ?
                """, limit)) {
            String predicate = predRow.get("predicate", String.class);
            int subjectCount = predRow.get("subject_count", Integer.class);
            String[] subjects = predRow.get("subjects", String[].class);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("predicate", predicate);
            entry.put("subject_count", subjectCount);

            if (subjectCount > MAX_SUBJECTS_FOR_PAIRS) {
                // Skip the O(k^2) pairwise similarity self-join for very fragmented predicates.
                List<String> truncated = List.of(subjects).subList(0, MAX_SUBJECTS_FOR_PAIRS);
                entry.put("subjects", List.copyOf(truncated));
                entry.put("subjects_truncated", true);
                entry.put("similar_pairs", List.of());
                results.add(entry);
                continue;
            }

            List<Map<String, Object>> pairs = new ArrayList<>();
            for (Record pairRow : dslContext.fetch("""
                    SELECT a.subject AS s_a, b.subject AS s_b, similarity(a.subject, b.subject) AS sim
                    FROM (SELECT DISTINCT subject FROM active_facts WHERE predicate = ?) a
                    JOIN (SELECT DISTINCT subject FROM active_facts WHERE predicate = ?) b
                      ON a.subject < b.subject
                    WHERE similarity(a.subject, b.subject) >= ?
                    ORDER BY sim DESC
                    """, predicate, predicate, similarityThreshold)) {
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("a", pairRow.get("s_a", String.class));
                pair.put("b", pairRow.get("s_b", String.class));
                pair.put("similarity", pairRow.get("sim", Float.class));
                pairs.add(pair);
            }

            entry.put("subjects", List.of(subjects));
            entry.put("subjects_truncated", false);
            entry.put("similar_pairs", pairs);
            results.add(entry);
        }
        return results;
    }

    private static Map<String, Object> cellRow(Record row) {
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("id", row.get("id", UUID.class).toString());
        cell.put("realm", row.get("realm", String.class));
        cell.put("signal", row.get("signal", String.class));
        cell.put("topic", row.get("topic", String.class));
        cell.put("summary", row.get("summary", String.class));
        return cell;
    }
}
