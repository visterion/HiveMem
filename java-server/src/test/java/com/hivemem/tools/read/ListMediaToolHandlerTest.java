package com.hivemem.tools.read;

import com.hivemem.mcp.ToolHandler;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

class ListMediaToolHandlerTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final ReadToolService readToolService = Mockito.mock(ReadToolService.class);
    private final ListMediaToolHandler handler = new ListMediaToolHandler(readToolService);

    @Test
    void nameAndSchemaAreCorrect() {
        assertThat(handler.name()).isEqualTo("list_media");
        Map<String, Object> schema = handler.inputSchema();
        assertThat(schema).containsKey("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).containsKeys("realm", "sort", "limit", "offset");
    }

    @Test
    void implementsToolHandler() {
        assertThat(handler).isInstanceOf(ToolHandler.class);
    }

    @Test
    void defaultsRealmNullSortNullLimit100Offset0() {
        when(readToolService.listMedia(isNull(), isNull(), eq(100), eq(0)))
                .thenReturn(List.of(Map.of("cell_id", "x")));
        Object result = handler.call(null, M.createObjectNode());
        assertThat(result).isInstanceOf(List.class);
    }

    @Test
    void rejectsOutOfRangeLimit() {
        ObjectNode args = M.createObjectNode();
        args.put("limit", 9999);
        assertThatThrownBy(() -> handler.call(null, args))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
