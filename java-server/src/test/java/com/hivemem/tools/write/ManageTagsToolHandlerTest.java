package com.hivemem.tools.write;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure schema unit test for {@link ManageTagsToolHandler} — no Spring context, no DB.
 * Verifies the merged tool advertises the new consolidated parameter set and none of
 * the removed per-tool parameter names (add_tags/remove_tags/bulk_tag legacy shapes).
 */
class ManageTagsToolHandlerTest {

    @Test
    void nameIsManageTags() {
        assertThat(new ManageTagsToolHandler(null).name()).isEqualTo("manage_tags");
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputSchemaExposesConsolidatedParameters() {
        Map<String, Object> schema = new ManageTagsToolHandler(null).inputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties)
                .containsKeys("cell_ids", "where", "add", "remove", "confirm")
                .doesNotContainKeys("cell_id", "add_tags", "remove_tags", "tags");
    }
}
