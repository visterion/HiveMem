package com.hivemem.tools.read;

import tools.jackson.databind.JsonNode;
import com.hivemem.write.WriteArgumentParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CellFieldSelection {

    private static final List<String> REQUIRED_FIELDS = List.of("id", "realm", "signal", "topic");
    private static final List<String> OPTIONAL_FIELDS = List.of(
            "summary", "key_points", "insight", "content",
            "tags", "importance", "source", "created_at",
            "valid_from", "valid_until"
    );
    private static final List<String> GET_CELL_ONLY_FIELDS = List.of(
            "parent_id", "actionability", "status", "created_by", "attachments", "confidence"
    );
    private static final List<String> SEARCH_DEFAULTS = List.of("summary", "tags", "importance", "created_at");
    private static final List<String> GET_CELL_DEFAULTS = List.of(
            "summary", "key_points", "insight", "tags", "importance",
            "source", "actionability", "status", "created_at", "attachments"
    );

    private final List<String> responseFields;
    private final Set<String> selectedOptionalFields;

    private CellFieldSelection(List<String> responseFields, Set<String> selectedOptionalFields) {
        this.responseFields = responseFields;
        this.selectedOptionalFields = selectedOptionalFields;
    }

    public static CellFieldSelection forSearch(List<String> include) {
        return from(include, SEARCH_DEFAULTS, OPTIONAL_FIELDS);
    }

    public static CellFieldSelection forGetCell(List<String> include) {
        List<String> allowed = new ArrayList<>(OPTIONAL_FIELDS);
        allowed.addAll(GET_CELL_ONLY_FIELDS);
        return from(include, GET_CELL_DEFAULTS, List.copyOf(allowed));
    }

    public static List<String> parseInclude(JsonNode arguments) {
        return WriteArgumentParser.optionalTextList(arguments, "include");
    }

    public static List<String> searchIncludeFields() {
        List<String> all = new ArrayList<>();
        all.add("realm");
        all.addAll(OPTIONAL_FIELDS);
        return List.copyOf(all);
    }

    public static List<String> getCellIncludeFields() {
        List<String> all = new ArrayList<>(OPTIONAL_FIELDS);
        all.addAll(GET_CELL_ONLY_FIELDS);
        return List.copyOf(all);
    }

    private static CellFieldSelection from(List<String> include, List<String> defaults, List<String> allowedOptional) {
        List<String> effective = include == null ? defaults : include;
        LinkedHashSet<String> optional = new LinkedHashSet<>();
        for (String field : effective) {
            if (REQUIRED_FIELDS.contains(field)) {
                continue;
            }
            if (!allowedOptional.contains(field)) {
                throw new IllegalArgumentException("Invalid include field: " + field);
            }
            optional.add(field);
        }

        ArrayList<String> ordered = new ArrayList<>(REQUIRED_FIELDS);
        for (String field : allowedOptional) {
            if (optional.contains(field)) {
                ordered.add(field);
            }
        }
        return new CellFieldSelection(List.copyOf(ordered), Set.copyOf(optional));
    }

    public List<String> responseFields() {
        return responseFields;
    }

    public boolean includes(String field) {
        return REQUIRED_FIELDS.contains(field) || selectedOptionalFields.contains(field);
    }

    public Map<String, Object> project(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : responseFields) {
            if (values.containsKey(field)) {
                result.put(field, values.get(field));
            }
        }
        return result;
    }
}
