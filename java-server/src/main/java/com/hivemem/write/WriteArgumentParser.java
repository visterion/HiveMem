package com.hivemem.write;

import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class WriteArgumentParser {

    private WriteArgumentParser() {
    }

    public static String requiredText(JsonNode arguments, String field) {
        JsonNode node = requiredNode(arguments, field);
        if (!node.isTextual()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        String value = node.asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return value;
    }

    public static String optionalText(JsonNode arguments, String field) {
        if (arguments == null || !arguments.has(field) || arguments.get(field).isNull()) {
            return null;
        }
        JsonNode node = arguments.get(field);
        if (!node.isTextual()) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        String value = node.asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value;
    }

    public static UUID requiredUuid(JsonNode arguments, String field) {
        String value = requiredText(arguments, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    public static UUID optionalUuid(JsonNode arguments, String field) {
        String value = optionalText(arguments, field);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    public static double requiredConfidence(JsonNode arguments, String field, double defaultValue) {
        if (arguments == null || !arguments.has(field) || arguments.get(field).isNull()) {
            return defaultValue;
        }
        JsonNode node = arguments.get(field);
        if (!node.isNumber()) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        double value = node.asDouble();
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("Invalid " + field + " (must be within [0,1])");
        }
        return value;
    }

    public static OffsetDateTime optionalTimestamp(JsonNode arguments, String field) {
        String value = optionalText(arguments, field);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException first) {
            try {
                return LocalDateTime.parse(value).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException second) {
                throw new IllegalArgumentException("Invalid " + field);
            }
        }
    }

    public static List<UUID> requiredUuidList(JsonNode arguments, String field) {
        JsonNode node = requiredNode(arguments, field);
        if (!node.isArray()) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        List<UUID> ids = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("Invalid " + field);
            }
            String text = item.asText();
            if (text.isBlank()) {
                throw new IllegalArgumentException("Invalid " + field);
            }
            try {
                ids.add(UUID.fromString(text));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid " + field);
            }
        }
        return List.copyOf(ids);
    }

    public static List<String> requiredTextList(JsonNode arguments, String field) {
        JsonNode node = requiredNode(arguments, field);
        if (!node.isArray()) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("Invalid " + field);
            }
            String text = item.asText();
            if (text.isBlank()) {
                throw new IllegalArgumentException("Invalid " + field);
            }
            values.add(text);
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return List.copyOf(values);
    }

    public static List<String> optionalTextList(JsonNode arguments, String field) {
        if (arguments == null || !arguments.has(field) || arguments.get(field).isNull()) {
            return null;
        }
        JsonNode node = arguments.get(field);
        if (!node.isArray()) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("Invalid " + field);
            }
            String text = item.asText();
            if (text.isBlank()) {
                throw new IllegalArgumentException("Invalid " + field);
            }
            values.add(text);
        }
        return List.copyOf(values);
    }

    public static List<UUID> optionalUuidList(JsonNode arguments, String field) {
        if (arguments == null || !arguments.has(field) || arguments.get(field).isNull()) {
            return null;
        }
        JsonNode node = arguments.get(field);
        if (!node.isArray()) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        List<UUID> ids = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("Invalid " + field);
            }
            String text = item.asText();
            if (text.isBlank()) {
                throw new IllegalArgumentException("Invalid " + field);
            }
            try {
                ids.add(UUID.fromString(text));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid " + field);
            }
        }
        return List.copyOf(ids);
    }

    public static Integer optionalInteger(JsonNode arguments, String field) {
        if (arguments == null || !arguments.has(field) || arguments.get(field).isNull()) {
            return null;
        }
        JsonNode node = arguments.get(field);
        if (!node.isIntegralNumber()) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return node.asInt();
    }

    private static JsonNode requiredNode(JsonNode arguments, String field) {
        if (arguments == null || !arguments.has(field) || arguments.get(field).isNull()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return arguments.get(field);
    }
}
