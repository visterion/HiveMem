package com.hivemem.queen;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps Vistierie runs JSON into shaped views; filters to Queen/Bee agents. */
@Service
public class QueenRunsService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VistierieRunsClient client;

    public QueenRunsService(VistierieRunsClient client) {
        this.client = client;
    }

    public QueenRunView.RunList listRuns(int limit, int offset) {
        boolean costAvailable = client.costAvailable();
        JsonNode body = client.listRuns(limit, offset);
        JsonNode itemsNode = body.has("items") ? body.get("items") : body;
        List<QueenRunView.RunSummary> items = new ArrayList<>();
        if (itemsNode != null && itemsNode.isArray()) {
            for (JsonNode r : itemsNode) {
                String agent = text(r, "agent");
                if (!AgentDefinitions.QUEEN_NAME.equals(agent)
                        && !AgentDefinitions.BEE_NAME.equals(agent)
                        && !AgentDefinitions.ARCHIVIST_NAME.equals(agent)) {
                    continue;
                }
                items.add(new QueenRunView.RunSummary(
                        text(r, "id"), agent, text(r, "trigger"), text(r, "status"),
                        text(r, "started_at"), text(r, "finished_at"),
                        longOrNull(r, "duration_ms"),
                        intOrNull(r, "llm_calls_count"),
                        longOrNull(r, "total_cost_micros")));
            }
        }
        int total = body.has("total") ? body.get("total").asInt() : items.size();
        return new QueenRunView.RunList(items, total, costAvailable);
    }

    public QueenRunView.RunDetail runDetail(String runId) {
        Map<String, Object> run = asMap(client.getRun(runId));
        JsonNode eventsNode = client.getRunEvents(runId);
        List<Map<String, Object>> events = new ArrayList<>();
        if (eventsNode != null && eventsNode.isArray()) {
            for (JsonNode e : eventsNode) {
                events.add(asMap(e));
            }
        }
        return new QueenRunView.RunDetail(run, events);
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static Long longOrNull(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asLong();
    }

    private static Integer intOrNull(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asInt();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(JsonNode n) {
        if (n == null || n.isNull()) return new LinkedHashMap<>();
        return MAPPER.convertValue(n, Map.class);
    }
}
