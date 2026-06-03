package com.hivemem.consumption;

import com.hivemem.queen.QueenProperties;
import com.hivemem.testsupport.MockVistierieServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

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
 * Pins the dispatch to Vistierie's real run-creation contract (RunController#trigger):
 * POST /agents/{name}/run with {payload, completion_webhook, completion_webhook_token}, and the
 * returned run_id parsed from the 202 body.
 */
class VistierieSeparationClientTest {

    private MockVistierieServer mock;
    private VistierieSeparationClient client;

    @BeforeEach
    void up() {
        mock = new MockVistierieServer();
        mock.start();
        QueenProperties props = new QueenProperties();
        props.setVistierieBaseUrl(mock.baseUrl());
        props.setVistierieToken("tenant-tok");
        props.setDocumentSeparatorAgent("document-separator");
        props.setHivememBaseUrl("http://hivemem:8080");
        props.setSeparationWebhookToken("sep-tok");
        props.setCallTimeoutSeconds(30);
        client = new VistierieSeparationClient(RestClient.builder(), props);
    }

    @AfterEach
    void down() { mock.stop(); }

    @Test
    void postsToSingularRunPathWithCorrectBodyAndParsesRunId() {
        stubFor(post(urlEqualTo("/agents/document-separator/run"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"run_id\":\"run-123\",\"agent_name\":\"document-separator\","
                                + "\"agent_version\":1,\"status\":\"queued\"}")));

        UUID corr = UUID.randomUUID();
        List<PageDigest> digests = List.of(
                new PageDigest(1, "head one", "tail one", false, false),
                new PageDigest(2, "head two", "tail two", true, true));

        String runId = client.dispatch(corr, digests);

        assertThat(runId).isEqualTo("run-123");
        verify(postRequestedFor(urlEqualTo("/agents/document-separator/run"))
                .withHeader("Authorization", equalTo("Bearer tenant-tok"))
                // correlation id + pages ride inside the free-form payload
                .withRequestBody(containing("\"payload\""))
                .withRequestBody(containing(corr.toString()))
                .withRequestBody(containing("\"pages\""))
                // completion webhook is a flat url string + separate token field (not a nested object)
                .withRequestBody(containing("\"completion_webhook\":\"http://hivemem:8080/vistierie/separation/done\""))
                .withRequestBody(containing("\"completion_webhook_token\":\"sep-tok\"")));
    }
}
