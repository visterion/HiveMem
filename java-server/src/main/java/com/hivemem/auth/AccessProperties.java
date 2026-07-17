package com.hivemem.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Cloudflare Access configuration. When disabled (the default, and the only mode
 * self-hosted deployments without Cloudflare can use), humans authenticate with the
 * legacy session login instead.
 */
@Component
@ConfigurationProperties(prefix = "hivemem.access")
public class AccessProperties {

    private boolean enabled = false;
    private String teamDomain = "";
    private String audience = "";
    private Duration jwksCacheTtl = Duration.ofMinutes(15);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getTeamDomain() { return teamDomain; }
    public void setTeamDomain(String teamDomain) { this.teamDomain = teamDomain; }

    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }

    public Duration getJwksCacheTtl() { return jwksCacheTtl; }
    public void setJwksCacheTtl(Duration jwksCacheTtl) { this.jwksCacheTtl = jwksCacheTtl; }
}
