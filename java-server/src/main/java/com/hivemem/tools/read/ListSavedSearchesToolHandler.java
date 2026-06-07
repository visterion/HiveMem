package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.savedsearch.SavedSearchRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Order(18)
public class ListSavedSearchesToolHandler implements ToolHandler {

    private final SavedSearchRepository savedSearchRepository;

    public ListSavedSearchesToolHandler(SavedSearchRepository savedSearchRepository) {
        this.savedSearchRepository = savedSearchRepository;
    }

    @Override
    public String name() {
        return "list_saved_searches";
    }

    @Override
    public String description() {
        return "Return all active saved searches belonging to the calling user " +
               "(id, name, filter, created_at). Use save_search to create and delete_saved_search to remove.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.empty();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        return savedSearchRepository.listByOwner(principal.name());
    }
}
