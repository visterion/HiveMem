package com.hivemem.search;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Resolves a CellSelector to active cell ids. Cheap: no embedding, tsv full-text for query. */
@Repository
public class CellSelectorRepository {

    private final DSLContext dslContext;

    public CellSelectorRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    public List<Map<String, Object>> selectIds(CellSelector sel, int limit, int offset) {
        List<Object> binds = new ArrayList<>();
        String where = buildWhere(sel, binds);
        binds.add(limit);
        binds.add(offset);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Record row : dslContext.fetch(
                "SELECT id, realm, signal, topic FROM cells WHERE " + where
                        + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                binds.toArray())) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", row.get("id", UUID.class).toString());
            r.put("realm", row.get("realm", String.class));
            r.put("signal", row.get("signal", String.class));
            r.put("topic", row.get("topic", String.class));
            results.add(r);
        }
        return results;
    }

    public int countMatches(CellSelector sel) {
        List<Object> binds = new ArrayList<>();
        String where = buildWhere(sel, binds);
        return dslContext.fetchOne("SELECT count(*)::int AS n FROM cells WHERE " + where, binds.toArray())
                .get("n", Integer.class);
    }

    public List<UUID> selectAllIds(CellSelector sel, int cap) {
        List<Object> binds = new ArrayList<>();
        String where = buildWhere(sel, binds);
        binds.add(cap);
        return dslContext.fetch(
                        "SELECT id FROM cells WHERE " + where + " ORDER BY created_at DESC, id LIMIT ?",
                        binds.toArray())
                .map(r -> r.get("id", UUID.class));
    }

    private static String buildWhere(CellSelector sel, List<Object> binds) {
        List<String> conds = new ArrayList<>();
        // Active predicate matches the active_cells view semantics.
        conds.add("(valid_until IS NULL OR valid_until > now())");
        conds.add("status = ?");
        binds.add(sel.status() == null ? "committed" : sel.status());
        if (sel.realm() != null) {
            if ("none".equals(sel.realm())) {
                conds.add("realm IS NULL");
            } else {
                conds.add("realm = ?");
                binds.add(sel.realm());
            }
        } else if (sel.realmIn() != null && !sel.realmIn().isEmpty()) {
            List<String> named = sel.realmIn().stream().filter(r -> !"none".equals(r)).toList();
            boolean withNull = sel.realmIn().contains("none");
            if (named.isEmpty()) {
                conds.add("realm IS NULL");
            } else if (withNull) {
                conds.add("(realm = ANY(?::text[]) OR realm IS NULL)");
                binds.add(named.toArray(new String[0]));
            } else {
                conds.add("realm = ANY(?::text[])");
                binds.add(named.toArray(new String[0]));
            }
        }
        if (sel.signal() != null) { conds.add("signal = ?"); binds.add(sel.signal()); }
        if (sel.topic() != null) { conds.add("topic = ?"); binds.add(sel.topic()); }
        if (sel.tags() != null && !sel.tags().isEmpty()) {
            conds.add("tags && ?::text[]");
            binds.add(sel.tags().toArray(new String[0]));
        }
        if (sel.query() != null && !sel.query().isBlank()) {
            conds.add("tsv @@ plainto_tsquery('simple', ?)");
            binds.add(sel.query());
        }
        return String.join(" AND ", conds);
    }
}
