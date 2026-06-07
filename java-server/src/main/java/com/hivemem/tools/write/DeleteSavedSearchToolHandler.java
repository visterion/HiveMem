package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.savedsearch.SavedSearchRepository;
import com.hivemem.write.WriteArgumentParser;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@Order(41)
public class DeleteSavedSearchToolHandler implements ToolHandler {

    private final SavedSearchRepository savedSearchRepository;

    public DeleteSavedSearchToolHandler(SavedSearchRepository savedSearchRepository) {
        this.savedSearchRepository = savedSearchRepository;
    }

    @Override
    public String name() {
        return "delete_saved_search";
    }

    @Override
    public String description() {
        return "Soft-delete a saved search by id. Only the owner can delete their own saved searches.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredUuid("id", "UUID of the saved search to delete")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        UUID id = WriteArgumentParser.requiredUuid(arguments, "id");
        boolean deleted = savedSearchRepository.delete(id, principal.name());
        return Map.of("id", id.toString(), "deleted", deleted);
    }
}
