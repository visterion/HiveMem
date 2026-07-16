package com.hivemem.queen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VistierieWebhookControllerArchivistTest {

    private final QueenProperties props = new QueenProperties();
    private final QueenWebhookService service = mock(QueenWebhookService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        props.setEnabled(true);
        props.setWebhookToken("secret");
        @SuppressWarnings("unchecked")
        ObjectProvider<com.hivemem.consumption.SeparationApplier> sep = mock(ObjectProvider.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new VistierieWebhookController(props, service, sep)).build();
    }

    @Test
    void findInboxCellsRequiresToken() throws Exception {
        mvc.perform(post("/vistierie/tools/find_inbox_cells").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void findInboxCellsHappyPath() throws Exception {
        when(service.findInboxCells(anyInt())).thenReturn(Map.of("cell_ids", java.util.List.of("a")));
        mvc.perform(post("/vistierie/tools/find_inbox_cells").header("Authorization", "Bearer secret")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"input\":{\"limit\":5}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output.cell_ids[0]").value("a"));
    }

    @Test
    void reclassifyCellHappyPath() throws Exception {
        when(service.reclassifyInboxCell(any(), any(), any(), any(), any())).thenReturn(Map.of("id", "a"));
        mvc.perform(post("/vistierie/tools/reclassify_cell").header("Authorization", "Bearer secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"cell_id\":\"a\",\"realm\":\"work\",\"signal\":\"facts\",\"topic\":\"t\",\"reason\":\"r\"}}"))
                .andExpect(status().isOk());
        verify(service).reclassifyInboxCell("a", "work", "facts", "t", "r");
    }

    @Test
    void skipInboxCellHappyPath() throws Exception {
        when(service.skipInboxCell(any(), any())).thenReturn(Map.of("skipped", true));
        mvc.perform(post("/vistierie/tools/skip_inbox_cell").header("Authorization", "Bearer secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"cell_id\":\"a\",\"reason\":\"ambiguous\"}}"))
                .andExpect(status().isOk());
        verify(service).skipInboxCell("a", "ambiguous");
    }
}
