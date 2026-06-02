package com.hivemem.testsupport;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

public class MockVistierieServer {
    private final WireMockServer wm = new WireMockServer(0);

    public void start() { wm.start(); configureFor("localhost", wm.port()); }
    public void stop()  { wm.stop(); }
    public String baseUrl() { return "http://localhost:" + wm.port(); }

    public void stubComplete(String text) {
        stubFor(post(urlEqualTo("/llm/complete")).willReturn(okJson("""
                {"text": "%s",
                 "stop_reason":"end_turn",
                 "usage":{"inputTokens":10,"outputTokens":3,
                          "cacheCreationInputTokens":0,"cacheReadInputTokens":0},
                 "provider":"anthropic","model":"claude-haiku-4-5",
                 "cost_micros":0,"llm_call_id":"X"}
                """.formatted(text))));
    }

    public void stubVision(String text) {
        stubFor(post(urlEqualTo("/llm/vision")).willReturn(okJson("""
                {"text":"%s","stop_reason":"end_turn",
                 "usage":{"inputTokens":50,"outputTokens":4,
                          "cacheCreationInputTokens":0,"cacheReadInputTokens":0},
                 "provider":"anthropic","model":"claude-haiku-4-5",
                 "cost_micros":0,"llm_call_id":"Y"}
                """.formatted(text))));
    }

    /** Agent does not exist yet → GET 404, so the client will POST to create. */
    public void stubAgentMissingThenCreate(String name) {
        stubFor(get(urlEqualTo("/agents/" + name))
                .willReturn(aResponse().withStatus(404)));
        stubFor(post(urlEqualTo("/agents"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"" + name + "\",\"version\":1}")));
    }

    /** Agent already exists → GET 200, so the client will PUT to replace. */
    public void stubAgentExistsThenReplace(String name) {
        stubFor(get(urlEqualTo("/agents/" + name))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"" + name + "\",\"version\":2}")));
        stubFor(put(urlEqualTo("/agents/" + name))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"" + name + "\",\"version\":3}")));
    }

    /** Vistierie unreachable for registration → 500 on create path. */
    public void stubAgentServerError(String name) {
        stubFor(get(urlEqualTo("/agents/" + name))
                .willReturn(aResponse().withStatus(404)));
        stubFor(post(urlEqualTo("/agents"))
                .willReturn(aResponse().withStatus(500)));
    }

    /** Admin runs list with cost fields. */
    public void stubAdminRuns(String body) {
        stubFor(get(urlPathEqualTo("/admin/runs"))
                .willReturn(okJson(body)));
    }

    /** Tenant runs list (no cost). */
    public void stubTenantRuns(String body) {
        stubFor(get(urlPathEqualTo("/runs"))
                .willReturn(okJson(body)));
    }

    public void stubRunDetail(String runId, String body) {
        stubFor(get(urlEqualTo("/runs/" + runId)).willReturn(okJson(body)));
    }

    public void stubRunEvents(String runId, String body) {
        stubFor(get(urlEqualTo("/runs/" + runId + "/events")).willReturn(okJson(body)));
    }

    public void stubRunsServerError() {
        stubFor(get(urlPathEqualTo("/admin/runs")).willReturn(aResponse().withStatus(500)));
        stubFor(get(urlPathEqualTo("/runs")).willReturn(aResponse().withStatus(500)));
    }
}
