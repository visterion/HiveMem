package com.hivemem.tools.write;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure schema unit test for {@link SavedSearchesToolHandler} — no Spring context, no DB.
 * Verifies the merged action-multiplexed tool advertises the consolidated parameter set
 * (action/name/filter/id) and marks {@code action} as required.
 */
class SavedSearchesToolHandlerTest {

    private SavedSearchesToolHandler handler() {
        return new SavedSearchesToolHandler(null, new tools.jackson.databind.ObjectMapper());
    }

    @Test
    void nameIsSavedSearches() {
        assertThat(handler().name()).isEqualTo("saved_searches");
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputSchemaExposesActionMultiplexedParameters() {
        Map<String, Object> schema = handler().inputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties).containsKeys("action", "name", "filter", "id");

        List<String> required = (List<String>) schema.get("required");
        assertThat(required).contains("action");
    }
}
