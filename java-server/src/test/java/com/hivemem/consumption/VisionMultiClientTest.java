package com.hivemem.consumption;

import com.hivemem.queen.QueenProperties;
import com.hivemem.testsupport.MockVistierieServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins VisionMultiClient to Vistierie's generic multi-image vision endpoint
 * (POST /llm/vision-multi). Mirrors VistierieSeparationClientTest.
 */
class VisionMultiClientTest {

    private MockVistierieServer mock;
    private VisionMultiClient client;

    @BeforeEach
    void up() {
        mock = new MockVistierieServer();
        mock.start();
        QueenProperties props = new QueenProperties();
        props.setVistierieBaseUrl(mock.baseUrl());
        props.setVistierieToken("tenant-tok");
        props.setDocumentSeparatorAgent("document-separator");
        props.setCallTimeoutSeconds(30);
        client = new VisionMultiClient(RestClient.builder(), props, new ConsumptionProperties());
    }

    @AfterEach
    void down() { mock.stop(); }

    @Test
    void postsToVisionMultiAndReturnsModelText() {
        String modelText = "[[{\"page\":1,\"docId\":\"A\"}]]";
        stubFor(post(urlEqualTo("/llm/vision-multi"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"text\":" + tools.jackson.databind.json.JsonMapper.builder().build()
                                .writeValueAsString(modelText)
                                + ",\"provider\":\"bedrock\",\"model\":\"claude-haiku-4-5\"}")));

        List<VisionMultiClient.Image> images = List.of(
                new VisionMultiClient.Image("image/png", "QQ=="),
                new VisionMultiClient.Image("image/png", "Qg=="));

        String text = client.group("documents", "GROUP THE PAGES", images);

        assertThat(text).isEqualTo(modelText);
        verify(postRequestedFor(urlEqualTo("/llm/vision-multi"))
                .withHeader("Authorization", equalTo("Bearer tenant-tok"))
                .withRequestBody(containing("\"agent_name\""))
                .withRequestBody(containing("\"purpose\""))
                .withRequestBody(containing("\"images\""))
                .withRequestBody(containing("GROUP THE PAGES")));
    }
}
