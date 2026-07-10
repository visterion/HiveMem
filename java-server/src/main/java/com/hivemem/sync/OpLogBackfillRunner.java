package com.hivemem.sync;

import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class OpLogBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(OpLogBackfillRunner.class);

    /**
     * Column-name → op-payload-key mapping per table. OpReplayer reads the op-payload key
     * contract the live write path emits ({@code cell_id}, {@code from_cell_id}, {@code agent_id},
     * …), not raw column names — backfilled ops must use the same keys or a fresh peer replays
     * none of the pre-existing data (every insert sees a NULL id). Columns not listed here pass
     * through unchanged; extra columns are ignored by the replayer.
     */
    private static final Map<String, Map<String, String>> PAYLOAD_KEY_OVERRIDES = Map.of(
            "cells", Map.of("id", "cell_id", "created_by", "agent_id"),
            "tunnels", Map.of("id", "tunnel_id", "from_cell", "from_cell_id",
                    "to_cell", "to_cell_id", "created_by", "agent_id"),
            "facts", Map.of("id", "fact_id", "created_by", "agent_id"),
            "agents", Map.of(),
            "references_", Map.of("id", "reference_id"),
            "blueprints", Map.of("id", "blueprint_id", "created_by", "agent_id"),
            "agent_diary", Map.of("id", "entry_id"));

    /** Tables whose rows are versioned; only the ACTIVE revision is backfilled (replaying a
     *  closed revision as an add_* op would resurrect it as an open row on the peer). */
    private static final java.util.Set<String> VERSIONED_TABLES =
            java.util.Set.of("cells", "facts", "tunnels", "blueprints");

    private final DSLContext dsl;
    private final OpLogWriter opLogWriter;
    private final ObjectMapper objectMapper;

    public OpLogBackfillRunner(DSLContext dsl, OpLogWriter opLogWriter, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.opLogWriter = opLogWriter;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void onStartup() {
        runBackfill();
    }

    public void runBackfill() {
        long existing = dsl.fetchOne("SELECT count(*) AS c FROM ops_log").get("c", Long.class);
        if (existing > 0) {
            return;
        }

        backfillTable("cells", "add_cell");
        backfillTable("tunnels", "add_tunnel");
        backfillTable("facts", "kg_add");
        backfillTable("agents", "register_agent");
        backfillTable("references_", "add_reference");
        backfillTable("blueprints", "update_blueprint");
        backfillTable("agent_diary", "diary_write");
    }

    private void backfillTable(String tableName, String opType) {
        Map<String, String> keyOverrides = PAYLOAD_KEY_OVERRIDES.getOrDefault(tableName, Map.of());
        Result<Record> rows;
        try {
            String sql = "SELECT * FROM " + tableName;
            if (VERSIONED_TABLES.contains(tableName)) {
                sql += " WHERE valid_until IS NULL";
            }
            rows = dsl.fetch(sql);
        } catch (Exception e) {
            log.error("Backfill failed for table '{}' (op_type='{}')", tableName, opType, e);
            throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
        }
        for (Record row : rows) {
            Map<String, Object> payload = new LinkedHashMap<>();
            for (var f : row.fields()) {
                if ("embedding".equals(f.getName())) continue;
                Object value = row.get(f);
                if (value == null) continue;
                String key = keyOverrides.getOrDefault(f.getName(), f.getName());
                payload.put(key, toPayloadValue(f, value));
            }
            opLogWriter.append(opType, payload);
        }
    }

    private Object toPayloadValue(Field<?> field, Object value) {
        if (value instanceof String[] arr) return Arrays.asList(arr);
        if (value instanceof UUID[] arr) return Arrays.stream(arr).map(UUID::toString).toList();
        if (value instanceof Object[] arr) {
            return Arrays.stream(arr).map(e -> e instanceof UUID u ? u.toString() : e).toList();
        }
        if (value instanceof UUID uuid) return uuid.toString();
        if (value instanceof org.jooq.JSONB jsonb) {
            try {
                return objectMapper.readTree(jsonb.data());
            } catch (Exception e) {
                return jsonb.data();
            }
        }
        if (value instanceof String s && "jsonb".equalsIgnoreCase(field.getDataType().getCastTypeName())) {
            try {
                return objectMapper.readTree(s);
            } catch (Exception e) {
                return s;
            }
        }
        return value;
    }
}
