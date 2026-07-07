package com.hivemem.tools.read;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CellFieldSelectionTest {

    @Test
    void searchDefaultsIncludeSummaryTagsImportanceAndCreatedAt() {
        CellFieldSelection selection = CellFieldSelection.forSearch(null);

        assertThat(selection.responseFields()).containsExactly(
                "id", "realm", "signal", "topic",
                "summary", "tags", "importance", "created_at"
        );
    }

    @Test
    void getCellDefaultsExcludeContent() {
        CellFieldSelection selection = CellFieldSelection.forGetCell(null);

        assertThat(selection.responseFields()).containsExactly(
                "id", "realm", "signal", "topic",
                "summary", "key_points", "insight", "tags", "importance", "source",
                "created_at", "actionability", "status", "attachments"
        );
    }

    @Test
    void getCellAllowsParentIdAndCreatedByViaInclude() {
        CellFieldSelection selection = CellFieldSelection.forGetCell(
                List.of("summary", "parent_id", "created_by"));

        assertThat(selection.responseFields()).containsExactly(
                "id", "realm", "signal", "topic",
                "summary", "parent_id", "created_by"
        );
        assertThat(selection.includes("parent_id")).isTrue();
        assertThat(selection.includes("created_by")).isTrue();
    }

    @Test
    void searchRejectsGetCellOnlyFields() {
        assertThatThrownBy(() -> CellFieldSelection.forSearch(List.of("parent_id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid include field: parent_id");
    }

    @Test
    void emptyIncludeMeansRequiredMetadataOnly() {
        CellFieldSelection selection = CellFieldSelection.forGetCell(List.of());

        assertThat(selection.responseFields()).containsExactly("id", "realm", "signal", "topic");
    }

    @Test
    void duplicateIncludeValuesAreDeduplicatedInCanonicalOrder() {
        CellFieldSelection selection = CellFieldSelection.forSearch(List.of("content", "summary", "summary"));

        assertThat(selection.responseFields()).containsExactly(
                "id", "realm", "signal", "topic",
                "summary", "content"
        );
    }

    @Test
    void unknownFieldFailsClearly() {
        assertThatThrownBy(() -> CellFieldSelection.forSearch(List.of("summary", "bogus")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid include field: bogus");
    }

    @Test
    void searchIncludeFieldsExposesRealmInEnum() {
        assertThat(CellFieldSelection.searchIncludeFields()).contains("realm");
    }

    @Test
    void forSearchToleratesRealmInIncludeList() {
        CellFieldSelection selection = CellFieldSelection.forSearch(List.of("realm", "summary"));

        assertThat(selection.responseFields()).containsExactly(
                "id", "realm", "signal", "topic", "summary"
        );
    }
}
