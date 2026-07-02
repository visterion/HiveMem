package com.hivemem.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.springframework.http.HttpStatus;

class EmbeddingRetryTest {

    private static EmbeddingProperties fastProps(int maxRetries) {
        EmbeddingProperties props = new EmbeddingProperties(
                URI.create("https://embeddings.local"), Duration.ofSeconds(2));
        props.setMaxRetries(maxRetries);
        props.setRetryBackoffMs(1); // fast
        return props;
    }

    @Test
    void retriesTransient5xxThenSucceeds() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpEmbeddingClient client = new HttpEmbeddingClient(builder, fastProps(3), false);

        // First call: 503
        server.expect(once(), requestTo("https://embeddings.local/embeddings"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        // Second call: success
        server.expect(once(), requestTo("https://embeddings.local/embeddings"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"vector\":[0.1,0.2],\"model\":\"m\",\"dimension\":2}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.encodeDocument("hello")).containsExactly(0.1f, 0.2f);
        server.verify();
    }

    @Test
    void exhaustedRetriesThrowsEmbeddingUnavailable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpEmbeddingClient client = new HttpEmbeddingClient(builder, fastProps(3), false);

        // 1 initial + 3 retries = 4 total
        server.expect(times(4), requestTo("https://embeddings.local/embeddings"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));

        assertThatThrownBy(() -> client.encodeDocument("hello"))
                .isInstanceOf(EmbeddingUnavailableException.class);
        server.verify();
    }

    @Test
    void clientError4xxNotRetried() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpEmbeddingClient client = new HttpEmbeddingClient(builder, fastProps(3), false);

        server.expect(once(), requestTo("https://embeddings.local/embeddings"))
                .andExpect(method(POST))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> client.encodeDocument("hello"))
                .isNotInstanceOf(EmbeddingUnavailableException.class);
        server.verify();
    }
}
