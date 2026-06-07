package com.hivemem.tools.write;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import com.hivemem.write.WriteArgumentParser;
import com.hivemem.write.WriteToolService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Order(24)
public class KgAddToolHandler implements ToolHandler {

    private static final double DEFAULT_CONFIDENCE = 1.0d;

    private final WriteToolService writeToolService;

    public KgAddToolHandler(WriteToolService writeToolService) {
        this.writeToolService = writeToolService;
    }

    @Override
    public String name() {
        return "kg_add";
    }

    @Override
    public String description() {
        return "Add a fact triple to the knowledge graph.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("subject", "Subject entity of the triple")
                .requiredString("predicate", "Predicate (relation type)")
                .requiredString("object_", "Object value of the triple")
                .optionalNumberInRange("confidence", "Confidence score (0.0-1.0, default: 1.0)", 0.0d, 1.0d)
                .optionalUuid("source_id", "UUID of the source cell")
                .optionalEnumString("status", "Initial status (default: committed)",
                        "pending", "committed", "rejected")
                .optionalDateTime("valid_from", "Event time for the fact (ISO-8601 date-time)")
                .optionalString("on_conflict", "Conflict resolution strategy")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String subject = WriteArgumentParser.requiredText(arguments, "subject");
        String predicate = WriteArgumentParser.requiredText(arguments, "predicate");
        String object = WriteArgumentParser.requiredText(arguments, "object_");
        double confidence = WriteArgumentParser.requiredConfidence(arguments, "confidence", DEFAULT_CONFIDENCE);
        java.util.UUID sourceId = WriteArgumentParser.optionalUuid(arguments, "source_id");
        String status = WriteArgumentParser.optionalText(arguments, "status");
        java.time.OffsetDateTime validFrom = WriteArgumentParser.optionalTimestamp(arguments, "valid_from");
        String onConflict = WriteArgumentParser.optionalText(arguments, "on_conflict");
        return writeToolService.kgAdd(principal, subject, predicate, object, confidence, sourceId, status, validFrom, onConflict);
    }
}
