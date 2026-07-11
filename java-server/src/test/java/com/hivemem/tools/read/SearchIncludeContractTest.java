package com.hivemem.tools.read;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Guards the UI/server search contract. The list below MUST stay in sync with
 * knowledge-ui/src/composables/useKnowledgeSearch.ts (searchArgs.include).
 * If this test fails, either the UI list or CellFieldSelection changed — fix
 * whichever side broke the contract, and update both places together.
 */
class SearchIncludeContractTest {

    private static final List<String> UI_SEARCH_INCLUDE = List.of(
            "content", "tags", "key_points", "insight",
            "importance", "summary", "created_at"); // "scores" is stripped by SearchToolHandler before forSearch()

    @Test
    void uiSearchIncludeListIsAcceptedByForSearch() {
        assertThatCode(() -> CellFieldSelection.forSearch(UI_SEARCH_INCLUDE))
                .doesNotThrowAnyException();
    }
}
