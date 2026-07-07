package com.hivemem.search;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Filter selector shared by list_cell_ids and the where-variants of bulk_reclassify/bulk_tag.
 * Realm semantics: realm == null and realmIn == null -> no realm filter;
 * "none" -> matches cells whose realm IS NULL; otherwise exact match.
 */
public record CellSelector(
        String realm,
        List<String> realmIn,
        String signal,
        String topic,
        List<String> tags,
        String query,
        String status
) {
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "realm", "realm_in", "signal", "topic", "tags", "query", "status");
    private static final Set<String> ALLOWED_STATUS = Set.of("committed", "pending", "rejected");

    public static CellSelector fromJson(JsonNode where) {
        if (where == null || where.isNull()) {
            return new CellSelector(null, null, null, null, null, null, null);
        }
        if (!where.isObject()) {
            throw new IllegalArgumentException("where must be an object");
        }
        for (String key : where.propertyNames()) {
            if (!ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown where field: " + key);
            }
        }
        String realm = text(where, "realm");
        List<String> realmIn = textList(where, "realm_in");
        if (realm != null && realmIn != null) {
            throw new IllegalArgumentException("where.realm and where.realm_in are mutually exclusive");
        }
        String status = text(where, "status");
        if (status != null && !ALLOWED_STATUS.contains(status)) {
            throw new IllegalArgumentException("Invalid where.status");
        }
        return new CellSelector(realm, realmIn, text(where, "signal"), text(where, "topic"),
                textList(where, "tags"), text(where, "query"), status);
    }

    public boolean isEmpty() {
        return realm == null && realmIn == null && signal == null && topic == null
                && tags == null && query == null && status == null;
    }

    private static String text(JsonNode where, String field) {
        if (!where.has(field) || where.get(field).isNull()) {
            return null;
        }
        JsonNode node = where.get(field);
        if (!node.isTextual()) {
            throw new IllegalArgumentException("Invalid where." + field);
        }
        String value = node.asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Invalid where." + field);
        }
        return value;
    }

    private static List<String> textList(JsonNode where, String field) {
        if (!where.has(field) || where.get(field).isNull()) {
            return null;
        }
        JsonNode node = where.get(field);
        if (!node.isArray()) {
            throw new IllegalArgumentException("Invalid where." + field);
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("Invalid where." + field);
            }
            String text = item.asText();
            if (text.isBlank()) {
                throw new IllegalArgumentException("Invalid where." + field);
            }
            values.add(text);
        }
        return List.copyOf(values);
    }
}
