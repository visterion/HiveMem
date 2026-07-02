package com.hivemem.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "hivemem.embedding")
public class EmbeddingProperties {

    private URI baseUrl = URI.create("http://localhost:8081");
    private Duration timeout = Duration.ofSeconds(30);
    private int maxRetries = 3;
    private long retryBackoffMs = 500;

    public EmbeddingProperties() {
    }

    public EmbeddingProperties(URI baseUrl, Duration timeout) {
        this.baseUrl = baseUrl;
        this.timeout = timeout;
    }

    public URI getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int v) {
        this.maxRetries = v;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long v) {
        this.retryBackoffMs = v;
    }
}
