package com.hivemem.summarize;

import com.hivemem.extraction.FactSpec;

import java.util.List;

public record SummaryResult(
        String title,
        String summary,
        List<String> keyPoints,
        String insight,
        List<String> tags,
        String documentType,
        List<FactSpec> facts,
        int inputTokens,
        int outputTokens
) {
    public SummaryResult {
        keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
        tags = tags == null ? List.of() : List.copyOf(tags);
        facts = facts == null ? List.of() : List.copyOf(facts);
    }
}
