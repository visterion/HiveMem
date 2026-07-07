package com.hivemem.search;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plain-JUnit unit tests for {@link CellSelector#fromJson(JsonNode)} — no Spring context needed. */
class CellSelectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode json(String json) {
        return MAPPER.readTree(json);
    }

    @Test
    void rejectsUnknownKeys() {
        JsonNode where = json("{\"bogus\": \"x\"}");
        assertThatThrownBy(() -> CellSelector.fromJson(where))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRealmAndRealmInTogether() {
        JsonNode where = json("{\"realm\": \"a\", \"realm_in\": [\"a\", \"b\"]}");
        assertThatThrownBy(() -> CellSelector.fromJson(where))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidStatus() {
        JsonNode where = json("{\"status\": \"bogus\"}");
        assertThatThrownBy(() -> CellSelector.fromJson(where))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesAllFields() {
        JsonNode where = json("""
                {
                  "realm": "hivemem",
                  "signal": "facts",
                  "topic": "infra",
                  "tags": ["a", "b"],
                  "query": "attachment",
                  "status": "pending"
                }
                """);
        CellSelector sel = CellSelector.fromJson(where);
        assertThat(sel.realm()).isEqualTo("hivemem");
        assertThat(sel.realmIn()).isNull();
        assertThat(sel.signal()).isEqualTo("facts");
        assertThat(sel.topic()).isEqualTo("infra");
        assertThat(sel.tags()).isEqualTo(List.of("a", "b"));
        assertThat(sel.query()).isEqualTo("attachment");
        assertThat(sel.status()).isEqualTo("pending");
    }

    @Test
    void emptyObjectReturnsEmptySelector() {
        CellSelector sel = CellSelector.fromJson(json("{}"));
        assertThat(sel.isEmpty()).isTrue();
    }

    @Test
    void nullNodeReturnsEmptySelector() {
        CellSelector sel = CellSelector.fromJson(null);
        assertThat(sel.isEmpty()).isTrue();
    }

    @Test
    void realmInParses() {
        CellSelector sel = CellSelector.fromJson(json("{\"realm_in\": [\"a\", \"none\"]}"));
        assertThat(sel.realmIn()).isEqualTo(List.of("a", "none"));
        assertThat(sel.realm()).isNull();
    }
}
