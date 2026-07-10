package com.hivemem.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpResponse(
        String jsonrpc,
        // JSON-RPC 2.0 requires the id field in every response, even when null.
        @JsonInclude(JsonInclude.Include.ALWAYS) Object id,
        Object result,
        McpError error
) {
    public static McpResponse success(Object id, Object result) {
        return new McpResponse("2.0", id, result, null);
    }

    public static McpResponse methodNotFound(Object id, String method) {
        return new McpResponse("2.0", id, null, new McpError(-32601, "Method not found: " + method, null));
    }

    public static McpResponse invalidParams(Object id, String message) {
        return new McpResponse("2.0", id, null, new McpError(-32602, message, null));
    }

    public static McpResponse toolNotFound(Object id, String toolName) {
        // Per MCP, an unknown tool in tools/call is an invalid-params error (-32602),
        // not method-not-found: the method (tools/call) itself exists.
        return new McpResponse("2.0", id, null, new McpError(-32602, "Unknown tool: " + toolName, null));
    }

    public static McpResponse forbidden(Object id, String toolName) {
        return new McpResponse("2.0", id, null, new McpError(-32003, "Tool not permitted: " + toolName, null));
    }

    public static McpResponse toolResult(Object id, String textContent) {
        return success(id, Map.of("content", List.of(Map.of("type", "text", "text", textContent))));
    }

    /** Tool execution failure, reported as an isError tool result (not a protocol error). */
    public static McpResponse toolExecutionError(Object id, String textContent) {
        return success(id, Map.of(
                "content", List.of(Map.of("type", "text", "text", textContent)),
                "isError", true));
    }

    public static McpResponse internalError(Object id, String message) {
        return new McpResponse("2.0", id, null, new McpError(-32603, message, null));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record McpError(int code, String message, Object data) {
    }
}

record McpTool(
        String name,
        String description,
        Map<String, Object> inputSchema
) {
    static McpTool of(String name, String description, Map<String, Object> inputSchema) {
        return new McpTool(name, description, inputSchema);
    }
}
