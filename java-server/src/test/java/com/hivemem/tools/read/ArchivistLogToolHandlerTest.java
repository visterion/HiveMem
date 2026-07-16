package com.hivemem.tools.read;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.queen.QueenRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ArchivistLogToolHandlerTest {

    @Test
    void nameIsArchivistLog() {
        assertThat(new ArchivistLogToolHandler(mock(QueenRepository.class), new ObjectMapper()).name())
                .isEqualTo("archivist_log");
    }

    @Test
    void returnsParsedEntriesNewestFirst() {
        QueenRepository repo = mock(QueenRepository.class);
        when(repo.findArchivistLog(anyInt())).thenReturn(List.of(
                Map.of("op_type", "reclassify_cell", "at", "2026-07-16T10:00:00Z",
                        "payload", "{\"cell_id\":\"c1\",\"old_realm\":\"inbox\",\"new_realm\":\"work\",\"reason\":\"r\",\"agent_id\":\"inbox-archivist\"}"),
                Map.of("op_type", "archivist_skip", "at", "2026-07-16T09:00:00Z",
                        "payload", "{\"cell_id\":\"c2\",\"reason\":\"ambiguous\",\"agent_id\":\"inbox-archivist\"}")));

        ArchivistLogToolHandler h = new ArchivistLogToolHandler(repo, new ObjectMapper());
        Object result = h.call(new AuthPrincipal("admin", AuthRole.ADMIN), JsonNodeFactory.instance.objectNode());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) ((Map<String, Object>) result).get("entries");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).containsEntry("op_type", "reclassify_cell")
                .containsEntry("new_realm", "work").containsEntry("reason", "r");
        assertThat(entries.get(1)).containsEntry("op_type", "archivist_skip")
                .containsEntry("reason", "ambiguous");
    }
}
