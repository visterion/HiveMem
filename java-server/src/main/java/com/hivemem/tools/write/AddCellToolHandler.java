package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import com.hivemem.write.WriteToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Component
@Order(23)
public class AddCellToolHandler implements ToolHandler {

    private final WriteToolService writeToolService;

    public AddCellToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "add_cell";
    }

    @Override
    public String description() {
        return "Create a new cell with progressive layers and embedding.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("content", "Full cell content")
                .optionalString("realm", "Realm to assign the cell to (free-form)")
                .optionalEnumString("signal", "Signal classification",
                        "facts", "events", "discoveries", "preferences", "advice")
                .optionalString("topic", "Topic tag (free-form)")
                .optionalString("source", "Source reference or URL")
                .optionalStringList("tags", "Free-form tags")
                .optionalIntegerInRange("importance", "Importance score (1-5)", 1, 5)
                .optionalString("summary", "Cell summary (auto-generated if omitted)")
                .optionalStringList("key_points", "Key points")
                .optionalString("insight", "Insight")
                .optionalEnumString("actionability", "Actionability bucket (omit for none)",
                        "actionable", "reference", "someday", "archive")
                .optionalEnumString("status", "Initial status (default: committed)",
                        "pending", "committed", "rejected")
                .optionalDateTime("valid_from", "Event time for the cell (ISO-8601 date-time)")
                .optionalNumber("dedupe_threshold", "Cosine similarity threshold for deduplication [-1, 1]")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String content = WriteArgumentParser.requiredText(arguments, "content");
        String realm = WriteArgumentParser.optionalText(arguments, "realm");
        String signal = WriteArgumentParser.optionalText(arguments, "signal");
        String topic = WriteArgumentParser.optionalText(arguments, "topic");
        String source = WriteArgumentParser.optionalText(arguments, "source");
        List<String> tags = WriteArgumentParser.optionalTextList(arguments, "tags");
        Integer importance = WriteArgumentParser.optionalInteger(arguments, "importance");
        String summary = WriteArgumentParser.optionalText(arguments, "summary");
        List<String> keyPoints = WriteArgumentParser.optionalTextList(arguments, "key_points");
        String insight = WriteArgumentParser.optionalText(arguments, "insight");
        String actionability = WriteArgumentParser.optionalText(arguments, "actionability");
        String status = WriteArgumentParser.optionalText(arguments, "status");
        OffsetDateTime validFrom = WriteArgumentParser.optionalTimestamp(arguments, "valid_from");
        Double dedupeThreshold = optionalDedupeThreshold(arguments);
        return writeToolService.addCell(
                principal,
                content,
                realm,
                signal,
                topic,
                source,
                tags,
                importance,
                summary,
                keyPoints,
                insight,
                actionability,
                status,
                validFrom,
                dedupeThreshold
        );
    }

    private static Double optionalDedupeThreshold(JsonNode arguments) {
        if (arguments == null || !arguments.has("dedupe_threshold")
                || arguments.get("dedupe_threshold").isNull()) {
            return null;
        }
        JsonNode node = arguments.get("dedupe_threshold");
        if (!node.isNumber()) {
            throw new IllegalArgumentException("Invalid dedupe_threshold");
        }
        double value = node.asDouble();
        if (!Double.isFinite(value) || value < -1.0d || value > 1.0d) {
            throw new IllegalArgumentException("Invalid dedupe_threshold");
        }
        return value;
    }
}
