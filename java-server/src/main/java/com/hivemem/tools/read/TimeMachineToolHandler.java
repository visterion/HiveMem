package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.mcp.ToolHandler;
import com.hivemem.mcp.ToolInputSchema;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;

@Component
@Order(8)
public class TimeMachineToolHandler implements ToolHandler {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 100;

    private final ReadToolService readToolService;

    public TimeMachineToolHandler(ReadToolService readToolService) {
        this.readToolService = readToolService;
    }

    @Override
    public String name() {
        return "time_machine";
    }

    @Override
    public String description() {
        return "Historical knowledge retrieval. Supports bi-temporal queries: 'as_of' filters by event time (when a fact was true); 'as_of_ingestion' filters by transaction time (when HiveMem learned of the fact).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolInputSchema.object()
                .requiredString("subject", "Entity name to query")
                .optionalDateTime("as_of", "Event time filter (ISO-8601 date-time)")
                .optionalDateTime("as_of_ingestion", "Ingestion time filter (ISO-8601 date-time)")
                .optionalInteger("limit", "Maximum number of results (default 100, max 100)")
                .build();
    }

    @Override
    public Object call(AuthPrincipal principal, JsonNode arguments) {
        String subject = requiredText(arguments, "subject");
        OffsetDateTime asOf = offsetDateTimeValue(arguments, "as_of");
        OffsetDateTime asOfIngestion = offsetDateTimeValue(arguments, "as_of_ingestion");
        int limit = intValue(arguments, "limit");
        return readToolService.timeMachine(subject, asOf, asOfIngestion, limit);
    }

    private static String requiredText(JsonNode arguments, String field) {
        if (arguments == null || !arguments.hasNonNull(field)) {
            throw new IllegalArgumentException("Missing " + field);
        }
        String value = arguments.get(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return value;
    }

    private static OffsetDateTime offsetDateTimeValue(JsonNode arguments, String field) {
        if (arguments == null || !arguments.hasNonNull(field)) {
            return null;
        }
        String value = arguments.get(field).asText();
        if (value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    private static int intValue(JsonNode arguments, String field) {
        if (arguments == null || !arguments.hasNonNull(field)) {
            return DEFAULT_LIMIT;
        }
        JsonNode node = arguments.get(field);
        if (!node.canConvertToInt()) {
            throw new IllegalArgumentException("Invalid limit");
        }
        int value = node.intValue();
        if (value <= 0 || value > MAX_LIMIT) {
            throw new IllegalArgumentException("Invalid limit");
        }
        return value;
    }
}
