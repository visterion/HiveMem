package com.hivemem.tools.write;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RejectCellToolHandlerTest {

    private final RejectCellToolHandler handler = new RejectCellToolHandler(null);

    @Test
    void nameIsRejectCell() {
        assertThat(handler.name()).isEqualTo("reject_cell");
    }

    @Test
    void inputSchemaExposesCellIdAndReason() {
        Map<String, Object> schema = handler.inputSchema();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKeys("cell_id", "reason");

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertThat(required).contains("cell_id");
        assertThat(required).doesNotContain("reason");
    }
}
