package com.hivemem.search;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class SearchKeywordPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(SearchKeywordProperties.class)
    static class TestConfig {
    }

    @Nested
    @SpringBootTest(classes = TestConfig.class)
    @TestPropertySource(properties = "hivemem.search.keyword.stop-word-configs=german, english")
    class WithConfiguredList {
        @Autowired SearchKeywordProperties props;

        @Test
        void bindsTheCommaSeparatedList() {
            assertThat(props.getStopWordConfigs()).containsExactly("german", "english");
        }
    }

    @Nested
    @SpringBootTest(classes = TestConfig.class)
    @TestPropertySource(properties = "hivemem.search.keyword.stop-word-configs=")
    class WithEmptyValue {
        @Autowired SearchKeywordProperties props;

        @Test
        void holdsNoNonBlankEntry() {
            // Spring may bind no entry or blank entries; the repository drops blank ones.
            assertThat(props.getStopWordConfigs().stream().filter(s -> !s.isBlank())).isEmpty();
        }
    }

    @Test
    void defaultsToEmpty() {
        assertThat(new SearchKeywordProperties().getStopWordConfigs()).isEmpty();
    }
}
