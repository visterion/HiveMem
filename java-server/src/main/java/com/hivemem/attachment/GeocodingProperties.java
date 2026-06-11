package com.hivemem.attachment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "hivemem.geocoding")
public class GeocodingProperties {

    private boolean enabled = true;
    private String baseUrl = "https://nominatim.openstreetmap.org";
    private String userAgent = "HiveMem/1.0 (+https://github.com/visterion/hivemem)";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
}
