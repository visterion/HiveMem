package com.hivemem.cells;

import com.hivemem.tools.read.CellFieldSelection;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CellReadRepository {

    private final DSLContext dslContext;

    public CellReadRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    public Map<String, Object> statusSnapshot() {
        Record counts = dslContext.fetchOne("""
                SELECT
                    (SELECT count(*) FROM active_cells) AS cells,
                    (SELECT count(*) FROM active_facts) AS facts,
                    (SELECT count(*) FROM active_tunnels) AS tunnels,
                    (SELECT count(*) FROM pending_approvals) AS pending,
                    (SELECT max(created_at) FROM cells) AS last_activity
                """);

        List<String> realms = dslContext.fetch("""
                        SELECT DISTINCT realm
                        FROM active_cells
                        WHERE realm IS NOT NULL
                        ORDER BY realm
                        """)
                .map(record -> record.get("realm", String.class));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cells", countValue(counts, "cells"));
        result.put("facts", countValue(counts, "facts"));
        result.put("tunnels", countValue(counts, "tunnels"));
        result.put("pending", countValue(counts, "pending"));
        result.put("last_activity", timestampValue(counts, "last_activity"));
        result.put("realms", realms);
        return result;
    }

    public List<Map<String, Object>> listRealms() {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT realm, count(*) AS cell_count
                FROM active_cells
                WHERE realm IS NOT NULL
                GROUP BY realm
                ORDER BY realm
                """)) {
            String realm = row.get("realm", String.class);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("value", realm);
            result.put("label", realm);
            result.put("cell_count", countValue(row, "cell_count"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> blueprintsMissing() {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT c.realm, count(*) AS cell_count
                FROM active_cells c
                WHERE c.realm IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM active_blueprints b WHERE b.realm = c.realm)
                GROUP BY c.realm
                ORDER BY c.realm
                """)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("realm", row.get("realm", String.class));
            result.put("cell_count", countValue(row, "cell_count"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> listSignals(String realm) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT signal, count(*) AS cell_count
                FROM active_cells
                WHERE realm = ? AND signal IS NOT NULL
                GROUP BY signal
                ORDER BY signal
                """, realm)) {
            String signal = row.get("signal", String.class);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("value", signal);
            result.put("label", signal);
            result.put("cell_count", countValue(row, "cell_count"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> listTopics(String realm, String signal) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT topic, count(*) AS cell_count
                FROM active_cells
                WHERE realm = ? AND signal = ? AND topic IS NOT NULL
                GROUP BY topic
                ORDER BY topic
                """, realm, signal)) {
            String topic = row.get("topic", String.class);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("value", topic);
            result.put("label", topic);
            result.put("cell_count", countValue(row, "cell_count"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> listCellsInTopic(String realm, String signal, String topic) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT id, summary, importance, created_at
                FROM active_cells
                WHERE realm = ? AND signal = ? AND topic = ?
                ORDER BY created_at DESC
                """, realm, signal, topic)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", uuidValue(row, "id"));
            result.put("summary", row.get("summary", String.class));
            result.put("importance", integerValue(row, "importance"));
            result.put("created_at", timestampValue(row, "created_at"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> traverse(UUID cellId, int maxDepth, String relationFilter, int edgeLimit) {
        String normalizedRelationFilter = relationFilter == null || relationFilter.isBlank() ? null : relationFilter;
        List<Object> params = new ArrayList<>();
        String sql;

        if (normalizedRelationFilter != null) {
            sql = """
                    WITH RECURSIVE
                    bidir AS (
                        SELECT from_cell AS node, to_cell AS neighbor, from_cell, to_cell, relation, note
                        FROM active_tunnels WHERE relation = ?
                        UNION ALL
                        SELECT to_cell AS node, from_cell AS neighbor, from_cell, to_cell, relation, note
                        FROM active_tunnels WHERE relation = ?
                    ),
                    graph AS (
                        SELECT from_cell, to_cell, relation, note, neighbor, 1 AS depth
                        FROM bidir
                        WHERE node = ?
                        UNION
                        SELECT b.from_cell, b.to_cell, b.relation, b.note, b.neighbor, g.depth + 1
                        FROM bidir b
                        JOIN graph g ON b.node = g.neighbor
                        WHERE g.depth < ?
                    )
                    SELECT DISTINCT from_cell, to_cell, relation, note, depth
                    FROM graph
                    ORDER BY depth, from_cell
                    LIMIT ?
                    """;
            params.add(normalizedRelationFilter);
            params.add(normalizedRelationFilter);
            params.add(cellId);
            params.add(maxDepth);
            params.add(edgeLimit);
        } else {
            sql = """
                    WITH RECURSIVE
                    bidir AS (
                        SELECT from_cell AS node, to_cell AS neighbor, from_cell, to_cell, relation, note
                        FROM active_tunnels
                        UNION ALL
                        SELECT to_cell AS node, from_cell AS neighbor, from_cell, to_cell, relation, note
                        FROM active_tunnels
                    ),
                    graph AS (
                        SELECT from_cell, to_cell, relation, note, neighbor, 1 AS depth
                        FROM bidir
                        WHERE node = ?
                        UNION
                        SELECT b.from_cell, b.to_cell, b.relation, b.note, b.neighbor, g.depth + 1
                        FROM bidir b
                        JOIN graph g ON b.node = g.neighbor
                        WHERE g.depth < ?
                    )
                    SELECT DISTINCT from_cell, to_cell, relation, note, depth
                    FROM graph
                    ORDER BY depth, from_cell
                    LIMIT ?
                    """;
            params.add(cellId);
            params.add(maxDepth);
            params.add(edgeLimit);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch(sql, params.toArray())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("from_cell", uuidValue(row, "from_cell"));
            result.put("to_cell", uuidValue(row, "to_cell"));
            result.put("relation", row.get("relation", String.class));
            result.put("note", row.get("note", String.class));
            result.put("depth", countValue(row, "depth"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> quickFacts(String entity) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT id, subject, predicate, "object", confidence, valid_from
                FROM active_facts
                WHERE subject = ? OR "object" = ?
                ORDER BY valid_from DESC
                """, entity, entity)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", uuidValue(row, "id"));
            result.put("subject", row.get("subject", String.class));
            result.put("predicate", row.get("predicate", String.class));
            result.put("object", row.get("object", String.class));
            result.put("confidence", numberValue(row, "confidence"));
            result.put("valid_from", timestampValue(row, "valid_from"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> timeMachine(String subject, OffsetDateTime asOf, OffsetDateTime asOfIngestion, int limit) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        if (asOf == null && asOfIngestion == null) {
            sql.append("""
                    SELECT id, subject, predicate, "object", confidence, valid_from, valid_until, ingested_at
                    FROM active_facts
                    WHERE subject ILIKE ?
                    ORDER BY valid_from DESC
                    LIMIT ?
                    """);
            params.add("%" + subject + "%");
            params.add(limit);
        } else {
            sql.append("""
                    SELECT id, subject, predicate, "object", confidence, valid_from, valid_until, ingested_at
                    FROM facts
                    WHERE subject ILIKE ?
                      AND status = 'committed'
                    """);
            params.add("%" + subject + "%");
            if (asOf != null) {
                sql.append("  AND valid_from <= ?::timestamptz\n");
                sql.append("  AND (valid_until IS NULL OR valid_until > ?::timestamptz)\n");
                params.add(asOf);
                params.add(asOf);
            }
            if (asOfIngestion != null) {
                sql.append("  AND ingested_at <= ?::timestamptz\n");
                params.add(asOfIngestion);
            }
            sql.append("ORDER BY valid_from DESC\n");
            sql.append("LIMIT ?\n");
            params.add(limit);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch(sql.toString(), params.toArray())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", uuidValue(row, "id"));
            result.put("subject", row.get("subject", String.class));
            result.put("predicate", row.get("predicate", String.class));
            result.put("object", row.get("object", String.class));
            result.put("confidence", numberValue(row, "confidence"));
            result.put("valid_from", timestampValue(row, "valid_from"));
            result.put("valid_until", timestampValue(row, "valid_until"));
            result.put("ingested_at", timestampValue(row, "ingested_at"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> cellHistory(UUID cellId) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                WITH RECURSIVE chain AS (
                    SELECT c.id, c.parent_id, c.summary, c.created_by, c.valid_from, c.valid_until, c.ingested_at, 1 AS depth
                    FROM cells c
                    WHERE c.id = ?
                    UNION ALL
                    SELECT c.id, c.parent_id, c.summary, c.created_by, c.valid_from, c.valid_until, c.ingested_at, ch.depth + 1
                    FROM cells c
                    JOIN chain ch ON c.id = ch.parent_id
                    WHERE ch.depth < 100
                )
                SELECT id, parent_id, summary, created_by, valid_from, valid_until, ingested_at
                FROM chain
                ORDER BY valid_from ASC
                """, cellId)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", uuidValue(row, "id"));
            result.put("parent_id", uuidValue(row, "parent_id"));
            result.put("summary", row.get("summary", String.class));
            result.put("created_by", row.get("created_by", String.class));
            result.put("valid_from", timestampValue(row, "valid_from"));
            result.put("valid_until", timestampValue(row, "valid_until"));
            result.put("ingested_at", timestampValue(row, "ingested_at"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> factHistory(UUID factId) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                WITH RECURSIVE chain AS (
                    SELECT f.id, f.parent_id, f.subject, f.predicate, f."object", f.created_by, f.valid_from, f.valid_until, f.ingested_at, 1 AS depth
                    FROM facts f
                    WHERE f.id = ?
                    UNION ALL
                    SELECT f.id, f.parent_id, f.subject, f.predicate, f."object", f.created_by, f.valid_from, f.valid_until, f.ingested_at, c.depth + 1
                    FROM facts f
                    JOIN chain c ON f.id = c.parent_id
                    WHERE c.depth < 100
                )
                SELECT id, parent_id, subject, predicate, "object", created_by, valid_from, valid_until, ingested_at
                FROM chain
                ORDER BY valid_from ASC
                """, factId)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", uuidValue(row, "id"));
            result.put("parent_id", uuidValue(row, "parent_id"));
            result.put("subject", row.get("subject", String.class));
            result.put("predicate", row.get("predicate", String.class));
            result.put("object", row.get("object", String.class));
            result.put("created_by", row.get("created_by", String.class));
            result.put("valid_from", timestampValue(row, "valid_from"));
            result.put("valid_until", timestampValue(row, "valid_until"));
            result.put("ingested_at", timestampValue(row, "ingested_at"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> pendingApprovals() {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT type, id, description, realm, signal, created_by, created_at
                FROM pending_approvals
                ORDER BY created_at ASC
                """)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", row.get("type", String.class));
            result.put("id", uuidValue(row, "id"));
            result.put("description", row.get("description", String.class));
            result.put("realm", row.get("realm", String.class));
            result.put("signal", row.get("signal", String.class));
            result.put("created_by", row.get("created_by", String.class));
            result.put("created_at", timestampValue(row, "created_at"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> readingList(String refType, int limit) {
        String normalizedRefType = refType == null || refType.isBlank() ? null : refType;
        String sql = """
                SELECT r.id, r.title, r.url, r.author, r.ref_type, r.status, r.importance, r.created_at,
                       count(cr.id) AS linked_cells
                FROM references_ r
                LEFT JOIN cell_references cr ON cr.reference_id = r.id
                WHERE r.status IN ('unread', 'reading')
                """;
        Object[] params;
        if (normalizedRefType != null) {
            sql += " AND r.ref_type = ?\n";
            params = new Object[]{normalizedRefType, limit};
        } else {
            params = new Object[]{limit};
        }
        sql += """
                GROUP BY r.id
                ORDER BY r.importance ASC NULLS LAST, r.created_at DESC
                LIMIT ?
                """;

        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch(sql, params)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", uuidValue(row, "id"));
            result.put("title", row.get("title", String.class));
            result.put("url", row.get("url", String.class));
            result.put("author", row.get("author", String.class));
            result.put("ref_type", row.get("ref_type", String.class));
            result.put("status", row.get("status", String.class));
            result.put("importance", integerValue(row, "importance"));
            result.put("linked_cells", countValue(row, "linked_cells"));
            result.put("created_at", timestampValue(row, "created_at"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> listAgents() {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT name, focus, schedule, created_at
                FROM agents
                ORDER BY name
                """)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", row.get("name", String.class));
            result.put("focus", row.get("focus", String.class));
            result.put("schedule", row.get("schedule", String.class));
            result.put("created_at", timestampValue(row, "created_at"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> diaryRead(String agent, int lastN) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT id, agent, entry, created_at
                FROM agent_diary
                WHERE agent = ?
                ORDER BY created_at DESC
                LIMIT ?
                """, agent, lastN)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", uuidValue(row, "id"));
            result.put("agent", row.get("agent", String.class));
            result.put("entry", row.get("entry", String.class));
            result.put("created_at", timestampValue(row, "created_at"));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> getBlueprint(String realm) {
        String normalizedRealm = realm == null || realm.isBlank() ? null : realm;
        List<Map<String, Object>> results = new ArrayList<>();
        if (normalizedRealm != null) {
            for (Record row : dslContext.fetch("""
                    SELECT id, realm, title, narrative, signal_order, key_cells, created_by, valid_from
                    FROM active_blueprints
                    WHERE realm = ?
                    ORDER BY valid_from DESC
                    """, normalizedRealm)) {
                results.add(blueprintRow(row));
            }
        } else {
            for (Record row : dslContext.fetch("""
                    SELECT id, realm, title, narrative, signal_order, key_cells, created_by, valid_from
                    FROM active_blueprints
                    ORDER BY realm, valid_from DESC
                    """)) {
                results.add(blueprintRow(row));
            }
        }
        return results;
    }

    public Map<String, Object> streamSnapshot(int cellLimit, int tunnelLimit) {
        List<Map<String, Object>> cells = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT id, realm, signal, topic, content, summary, key_points, insight,
                       tags, importance, status, created_by, created_at, valid_from, valid_until
                FROM active_cells
                ORDER BY created_at DESC
                LIMIT ?
                """, cellLimit)) {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("id", uuidValue(row, "id"));
            cell.put("realm", row.get("realm", String.class));
            cell.put("signal", row.get("signal", String.class));
            cell.put("topic", row.get("topic", String.class));
            cell.put("title", deriveTitle(row.get("summary", String.class), row.get("content", String.class)));
            cell.put("content", row.get("content", String.class));
            cell.put("summary", row.get("summary", String.class));
            cell.put("key_points", stringArrayValue(row, "key_points"));
            cell.put("insight", row.get("insight", String.class));
            cell.put("tags", stringArrayValue(row, "tags"));
            cell.put("importance", integerValue(row, "importance"));
            cell.put("status", row.get("status", String.class));
            cell.put("created_by", row.get("created_by", String.class));
            cell.put("created_at", timestampValue(row, "created_at"));
            cell.put("valid_from", timestampValue(row, "valid_from"));
            cell.put("valid_until", timestampValue(row, "valid_until"));
            cells.add(cell);
        }

        List<Map<String, Object>> tunnels = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT id, from_cell, to_cell, relation, note, status, created_at, valid_until
                FROM active_tunnels
                ORDER BY created_at DESC
                LIMIT ?
                """, tunnelLimit)) {
            Map<String, Object> tunnel = new LinkedHashMap<>();
            tunnel.put("id", uuidValue(row, "id"));
            tunnel.put("from_cell", uuidValue(row, "from_cell"));
            tunnel.put("to_cell", uuidValue(row, "to_cell"));
            tunnel.put("relation", row.get("relation", String.class));
            tunnel.put("note", row.get("note", String.class));
            tunnel.put("status", row.get("status", String.class));
            tunnel.put("created_at", timestampValue(row, "created_at"));
            tunnel.put("valid_until", timestampValue(row, "valid_until"));
            tunnels.add(tunnel);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cells", cells);
        result.put("tunnels", tunnels);
        result.put("done", true);
        return result;
    }

    private static String deriveTitle(String summary, String content) {
        if (summary != null && !summary.isBlank()) {
            String firstLine = summary.strip().split("\\R", 2)[0];
            return firstLine.length() > 80 ? firstLine.substring(0, 80) : firstLine;
        }
        if (content != null && !content.isBlank()) {
            String firstLine = content.strip().split("\\R", 2)[0];
            return firstLine.length() > 80 ? firstLine.substring(0, 80) : firstLine;
        }
        return "(untitled)";
    }

    public Map<String, Object> wakeUp() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Record row : dslContext.fetch("""
                SELECT key, content, token_count
                FROM identity
                ORDER BY key
                """)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("content", row.get("content", String.class));
            entry.put("token_count", integerValue(row, "token_count"));
            result.put(row.get("key", String.class), entry);
        }
        return result;
    }

    public Optional<Map<String, Object>> findCell(UUID cellId, CellFieldSelection selection) {
        List<String> projections = new ArrayList<>(List.of("id", "realm", "signal", "topic"));
        projections.add(uuidProjection("parent_id", selection));
        projections.add(textProjection("content", selection));
        projections.add(textProjection("source", selection));
        projections.add(textArrayProjection("tags", selection));
        projections.add(integerProjection("importance", selection));
        projections.add(textProjection("summary", selection));
        projections.add(textArrayProjection("key_points", selection));
        projections.add(textProjection("insight", selection));
        projections.add(textProjection("actionability", selection));
        projections.add(textProjection("status", selection));
        projections.add(textProjection("created_by", selection));
        projections.add(timestampProjection("created_at", selection));
        projections.add(timestampProjection("valid_from", selection));
        projections.add(timestampProjection("valid_until", selection));
        if (selection.includes("confidence")) {
            projections.add("(SELECT AVG(confidence)::real FROM active_facts WHERE source_id = cells.id) AS confidence");
        } else {
            projections.add("NULL::real AS confidence");
        }

        String sql = "SELECT " + String.join(", ", projections) + " FROM cells WHERE id = ?";
        Record row = dslContext.fetchOne(sql, cellId);
        if (row == null) {
            return Optional.empty();
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", uuidValue(row, "id"));
        values.put("realm", row.get("realm", String.class));
        values.put("signal", row.get("signal", String.class));
        values.put("topic", row.get("topic", String.class));
        if (selection.includes("parent_id")) {
            values.put("parent_id", uuidValue(row, "parent_id"));
        }
        if (selection.includes("content")) {
            values.put("content", row.get("content", String.class));
        }
        if (selection.includes("source")) {
            values.put("source", row.get("source", String.class));
        }
        if (selection.includes("tags")) {
            values.put("tags", stringArrayValue(row, "tags"));
        }
        if (selection.includes("importance")) {
            values.put("importance", integerValue(row, "importance"));
        }
        if (selection.includes("summary")) {
            values.put("summary", row.get("summary", String.class));
        }
        if (selection.includes("key_points")) {
            values.put("key_points", stringArrayValue(row, "key_points"));
        }
        if (selection.includes("insight")) {
            values.put("insight", row.get("insight", String.class));
        }
        if (selection.includes("actionability")) {
            values.put("actionability", row.get("actionability", String.class));
        }
        if (selection.includes("status")) {
            values.put("status", row.get("status", String.class));
        }
        if (selection.includes("created_by")) {
            values.put("created_by", row.get("created_by", String.class));
        }
        if (selection.includes("created_at")) {
            values.put("created_at", timestampValue(row, "created_at"));
        }
        if (selection.includes("valid_from")) {
            values.put("valid_from", timestampValue(row, "valid_from"));
        }
        if (selection.includes("valid_until")) {
            values.put("valid_until", timestampValue(row, "valid_until"));
        }
        if (selection.includes("confidence")) {
            Number conf = row.get("confidence", Number.class);
            values.put("confidence", conf == null ? null : conf.doubleValue());
        }
        return Optional.of(selection.project(values));
    }

    private static String textProjection(String field, CellFieldSelection selection) {
        return selection.includes(field) ? "%s AS %s".formatted(field, field) : "NULL::text AS %s".formatted(field);
    }

    private static String textArrayProjection(String field, CellFieldSelection selection) {
        return selection.includes(field) ? "%s AS %s".formatted(field, field) : "NULL::text[] AS %s".formatted(field);
    }

    private static String integerProjection(String field, CellFieldSelection selection) {
        return selection.includes(field) ? "%s AS %s".formatted(field, field) : "NULL::integer AS %s".formatted(field);
    }

    private static String timestampProjection(String field, CellFieldSelection selection) {
        return selection.includes(field) ? "%s AS %s".formatted(field, field) : "NULL::timestamptz AS %s".formatted(field);
    }

    private static String uuidProjection(String field, CellFieldSelection selection) {
        return selection.includes(field) ? "%s AS %s".formatted(field, field) : "NULL::uuid AS %s".formatted(field);
    }

    private static Map<String, Object> blueprintRow(Record row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", uuidValue(row, "id"));
        result.put("realm", row.get("realm", String.class));
        result.put("title", row.get("title", String.class));
        result.put("narrative", row.get("narrative", String.class));
        result.put("signal_order", stringArrayValue(row, "signal_order"));
        result.put("key_cells", uuidArrayValue(row, "key_cells"));
        result.put("created_by", row.get("created_by", String.class));
        result.put("valid_from", timestampValue(row, "valid_from"));
        return result;
    }

    private static long countValue(Record row, String field) {
        Number count = row.get(field, Number.class);
        return count == null ? 0L : count.longValue();
    }

    private static String uuidValue(Record row, String field) {
        UUID value = row.get(field, UUID.class);
        return value == null ? null : value.toString();
    }

    private static Integer integerValue(Record row, String field) {
        Number value = row.get(field, Number.class);
        return value == null ? null : value.intValue();
    }

    private static double numberValue(Record row, String field) {
        Number value = row.get(field, Number.class);
        return value == null ? 0.0d : value.doubleValue();
    }

    private static String timestampValue(Record row, String field) {
        OffsetDateTime value = row.get(field, OffsetDateTime.class);
        return value == null ? null : DateTimeFormatter.ISO_INSTANT.format(value.toInstant());
    }

    private static List<String> stringArrayValue(Record row, String field) {
        String[] values = row.get(field, String[].class);
        return values == null ? List.of() : List.copyOf(Arrays.asList(values));
    }

    private static List<String> uuidArrayValue(Record row, String field) {
        UUID[] values = row.get(field, UUID[].class);
        if (values == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>(values.length);
        for (UUID value : values) {
            result.add(value == null ? null : value.toString());
        }
        return List.copyOf(result);
    }
}
