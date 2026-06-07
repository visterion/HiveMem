package com.hivemem.savedsearch;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class SavedSearchRepository {

    private final DSLContext dsl;

    public SavedSearchRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Upsert a saved search by (owner, name): soft-delete any existing row with the
     * same (owner, name), then insert a fresh row. Returns the new row fields.
     */
    @Transactional
    public Map<String, Object> save(String owner, String name, String filterJson) {
        // Soft-delete existing active row with same name for this owner
        dsl.execute(
                "UPDATE saved_searches SET valid_until = now() " +
                "WHERE owner = ? AND name = ? AND valid_until IS NULL",
                owner, name);

        // Insert new row; bind filter as a JSON string cast to jsonb
        var row = dsl.fetchOne(
                "INSERT INTO saved_searches (owner, name, filter) " +
                "VALUES (?, ?, ?::jsonb) " +
                "RETURNING id, name, filter::text AS filter, created_at",
                owner, name, filterJson);

        return toMap(row);
    }

    /**
     * Return all active rows for this owner (valid_until IS NULL), ordered by name.
     */
    public List<Map<String, Object>> listByOwner(String owner) {
        var result = dsl.fetch(
                "SELECT id, name, filter::text AS filter, created_at " +
                "FROM saved_searches " +
                "WHERE owner = ? AND valid_until IS NULL " +
                "ORDER BY name",
                owner);

        return result.stream().map(this::toMap).toList();
    }

    /**
     * Soft-delete a saved search by id, scoped to the owner (owner safety).
     * Returns true if a row was updated, false if not found or not owned.
     */
    @Transactional
    public boolean delete(UUID id, String owner) {
        int updated = dsl.execute(
                "UPDATE saved_searches SET valid_until = now() " +
                "WHERE id = ? AND owner = ? AND valid_until IS NULL",
                id, owner);
        return updated > 0;
    }

    private Map<String, Object> toMap(org.jooq.Record row) {
        if (row == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        Object id = row.get("id");
        m.put("id", id == null ? null : id.toString());
        m.put("name", row.get("name", String.class));
        m.put("filter", row.get("filter", String.class));
        Object createdAt = row.get("created_at");
        m.put("created_at", createdAt == null ? null : createdAt.toString());
        return m;
    }
}
