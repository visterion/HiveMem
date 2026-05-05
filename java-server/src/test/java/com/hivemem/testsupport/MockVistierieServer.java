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
}
