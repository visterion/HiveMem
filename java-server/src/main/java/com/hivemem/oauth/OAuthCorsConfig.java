package com.hivemem.oauth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * CORS configuration for the OAuth + MCP endpoints. MCP clients (Claude.ai, ChatGPT, Grok) invoke
 * discovery, registration and the MCP endpoint from a browser context, which requires preflight
 * OPTIONS to be answered with appropriate Access-Control-* headers.
 *
 * <p>Allowlist is intentionally narrow — the known MCP host origins plus the server's own issuer
 * origin. The issuer must be allowed because the OAuth consent form is served from the issuer's
 * page and POSTs back to it: behind a reverse proxy (Cloudflare) the app sees that same-origin
 * POST as cross-origin, so without the issuer in the allowlist the consent submit is rejected as
 * "Invalid CORS request".
 */
@Configuration
public class OAuthCorsConfig {

    // Claude uses both claude.ai and claude.com (and app subdomains); ChatGPT and Grok their
    // own hosts. Patterns (allowedOriginPatterns) so a subdomain variant doesn't break the
    // connector.
    private static final List<String> ALLOWED_ORIGINS = List.of(
            "https://claude.ai",
            "https://*.claude.ai",
            "https://claude.com",
            "https://*.claude.com",
            "https://chatgpt.com",
            "https://chat.openai.com",
            "https://grok.com",
            "https://*.grok.com"
    );

    @Bean
    public WebMvcConfigurer oauthCorsConfigurer(OAuthProperties oauthProperties) {
        List<String> originList = new ArrayList<>(ALLOWED_ORIGINS);
        String issuer = oauthProperties.getIssuer();
        if (issuer != null && !issuer.isBlank()) {
            originList.add(issuer);
        }
        String[] origins = originList.toArray(String[]::new);
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/.well-known/oauth-**")
                        .allowedOriginPatterns(origins)
                        .allowedMethods("GET", "OPTIONS")
                        .maxAge(3600);
                registry.addMapping("/oauth/**")
                        .allowedOriginPatterns(origins)
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders("Authorization", "Content-Type", "Accept")
                        .maxAge(3600);
                // The MCP endpoint itself: a browser-context MCP client (claude.ai, ChatGPT,
                // Grok) preflights /mcp and must be able to read the 401 + WWW-Authenticate
                // (RFC 9728) that bootstraps OAuth, plus the Mcp-Session-Id on the Streamable
                // HTTP transport. Auth still applies (the AuthFilter guards non-OPTIONS /mcp);
                // this only makes the endpoint CORS-reachable from the browser.
                registry.addMapping("/mcp/**")
                        .allowedOriginPatterns(origins)
                        .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                        .allowedHeaders("Authorization", "Content-Type", "Accept",
                                "Mcp-Session-Id", "Mcp-Protocol-Version")
                        .exposedHeaders("WWW-Authenticate", "Mcp-Session-Id")
                        .maxAge(3600);
                registry.addMapping("/mcp")
                        .allowedOriginPatterns(origins)
                        .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                        .allowedHeaders("Authorization", "Content-Type", "Accept",
                                "Mcp-Session-Id", "Mcp-Protocol-Version")
                        .exposedHeaders("WWW-Authenticate", "Mcp-Session-Id")
                        .maxAge(3600);
            }
        };
    }
}
