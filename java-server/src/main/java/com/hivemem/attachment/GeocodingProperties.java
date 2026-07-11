package com.hivemem.attachment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "hivemem.geocoding")
public class GeocodingProperties {

    private boolean enabled = true;
    private String baseUrl = "https://nominatim.openstreetmap.org";
    private String userAgent = "HiveMem/1.0 (+https://github.com/visterion/hivemem)";
    /** Connect+read timeout for reverse-geocode calls. Without this, the JDK HTTP client used
     *  by RestClient has NO read timeout, so a stalled Nominatim response hangs the calling
     *  scheduler thread forever (GeocodingService.retryPendingGeocodes shares a small pool). */
    private int timeoutSeconds = 10;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
