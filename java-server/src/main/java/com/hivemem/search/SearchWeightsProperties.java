package com.hivemem.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "hivemem.search.weights")
public class SearchWeightsProperties {

    private double semantic = 0.20;
    private double keyword = 0.25;
    private double recency = 0.15;
    private double importance = 0.15;
    private double popularity = 0.15;
    private double graphProximity = 0.10;

    public SearchWeights toSearchWeights() {
        return new SearchWeights(semantic, keyword, recency, importance, popularity, graphProximity);
    }

    public double getSemantic() { return semantic; }
    public void setSemantic(double v) { this.semantic = v; }

    public double getKeyword() { return keyword; }
    public void setKeyword(double v) { this.keyword = v; }

    public double getRecency() { return recency; }
    public void setRecency(double v) { this.recency = v; }

    public double getImportance() { return importance; }
    public void setImportance(double v) { this.importance = v; }

    public double getPopularity() { return popularity; }
    public void setPopularity(double v) { this.popularity = v; }

    public double getGraphProximity() { return graphProximity; }
    public void setGraphProximity(double v) { this.graphProximity = v; }
}
