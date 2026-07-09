package com.hivemem.consumption;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Fence/prose-tolerant parsing of LLM JSON replies (moved out of the former PageGrouper).
 *  Narrows to the substring between the outermost brackets/braces, so it survives ```json fences,
 *  single-line fences and stray prose around the payload. */
final class LlmJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmJson() {}

    /** Cap payload excerpts in exception messages so document content does not flood logs. */
    private static String excerpt(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "…[truncated]";
    }

    /** Parse the first JSON object in {@code text}. @throws IllegalStateException on no/invalid JSON. */
    static JsonNode parseObject(String text) {
        return parse(text, '{', '}');
    }

    /** Parse the first JSON array in {@code text}. @throws IllegalStateException on no/invalid JSON. */
    static JsonNode parseArray(String text) {
        return parse(text, '[', ']');
    }

    private static JsonNode parse(String text, char open, char close) {
        if (text == null) throw new IllegalStateException("LLM returned no text");
        int s = text.indexOf(open);
        int e = text.lastIndexOf(close);
        if (s < 0 || e <= s) throw new IllegalStateException("no JSON payload in LLM output: " + excerpt(text));
        try {
            return MAPPER.readTree(text.substring(s, e + 1));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("failed to parse LLM output: " + ex.getMessage(), ex);
        }
    }
}
