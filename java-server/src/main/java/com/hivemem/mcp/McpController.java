package com.hivemem.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.ToolPermissionService;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private static final String SESSION_HEADER = "Mcp-Session-Id";

    /**
     * MCP protocol versions this server can serve. 2025-06-18 is the latest (and default);
     * 2025-03-26 (Streamable HTTP, pre-batching-removal) is also compatible since this server
     * never implemented JSON-RPC batching regardless of declared version. A client requesting
     * one of these gets it echoed back; anything else falls back to the latest.
     */
    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26");
    private static final String LATEST_PROTOCOL_VERSION = SUPPORTED_PROTOCOL_VERSIONS.get(0);

    /** SSE emitter lifetime; bounds how long a dead client connection can linger. */
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolRegistry toolRegistry;
    private final ToolPermissionService toolPermissionService;
    private final ToolCallDispatcher toolCallDispatcher;

    public McpController(ToolRegistry toolRegistry, ToolPermissionService toolPermissionService,
                         ToolCallDispatcher toolCallDispatcher) {
        this.toolRegistry = toolRegistry;
        this.toolPermissionService = toolPermissionService;
        this.toolCallDispatcher = toolCallDispatcher;
    }

    /**
     * SSE endpoint for server-initiated messages (MCP Streamable HTTP spec).
     * We don't send server-initiated messages, but Claude Code requires this
     * endpoint to exist and return 200 with text/event-stream. The emitter
     * stays open until the client disconnects.
     */
    @GetMapping(value = "/mcp", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (java.io.IOException ignored) {
            // client already disconnected
        }
        emitter.onTimeout(emitter::complete);
        return emitter;
    }

    /**
     * Session termination (MCP Streamable HTTP spec). No-op for stateless server.
     */
    @DeleteMapping(value = "/mcp")
    public ResponseEntity<Void> deleteSession() {
        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/mcp")
    public ResponseEntity<?> handle(@RequestBody JsonNode body, HttpServletRequest servletRequest) {
        if (body != null && body.isArray()) {
            // Protocol 2025-03-26 permits a client to send a JSON-RPC batch (a top-level
            // array). This server never implemented batching; reject it with a proper
            // JSON-RPC error instead of letting a raw Jackson bind failure surface.
            return ResponseEntity.ok(McpResponse.invalidRequest(null,
                    "Invalid Request: JSON-RPC batching is not supported"));
        }
        McpRequest request;
        try {
            request = MAPPER.treeToValue(body, McpRequest.class);
        } catch (Exception e) {
            // A syntactically-valid JSON body that doesn't fit the McpRequest shape (e.g. wrong
            // field types) previously escaped as a raw Jackson exception -> HTTP 500. Return a
            // proper JSON-RPC error instead (Spring's own binder gave 400 for this before commit
            // ca0fc38 introduced the manual treeToValue call).
            log.warn("MCP request body failed to bind to McpRequest: {}", e.getMessage());
            return ResponseEntity.ok(McpResponse.invalidRequest(null, "Invalid Request: " + e.getMessage()));
        }
        log.info("MCP request: method={} id={} accept={} content-type={}",
                request.method(), request.id(),
                servletRequest.getHeader("Accept"),
                servletRequest.getHeader("Content-Type"));
        AuthPrincipal principal = (AuthPrincipal) servletRequest.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            return ResponseEntity.badRequest().body(
                    McpResponse.invalidParams(request.id(), "Missing authenticated principal"));
        }

        String method = request.method();
        if (method == null || method.isBlank()) {
            return ResponseEntity.ok(McpResponse.methodNotFound(request.id(), method));
        }

        // Notifications (no id) get 202 Accepted with no body per MCP Streamable HTTP spec.
        if (method.startsWith("notifications/")) {
            return ResponseEntity.accepted().build();
        }

        return switch (method) {
            // Protocol 2025-06-18 removed JSON-RPC batching, which matches this
            // server's single-request handling (batch bodies were never supported).
            case "initialize" -> ResponseEntity.ok()
                    .header(SESSION_HEADER, UUID.randomUUID().toString())
                    .body(McpResponse.success(
                            request.id(),
                            Map.of(
                                    "protocolVersion", negotiateProtocolVersion(request.params()),
                                    "capabilities", Map.of("tools", Map.of()),
                                    "serverInfo", Map.of("name", "hivemem", "version", "4.0.0")
                            )
                    ));
            case "ping" -> ResponseEntity.ok(McpResponse.success(request.id(), Map.of()));
            case "tools/list" -> ResponseEntity.ok(McpResponse.success(
                    request.id(),
                    Map.of("tools", toolRegistry.visibleTools(principal.role(), toolPermissionService))
            ));
            case "tools/call" -> handleToolCall(request, principal);
            default -> ResponseEntity.ok(McpResponse.methodNotFound(request.id(), method));
        };
    }

    /**
     * Echoes the client's requested {@code protocolVersion} when this server supports it;
     * otherwise (missing, blank, or an unsupported version) falls back to the latest supported
     * version. Previously this always returned the latest hardcoded string regardless of what
     * the client asked for.
     */
    private static String negotiateProtocolVersion(JsonNode params) {
        if (params == null || !params.hasNonNull("protocolVersion")) {
            return LATEST_PROTOCOL_VERSION;
        }
        String requested = params.get("protocolVersion").asText();
        return SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ? requested : LATEST_PROTOCOL_VERSION;
    }

    private ResponseEntity<McpResponse> handleToolCall(McpRequest request, AuthPrincipal principal) {
        return toolCallDispatcher.dispatch(principal, request.id(), request.params());
    }
}
