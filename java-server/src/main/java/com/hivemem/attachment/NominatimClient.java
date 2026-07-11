package com.hivemem.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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

    @Autowired
    public NominatimClient(GeocodingProperties props) {
        this(props, RestClient.builder(), true);
    }

    NominatimClient(GeocodingProperties props, RestClient.Builder builder, boolean configureRequestFactory) {
        if (configureRequestFactory) {
            // No request factory means the JDK HTTP client with NO read timeout — a stalled
            // Nominatim response would otherwise hang the calling thread forever. Mirrors
            // KrokiClient's timeout setup.
            int timeoutMs = props.getTimeoutSeconds() * 1000;
            SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
            rf.setConnectTimeout(timeoutMs);
            rf.setReadTimeout(timeoutMs);
            builder = builder.requestFactory(rf);
        }
        this.http = builder.baseUrl(props.getBaseUrl()).build();
        this.userAgent = props.getUserAgent();
    }

    /**
     * @return the resolved place, or empty when Nominatim answered but knows no place there
     *         (a definitive negative).
     * @throws GeocodeUnavailableException on transport/HTTP failures — callers must treat
     *         this as transient and retry later, not as a permanent "failed".
     */
    public Optional<String> reverse(double lat, double lon) {
        JsonNode body;
        try {
            body = http.get()
                    .uri(uri -> uri.path("/reverse")
                            .queryParam("lat", lat)
                            .queryParam("lon", lon)
                            .queryParam("format", "jsonv2")
                            .queryParam("zoom", 10)
                            .build())
                    .header("User-Agent", userAgent)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.debug("Reverse-geocode lookup failed for {},{}: {}", lat, lon, e.getMessage());
            throw new GeocodeUnavailableException(
                    "Reverse-geocode lookup failed for " + lat + "," + lon, e);
        }
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
    }

    /** Transient lookup failure (network/HTTP) — distinct from a definitive "no place found". */
    public static class GeocodeUnavailableException extends RuntimeException {
        public GeocodeUnavailableException(String message, Throwable cause) {
            super(message, cause);
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
