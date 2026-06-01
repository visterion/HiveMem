package com.hivemem.queen.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** Envelope Vistierie POSTs to every HTTP tool webhook: {run_id, tool_name, input}. */
public record ToolCallRequest(
        @JsonProperty("run_id") String run_id,
        @JsonProperty("tool_name") String tool_name,
        @JsonProperty("input") Map<String, Object> input
) {}
