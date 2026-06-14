package com.hivemem.summarize;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentDateParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 14);

    @Test
    void parsesFullIsoDate() {
        assertThat(DocumentDateParser.parse("2025-03-09", TODAY))
                .contains(LocalDate.of(2025, 3, 9));
    }

    @Test
    void tolerantYearMonthFirstOfMonth() {
        assertThat(DocumentDateParser.parse("2025-03", TODAY))
                .contains(LocalDate.of(2025, 3, 1));
    }

    @Test
    void tolerantYearOnlyFirstOfJanuary() {
        assertThat(DocumentDateParser.parse("2025", TODAY))
                .contains(LocalDate.of(2025, 1, 1));
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(DocumentDateParser.parse("  2025-03-09  ", TODAY))
                .contains(LocalDate.of(2025, 3, 9));
    }

    @Test
    void rejectsNullAndBlank() {
        assertThat(DocumentDateParser.parse(null, TODAY)).isEmpty();
        assertThat(DocumentDateParser.parse("   ", TODAY)).isEmpty();
    }

    @Test
    void rejectsUnparseable() {
        assertThat(DocumentDateParser.parse("Q3 last year", TODAY)).isEmpty();
        assertThat(DocumentDateParser.parse("09.03.2025", TODAY)).isEmpty(); // LLM must emit ISO
    }

    @Test
    void rejectsImplausiblyOld() {
        assertThat(DocumentDateParser.parse("1969-12-31", TODAY)).isEmpty();
    }

    @Test
    void rejectsFutureBeyondTomorrow() {
        assertThat(DocumentDateParser.parse("2026-06-16", TODAY)).isEmpty();
    }

    @Test
    void acceptsTomorrowBoundary() {
        assertThat(DocumentDateParser.parse("2026-06-15", TODAY))
                .contains(LocalDate.of(2026, 6, 15));
    }
}
