package com.hivemem.mcp;

import com.hivemem.auth.ToolPermissionService;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.embedding.EmbeddingMigrationService;
import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import org.mockito.Mockito;

/**
 * Builds a {@link ToolCallDispatcher} wired against a minimal in-memory {@link ToolRegistry}
 * for unit tests, without needing the full Spring context.
 */
final class TestDispatchers {

    private TestDispatchers() {
    }

    /** A dispatcher whose registry only knows the given tool names, each a stub no-op handler. */
    static ToolCallDispatcher withTools(String... toolNames) {
        List<ToolHandler> handlers = List.of(toolNames).stream()
                .map(TestDispatchers::stubHandler)
                .toList();
        ToolRegistry registry = new ToolRegistry(handlers);
        ToolPermissionService permissionService = new ToolPermissionService();
        EmbeddingMigrationService embeddingMigrationService = Mockito.mock(EmbeddingMigrationService.class);
        Mockito.when(embeddingMigrationService.isReencodingActive()).thenReturn(false);
        Mockito.when(embeddingMigrationService.getProgress()).thenReturn(Optional.empty());
        return new ToolCallDispatcher(registry, permissionService, embeddingMigrationService);
    }

    private static ToolHandler stubHandler(String toolName) {
        return new ToolHandler() {
            @Override
            public String name() {
                return toolName;
            }

            @Override
            public String description() {
                return "stub handler for " + toolName;
            }

            @Override
            public Object call(AuthPrincipal principal, JsonNode arguments) {
                return java.util.Map.of("type", "json", "ok", true);
            }
        };
    }
}
