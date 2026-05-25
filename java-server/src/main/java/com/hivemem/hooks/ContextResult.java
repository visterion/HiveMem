package com.hivemem.hooks;

import java.util.List;

public record ContextResult(String formattedContext, List<SourceAttribution> citedSources) {
    public static ContextResult empty() {
        return new ContextResult("", List.of());
    }
}
