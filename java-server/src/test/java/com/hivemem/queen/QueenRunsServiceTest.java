package com.hivemem.queen;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class QueenRunsServiceTest {

    private final ObjectMapper om = new ObjectMapper();
    private JsonNode json(String s) { return om.readTree(s); }

    @Test
    void mapsAdminListWithCostAndMarksCostAvailable() {
        VistierieRunsClient client = Mockito.mock(VistierieRunsClient.class);
        Mockito.when(client.costAvailable()).thenReturn(true);
        Mockito.when(client.listRuns(50, 0)).thenReturn(json("""
            {"items":[{"id":"r1","agent":"queen","trigger":"scheduled","status":"done",
                       "started_at":"2026-06-02T03:00:00Z","finished_at":"2026-06-02T03:00:14Z",
                       "duration_ms":14000,"llm_calls_count":3,"total_cost_micros":12450}],"total":1}
            """));
        QueenRunsService svc = new QueenRunsService(client);
        QueenRunView.RunList out = svc.listRuns(50, 0);
        assertThat(out.costAvailable()).isTrue();
        assertThat(out.total()).isEqualTo(1);
        assertThat(out.items()).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo("r1");
            assertThat(r.costMicros()).isEqualTo(12450L);
            assertThat(r.llmCalls()).isEqualTo(3);
        });
    }

    @Test
    void mapsTenantListWithoutCost() {
        VistierieRunsClient client = Mockito.mock(VistierieRunsClient.class);
        Mockito.when(client.costAvailable()).thenReturn(false);
        Mockito.when(client.listRuns(50, 0)).thenReturn(json("""
            {"items":[{"id":"r2","agent":"queen","status":"done"}]}
            """));
        QueenRunsService svc = new QueenRunsService(client);
        QueenRunView.RunList out = svc.listRuns(50, 0);
        assertThat(out.costAvailable()).isFalse();
        assertThat(out.items().get(0).costMicros()).isNull();
        assertThat(out.items().get(0).llmCalls()).isNull();
    }

    @Test
    void filtersOutNonQueenBeeAgents() {
        VistierieRunsClient client = Mockito.mock(VistierieRunsClient.class);
        Mockito.when(client.costAvailable()).thenReturn(false);
        Mockito.when(client.listRuns(50, 0)).thenReturn(json("""
            {"items":[
               {"id":"r1","agent":"queen","status":"done"},
               {"id":"r2","agent":"isolated-cell-bee","status":"done"},
               {"id":"r3","agent":"some-other-agent","status":"done"}
            ]}
            """));
        QueenRunsService svc = new QueenRunsService(client);
        QueenRunView.RunList out = svc.listRuns(50, 0);
        assertThat(out.items()).hasSize(2);
        assertThat(out.items()).extracting(QueenRunView.RunSummary::agent)
                .containsExactlyInAnyOrder("queen", "isolated-cell-bee");
    }

    @Test
    void detailCombinesRunAndEvents() {
        VistierieRunsClient client = Mockito.mock(VistierieRunsClient.class);
        Mockito.when(client.getRun("r1")).thenReturn(json("{\"id\":\"r1\",\"status\":\"done\"}"));
        Mockito.when(client.getRunEvents("r1")).thenReturn(json("[{\"type\":\"subagent_spawned\"}]"));
        QueenRunsService svc = new QueenRunsService(client);
        QueenRunView.RunDetail out = svc.runDetail("r1");
        assertThat(out.run().get("status")).isEqualTo("done");
        assertThat(out.events()).singleElement()
                .satisfies(e -> assertThat(e.get("type")).isEqualTo("subagent_spawned"));
    }
}
