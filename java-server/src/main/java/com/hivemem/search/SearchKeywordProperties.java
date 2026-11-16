package com.hivemem.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings of the keyword (BM25) signal of ranked_search. Bound to hivemem.search.keyword.* in application.yml.
 * stopWordConfigs: names of PostgreSQL text search configurations whose stop words are dropped from the
 * query; empty (the default) means every query word counts.
 */
@Configuration
@ConfigurationProperties(prefix = "hivemem.search.keyword")
public class SearchKeywordProperties {

    private List<String> stopWordConfigs = new ArrayList<>();

    /** No-arg constructor required by Spring @ConfigurationProperties. */
    public SearchKeywordProperties() {}

    /** Convenience constructor used in tests — no Spring context needed. */
    public SearchKeywordProperties(List<String> stopWordConfigs) {
        setStopWordConfigs(stopWordConfigs);
    }

    public List<String> getStopWordConfigs() { return stopWordConfigs; }

    public void setStopWordConfigs(List<String> v) {
        this.stopWordConfigs = v == null ? new ArrayList<>() : new ArrayList<>(v);
    }
}
