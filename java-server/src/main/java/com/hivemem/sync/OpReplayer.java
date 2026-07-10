package com.hivemem.sync;

import com.hivemem.embedding.EmbeddingClient;
import tools.jackson.databind.JsonNode;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class OpReplayer {

    private static final Logger log = LoggerFactory.getLogger(OpReplayer.class);

    /**
     * SKIPPED means "legitimately not applied" (already applied, duplicate, stale) — safe to
     * advance past. FAILED means the op threw during execution (e.g. embedding service down,
     * malformed payload) — the caller must NOT advance {@code last_seen_seq} past it, so the op
     * is retried on the next pull instead of being silently lost.
     */
    public enum ReplayResult { REPLAYED, SKIPPED, CONFLICT, UNKNOWN_OP, FAILED }

    public record BatchResult(int replayed, int skipped, int failed) {}

    private final DSLContext dsl;
    private final EmbeddingClient embeddingClient;
    private final SyncPeerRepository syncPeerRepository;

    public OpReplayer(DSLContext dsl, EmbeddingClient embeddingClient, SyncPeerRepository syncPeerRepository) {
        this.dsl = dsl;
        this.embeddingClient = embeddingClient;
        this.syncPeerRepository = syncPeerRepository;
    }

    public ReplayResult replay(UUID sourcePeer, OpDto op) {
        if (syncPeerRepository.isApplied(op.opId())) return ReplayResult.SKIPPED;

        ReplayResult result;
        try {
            result = executeOp(op);
        } catch (Exception e) {
            log.warn("Op replay failed op_id={} op_type={}", op.opId(), op.opType(), e);
            return ReplayResult.FAILED;
        }

        if (result == ReplayResult.REPLAYED || result == ReplayResult.CONFLICT) {
            syncPeerRepository.recordApplied(op.opId(), sourcePeer);
        }
        return result;
    }

    public BatchResult replayAll(UUID sourcePeer, List<OpDto> ops) {
        if (ops == null || sourcePeer == null) return new BatchResult(0, 0, 0);
        int replayed = 0, skipped = 0, failed = 0;
        for (OpDto op : ops) {
            ReplayResult r = replay(sourcePeer, op);
            if (r == ReplayResult.FAILED) {
                // Ops are sequential and may be causally dependent (add_cell → revise_cell);
                // stop at the first failure. The remaining ops arrive again via the seq-based pull.
                failed++;
                break;
            }
            if (r == ReplayResult.REPLAYED || r == ReplayResult.CONFLICT) replayed++;
            else skipped++;
        }
        return new BatchResult(replayed, skipped, failed);
    }

    private ReplayResult executeOp(OpDto op) {
        JsonNode p = op.payload();
        return switch (op.opType()) {
            case "add_cell" -> replayAddCell(p);
            case "revise_cell" -> replayReviseCell(p);
            case "kg_add" -> replayKgAdd(p);
            case "kg_invalidate" -> replayKgInvalidate(p);
            case "revise_fact" -> replayReviseFact(p);
            case "add_tunnel" -> replayAddTunnel(p);
            case "remove_tunnel" -> replayRemoveTunnel(p);
            case "register_agent" -> replayRegisterAgent(p);
            case "reclassify_cell" -> replayReclassifyCell(p);
            case "update_identity" -> replayUpdateIdentity(p);
            case "add_reference" -> replayAddReference(p);
            case "link_reference" -> replayLinkReference(p);
            case "diary_write" -> replayDiaryWrite(p);
            case "update_blueprint" -> replayUpdateBlueprint(p);
            case "approve_pending" -> replayApprovePending(p);
            case "reject_cell" -> replayRejectCell(p);
            case "add_tags" -> replayAddTags(p);
            case "remove_tags" -> replayRemoveTags(p);
            case "bulk_tag" -> replayBulkTag(p);
            case "update_cell_meta" -> replayUpdateCellMeta(p);
            default -> {
                log.debug("Unknown op_type='{}' — skipping", op.opType());
                yield ReplayResult.UNKNOWN_OP;
            }
        };
    }

    private ReplayResult replayAddCell(JsonNode p) {
        UUID cellId = uuid(p, "cell_id");
        boolean exists = dsl.fetchOne("SELECT 1 FROM cells WHERE id = ?", cellId) != null;
        if (exists) {
            UUID localOpId = findLocalOpForCell(cellId);
            syncPeerRepository.recordConflict(cellId,
                    localOpId != null ? localOpId : cellId,
                    cellId);
            return ReplayResult.CONFLICT;
        }
        String content = text(p, "content");
        String summary = text(p, "summary");
        // encodeForCell returns null by contract for long content without a summary: mirror the
        // local write path — insert with NULL embedding and tag needs_summary instead of NPEing.
        List<Float> embedding = embeddingClient.encodeForCell(content, summary);
        Float[] embArr = embedding == null ? null : embedding.toArray(Float[]::new);
        String[] tags = arrayField(p, "tags");
        if (embArr == null) tags = appendIfMissing(tags, "needs_summary");
        String[] keyPoints = arrayField(p, "key_points");
        OffsetDateTime validFrom = p.hasNonNull("valid_from")
                ? OffsetDateTime.parse(p.get("valid_from").asText()) : null;

        dsl.execute("""
                INSERT INTO cells (id, content, embedding, realm, signal, topic, source, tags, importance,
                                   summary, key_points, insight, actionability, status, created_by, valid_from)
                VALUES (?::uuid, ?, ?::vector, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        COALESCE(?::timestamptz, now()))
                """,
                cellId, content, embArr,
                text(p, "realm"), text(p, "signal"), text(p, "topic"), text(p, "source"),
                tags, intField(p, "importance"),
                text(p, "summary"), keyPoints, text(p, "insight"), text(p, "actionability"),
                textOrDefault(p, "status", "committed"), text(p, "agent_id"),
                validFrom);
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayReviseCell(JsonNode p) {
        UUID oldId = uuid(p, "cell_id");
        UUID newId = uuid(p, "new_cell_id");
        if (newId == null) return ReplayResult.SKIPPED;
        if (dsl.fetchOne("SELECT 1 FROM cells WHERE id = ?", newId) != null) return ReplayResult.SKIPPED;
        var meta = dsl.fetchOne("""
                SELECT realm, signal, topic, source, tags, importance, key_points, insight, actionability
                FROM cells WHERE id = ? AND valid_until IS NULL
                """, oldId);
        if (meta == null) {
            log.warn("revise_cell replay: old cell {} not found or already closed", oldId);
            return ReplayResult.SKIPPED;
        }
        String newContent = text(p, "new_content");
        String newSummary = text(p, "new_summary");
        // Same null-embedding contract as replayAddCell: NULL vector + needs_summary tag.
        List<Float> embedding = embeddingClient.encodeForCell(newContent, newSummary);
        Float[] embArr = embedding == null ? null : embedding.toArray(Float[]::new);
        String status = textOrDefault(p, "status", "committed");

        // Prefer payload metadata over the old revision's: the summarizer ships its LLM-derived
        // key_points/insight/tags in the op so the enrichment reaches peers. Absent fields carry
        // the old values over; new_tags are merged (union) with the old tags — mirroring the
        // local WriteToolRepository.reviseCell semantics.
        String[] keyPoints = p.hasNonNull("new_key_points")
                ? arrayField(p, "new_key_points") : meta.get("key_points", String[].class);
        String insight = p.hasNonNull("new_insight")
                ? text(p, "new_insight") : meta.get("insight", String.class);
        String[] mergedTags = mergeTags(meta.get("tags", String[].class),
                p.hasNonNull("new_tags") ? arrayField(p, "new_tags") : null);
        String[] tags = embArr == null ? appendIfMissing(mergedTags, "needs_summary") : mergedTags;

        dsl.transaction(ctx -> {
            var tx = ctx.dsl();
            tx.execute("UPDATE cells SET valid_until = now() WHERE id = ? AND valid_until IS NULL", oldId);
            tx.execute("""
                    INSERT INTO cells (id, parent_id, content, embedding, realm, signal, topic, source, tags,
                                       importance, summary, key_points, insight, actionability, status, created_by, valid_from)
                    VALUES (?::uuid, ?::uuid, ?, ?::vector, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    """,
                    newId, oldId, newContent, embArr,
                    meta.get("realm", String.class), meta.get("signal", String.class),
                    meta.get("topic", String.class), meta.get("source", String.class),
                    tags, meta.get("importance", Integer.class),
                    newSummary, keyPoints,
                    insight, meta.get("actionability", String.class),
                    status, text(p, "agent_id"));
        });
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayKgAdd(JsonNode p) {
        UUID factId = uuid(p, "fact_id");
        if (dsl.fetchOne("SELECT 1 FROM facts WHERE id = ?", factId) != null) return ReplayResult.SKIPPED;
        OffsetDateTime validFrom = p.hasNonNull("valid_from")
                ? OffsetDateTime.parse(p.get("valid_from").asText()) : null;
        UUID sourceId = p.hasNonNull("source_id") ? UUID.fromString(p.get("source_id").asText()) : null;
        dsl.execute("""
                INSERT INTO facts (id, subject, predicate, "object", confidence, source_id, status, created_by, valid_from)
                VALUES (?::uuid, ?, ?, ?, ?, ?::uuid, ?, ?, COALESCE(?::timestamptz, now()))
                """,
                factId, text(p, "subject"), text(p, "predicate"), text(p, "object"),
                p.hasNonNull("confidence") ? p.get("confidence").asDouble() : 1.0,
                sourceId,
                textOrDefault(p, "status", "committed"), text(p, "agent_id"), validFrom);
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayKgInvalidate(JsonNode p) {
        UUID factId = uuid(p, "fact_id");
        dsl.execute("UPDATE facts SET valid_until = now() WHERE id = ? AND valid_until IS NULL", factId);
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayReviseFact(JsonNode p) {
        UUID oldId = uuid(p, "fact_id");
        UUID newId = uuid(p, "new_fact_id");
        if (newId == null) return ReplayResult.SKIPPED;
        if (dsl.fetchOne("SELECT 1 FROM facts WHERE id = ?", newId) != null) return ReplayResult.SKIPPED;
        var old = dsl.fetchOne(
                "SELECT subject, predicate, confidence, source_id FROM facts WHERE id = ? AND valid_until IS NULL",
                oldId);
        if (old == null) return ReplayResult.SKIPPED;
        String status = textOrDefault(p, "status", "committed");
        dsl.transaction(ctx -> {
            var tx = ctx.dsl();
            tx.execute("UPDATE facts SET valid_until = now() WHERE id = ? AND valid_until IS NULL", oldId);
            tx.execute("""
                    INSERT INTO facts (id, subject, predicate, "object", confidence, source_id, status, created_by, valid_from)
                    VALUES (?::uuid, ?, ?, ?, ?, ?::uuid, ?, ?, now())
                    """,
                    newId, old.get("subject", String.class), old.get("predicate", String.class),
                    text(p, "new_object"), old.get("confidence", Double.class),
                    old.get("source_id", UUID.class), status, text(p, "agent_id"));
        });
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayAddTunnel(JsonNode p) {
        UUID tunnelId = uuid(p, "tunnel_id");
        if (dsl.fetchOne("SELECT 1 FROM tunnels WHERE id = ?", tunnelId) != null) return ReplayResult.SKIPPED;
        dsl.execute("""
                INSERT INTO tunnels (id, from_cell, to_cell, relation, note, status, created_by)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?)
                """,
                tunnelId, uuid(p, "from_cell_id"), uuid(p, "to_cell_id"),
                text(p, "relation"), text(p, "note"),
                textOrDefault(p, "status", "committed"), text(p, "agent_id"));
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayRemoveTunnel(JsonNode p) {
        UUID tunnelId = uuid(p, "tunnel_id");
        dsl.execute("UPDATE tunnels SET valid_until = now() WHERE id = ? AND valid_until IS NULL", tunnelId);
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayRegisterAgent(JsonNode p) {
        String name = text(p, "name");
        dsl.execute("""
                INSERT INTO agents (name, focus, autonomy, schedule, model_routing, tools)
                VALUES (?, ?, COALESCE(?::jsonb, '{"default":"suggest_only"}'::jsonb), ?, ?::jsonb, ?)
                ON CONFLICT (name) DO UPDATE
                SET focus = EXCLUDED.focus, autonomy = EXCLUDED.autonomy,
                    schedule = EXCLUDED.schedule, model_routing = EXCLUDED.model_routing,
                    tools = EXCLUDED.tools
                """,
                name, text(p, "focus"),
                p.hasNonNull("autonomy") ? p.get("autonomy").toString() : null,
                text(p, "schedule"),
                p.hasNonNull("model_routing") ? p.get("model_routing").toString() : null,
                (Object) (p.hasNonNull("tools") ? arrayFromNode(p.get("tools")) : new String[0]));
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayReclassifyCell(JsonNode p) {
        UUID cellId = uuid(p, "cell_id");
        String realm = text(p, "new_realm");
        String topic = text(p, "new_topic");
        String signal = text(p, "new_signal");
        dsl.execute("""
                UPDATE cells
                SET realm  = COALESCE(?, realm),
                    topic  = COALESCE(?, topic),
                    signal = COALESCE(?, signal)
                WHERE id = ?
                  AND valid_until IS NULL
                  AND status IN ('committed', 'pending')
                """, realm, topic, signal, cellId);
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayUpdateIdentity(JsonNode p) {
        String key = text(p, "key");
        String content = text(p, "content");
        int tokenCount = p.hasNonNull("token_count") ? p.get("token_count").asInt() : content.length() / 4;
        dsl.execute("""
                INSERT INTO identity (key, content, token_count, updated_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (key) DO UPDATE
                SET content = EXCLUDED.content,
                    token_count = EXCLUDED.token_count,
                    updated_at = now()
                """, key, content, tokenCount);
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayAddReference(JsonNode p) {
        UUID refId = uuid(p, "reference_id");
        if (dsl.fetchOne("SELECT 1 FROM references_ WHERE id = ?", refId) != null) return ReplayResult.SKIPPED;
        String[] tags = arrayField(p, "tags");
        Integer importance = p.hasNonNull("importance") ? p.get("importance").asInt() : null;
        dsl.execute("""
                INSERT INTO references_ (id, title, url, author, ref_type, status, notes, tags, importance)
                VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                refId, text(p, "title"), text(p, "url"), text(p, "author"),
                text(p, "ref_type"), textOrDefault(p, "status", "read"),
                text(p, "notes"), tags, importance);
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayLinkReference(JsonNode p) {
        UUID cellId = uuid(p, "cell_id");
        UUID refId = uuid(p, "reference_id");
        String relation = textOrDefault(p, "relation", "source");
        boolean exists = dsl.fetchOne("""
                SELECT 1 FROM cell_references WHERE cell_id = ? AND reference_id = ? AND relation = ?
                """, cellId, refId, relation) != null;
        if (exists) return ReplayResult.SKIPPED;
        dsl.execute("""
                INSERT INTO cell_references (cell_id, reference_id, relation)
                VALUES (?::uuid, ?::uuid, ?)
                """, cellId, refId, relation);
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayDiaryWrite(JsonNode p) {
        UUID entryId = uuid(p, "entry_id");
        if (dsl.fetchOne("SELECT 1 FROM agent_diary WHERE id = ?", entryId) != null) return ReplayResult.SKIPPED;
        dsl.execute("""
                INSERT INTO agent_diary (id, agent, entry)
                VALUES (?::uuid, ?, ?)
                """, entryId, text(p, "agent"), text(p, "entry"));
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayUpdateBlueprint(JsonNode p) {
        UUID blueprintId = uuid(p, "blueprint_id");
        if (dsl.fetchOne("SELECT 1 FROM blueprints WHERE id = ?", blueprintId) != null) return ReplayResult.SKIPPED;
        String realm = text(p, "realm");
        String[] signalOrder = arrayField(p, "signal_order");
        String[] keyCellStrings = arrayField(p, "key_cells");
        UUID[] keyCells = new UUID[keyCellStrings.length];
        for (int i = 0; i < keyCellStrings.length; i++) keyCells[i] = UUID.fromString(keyCellStrings[i]);
        dsl.transaction(ctx -> {
            var tx = ctx.dsl();
            tx.execute("UPDATE blueprints SET valid_until = now() WHERE realm = ? AND valid_until IS NULL", realm);
            tx.execute("""
                    INSERT INTO blueprints (id, realm, title, narrative, signal_order, key_cells, created_by)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, ?)
                    """,
                    blueprintId, realm, text(p, "title"), text(p, "narrative"),
                    signalOrder, keyCells, text(p, "agent_id"));
        });
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayApprovePending(JsonNode p) {
        String decision = text(p, "decision");
        String[] idStrings = arrayField(p, "ids");
        UUID[] ids = new UUID[idStrings.length];
        for (int i = 0; i < idStrings.length; i++) ids[i] = UUID.fromString(idStrings[i]);
        dsl.transaction(ctx -> {
            var tx = ctx.dsl();
            tx.execute("UPDATE cells SET status = ? WHERE id = ANY(?::uuid[]) AND status = 'pending'", decision, ids);
            tx.execute("UPDATE facts SET status = ? WHERE id = ANY(?::uuid[]) AND status = 'pending'", decision, ids);
            tx.execute("UPDATE tunnels SET status = ? WHERE id = ANY(?::uuid[]) AND status = 'pending'", decision, ids);
        });
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayRejectCell(JsonNode p) {
        UUID cellId = uuid(p, "cell_id");
        // Idempotent: 0 rows affected (already rejected, closed, missing) is still REPLAYED.
        dsl.execute("""
                UPDATE cells
                SET status = 'rejected'
                WHERE id = ?
                  AND valid_until IS NULL
                  AND status IN ('committed', 'pending')
                """, cellId);
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayAddTags(JsonNode p) {
        applyAddTags(uuid(p, "cell_id"), arrayField(p, "tags"));
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayRemoveTags(JsonNode p) {
        applyRemoveTags(uuid(p, "cell_id"), arrayField(p, "tags"));
        return ReplayResult.REPLAYED;
    }

    private ReplayResult replayBulkTag(JsonNode p) {
        String[] cellIds = arrayField(p, "cell_ids");
        String[] addTags = arrayField(p, "add_tags");
        String[] removeTags = arrayField(p, "remove_tags");
        for (String idString : cellIds) {
            UUID cellId = UUID.fromString(idString);
            if (addTags.length > 0) applyAddTags(cellId, addTags);
            if (removeTags.length > 0) applyRemoveTags(cellId, removeTags);
        }
        return ReplayResult.REPLAYED;
    }

    /**
     * Derived cell metadata written by the summarizer (document_type, topic/title, valid_from,
     * extra tags). Absent payload fields leave the column unchanged.
     */
    private ReplayResult replayUpdateCellMeta(JsonNode p) {
        UUID cellId = uuid(p, "cell_id");
        OffsetDateTime validFrom = p.hasNonNull("valid_from")
                ? OffsetDateTime.parse(p.get("valid_from").asText()) : null;
        dsl.execute("""
                UPDATE cells
                SET document_type = COALESCE(?, document_type),
                    topic = COALESCE(?, topic),
                    valid_from = COALESCE(?::timestamptz, valid_from)
                WHERE id = ?
                  AND valid_until IS NULL
                """, text(p, "document_type"), text(p, "topic"), validFrom, cellId);
        String[] addTags = arrayField(p, "add_tags");
        if (addTags.length > 0) applyAddTags(cellId, addTags);
        return ReplayResult.REPLAYED;
    }

    /** Mirrors WriteToolRepository.addTags (union, de-duplicated; array_cat tolerates NULL tags). */
    private void applyAddTags(UUID cellId, String[] tags) {
        dsl.execute("""
                UPDATE cells
                SET tags = (SELECT array_agg(DISTINCT t) FROM unnest(array_cat(tags, ?::text[])) t)
                WHERE id = ?
                  AND valid_until IS NULL
                """, tags, cellId);
    }

    /** Mirrors WriteToolRepository.removeTags. */
    private void applyRemoveTags(UUID cellId, String[] tags) {
        dsl.execute("""
                UPDATE cells
                SET tags = CASE
                    WHEN tags IS NULL THEN NULL
                    ELSE array(SELECT t FROM unnest(tags) t WHERE t <> ALL(?::text[]))
                END
                WHERE id = ?
                  AND valid_until IS NULL
                """, tags, cellId);
    }

    private UUID findLocalOpForCell(UUID cellId) {
        var row = dsl.fetchOne(
                "SELECT op_id FROM ops_log WHERE op_type = 'add_cell' AND payload->>'cell_id' = ? LIMIT 1",
                cellId.toString());
        return row == null ? null : row.get("op_id", UUID.class);
    }

    private static UUID uuid(JsonNode p, String field) {
        return p.hasNonNull(field) ? UUID.fromString(p.get(field).asText()) : null;
    }

    private static String text(JsonNode p, String field) {
        return p.hasNonNull(field) ? p.get(field).asText() : null;
    }

    private static String textOrDefault(JsonNode p, String field, String def) {
        return p.hasNonNull(field) ? p.get(field).asText() : def;
    }

    private static Integer intField(JsonNode p, String field) {
        return p.hasNonNull(field) ? p.get(field).asInt() : null;
    }

    private static String[] arrayField(JsonNode p, String field) {
        if (!p.hasNonNull(field)) return new String[0];
        JsonNode arr = p.get(field);
        if (!arr.isArray()) return new String[0];
        String[] result = new String[arr.size()];
        for (int i = 0; i < arr.size(); i++) result[i] = arr.get(i).asText();
        return result;
    }

    private static String[] arrayFromNode(JsonNode arr) {
        if (!arr.isArray()) return new String[0];
        String[] result = new String[arr.size()];
        for (int i = 0; i < arr.size(); i++) result[i] = arr.get(i).asText();
        return result;
    }

    /** Union of existing and new tags, order-preserving and de-duplicated. */
    private static String[] mergeTags(String[] oldTags, String[] newTags) {
        if (newTags == null || newTags.length == 0) return oldTags;
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        if (oldTags != null) merged.addAll(java.util.Arrays.asList(oldTags));
        merged.addAll(java.util.Arrays.asList(newTags));
        return merged.toArray(String[]::new);
    }

    private static String[] appendIfMissing(String[] tags, String tag) {
        if (tags == null) return new String[] {tag};
        for (String t : tags) {
            if (tag.equals(t)) return tags;
        }
        String[] result = java.util.Arrays.copyOf(tags, tags.length + 1);
        result[tags.length] = tag;
        return result;
    }
}
