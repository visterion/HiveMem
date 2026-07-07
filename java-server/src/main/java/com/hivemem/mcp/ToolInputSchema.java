package com.hivemem.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder for JSON-Schema input descriptors attached to {@link McpTool}.
 *
 * <p>MCP clients rely on {@code tools/list} responses to know which arguments
 * a tool accepts. Prior to this helper the schema was always {@code properties: {}},
 * forcing clients to discover parameter names via trial-and-error {@code -32602}
 * errors. {@code ToolInputSchema} lets each {@link ToolHandler} describe its own
 * arguments in a readable, type-safe way.
 *
 * <p>Output shape:
 * <pre>{@code
 * {
 *   "type": "object",
 *   "properties": { ... },
 *   "required": [ ... ],         // omitted when no field is required
 *   "additionalProperties": false
 * }
 * }</pre>
 */
public final class ToolInputSchema {

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    private ToolInputSchema() {
    }

    public static ToolInputSchema object() {
        return new ToolInputSchema();
    }

    /** Immutable empty schema — use for tools that take no arguments. */
    public static Map<String, Object> empty() {
        return schemaOf(Map.of(), List.of());
    }

    public ToolInputSchema requiredString(String name, String description) {
        return addScalar(name, "string", null, description, true);
    }

    public ToolInputSchema optionalString(String name, String description) {
        return addScalar(name, "string", null, description, false);
    }

    public ToolInputSchema requiredEnumString(String name, String description, String... values) {
        return addEnumString(name, description, values, true);
    }

    public ToolInputSchema optionalEnumString(String name, String description, String... values) {
        return addEnumString(name, description, values, false);
    }

    public ToolInputSchema requiredInteger(String name, String description) {
        return addScalar(name, "integer", null, description, true);
    }

    public ToolInputSchema optionalInteger(String name, String description) {
        return addScalar(name, "integer", null, description, false);
    }

    public ToolInputSchema requiredIntegerInRange(String name, String description, int min, int max) {
        return addNumericInRange(name, "integer", description, min, max, true);
    }

    public ToolInputSchema optionalIntegerInRange(String name, String description, int min, int max) {
        return addNumericInRange(name, "integer", description, min, max, false);
    }

    public ToolInputSchema requiredNumber(String name, String description) {
        return addScalar(name, "number", null, description, true);
    }

    public ToolInputSchema optionalNumber(String name, String description) {
        return addScalar(name, "number", null, description, false);
    }

    public ToolInputSchema requiredNumberInRange(String name, String description, double min, double max) {
        return addNumericInRange(name, "number", description, min, max, true);
    }

    public ToolInputSchema optionalNumberInRange(String name, String description, double min, double max) {
        return addNumericInRange(name, "number", description, min, max, false);
    }

    public ToolInputSchema requiredUuid(String name, String description) {
        return addScalar(name, "string", "uuid", description, true);
    }

    public ToolInputSchema optionalUuid(String name, String description) {
        return addScalar(name, "string", "uuid", description, false);
    }

    public ToolInputSchema requiredDateTime(String name, String description) {
        return addScalar(name, "string", "date-time", description, true);
    }

    public ToolInputSchema optionalDateTime(String name, String description) {
        return addScalar(name, "string", "date-time", description, false);
    }

    public ToolInputSchema requiredStringList(String name, String description) {
        return addArray(name, "string", null, description, true);
    }

    public ToolInputSchema optionalStringList(String name, String description) {
        return addArray(name, "string", null, description, false);
    }

    public ToolInputSchema requiredEnumStringList(String name, String description, String... values) {
        return addEnumArray(name, description, values, true);
    }

    public ToolInputSchema optionalEnumStringList(String name, String description, String... values) {
        return addEnumArray(name, description, values, false);
    }

    public ToolInputSchema requiredUuidList(String name, String description) {
        return addArray(name, "string", "uuid", description, true);
    }

    public ToolInputSchema optionalUuidList(String name, String description) {
        return addArray(name, "string", "uuid", description, false);
    }

    public ToolInputSchema optionalBoolean(String name, String description) {
        return addScalar(name, "boolean", null, description, false);
    }

    public ToolInputSchema optionalObject(String name, String description, ToolInputSchema nested) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "object");
        prop.put("properties", Map.copyOf(nested.properties));
        if (!nested.required.isEmpty()) {
            prop.put("required", List.copyOf(nested.required));
        }
        prop.put("additionalProperties", Boolean.FALSE);
        if (description != null && !description.isBlank()) {
            prop.put("description", description);
        }
        properties.put(name, Map.copyOf(prop));
        return this;
    }

    public Map<String, Object> build() {
        return schemaOf(Map.copyOf(properties), List.copyOf(required));
    }

    private ToolInputSchema addScalar(String name, String type, String format, String description, boolean isRequired) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", type);
        if (format != null) {
            prop.put("format", format);
        }
        if (description != null && !description.isBlank()) {
            prop.put("description", description);
        }
        properties.put(name, Map.copyOf(prop));
        if (isRequired) {
            required.add(name);
        }
        return this;
    }

    private ToolInputSchema addEnumString(String name, String description, String[] values, boolean isRequired) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("enum values must not be empty for property '" + name + "'");
        }
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "string");
        prop.put("enum", List.of(values));
        String mergedDescription = appendEnumHint(description, values);
        if (!mergedDescription.isBlank()) {
            prop.put("description", mergedDescription);
        }
        properties.put(name, Map.copyOf(prop));
        if (isRequired) {
            required.add(name);
        }
        return this;
    }

    private ToolInputSchema addNumericInRange(String name, String type, String description, Number min, Number max, boolean isRequired) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", type);
        prop.put("minimum", min);
        prop.put("maximum", max);
        if (description != null && !description.isBlank()) {
            prop.put("description", description);
        }
        properties.put(name, Map.copyOf(prop));
        if (isRequired) {
            required.add(name);
        }
        return this;
    }

    private static String appendEnumHint(String description, String[] values) {
        String hint = "One of: " + String.join(", ", values);
        if (description == null || description.isBlank()) {
            return hint;
        }
        return description + ". " + hint;
    }

    private ToolInputSchema addArray(String name, String itemType, String itemFormat, String description, boolean isRequired) {
        Map<String, Object> itemSchema = new LinkedHashMap<>();
        itemSchema.put("type", itemType);
        if (itemFormat != null) {
            itemSchema.put("format", itemFormat);
        }
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "array");
        prop.put("items", Map.copyOf(itemSchema));
        if (description != null && !description.isBlank()) {
            prop.put("description", description);
        }
        properties.put(name, Map.copyOf(prop));
        if (isRequired) {
            required.add(name);
        }
        return this;
    }

    private ToolInputSchema addEnumArray(String name, String description, String[] values, boolean isRequired) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("enum values must not be empty for property '" + name + "'");
        }
        Map<String, Object> itemSchema = new LinkedHashMap<>();
        itemSchema.put("type", "string");
        itemSchema.put("enum", List.of(values));
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "array");
        prop.put("items", Map.copyOf(itemSchema));
        String mergedDescription = appendEnumHint(description, values);
        if (!mergedDescription.isBlank()) {
            prop.put("description", mergedDescription);
        }
        properties.put(name, Map.copyOf(prop));
        if (isRequired) {
            required.add(name);
        }
        return this;
    }

    private static Map<String, Object> schemaOf(Map<String, Object> props, List<String> requiredFields) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        if (!requiredFields.isEmpty()) {
            schema.put("required", requiredFields);
        }
        schema.put("additionalProperties", Boolean.FALSE);
        return Map.copyOf(schema);
    }
}
