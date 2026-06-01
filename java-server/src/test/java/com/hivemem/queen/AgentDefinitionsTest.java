package com.hivemem.queen;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDefinitionsTest {

    private QueenProperties props() {
        QueenProperties p = new QueenProperties();
        p.setHivememBaseUrl("http://hivemem:8080");
        p.setWebhookToken("wt");
        p.setCompletionWebhookToken("cwt");
        p.setSchedule("0 0 3 * * *");
        return p;
    }

    @Test
    @SuppressWarnings("unchecked")
    void beeDefinitionHasReadOnlyHttpToolsAndOutputSchema() {
        Map<String, Object> bee = new AgentDefinitions(props()).isolatedCellBee();
        assertThat(bee.get("name")).isEqualTo("isolated-cell-bee");
        assertThat(bee.get("model_purpose")).isEqualTo("bee_link");
        assertThat(bee).containsKey("output_schema");
        assertThat(bee).doesNotContainKey("schedule");

        List<Map<String, Object>> tools = (List<Map<String, Object>>) bee.get("tools");
        assertThat(tools).hasSize(2);
        assertThat(tools).extracting(t -> t.get("name"))
                .containsExactlyInAnyOrder("read_cell", "search_similar_cells");
        assertThat(tools).allSatisfy(t -> {
            assertThat((String) t.get("webhook_url")).startsWith("http://hivemem:8080/vistierie/tools/");
            assertThat(t).doesNotContainKey("type"); // not subagent
        });
        assertThat(bee.get("webhook_token")).isEqualTo("wt");
    }

    @Test
    @SuppressWarnings("unchecked")
    void queenDefinitionHasScheduleSubagentToolAndCompletionWebhook() {
        Map<String, Object> queen = new AgentDefinitions(props()).queen();
        assertThat(queen.get("name")).isEqualTo("queen");
        assertThat(queen.get("schedule")).isEqualTo("0 0 3 * * *");
        assertThat(queen.get("completion_webhook"))
                .isEqualTo("http://hivemem:8080/vistierie/runs/done");
        assertThat(queen.get("completion_webhook_token")).isEqualTo("cwt");

        List<Map<String, Object>> tools = (List<Map<String, Object>>) queen.get("tools");
        assertThat(tools).extracting(t -> t.get("name"))
                .containsExactlyInAnyOrder("find_isolated_cells", "dispatch_bee");
        Map<String, Object> dispatch = tools.stream()
                .filter(t -> t.get("name").equals("dispatch_bee")).findFirst().orElseThrow();
        assertThat(dispatch.get("type")).isEqualTo("subagent");
        assertThat(dispatch.get("target_agent")).isEqualTo("isolated-cell-bee");
    }
}
