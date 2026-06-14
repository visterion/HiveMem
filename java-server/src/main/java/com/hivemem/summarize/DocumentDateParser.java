package com.hivemem.summarize;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses and plausibility-checks the {@code document_date} string the LLM emits. The LLM is
 * instructed to output ISO-8601 ({@code YYYY-MM-DD}); we additionally tolerate {@code YYYY-MM}
 * (→ first of month) and {@code YYYY} (→ Jan 1). Anything else, or a date outside
 * {@code [1970-01-01 .. today+1]}, yields {@link Optional#empty()} so the caller keeps the
 * cell's ingest date untouched.
 */
public final class DocumentDateParser {

    private static final LocalDate MIN = LocalDate.of(1970, 1, 1);
    private static final Pattern FULL = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern YEAR_MONTH = Pattern.compile("\\d{4}-\\d{2}");
    private static final Pattern YEAR = Pattern.compile("\\d{4}");

    private DocumentDateParser() {}

    /** Production entry point — validates against the current date. */
    public static Optional<LocalDate> parse(String raw) {
        return parse(raw, LocalDate.now());
    }

    /** Testable overload — validates against an injected {@code today}. */
    public static Optional<LocalDate> parse(String raw, LocalDate today) {
        if (raw == null) return Optional.empty();
        String s = raw.trim();
        if (s.isEmpty()) return Optional.empty();

        LocalDate parsed = tryParse(s);
        if (parsed == null) return Optional.empty();

        if (parsed.isBefore(MIN)) return Optional.empty();
        if (parsed.isAfter(today.plusDays(1))) return Optional.empty();
        return Optional.of(parsed);
    }

    private static LocalDate tryParse(String s) {
        try {
            if (FULL.matcher(s).matches()) {
                return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
            }
            if (YEAR_MONTH.matcher(s).matches()) {
                String[] p = s.split("-");
                return LocalDate.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), 1);
            }
            if (YEAR.matcher(s).matches()) {
                return LocalDate.of(Integer.parseInt(s), 1, 1);
            }
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return null;
        }
        return null;
    }
}
