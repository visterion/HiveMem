package com.hivemem.embedding;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EmbeddingBackfillRepository {

    private final DSLContext dsl;

    public EmbeddingBackfillRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<UUID> findCellsMissingEmbedding(int limit) {
        var rows = dsl.fetch(
                "SELECT id FROM cells WHERE embedding IS NULL AND status = 'committed' AND valid_until IS NULL "
                + "AND content IS NOT NULL AND content <> '' "
                + "ORDER BY created_at LIMIT ?", limit);
        List<UUID> ids = new ArrayList<>();
        for (Record r : rows) ids.add(r.get(0, UUID.class));
        return ids;
    }

    public Optional<Snapshot> findSnapshot(UUID id) {
        var rec = dsl.fetchOptional(
                "SELECT content, summary FROM cells WHERE id = ? AND status = 'committed' AND valid_until IS NULL", id);
        return rec.map(r -> new Snapshot(
                r.get("content", String.class),
                r.get("summary", String.class)));
    }

    public void setEmbedding(UUID id, Float[] embedding) {
        dsl.execute("UPDATE cells SET embedding = ?::vector WHERE id = ?", embedding, id);
        dsl.execute("UPDATE cells SET tags = array_remove(tags, 'embedding_pending') WHERE id = ?", id);
    }

    public List<UUID> findFactsMissingEmbedding(int limit) {
        var rows = dsl.fetch(
                "SELECT id FROM facts WHERE embedding IS NULL AND status = 'committed' "
                + "AND (valid_until IS NULL OR valid_until > now()) ORDER BY created_at LIMIT ?", limit);
        List<UUID> ids = new ArrayList<>();
        for (Record r : rows) ids.add(r.get(0, UUID.class));
        return ids;
    }

    public Optional<FactSnapshot> findFactSnapshot(UUID id) {
        var rec = dsl.fetchOptional(
                "SELECT subject, predicate, \"object\" FROM facts WHERE id = ? AND status = 'committed' "
                + "AND (valid_until IS NULL OR valid_until > now())", id);
        return rec.map(r -> new FactSnapshot(
                r.get("subject", String.class),
                r.get("predicate", String.class),
                r.get("object", String.class)));
    }

    public void setFactEmbedding(UUID id, Float[] embedding) {
        dsl.execute("UPDATE facts SET embedding = ?::vector WHERE id = ?", embedding, id);
    }

    public record Snapshot(String content, String summary) {}

    public record FactSnapshot(String subject, String predicate, String object) {}
}
