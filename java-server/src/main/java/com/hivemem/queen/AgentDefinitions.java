package com.hivemem.queen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned, as-code definitions for the Queen + isolated-cell-Bee agents, built as
 * the JSON bodies Vistierie's POST/PUT /agents expects. Pure functions of QueenProperties.
 */
public class AgentDefinitions {

    public static final String BEE_NAME = "isolated-cell-bee";
    public static final String QUEEN_NAME = "queen";

    private static final String BEE_SYSTEM = """
            You are an isolated-cell Bee in HiveMem, a personal knowledge graph.
            You receive ONE cell that currently has no links (tunnels) to other cells.
            Your job: read it, search for semantically similar cells, and propose only
            GENUINE relationships to other cells. Relations must be one of:
            related_to, builds_on, contradicts, refines.

            Steps:
            1. Call read_cell with the given cell_id to read its content.
            2. Call search_similar_cells to get candidate neighbours.
            3. For each candidate that is truly related, add a proposal with the best-fitting
               relation and a one-sentence note explaining the link. Skip weak/uncertain matches.

            Prefer proposing nothing over proposing noise. Output ONLY the structured result.
            """;

    private static final String QUEEN_SYSTEM = """
            You are the Queen of a HiveMem knowledge hive. On each run:
            1. Call find_isolated_cells to get cells that have no links yet.
            2. For each returned cell_id, call dispatch_bee with input {"cell_id": "<id>"}.
            3. Collect every Bee's proposals. For each proposal, set from_cell to the Bee's
               input cell_id and copy to_cell, relation, note from the Bee output.
            Return all collected proposals plus the count of cells you surveyed.
            """;

    private final QueenProperties props;

    public AgentDefinitions(QueenProperties props) {
        this.props = props;
    }

    private String toolUrl(String tool) {
        return props.getHivememBaseUrl() + "/vistierie/tools/" + tool;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> stringProp() {
        return Map.of("type", "string");
    }

    private Map<String, Object> httpTool(String name, String description, Map<String, Object> inputSchema) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("input_schema", inputSchema);
        t.put("webhook_url", toolUrl(name));
        t.put("webhook_timeout_seconds", 10);
        return t;
    }

    public Map<String, Object> isolatedCellBee() {
        Map<String, Object> readCellIn = objectSchema(
                Map.of("cell_id", stringProp()), List.of("cell_id"));
        Map<String, Object> searchIn = objectSchema(
                Map.of("cell_id", stringProp(), "limit", Map.of("type", "integer")),
                List.of("cell_id"));

        Map<String, Object> proposalItem = objectSchema(
                Map.of(
                        "to_cell", stringProp(),
                        "relation", Map.of("type", "string",
                                "enum", List.of("related_to", "builds_on", "contradicts", "refines")),
                        "note", stringProp()),
                List.of("to_cell", "relation"));
        Map<String, Object> outputSchema = objectSchema(
                Map.of(
                        "cell_id", stringProp(),
                        "proposals", Map.of("type", "array", "items", proposalItem)),
                List.of("cell_id", "proposals"));

        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", BEE_NAME);
        def.put("system_prompt", BEE_SYSTEM);
        def.put("model_purpose", "bee_link");
        def.put("tools", List.of(
                httpTool("read_cell", "Read a HiveMem cell by id", readCellIn),
                httpTool("search_similar_cells", "Find cells semantically similar to a cell", searchIn)));
        def.put("output_schema", outputSchema);
        def.put("max_turns", 10);
        def.put("max_run_seconds", 60);
        def.put("webhook_token", props.getWebhookToken());
        return def;
    }

    public Map<String, Object> queen() {
        Map<String, Object> findIn = objectSchema(
                Map.of("limit", Map.of("type", "integer")), List.of());

        Map<String, Object> dispatch = new LinkedHashMap<>();
        dispatch.put("name", "dispatch_bee");
        dispatch.put("description", "Dispatch an isolated-cell Bee for one cell");
        dispatch.put("input_schema", objectSchema(Map.of("cell_id", stringProp()), List.of("cell_id")));
        dispatch.put("type", "subagent");
        dispatch.put("target_agent", BEE_NAME);

        Map<String, Object> proposalItem = objectSchema(
                Map.of(
                        "from_cell", stringProp(),
                        "to_cell", stringProp(),
                        "relation", Map.of("type", "string",
                                "enum", List.of("related_to", "builds_on", "contradicts", "refines")),
                        "note", stringProp()),
                List.of("from_cell", "to_cell", "relation"));
        Map<String, Object> outputSchema = objectSchema(
                Map.of(
                        "proposals", Map.of("type", "array", "items", proposalItem),
                        "surveyed", Map.of("type", "integer")),
                List.of("proposals", "surveyed"));

        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", QUEEN_NAME);
        def.put("system_prompt", QUEEN_SYSTEM);
        def.put("model_purpose", "queen_survey");
        def.put("tools", List.of(
                httpTool("find_isolated_cells", "List cells that have no tunnels yet", findIn),
                dispatch));
        def.put("output_schema", outputSchema);
        def.put("max_turns", 40);
        def.put("max_run_seconds", 300);
        def.put("webhook_token", props.getWebhookToken());
        def.put("schedule", props.getSchedule());
        def.put("completion_webhook", props.getHivememBaseUrl() + "/vistierie/runs/done");
        def.put("completion_webhook_token", props.getCompletionWebhookToken());
        return def;
    }
}
