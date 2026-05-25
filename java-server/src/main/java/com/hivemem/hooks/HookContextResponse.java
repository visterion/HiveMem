package com.hivemem.hooks;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record HookContextResponse(
        @JsonProperty("hookSpecificOutput") HookSpecificOutput hookSpecificOutput,
        @JsonProperty("citedSources") List<SourceAttribution> citedSources
) {
    public record HookSpecificOutput(
            @JsonProperty("hookEventName") String hookEventName,
            @JsonProperty("additionalContext") String additionalContext
    ) {}

    public static HookContextResponse of(String eventName, String additionalContext,
                                          List<SourceAttribution> citedSources) {
        return new HookContextResponse(
                new HookSpecificOutput(eventName, additionalContext),
                citedSources != null ? citedSources : List.of()
        );
    }
}
