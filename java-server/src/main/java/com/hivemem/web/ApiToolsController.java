package com.hivemem.web;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.McpResponse;
import com.hivemem.mcp.ToolCallDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Human-facing twin of /mcp: same JSON-RPC tools/call payload, same dispatcher, but the
 * caller proves identity with an Access JWT or a session cookie instead of a bearer
 * token. HumanAuthFilter has already populated the principal when this runs.
 */
@RestController
public class ApiToolsController {

    private final ToolCallDispatcher toolCallDispatcher;

    public ApiToolsController(ToolCallDispatcher toolCallDispatcher) {
        this.toolCallDispatcher = toolCallDispatcher;
    }

    @PostMapping("/api/tools/call")
    public ResponseEntity<McpResponse> call(@RequestBody JsonNode request, HttpServletRequest httpRequest) {
        AuthPrincipal principal =
                (AuthPrincipal) httpRequest.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
        return toolCallDispatcher.dispatch(principal, request.path("id").asText(),
                request.path("params"));
    }
}
