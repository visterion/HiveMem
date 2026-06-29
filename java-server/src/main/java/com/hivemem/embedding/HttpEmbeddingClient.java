package com.hivemem.embedding;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class HttpEmbeddingClient implements EmbeddingClient {

    private final RestClient restClient;
    private volatile int expectedDimension = -1;
    private final int maxRetries;
    private final long retryBackoffMs;

    @Autowired
    public HttpEmbeddingClient(RestClient.Builder builder, EmbeddingProperties properties) {
        this(builder, properties, true);
    }

    HttpEmbeddingClient(RestClient.Builder builder, EmbeddingProperties properties, boolean configureRequestFactory) {
        int timeoutMillis = Math.toIntExact(properties.getTimeout().toMillis());
        if (configureRequestFactory) {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(timeoutMillis);
            requestFactory.setReadTimeout(timeoutMillis);
            builder = builder.requestFactory(requestFactory);
        }
        this.restClient = builder.baseUrl(properties.getBaseUrl().toString()).build();
        this.maxRetries = Math.max(0, properties.getMaxRetries());
        this.retryBackoffMs = Math.max(0, properties.getRetryBackoffMs());
    }

    @Override
    public EmbeddingInfo getInfo() {
        InfoResponse response = restClient.get()
                .uri("/info")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(InfoResponse.class);
        if (response == null || response.model() == null) {
            throw new IllegalStateException("Embedding service /info returned invalid response");
        }
        this.expectedDimension = response.dimension();
        return new EmbeddingInfo(response.model(), response.dimension());
    }

    @Override
    public List<Float> encodeDocument(String text) {
        return encode(text, "document");
    }

    @Override
    public List<Float> encodeQuery(String text) {
        return encode(text, "query");
    }

    private List<Float> encode(String text, String mode) {
        String jsonBody = "{\"text\":" + toJsonString(text) + ",\"mode\":\"" + mode + "\"}";
        int attempt = 0;
        while (true) {
            try {
                EmbeddingResponse response = restClient.post()
                        .uri("/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(jsonBody)
                        .retrieve()
                        .body(EmbeddingResponse.class);
                if (response == null || response.vector() == null) {
                    throw new IllegalStateException("Missing embedding vector");
                }
                if (expectedDimension > 0 && response.vector().size() != expectedDimension) {
                    throw new IllegalStateException("Embedding dimension mismatch: expected "
                            + expectedDimension + " but got " + response.vector().size());
                }
                return List.copyOf(response.vector());
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                throw e; // 4xx deterministic — no retry
            } catch (org.springframework.web.client.HttpServerErrorException
                     | org.springframework.web.client.ResourceAccessException e) {
                if (attempt++ >= maxRetries) {
                    throw new EmbeddingUnavailableException(
                            "Embedding service unavailable after " + attempt + " attempt(s)", e);
                }
                sleepBackoff(attempt);
            }
        }
    }

    private void sleepBackoff(int attempt) {
        long delay = retryBackoffMs * (1L << Math.min(attempt - 1, 30)); // cap shift so large retry counts can't wrap
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new EmbeddingUnavailableException("Interrupted during embedding retry backoff", ie);
        }
    }

    private static String toJsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    record InfoResponse(String model, int dimension) {
    }

    record EmbeddingResponse(List<Float> vector, String model, Integer dimension) {
    }
}
