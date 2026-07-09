package com.hivemem.tools.read;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test over the advertised input schema of the {@code search} tool.
 * {@code inputSchema()} does not touch the service, so a null service is fine.
 */
class SearchToolHandlerSchemaTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties() {
        Map<String, Object> schema = new SearchToolHandler(null).inputSchema();
        assertThat(schema).containsEntry("type", "object");
        return (Map<String, Object>) schema.get("properties");
    }

    @Test
    void advertisesProfileParam() {
        assertThat(properties()).containsKey("profile");
    }

    @Test
    void doesNotAdvertiseWeightParams() {
        assertThat(properties()).doesNotContainKeys(
                "weight_semantic",
                "weight_keyword",
                "weight_recency",
                "weight_importance",
                "weight_popularity",
                "weight_graph_proximity");
    }

    @Test
    void doesNotAdvertiseFlatFilterParams() {
        assertThat(properties()).doesNotContainKeys(
                "realm",
                "signal",
                "topic",
                "tags",
                "status");
    }

    @Test
    void retainsCoreParams() {
        assertThat(properties()).containsKeys("query", "limit", "include", "profile", "where");
    }
}
