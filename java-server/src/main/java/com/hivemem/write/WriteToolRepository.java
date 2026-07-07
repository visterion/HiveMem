package com.hivemem.write;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WriteToolRepository {

    private static final DateTimeFormatter PYTHON_TIMESTAMP_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 0, 6, true)
            .optionalEnd()
            .appendOffset("+HH:MM", "+00:00")
            .toFormatter();

    private final DSLContext dslContext;

    public WriteToolRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    public Map<String, Object> addFact(
            String subject,
            String predicate,
            String object,
            double confidence,
            UUID sourceId,
            String status,
            String createdBy,
            OffsetDateTime validFrom,
            List<Float> embedding
    ) {
        Float[] embeddingArray = embedding == null ? null : embedding.toArray(Float[]::new);
        Record row = dslContext.fetchOne("""
                INSERT INTO facts (subject, predicate, "object", confidence, source_id, status, created_by, valid_from, embedding)
                VALUES (?, ?, ?, ?, ?, ?, ?, COALESCE(?::timestamptz, now()), ?::vector)
                RETURNING id, subject, predicate, "object", status
                """, subject, predicate, object, confidence, sourceId, status, createdBy, validFrom, embeddingArray);
        return factRow(row);
    }

    public Map<String, Object> addCell(
            String content,
            List<Float> embedding,
            String realm,
            String signal,
            String topic,
            String source,
            List<String> tags,
            Integer importance,
            String summary,
            List<String> keyPoints,
            String insight,
            String actionability,
            String status,
            String createdBy,
            OffsetDateTime validFrom
    ) {
        String[] tagArray = tags == null ? new String[0] : tags.toArray(String[]::new);
        String[] keyPointArray = keyPoints == null ? new String[0] : keyPoints.toArray(String[]::new);
        Float[] embeddingArray = embedding == null ? null : embedding.toArray(Float[]::new);
        Record row = dslContext.fetchOne("""
                INSERT INTO cells (content, embedding, realm, signal, topic, source, tags, importance,
                                     summary, key_points, insight, actionability, status, created_by, valid_from)
                VALUES (?, ?::vector, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?::timestamptz, now()))
                RETURNING id, realm, signal, topic, status
                """,
                content, embeddingArray, realm, signal, topic, source, tagArray, importance,
                summary, keyPointArray, insight, actionability, status, createdBy, validFrom);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", uuidValue(row, "id"));
        result.put("realm", row.get("realm", String.class));
        result.put("signal", row.get("signal", String.class));
        result.put("topic", row.get("topic", String.class));
        result.put("status", row.get("status", String.class));
        return result;
    }

    public int addTags(UUID id, List<String> tags) {
        String[] tagArray = tags == null ? new String[0] : tags.toArray(String[]::new);
        return dslContext.execute("""
                UPDATE cells
                SET tags = (SELECT array_agg(DISTINCT t) FROM unnest(array_cat(tags, ?::text[])) t)
                WHERE id = ?
                  AND valid_until IS NULL
                """, tagArray, id);
    }

    public int removeTags(UUID id, List<String> tags) {
        String[] tagArray = tags == null ? new String[0] : tags.toArray(String[]::new);
        return dslContext.execute("""
                UPDATE cells
                SET tags = CASE
                    WHEN tags IS NULL THEN NULL
                    ELSE array(SELECT t FROM unnest(tags) t WHERE t <> ALL(?::text[]))
                END
                WHERE id = ?
                  AND valid_until IS NULL
                """, tagArray, id);
    }

    public void tagNeedsSummary(UUID id) {
        dslContext.execute(
                "UPDATE cells SET tags = "
                + "  CASE WHEN 'needs_summary' = ANY(tags) THEN tags ELSE array_append(tags, 'needs_summary') END "
                + "WHERE id = ?", id);
    }

    public void tagOcrPending(UUID id) {
        dslContext.execute(
                "UPDATE cells SET tags = "
                + "  CASE WHEN 'ocr_pending' = ANY(tags) THEN tags ELSE array_append(tags, 'ocr_pending') END "
                + "WHERE id = ?", id);
    }

    public void tagVisionPending(UUID id) {
        dslContext.execute(
                "UPDATE cells SET tags = "
                + "  CASE WHEN 'vision_pending' = ANY(tags) THEN tags ELSE array_append(tags, 'vision_pending') END "
                + "WHERE id = ?", id);
    }

    public void tagKrokiPending(UUID id) {
        dslContext.execute(
                "UPDATE cells SET tags = "
                + "  CASE WHEN 'kroki_pending' = ANY(tags) THEN tags ELSE array_append(tags, 'kroki_pending') END "
                + "WHERE id = ?", id);
    }

    public void tagEmbeddingPending(UUID id) {
        dslContext.execute(
                "UPDATE cells SET tags = "
                + "  CASE WHEN 'embedding_pending' = ANY(tags) THEN tags ELSE array_append(tags, 'embedding_pending') END "
                + "WHERE id = ?", id);
    }

    public int upsertIdentity(String key, String content, int tokenCount) {
        return dslContext.execute("""
                INSERT INTO identity (key, content, token_count, updated_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (key) DO UPDATE
                SET content = EXCLUDED.content,
                    token_count = EXCLUDED.token_count,
                    updated_at = now()
                """, key, content, tokenCount);
    }

    public Map<String, Object> addReference(
            String title,
            String url,
            String author,
            String refType,
            String status,
            String notes,
            List<String> tags,
            Integer importance
    ) {
        String[] tagArray = tags == null ? new String[0] : tags.toArray(String[]::new);
        Record row = dslContext.fetchOne("""
                INSERT INTO references_ (title, url, author, ref_type, status, notes, tags, importance)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, title, status
                """, title, url, author, refType, status, notes, tagArray, importance);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", uuidValue(row, "id"));
        result.put("title", row.get("title", String.class));
        result.put("status", row.get("status", String.class));
        return result;
    }

    public Map<String, Object> linkReference(UUID cellId, UUID referenceId, String relation) {
        Record row = dslContext.fetchOne("""
                INSERT INTO cell_references (cell_id, reference_id, relation)
                VALUES (?, ?, ?)
                RETURNING id
                """, cellId, referenceId, relation);
        return Map.of(
                "id", uuidValue(row, "id"),
                "cell_id", cellId.toString(),
                "reference_id", referenceId.toString(),
                "relation", relation
        );
    }

    public Map<String, Object> registerAgent(
            String name,
            String focus,
            String autonomyJson,
            String schedule,
            String modelRoutingJson,
            List<String> tools
    ) {
        String[] toolArray = tools == null ? new String[0] : tools.toArray(String[]::new);
        String autonomyValue = autonomyJson == null ? "{\"default\":\"suggest_only\"}" : autonomyJson;
        Record row = dslContext.fetchOne("""
                INSERT INTO agents (name, focus, autonomy, schedule, model_routing, tools)
                VALUES (?, ?, COALESCE(?::jsonb, '{"default":"suggest_only"}'::jsonb), ?, ?::jsonb, ?)
                ON CONFLICT (name) DO UPDATE
                SET focus = EXCLUDED.focus,
                    autonomy = EXCLUDED.autonomy,
                    schedule = EXCLUDED.schedule,
                    model_routing = EXCLUDED.model_routing,
                    tools = EXCLUDED.tools
                RETURNING name, focus
                """, name, focus, autonomyValue, schedule, modelRoutingJson, toolArray);
        return Map.of(
                "name", row.get("name", String.class),
                "focus", row.get("focus", String.class)
        );
    }

    public Map<String, Object> diaryWrite(String agent, String entry) {
        Record row = dslContext.fetchOne("""
                INSERT INTO agent_diary (agent, entry)
                VALUES (?, ?)
                RETURNING id
                """, agent, entry);
        return Map.of(
                "id", uuidValue(row, "id"),
                "agent", agent
        );
    }

    public Map<String, Object> updateBlueprint(
            String createdBy,
            String realm,
            String title,
            String narrative,
            List<String> signalOrder,
            List<UUID> keyCells
    ) {
        return dslContext.transactionResult(configuration -> {
            DSLContext tx = DSL.using(configuration);
            tx.execute("SELECT pg_advisory_xact_lock(hashtext(?))", "blueprint:" + realm);
            OffsetDateTime timestamp = tx.fetchOne("SELECT now() AS ts").get("ts", OffsetDateTime.class);
            tx.execute("""
                    UPDATE blueprints
                    SET valid_until = ?::timestamptz
                    WHERE realm = ?
                      AND valid_until IS NULL
                    """, timestamp, realm);
            String[] signalOrderArray = signalOrder == null ? new String[0] : signalOrder.toArray(String[]::new);
            UUID[] keyCellArray = keyCells == null ? new UUID[0] : keyCells.toArray(UUID[]::new);
            Record row = tx.fetchOne("""
                    INSERT INTO blueprints (realm, title, narrative, signal_order, key_cells, created_by, valid_from)
                    VALUES (?, ?, ?, ?, ?, ?, ?::timestamptz)
                    RETURNING id, realm, title
                    """, realm, title, narrative, signalOrderArray, keyCellArray, createdBy, timestamp);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", uuidValue(row, "id"));
            result.put("realm", row.get("realm", String.class));
            result.put("title", row.get("title", String.class));
            return result;
        });
    }

    public Map<String, Object> addTunnel(
            UUID fromCell,
            UUID toCell,
            String relation,
            String note,
            String status,
            String createdBy
    ) {
        Record row = dslContext.fetchOne("""
                INSERT INTO tunnels (from_cell, to_cell, relation, note, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id, from_cell, to_cell, relation, note, status
                """, fromCell, toCell, relation, note, status, createdBy);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", uuidValue(row, "id"));
        result.put("from_cell", uuidValue(row, "from_cell"));
        result.put("to_cell", uuidValue(row, "to_cell"));
        result.put("relation", row.get("relation", String.class));
        result.put("note", row.get("note", String.class));
        result.put("status", row.get("status", String.class));
        return result;
    }

    public int removeTunnel(UUID tunnelId) {
        return dslContext.execute("""
                UPDATE tunnels
                SET valid_until = now()
                WHERE id = ?
                  AND valid_until IS NULL
                """, tunnelId);
    }

    public int invalidateFact(UUID factId) {
        return dslContext.execute("""
                UPDATE facts
                SET valid_until = now()
                WHERE id = ?
                  AND valid_until IS NULL
                """, factId);
    }

    public Map<String, Object> reviseFact(
            UUID oldId,
            String newObject,
            String createdBy,
            String status,
            List<Float> embedding
    ) {
        Float[] embeddingArray = embedding == null ? null : embedding.toArray(Float[]::new);
        return dslContext.transactionResult(configuration -> {
            DSLContext tx = DSL.using(configuration);
            Record timestampRow = tx.fetchOne("SELECT now() AS ts");
            OffsetDateTime revisionTimestamp = timestampRow.get("ts", OffsetDateTime.class);
            Record oldRow = tx.fetchOne("""
                    SELECT subject, predicate, confidence, source_id
                    FROM facts
                    WHERE id = ? AND valid_until IS NULL
                    FOR UPDATE
                    """, oldId);
            if (oldRow == null) {
                throw new IllegalArgumentException("Fact " + oldId + " not found or already revised");
            }

            tx.execute("""
                    UPDATE facts
                    SET valid_until = ?::timestamptz
                    WHERE id = ?
                    """, revisionTimestamp, oldId);

            Record newRow = tx.fetchOne("""
                    INSERT INTO facts (parent_id, subject, predicate, "object", confidence, source_id, status, created_by, valid_from, embedding)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::vector)
                    RETURNING id
                    """,
                    oldId,
                    oldRow.get("subject", String.class),
                    oldRow.get("predicate", String.class),
                    newObject,
                    confidenceValue(oldRow),
                    oldRow.get("source_id", UUID.class),
                    status,
                    createdBy,
                    revisionTimestamp,
                    embeddingArray);
            return Map.of(
                    "old_id", oldId.toString(),
                    "new_id", uuidValue(newRow, "id")
            );
        });
    }

    public Map<String, Object> reviseCell(
            UUID oldId,
            String newContent,
            String newSummary,
            List<Float> embedding,
            String createdBy,
            String status
    ) {
        // Carry over key_points/insight/tags from the old revision unchanged.
        return reviseCell(oldId, newContent, newSummary, null, null, null, embedding, createdBy, status);
    }

    /**
     * Revise a cell, optionally replacing its derived metadata. A null {@code newSummary},
     * {@code newKeyPoints} or {@code newInsight} carries the old value over; {@code newTags}
     * are merged (union) with the existing tags. Used by the summarizer so the LLM-produced
     * key_points/insight/tags are persisted, not just the summary.
     */
    public Map<String, Object> reviseCell(
            UUID oldId,
            String newContent,
            String newSummary,
            List<String> newKeyPoints,
            String newInsight,
            List<String> newTags,
            List<Float> embedding,
            String createdBy,
            String status
    ) {
        Float[] embeddingArray = embedding == null ? null : embedding.toArray(Float[]::new);
        return dslContext.transactionResult(configuration -> {
            DSLContext tx = DSL.using(configuration);
            Record timestampRow = tx.fetchOne("SELECT now() AS ts");
            OffsetDateTime revisionTimestamp = timestampRow.get("ts", OffsetDateTime.class);
            Record oldRow = tx.fetchOne("""
                    SELECT realm, signal, topic, source, tags, importance, summary, key_points, insight, actionability
                    FROM cells
                    WHERE id = ? AND valid_until IS NULL
                    FOR UPDATE
                    """, oldId);
            if (oldRow == null) {
                throw new IllegalArgumentException("Cell " + oldId + " not found or already revised");
            }

            tx.execute("""
                    UPDATE cells
                    SET valid_until = ?::timestamptz
                    WHERE id = ?
                    """, revisionTimestamp, oldId);

            String[] keyPointsArray = newKeyPoints != null
                    ? newKeyPoints.toArray(String[]::new)
                    : oldRow.get("key_points", String[].class);
            String insightValue = newInsight != null
                    ? newInsight
                    : oldRow.get("insight", String.class);
            String[] tagsArray = mergeTags(oldRow.get("tags", String[].class), newTags);

            Record newRow = tx.fetchOne("""
                    INSERT INTO cells (parent_id, content, embedding, realm, signal, topic, source, tags, importance,
                                         summary, key_points, insight, actionability, status, created_by, valid_from)
                    VALUES (?, ?, ?::vector, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz)
                    RETURNING id
                    """,
                    oldId,
                    newContent,
                    embeddingArray,
                    oldRow.get("realm", String.class),
                    oldRow.get("signal", String.class),
                    oldRow.get("topic", String.class),
                    oldRow.get("source", String.class),
                    tagsArray,
                    oldRow.get("importance", Integer.class),
                    newSummary == null ? oldRow.get("summary", String.class) : newSummary,
                    keyPointsArray,
                    insightValue,
                    oldRow.get("actionability", String.class),
                    status,
                    createdBy,
                    revisionTimestamp
            );
            UUID newId = newRow.get("id", UUID.class);
            // Carry cell↔attachment links to the new revision so the current cell stays linked to its
            // source attachment(s) — e.g. a scanned PDF whose cell is later revised by OCR. Without this
            // the OCR'd current revision would lose its link to the original PDF.
            tx.execute("""
                    INSERT INTO cell_attachments (cell_id, attachment_id, extraction_source)
                    SELECT ?, attachment_id, extraction_source
                    FROM cell_attachments
                    WHERE cell_id = ?
                    """, newId, oldId);
            return Map.of(
                    "old_id", oldId.toString(),
                    "new_id", newId.toString()
            );
        });
    }

    /** Union of existing tags and new tags, order-preserving and de-duplicated. */
    private static String[] mergeTags(String[] oldTags, List<String> newTags) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        if (oldTags != null) {
            merged.addAll(java.util.Arrays.asList(oldTags));
        }
        if (newTags != null) {
            merged.addAll(newTags);
        }
        return merged.toArray(String[]::new);
    }

    public Map<String, Object> reclassifyCell(
            UUID cellId,
            String realm,
            String topic,
            String signal
    ) {
        return dslContext.transactionResult(configuration -> {
            DSLContext tx = DSL.using(configuration);
            Record existing = tx.fetchOne("""
                    SELECT valid_until, status
                    FROM cells
                    WHERE id = ?
                    FOR UPDATE
                    """, cellId);
            if (existing == null) {
                throw new IllegalArgumentException("cell not found");
            }
            OffsetDateTime validUntil = existing.get("valid_until", OffsetDateTime.class);
            if (validUntil != null) {
                throw new IllegalArgumentException(
                        "cell version is not current — target the live version");
            }
            String currentStatus = existing.get("status", String.class);
            if ("rejected".equals(currentStatus)) {
                throw new IllegalArgumentException("cannot reclassify rejected cell");
            }

            Record updated = tx.fetchOne("""
                    UPDATE cells
                    SET realm  = COALESCE(?, realm),
                        topic  = COALESCE(?, topic),
                        signal = COALESCE(?, signal)
                    WHERE id = ?
                      AND valid_until IS NULL
                      AND status IN ('committed', 'pending')
                    RETURNING id, realm, topic, signal
                    """, realm, topic, signal, cellId);
            if (updated == null) {
                throw new IllegalArgumentException("cell not found, closed, or rejected");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", uuidValue(updated, "id"));
            result.put("realm", updated.get("realm", String.class));
            result.put("topic", updated.get("topic", String.class));
            result.put("signal", updated.get("signal", String.class));
            return result;
        });
    }

    public List<Map<String, Object>> checkDuplicateCell(String vectorLiteral, double threshold) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch(
                "SELECT * FROM check_duplicate_cell(?::vector, ?::real)",
                vectorLiteral, threshold)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", uuidValue(row, "id"));
            result.put("similarity", Math.round(row.get("similarity", Double.class) * 10_000.0d) / 10_000.0d);
            result.put("summary", row.get("summary", String.class));
            results.add(result);
        }
        return results;
    }

    public List<Map<String, Object>> checkContradiction(String subject, String predicate, String newObject) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch("""
                SELECT id AS fact_id, "object" AS existing_object, valid_from
                FROM active_facts
                WHERE subject = ?
                  AND predicate = ?
                  AND "object" <> ?
                """, subject, predicate, newObject)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("fact_id", uuidValue(row, "fact_id"));
            result.put("existing_object", row.get("existing_object", String.class));
            result.put("valid_from", defaultTimestampValue(row, "valid_from"));
            results.add(result);
        }
        return results;
    }

    public int approvePending(List<UUID> ids, String decision) {
        UUID[] idArray = ids.toArray(UUID[]::new);
        return dslContext.transactionResult(configuration -> {
            DSLContext tx = DSL.using(configuration);
            int cellCount = tx.execute("""
                    UPDATE cells
                    SET status = ?
                    WHERE id = ANY(?::uuid[])
                      AND status = 'pending'
                    """, decision, idArray);
            int factCount = tx.execute("""
                    UPDATE facts
                    SET status = ?
                    WHERE id = ANY(?::uuid[])
                      AND status = 'pending'
                    """, decision, idArray);
            int tunnelCount = tx.execute("""
                    UPDATE tunnels
                    SET status = ?
                    WHERE id = ANY(?::uuid[])
                      AND status = 'pending'
                    """, decision, idArray);
            return cellCount + factCount + tunnelCount;
        });
    }

    public Optional<String[]> findFactSubjectPredicate(UUID id) {
        Record row = dslContext.fetchOne("""
                SELECT subject, predicate
                FROM facts
                WHERE id = ? AND valid_until IS NULL
                """, id);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new String[] {row.get("subject", String.class), row.get("predicate", String.class)});
    }

    private static Map<String, Object> factRow(Record row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", uuidValue(row, "id"));
        result.put("subject", row.get("subject", String.class));
        result.put("predicate", row.get("predicate", String.class));
        result.put("object", row.get("object", String.class));
        result.put("status", row.get("status", String.class));
        return result;
    }

    private static String uuidValue(Record row, String field) {
        UUID value = row.get(field, UUID.class);
        return value == null ? null : value.toString();
    }

    private static Double confidenceValue(Record row) {
        Number value = row.get("confidence", Number.class);
        return value == null ? null : value.doubleValue();
    }

    private static String timestampValue(Record row, String field) {
        OffsetDateTime value = row.get(field, OffsetDateTime.class);
        return value == null ? null : DateTimeFormatter.ISO_INSTANT.format(value.toInstant());
    }

    private static String defaultTimestampValue(Record row, String field) {
        OffsetDateTime value = row.get(field, OffsetDateTime.class);
        return value == null ? null : PYTHON_TIMESTAMP_FORMATTER.format(value);
    }
}
