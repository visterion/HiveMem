package com.hivemem.queen;

import com.hivemem.testsupport.MockVistierieServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VistierieRunsClientTest {

    private MockVistierieServer mock;

    @BeforeEach
    void up() { mock = new MockVistierieServer(); mock.start(); }

    @AfterEach
    void down() { mock.stop(); }

    private VistierieRunsClient client(String adminToken) {
        return new VistierieRunsClient(RestClient.builder(), mock.baseUrl(), "tenant-tok", adminToken, 30);
    }

    @Test
    void adminListUsesAdminTokenWhenPresent() {
        mock.stubAdminRuns("{\"items\":[{\"id\":\"r1\",\"agent\":\"queen\",\"status\":\"done\"}],\"total\":1}");
        JsonNode out = client("admin-tok").listRuns(50, 0);
        assertThat(out.get("items").get(0).get("id").asString()).isEqualTo("r1");
        verify(getRequestedFor(urlPathEqualTo("/admin/runs"))
                .withHeader("Authorization", equalTo("Bearer admin-tok")));
    }

    @Test
    void tenantListUsedWhenNoAdminToken() {
        mock.stubTenantRuns("{\"items\":[{\"id\":\"r2\",\"agent\":\"queen\",\"status\":\"done\"}]}");
        JsonNode out = client("").listRuns(50, 0);
        assertThat(out.get("items").get(0).get("id").asString()).isEqualTo("r2");
        verify(getRequestedFor(urlPathEqualTo("/runs"))
                .withHeader("Authorization", equalTo("Bearer tenant-tok")));
    }

    @Test
    void detailAndEventsUseTenantToken() {
        mock.stubRunDetail("r1", "{\"id\":\"r1\",\"status\":\"done\",\"output\":{}}");
        mock.stubRunEvents("r1", "[{\"type\":\"subagent_spawned\"}]");
        assertThat(client("admin-tok").getRun("r1").get("status").asString()).isEqualTo("done");
        assertThat(client("admin-tok").getRunEvents("r1").get(0).get("type").asString()).isEqualTo("subagent_spawned");
    }

    @Test
    void serverErrorMapsToUnavailable() {
        mock.stubRunsServerError();
        assertThatThrownBy(() -> client("admin-tok").listRuns(50, 0))
                .isInstanceOf(VistierieUnavailableException.class);
    }
}
