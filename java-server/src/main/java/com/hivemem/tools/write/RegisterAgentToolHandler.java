package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import com.hivemem.write.WriteToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Order(29)
public class RegisterAgentToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public RegisterAgentToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
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
        String autonomyJson = arguments != null && arguments.hasNonNull("autonomy") ? arguments.get("autonomy").toString() : null;
        String modelRoutingJson = arguments != null && arguments.hasNonNull("model_routing") ? arguments.get("model_routing").toString() : null;
        java.util.List<String> tools = WriteArgumentParser.optionalTextList(arguments, "tools");
        return writeToolService.registerAgent(name, focus, autonomyJson, schedule, modelRoutingJson, tools);
    }
}
