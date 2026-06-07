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

@Component
@Order(38)
public class SaveSearchToolHandler implements ToolHandler {

    private final SavedSearchRepository savedSearchRepository;

    public SaveSearchToolHandler(SavedSearchRepository savedSearchRepository) {
        this.savedSearchRepository = savedSearchRepository;
    }

    @Override
    public String name() {
        return "save_search";
    }

    @Override
    public String description() {
        return "Persist the current Scans filter as a named saved search for the calling user. " +
               "If a saved search with the same name already exists for this user it is replaced (upsert by name).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("name", "Human-readable name for this saved search")
                .optionalString("filter", "JSON object describing the filter state (serialized by the UI); defaults to empty object")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String name = WriteArgumentParser.requiredText(arguments, "name");

        // filter is passed as a JSON string from the UI; if omitted, default to "{}"
        String filterJson = "{}";
        if (arguments != null && arguments.hasNonNull("filter")) {
            JsonNode filterNode = arguments.get("filter");
            // Accept both a JSON string ("{}") and an inline object ({}); normalise to string
            if (filterNode.isTextual()) {
                filterJson = filterNode.asText();
            } else {
                filterJson = filterNode.toString();
            }
        }

        return savedSearchRepository.save(principal.name(), name, filterJson);
    }
}
