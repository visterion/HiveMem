package com.hivemem.consumption;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The completion-webhook envelope Vistierie POSTs to /vistierie/separation/done.
 *
 * Vistierie's CompletionWebhookDispatcher sends
 * {run_id, agent_version, status, started_at, finished_at, summary, output, error}; the project's
 * ObjectMapper ignores the fields we don't bind. The separator agent's output_schema shapes
 * {@code output} as {boundaries:[{afterPage,confidence}]}.
 */
public record SeparationResult(
        @JsonProperty("run_id") String runId,
        @JsonProperty("status") String status,
        @JsonProperty("output") Output output,
        @JsonProperty("error") String error) {

    public record Output(@JsonProperty("boundaries") List<Boundary> boundaries) {}

    /** Cut AFTER this 1-based page, with the model's confidence in this boundary. */
    public record Boundary(
            @JsonProperty("afterPage") int afterPage,
            @JsonProperty("confidence") double confidence) {}
}
