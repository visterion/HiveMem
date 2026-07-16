package com.hivemem.tools.read;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.queen.QueenRepository;
import com.hivemem.write.WriteArgumentParser;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Admin-only view of the inbox-archivist's moves + skips (before→after + reason), newest first. */
@Component
@Order(22)
public class ArchivistLogToolHandler implements ToolHandler {

    private final QueenRepository repo;
    private final ObjectMapper mapper;

    public ArchivistLogToolHandler(QueenRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Override
    public String name() { return "archivist_log"; }

    @Override
    public String description() {
        return "Inbox-archivist move log (reclassify + skip, before→after + reason), admin-only, newest first.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .optionalIntegerInRange("limit", "Max entries to return (default 100).", 1, 500)
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        int limit = boundedInt(arguments, "limit", 100, 1, 500);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> row : repo.findArchivistLog(limit)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("op_type", row.get("op_type"));
            entry.put("at", row.get("at"));
            JsonNode payload = mapper.readTree(String.valueOf(row.get("payload")));
            payload.propertyStream().forEach(e -> entry.put(e.getKey(),
                    e.getValue().isNull() ? null : e.getValue().asString()));
            entries.add(entry);
        }
        return Map.of("entries", entries);
    }

    /** Enforces the bounds the input schema advertises. */
    private static int boundedInt(JsonNode arguments, String field, int defaultValue, int min, int max) {
        Integer value = WriteArgumentParser.optionalInteger(arguments, field);
        if (value == null) {
            return defaultValue;
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException("Invalid " + field + " (must be " + min + "-" + max + ")");
        }
        return value;
    }
}
