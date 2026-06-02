package com.hivemem.queen;

import java.util.List;
import java.util.Map;

/** Shaped views returned by {@link QueenRunsService} to the MCP tool handlers. */
public final class QueenRunView {

    /** One row in the run feed. cost/llmCalls are null when no admin token is set. */
    public record RunSummary(String id, String agent, String trigger, String status,
                             String startedAt, String finishedAt, Long durationMs,
                             Integer llmCalls, Long costMicros) {}

    public record RunList(List<RunSummary> items, int total, boolean costAvailable) {}

    /** Drill-down: the raw run detail map plus the event timeline. */
    public record RunDetail(Map<String, Object> run, List<Map<String, Object>> events) {}

    private QueenRunView() {}
}
