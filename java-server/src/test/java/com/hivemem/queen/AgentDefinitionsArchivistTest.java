package com.hivemem.queen;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class AgentDefinitionsArchivistTest {

    @Test
    void inboxArchivistHasExpectedShape() {
        QueenProperties props = new QueenProperties();
        props.setWebhookToken("wt");
        props.setHivememBaseUrl("http://hivemem:8421");
        props.setArchivistSchedule("0 0 4 * * *");
        Map<String, Object> def = new AgentDefinitions(props).inboxArchivist();

        assertThat(def.get("name")).isEqualTo("inbox-archivist");
        assertThat(def.get("model_purpose")).isEqualTo("archivist");
        assertThat(def.get("schedule")).isEqualTo("0 0 4 * * *");
        assertThat(def.get("webhook_token")).isEqualTo("wt");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) def.get("tools");
        assertThat(tools).extracting(t -> t.get("name"))
                .containsExactlyInAnyOrder("find_inbox_cells", "read_cell", "list_taxonomy",
                        "reclassify_cell", "skip_inbox_cell");
        assertThat(tools).allSatisfy(t ->
                assertThat((String) t.get("webhook_url")).startsWith("http://hivemem:8421/vistierie/tools/"));
    }
}
