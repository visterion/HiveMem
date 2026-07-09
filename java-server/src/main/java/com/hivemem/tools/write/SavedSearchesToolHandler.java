package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
@Order(39)
public class SavedSearchesToolHandler implements ToolHandler {

    private static final String[] ACTIONS = {"save", "delete", "list"};

    private final SavedSearchRepository savedSearchRepository;
    private final ObjectMapper objectMapper;

    public SavedSearchesToolHandler(SavedSearchRepository savedSearchRepository, ObjectMapper objectMapper) {
        this.savedSearchRepository = savedSearchRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "saved_searches";
    }

    @Override
    public String description() {
        return "Manage the calling user's saved searches. "
                + "action=save (requires name; optional filter JSON, upsert by name) | "
                + "action=delete (requires id; soft-delete, owner-scoped) | "
                + "action=list (returns id, name, filter, created_at). "
                + "Return shape depends on action.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredEnumString("action", "Operation to perform", ACTIONS)
                .optionalString("name", "Saved-search name (required for action=save; upsert by name)")
                .optionalString("filter", "JSON object/string describing filter state (action=save; defaults to {})")
                .optionalUuid("id", "Saved-search UUID (required for action=delete)")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String action = WriteArgumentParser.requiredText(arguments, "action");
        switch (action) {
            case "save": {
                String name = WriteArgumentParser.requiredText(arguments, "name");
                String filterJson = "{}";
                if (arguments != null && arguments.hasNonNull("filter")) {
                    JsonNode filterNode = arguments.get("filter");
                    if (filterNode.isTextual()) {
                        filterJson = filterNode.asText();
                        try {
                            objectMapper.readTree(filterJson);
                        } catch (Exception e) {
                            throw new IllegalArgumentException("filter must be valid JSON");
                        }
                    } else {
                        filterJson = filterNode.toString();
                    }
                }
                return savedSearchRepository.save(principal.name(), name, filterJson);
            }
            case "delete": {
                UUID id = WriteArgumentParser.requiredUuid(arguments, "id");
                boolean deleted = savedSearchRepository.delete(id, principal.name());
                return Map.of("id", id.toString(), "deleted", deleted);
            }
            case "list":
                return savedSearchRepository.listByOwner(principal.name());
            default:
                throw new IllegalArgumentException("unknown action: " + action + " (expected save|delete|list)");
        }
    }
}
