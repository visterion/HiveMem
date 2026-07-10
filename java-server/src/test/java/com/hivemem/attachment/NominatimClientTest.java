package com.hivemem.attachment;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class NominatimClientTest {

    private WireMockServer server;
    private NominatimClient client;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        GeocodingProperties props = new GeocodingProperties();
        props.setBaseUrl("http://localhost:" + server.port());
        client = new NominatimClient(props);
    }

    @AfterEach
    void tearDown() { server.stop(); }

    @Test
    void buildsCityCommaCountryFromAddress() {
        server.stubFor(get(urlPathEqualTo("/reverse")).willReturn(okJson("""
                {"address":{"city":"Mannheim","country_code":"de"}}
                """)));
        Optional<String> name = client.reverse(49.4874, 8.4660);
        assertThat(name).contains("Mannheim, DE");
    }

    @Test
    void fallsBackToTownThenVillageThenMunicipality() {
        server.stubFor(get(urlPathEqualTo("/reverse")).willReturn(okJson("""
                {"address":{"village":"Edingen","country_code":"de"}}
                """)));
        assertThat(client.reverse(49.0, 8.0)).contains("Edingen, DE");
    }

    @Test
    void throwsGeocodeUnavailableOnServerError() {
        server.stubFor(get(urlPathEqualTo("/reverse")).willReturn(aResponse().withStatus(500)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.reverse(1.0, 2.0))
                .isInstanceOf(NominatimClient.GeocodeUnavailableException.class);
    }

    @Test
    void returnsEmptyWhenNoPlaceInAnswer() {
        server.stubFor(get(urlPathEqualTo("/reverse")).willReturn(okJson("""
                {"address":{"country_code":"de"}}
                """)));
        assertThat(client.reverse(1.0, 2.0)).isEmpty();
    }
}
