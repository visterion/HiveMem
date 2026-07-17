package com.hivemem.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.ToolPermissionService;
import com.hivemem.embedding.EmbeddingMigrationService;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Single dispatch path for MCP tool calls, shared by the machine endpoint (/mcp) and
 * the human endpoint (/api/tools/call). Holds the authorization gates that used to sit
 * inside {@link McpController}: role permission, realm denial, the embedding-migration
 * gate, read-argument rewriting and response filtering.
 *
 * <p>No caller may reach {@link ToolRegistry} directly — that would bypass all of the
 * above.
 */
@Component
public class ToolCallDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ToolCallDispatcher.class);

    /** Tools that require live embeddings and must be gated while re-encoding runs. */
    private static final Set<String> EMBEDDING_TOOLS =
            Set.of("search", "entity_overview", "search_kg", "data_quality_report", "add_cell");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolRegistry toolRegistry;
    private final ToolPermissionService toolPermissionService;
    private final EmbeddingMigrationService embeddingMigrationService;

    public ToolCallDispatcher(ToolRegistry toolRegistry, ToolPermissionService toolPermissionService,
                              EmbeddingMigrationService embeddingMigrationService) {
        this.toolRegistry = toolRegistry;
        this.toolPermissionService = toolPermissionService;
        this.embeddingMigrationService = embeddingMigrationService;
    }

    public ResponseEntity<McpResponse> dispatch(AuthPrincipal principal, Object requestId, JsonNode params) {
        if (params == null || !params.hasNonNull("name")) {
            return ResponseEntity.ok(
                    McpResponse.invalidParams(requestId, "Missing tool name"));
        }

        String toolName = params.get("name").asText();
        if (toolName.isBlank()) {
            return ResponseEntity.ok(
                    McpResponse.invalidParams(requestId, "Missing tool name"));
        }
        if (!toolPermissionService.isAllowed(principal.role(), toolName)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    McpResponse.forbidden(requestId, toolName));
        }
        Optional<String> realmDenied =
                toolPermissionService.realmDenial(principal, toolName, params.path("arguments"));
        if (realmDenied.isPresent()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    McpResponse.forbidden(requestId, toolName));
        }

        if (EMBEDDING_TOOLS.contains(toolName) && embeddingMigrationService.isReencodingActive()) {
            String progress = embeddingMigrationService.getProgress().orElse("unknown");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    McpResponse.internalError(requestId,
                            "Embedding re-encoding in progress (" + progress + "). This tool depends on embeddings and is temporarily unavailable."));
        }

        return toolRegistry.resolve(toolName)
                .map(handler -> {
                    try {
                        JsonNode callArgs = toolPermissionService.rewriteReadArgs(
                                principal, toolName, params.path("arguments"));
                        Object result = handler.call(principal, callArgs);
                        JsonNode filtered = toolPermissionService.filterReadResponse(
                                principal, toolName, callArgs, MAPPER.valueToTree(result));
                        String json = MAPPER.writeValueAsString(filtered);
                        return ResponseEntity.ok(
                                McpResponse.toolResult(requestId, json));
                    } catch (IllegalArgumentException e) {
                        // Argument validation failures are protocol-level invalid params.
                        return ResponseEntity.ok(
                                McpResponse.invalidParams(requestId, messageOf(e)));
                    } catch (Exception e) {
                        // Execution failures are reported inside the tool result
                        // (isError: true) so the model can see and react to them.
                        log.error("Tool call failed: {}", toolName, e);
                        return ResponseEntity.ok(
                                McpResponse.toolExecutionError(requestId, messageOf(e)));
                    }
                })
                .orElseGet(() -> ResponseEntity.ok(McpResponse.toolNotFound(requestId, toolName)));
    }

    private static String messageOf(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
