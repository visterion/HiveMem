package com.hivemem.queen;

import com.hivemem.testsupport.MockVistierieServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Note: uses the repo's WireMock-backed {@link MockVistierieServer} (not
 * {@code MockRestServiceServer}) because {@link VistierieAgentClient}'s package-private
 * constructor sets an explicit {@code SimpleClientHttpRequestFactory} on the builder,
 * which would clobber the request factory {@code MockRestServiceServer.bindTo} installs.
 */
class VistierieAgentClientTriggerTest {

    private MockVistierieServer mock;
    private VistierieAgentClient client;

    @BeforeEach
    void up() {
        mock = new MockVistierieServer();
        mock.start();
        client = new VistierieAgentClient(RestClient.builder(), mock.baseUrl(), "tok", 5);
    }

    @AfterEach
    void down() { mock.stop(); }

    @Test
    void triggerRunPostsRunAndAccepts202() {
        stubFor(post(urlEqualTo("/agents/inbox-archivist/run"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"run_id\":\"r1\"}")));
        client.triggerRun("inbox-archivist", Map.of());
        verify(postRequestedFor(urlEqualTo("/agents/inbox-archivist/run"))
                .withHeader("Authorization", equalTo("Bearer tok")));
    }

    @Test
    void triggerRunSwallowsPaused409() {
        stubFor(post(urlEqualTo("/agents/inbox-archivist/run"))
                .willReturn(aResponse().withStatus(409)));
        assertThatCode(() -> client.triggerRun("inbox-archivist", Map.of())).doesNotThrowAnyException();
    }

    @Test
    void triggerRunSwallowsBudget403() {
        stubFor(post(urlEqualTo("/agents/inbox-archivist/run"))
                .willReturn(aResponse().withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"budget_exceeded_tenant_daily\"}")));
        assertThatCode(() -> client.triggerRun("inbox-archivist", Map.of())).doesNotThrowAnyException();
    }

    @Test
    void triggerRunRethrowsServerError() {
        stubFor(post(urlEqualTo("/agents/inbox-archivist/run"))
                .willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client.triggerRun("inbox-archivist", Map.of()))
                .isInstanceOf(RestClientResponseException.class);
    }
}
