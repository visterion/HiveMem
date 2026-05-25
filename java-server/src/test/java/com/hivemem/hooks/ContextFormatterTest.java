package com.hivemem.hooks;

import com.hivemem.search.CellSearchRepository.RankedRow;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContextFormatterTest {

    private final ContextFormatter f = new ContextFormatter();

    private CellWithCitation cell(RankedRow row, List<ReferenceInfo> refs) {
        return new CellWithCitation(row, refs);
    }

    private RankedRow row(UUID id, String summary, String realm, String topic, OffsetDateTime validFrom) {
        return new RankedRow(id, "content", summary, realm, "facts", topic, List.of(), 3,
                OffsetDateTime.now(), validFrom, null,
                0.8, 0.0, 0.0, 0.0, 0.0, 0.0, 0.85);
    }

    @Test
    void formatsSingleCellAsCompactXmlWithTurn() {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var c = cell(row(id, "Phase 3 plan: SDK wrapper, 4 weeks", "engineering", "events",
                OffsetDateTime.parse("2025-03-01T00:00:00Z")), List.of());

        String out = f.format(List.of(c), 23);

        assertThat(out).startsWith("<hivemem_context turn=\"23\">");
        assertThat(out).contains("Phase 3 plan: SDK wrapper, 4 weeks");
        assertThat(out).endsWith("</hivemem_context>");
    }

    @Test
    void emptyListReturnsEmptyString() {
        assertThat(f.format(List.of(), 1)).isEmpty();
    }

    @Test
    void nullListReturnsEmptyString() {
        assertThat(f.format(null, 1)).isEmpty();
    }

    @Test
    void multipleCellsRenderAsBulletList() {
        var a = cell(row(UUID.randomUUID(), "first summary", "r", "t", null), List.of());
        var b = cell(row(UUID.randomUUID(), "second summary", "r", "t", null), List.of());

        String out = f.format(List.of(a, b), 7);

        assertThat(out).contains("- first summary");
        assertThat(out).contains("- second summary");
    }

    @Test
    void fallsBackToContentWhenSummaryIsBlank() {
        RankedRow r = new RankedRow(UUID.randomUUID(), "this is the full content",
                "", "r", "facts", "t", List.of(), 3,
                OffsetDateTime.now(), null, null,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.5);

        String out = f.format(List.of(cell(r, List.of())), 1);

        assertThat(out).contains("this is the full content");
    }

    @Test
    void sourceLineWithoutReferenceContainsCellIdAndYear() {
        UUID id = UUID.fromString("abcdef01-0000-0000-0000-000000000000");
        var c = cell(row(id, "summary", "engineering", "hivemem",
                OffsetDateTime.parse("2025-06-15T00:00:00Z")), List.of());

        String out = f.format(List.of(c), 1);

        assertThat(out).contains("[Quelle: engineering/hivemem · Cell abcdef01 · 2025]");
    }

    @Test
    void sourceLineWithReferenceContainsTitleAndUrl() {
        UUID id = UUID.randomUUID();
        ReferenceInfo ref = new ReferenceInfo(id, "Great Article", "https://example.com/art");
        var c = cell(row(id, "summary", "engineering", "hivemem", null), List.of(ref));

        String out = f.format(List.of(c), 1);

        assertThat(out).contains("[Quelle: engineering/hivemem · Great Article — https://example.com/art]");
    }

    @Test
    void sourceLineWithMultipleReferencesUsesFirst() {
        UUID id = UUID.randomUUID();
        ReferenceInfo first = new ReferenceInfo(id, "First", "https://first.com");
        ReferenceInfo second = new ReferenceInfo(id, "Second", "https://second.com");
        var c = cell(row(id, "summary", "r", "t", null), List.of(first, second));

        String out = f.format(List.of(c), 1);

        assertThat(out).contains("First");
        assertThat(out).doesNotContain("Second");
    }

    @Test
    void sourceLineWithoutValidFromOmitsYear() {
        UUID id = UUID.fromString("abcdef01-0000-0000-0000-000000000000");
        var c = cell(row(id, "summary", "r", "t", null), List.of());

        String out = f.format(List.of(c), 1);

        assertThat(out).contains("[Quelle: r/t · Cell abcdef01]");
        assertThat(out).doesNotContain("·  ·");
    }

    @Test
    void sourceLineWithReferenceAndNullUrlOmitsUrl() {
        UUID id = UUID.randomUUID();
        ReferenceInfo ref = new ReferenceInfo(id, "Book Without URL", null);
        var c = cell(row(id, "summary", "r", "t", null), List.of(ref));

        String out = f.format(List.of(c), 1);

        assertThat(out).contains("[Quelle: r/t · Book Without URL]");
        assertThat(out).doesNotContain("Book Without URL —");
    }
}
