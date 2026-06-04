package com.hivemem.consumption;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Owns the per-block call to Vistierie + the carry-over state update. For each block of page images
 *  it asks the model to group each page into an existing document (carry-over) or a new one, then folds
 *  the returned assignments into the running {@code List<DocGroup>}. */
public class PageGrouper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One rendered page in a block: its global 1-based page number + the image to send. */
    public record BlockImage(int page, VisionMultiClient.Image image) {}

    private final VisionMultiClient vision;
    private final ConsumptionProperties props;

    public PageGrouper(VisionMultiClient vision, ConsumptionProperties props) {
        this.vision = vision;
        this.props = props;
    }

    /** Call the model for one block of pages and fold the assignments into {@code groups} in place. */
    public void groupBlock(String realm, List<DocGroup> groups, List<BlockImage> block) {
        String prompt = buildPrompt(groups, block);
        List<VisionMultiClient.Image> images = new ArrayList<>();
        for (BlockImage bi : block) images.add(bi.image());

        String text = vision.group(realm, prompt, images);
        JsonNode arr = parse(text);

        for (JsonNode a : arr) {
            int page = a.path("page").asInt();
            String docId = a.path("docId").asString(null);
            if (docId == null || docId.isBlank()) {
                throw new IllegalStateException("vision assignment missing docId: " + a);
            }
            String descriptor = a.path("descriptor").asString(null);
            double confidence = a.path("confidence").asDouble(0.0);

            DocGroup target = null;
            for (DocGroup g : groups) {
                if (g.id.equals(docId)) { target = g; break; }
            }
            if (target == null) {
                target = new DocGroup(docId, descriptor);
                groups.add(target);
            }
            target.pages.add(page);
            target.minConfidence = Math.min(target.minConfidence, confidence);
            if (descriptor != null && !descriptor.isBlank()) target.descriptor = descriptor;
        }
    }

    private String buildPrompt(List<DocGroup> groups, List<BlockImage> block) {
        StringBuilder existing = new StringBuilder();
        if (groups.isEmpty()) {
            existing.append("(none yet)");
        } else {
            for (DocGroup g : groups) {
                existing.append(g.id).append(" => ").append(g.descriptor == null ? "" : g.descriptor).append('\n');
            }
        }
        StringBuilder pageNumbers = new StringBuilder();
        for (int i = 0; i < block.size(); i++) {
            if (i > 0) pageNumbers.append(", ");
            pageNumbers.append(block.get(i).page());
        }
        return """
                You are grouping the pages of one scanned batch into individual documents by content.
                Documents already identified so far (id => short description):
                %s
                This block contains these pages (by their global page number): %s
                For EACH page in this block decide whether it continues one of the existing documents or starts a new one.
                Use letterhead, layout, sender, topic and visual continuity. Reply with STRICT JSON only, no prose, no markdown:
                [{"page": <globalPageNumber>, "docId": "<existing id or a new short id>", "isNew": <true|false>, "descriptor": "<short description if new>", "confidence": <0.0-1.0>}]
                Every page in this block must appear exactly once. If unsure, use a low confidence.""".formatted(
                existing.toString().strip(), pageNumbers.toString());
    }

    /** Tolerant parse: strip ```json / ``` fences + whitespace, then read the JSON array. */
    private JsonNode parse(String text) {
        if (text == null) throw new IllegalStateException("vision returned no text");
        String cleaned = text.strip();
        if (cleaned.startsWith("```")) {
            int firstNl = cleaned.indexOf('\n');
            if (firstNl >= 0) cleaned = cleaned.substring(firstNl + 1);
            if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
            cleaned = cleaned.strip();
        }
        try {
            JsonNode node = MAPPER.readTree(cleaned);
            if (!node.isArray()) throw new IllegalStateException("vision output is not a JSON array: " + cleaned);
            return node;
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to parse vision output: " + e.getMessage(), e);
        }
    }
}
