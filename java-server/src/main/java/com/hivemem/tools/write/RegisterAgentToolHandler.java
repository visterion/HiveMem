package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import com.hivemem.write.WriteToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Order(31)
public class RegisterAgentToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;
    private final ObjectMapper objectMapper;

    public RegisterAgentToolHandler(WriteToolService writeToolService, ObjectMapper objectMapper) {
        this.writeToolService = writeToolService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "register_agent";
    }

    @Override
    public String description() {
        return "Register or update an agent in the fleet.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("name", "Unique agent name")
                .requiredString("focus", "Agent focus description")
                .optionalString("schedule", "Cron or interval schedule")
                .optionalString("autonomy", "Autonomy config as JSON object")
                .optionalString("model_routing", "Model routing config as JSON object")
                .optionalStringList("tools", "List of tool names the agent may use")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String name = WriteArgumentParser.requiredText(arguments, "name");
        String focus = WriteArgumentParser.requiredText(arguments, "focus");
        String schedule = WriteArgumentParser.optionalText(arguments, "schedule");
        String autonomyJson = optionalJson(arguments, "autonomy");
        String modelRoutingJson = optionalJson(arguments, "model_routing");
        java.util.List<String> tools = WriteArgumentParser.optionalTextList(arguments, "tools");
        return writeToolService.registerAgent(name, focus, autonomyJson, schedule, modelRoutingJson, tools);
    }

    /**
     * The schema declares these fields as "JSON object as string" (TextNode). A
     * TextNode's toString() is the quoted/escaped literal, which `?::jsonb` would
     * store as a jsonb string scalar — so unwrap textual nodes via asText() after
     * validating, and serialize non-textual nodes as-is.
     */
    private String optionalJson(JsonNode arguments, String field) {
        if (arguments == null || !arguments.hasNonNull(field)) {
            return null;
        }
        JsonNode node = arguments.get(field);
        if (node.isTextual()) {
            String json = node.asText();
            try {
                objectMapper.readTree(json);
            } catch (Exception e) {
                throw new IllegalArgumentException(field + " must be valid JSON");
            }
            return json;
        }
        return node.toString();
    }
}
