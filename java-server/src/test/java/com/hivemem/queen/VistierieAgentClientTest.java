package com.hivemem.queen;

import com.hivemem.testsupport.MockVistierieServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VistierieAgentClientTest {

    private MockVistierieServer mock;
    private VistierieAgentClient client;

    @BeforeEach
    void up() {
        mock = new MockVistierieServer();
        mock.start();
        client = new VistierieAgentClient(RestClient.builder(), mock.baseUrl(), "tenant-tok", 30);
    }

    @AfterEach
    void down() { mock.stop(); }

    @Test
    void createsWhenMissing() {
        mock.stubAgentMissingThenCreate("queen");
        client.upsertAgent("queen", Map.of("name", "queen", "system_prompt", "x"));
        verify(postRequestedFor(urlEqualTo("/agents"))
                .withHeader("Authorization", equalTo("Bearer tenant-tok"))
                .withRequestBody(containing("\"name\"")));
    }

    @Test
    void replacesWhenExists() {
        mock.stubAgentExistsThenReplace("queen");
        client.upsertAgent("queen", Map.of("name", "queen", "system_prompt", "x"));
        verify(putRequestedFor(urlEqualTo("/agents/queen"))
                .withHeader("Authorization", equalTo("Bearer tenant-tok"))
                .withRequestBody(notContaining("\"name\"")));
    }

    @Test
    void serverErrorBubbles() {
        mock.stubAgentServerError("queen");
        assertThatThrownBy(() -> client.upsertAgent("queen", Map.of("name", "queen")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void createTolerates409() {
        stubFor(get(urlEqualTo("/agents/queen"))
                .willReturn(aResponse().withStatus(404)));
        stubFor(post(urlEqualTo("/agents"))
                .willReturn(aResponse().withStatus(409)));
        // must NOT throw
        client.upsertAgent("queen", Map.of("name", "queen"));
    }
}
