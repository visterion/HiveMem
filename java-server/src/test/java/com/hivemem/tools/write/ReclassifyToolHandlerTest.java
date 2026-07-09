package com.hivemem.tools.write;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure schema unit test for {@link ReclassifyToolHandler} — no Spring context, no DB.
 * Verifies the merged tool advertises the consolidated parameter set (cell_ids + where)
 * and none of the removed single-cell parameter names (reclassify_cell's cell_id shape).
 */
class ReclassifyToolHandlerTest {

    @Test
    void nameIsReclassify() {
        assertThat(new ReclassifyToolHandler(null).name()).isEqualTo("reclassify");
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputSchemaExposesConsolidatedParameters() {
        Map<String, Object> schema = new ReclassifyToolHandler(null).inputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties)
                .containsKeys("cell_ids", "where", "confirm", "realm", "signal", "topic")
                .doesNotContainKey("cell_id");
    }
}
