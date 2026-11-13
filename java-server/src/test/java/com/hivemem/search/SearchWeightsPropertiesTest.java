package com.hivemem.search;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = SearchWeightsPropertiesTest.TestConfig.class)
@TestPropertySource(properties = {
        "hivemem.search.weights.semantic=0.41",
        "hivemem.search.weights.keyword=0.07",
        "hivemem.search.weights.recency=0.13",
        "hivemem.search.weights.importance=0.17",
        "hivemem.search.weights.popularity=0.11",
        "hivemem.search.weights.graph-proximity=0.23",
})
class SearchWeightsPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(SearchWeightsProperties.class)
    static class TestConfig {
    }

    @Autowired SearchWeightsProperties props;

    @Test
    void weightsBindFromYaml() {
        SearchWeights w = props.toSearchWeights();
        assertThat(w.semantic()).isEqualTo(0.41);
        assertThat(w.keyword()).isEqualTo(0.07);
        assertThat(w.recency()).isEqualTo(0.13);
        assertThat(w.importance()).isEqualTo(0.17);
        assertThat(w.popularity()).isEqualTo(0.11);
        assertThat(w.graphProximity()).isEqualTo(0.23);
    }

    @Test
    void defaultsAreSelfConsistent() {
        SearchWeights w = SearchWeights.defaults();
        assertThat(w.semantic() + w.keyword() + w.recency()
                + w.importance() + w.popularity() + w.graphProximity())
                .isEqualTo(1.00);
    }

    @Test
    void javaDefaultsEqualTheBalancedProfile() {
        assertMatchesBalanced(SearchWeights.defaults());
        assertMatchesBalanced(new SearchWeightsProperties().toSearchWeights());
    }

    @Test
    void applicationYmlEqualsTheBalancedProfile() throws Exception {
        java.util.List<org.springframework.core.env.PropertySource<?>> sources =
                new org.springframework.boot.env.YamlPropertySourceLoader().load(
                        "application", new org.springframework.core.io.ClassPathResource("application.yml"));
        SearchWeightsProperties bound = new org.springframework.boot.context.properties.bind.Binder(
                org.springframework.boot.context.properties.source.ConfigurationPropertySources
                        .from(sources.get(0)))
                .bind("hivemem.search.weights", SearchWeightsProperties.class)
                .get();
        assertMatchesBalanced(bound.toSearchWeights());
    }

    private static void assertMatchesBalanced(SearchWeights w) {
        SearchProfile b = SearchProfile.BALANCED;
        assertThat(w.semantic()).isEqualTo(b.semantic);
        assertThat(w.keyword()).isEqualTo(b.keyword);
        assertThat(w.recency()).isEqualTo(b.recency);
        assertThat(w.importance()).isEqualTo(b.importance);
        assertThat(w.popularity()).isEqualTo(b.popularity);
        assertThat(w.graphProximity()).isEqualTo(b.graphProximity);
    }
}
