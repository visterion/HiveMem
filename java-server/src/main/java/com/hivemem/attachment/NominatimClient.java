package com.hivemem.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.Optional;

/** Reverse-geocodes GPS coordinates to a short "City, CC" name via a Nominatim endpoint. */
@Component
public class NominatimClient {

    private static final Logger log = LoggerFactory.getLogger(NominatimClient.class);

    private final RestClient http;
    private final String userAgent;

    public NominatimClient(GeocodingProperties props) {
        this.http = RestClient.builder().baseUrl(props.getBaseUrl()).build();
        this.userAgent = props.getUserAgent();
    }

    public Optional<String> reverse(double lat, double lon) {
        try {
            JsonNode body = http.get()
                    .uri(uri -> uri.path("/reverse")
                            .queryParam("lat", lat)
                            .queryParam("lon", lon)
                            .queryParam("format", "jsonv2")
                            .queryParam("zoom", 10)
                            .build())
                    .header("User-Agent", userAgent)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) return Optional.empty();
            JsonNode addr = body.path("address");
            String place = firstNonBlank(
                    textOrNull(addr, "city"),
                    textOrNull(addr, "town"),
                    textOrNull(addr, "village"),
                    textOrNull(addr, "municipality"));
            if (place == null) return Optional.empty();
            String cc = addr.path("country_code").asText("");
            return Optional.of(cc.isBlank() ? place : place + ", " + cc.toUpperCase());
        } catch (Exception e) {
            log.debug("Reverse-geocode failed for {},{}: {}", lat, lon, e.getMessage());
            return Optional.empty();
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        String v = node.path(field).asText("");
        return v.isBlank() ? null : v;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
