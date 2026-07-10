package com.hivemem.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hivemem.oauth.OAuthProperties;
import com.hivemem.oauth.OAuthRepository;
import com.hivemem.oauth.TokenHasher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = AuthPrincipal.class.getName();
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Header the Cloudflare Tunnel injects/overwrites with the real client IP. Not
     * spoofable by a client going through the tunnel — see {@link SecurityProperties}.
     */
    private static final String CF_CONNECTING_IP_HEADER = "CF-Connecting-IP";

    private static final Duration OAUTH_CACHE_TTL = Duration.ofSeconds(60);
    private static final int OAUTH_CACHE_MAX_SIZE = 1000;

    private final Optional<TokenService> tokenService;
    private final RateLimiter rateLimiter;
    private final Optional<OAuthRepository> oauthRepository;
    private final Optional<OAuthProperties> oauthProperties;
    private final SecurityProperties securityProperties;

    /**
     * Short-TTL cache for OAuth bearer resolution, keyed by the token's SHA-256 hash
     * (never the plaintext). Avoids the two DB lookups (oauth_tokens + api_tokens) on
     * every request. Mirrors {@link CachedTokenService}: revocation/expiry take effect
     * within {@link #OAUTH_CACHE_TTL} at the latest.
     */
    private final Cache<String, Optional<AuthPrincipal>> oauthPrincipalCache = Caffeine.newBuilder()
            .expireAfterWrite(OAUTH_CACHE_TTL)
            .maximumSize(OAUTH_CACHE_MAX_SIZE)
            .build();

    public AuthFilter(Optional<TokenService> tokenService,
                      RateLimiter rateLimiter,
                      Optional<OAuthRepository> oauthRepository,
                      Optional<OAuthProperties> oauthProperties,
                      SecurityProperties securityProperties) {
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
        this.oauthRepository = oauthRepository;
        this.oauthProperties = oauthProperties;
        this.securityProperties = securityProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        // OAuth discovery + registration must be reachable without a token —
        // they are how MCP clients (Claude.ai, ChatGPT) bootstrap the auth flow.
        if (requestPath.startsWith("/.well-known/oauth-")) return true;
        if (requestPath.startsWith("/oauth/")) return true;
        // Vistierie webhooks present their own webhook_token (not an api_tokens bearer);
        // VistierieWebhookController does its own constant-time token check.
        if (requestPath.startsWith("/vistierie")) return true;
        // /mcp, /hooks, /sync and /admin are bearer-authenticated here (/admin only when
        // the request carries an Authorization header — browser sessions are handled by
        // SessionAuthFilter, which runs first and redirects sessionless /admin requests
        // without a bearer to /login). /api/** is session-cookie-only: SessionAuthFilter
        // rejects it before this filter could ever see a bearer token, so it must not
        // be listed here.
        return !requestPath.startsWith("/mcp") && !requestPath.startsWith("/hooks")
                && !requestPath.startsWith("/sync") && !requestPath.startsWith("/admin");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (request.getAttribute(PRINCIPAL_ATTRIBUTE) != null) {
            filterChain.doFilter(request, response);
            return;
        }

        // Use the actual TCP peer address for rate-limit bucketing, NOT the X-Forwarded-For
        // address Spring's ForwardedHeaderFilter would have substituted via getRemoteAddr().
        // Otherwise an attacker can spoof XFF to evade per-IP rate limits. In production
        // HiveMem is reachable only through the Cloudflare Tunnel, so the TCP peer is
        // loopback for every external request; when trusted-proxy is on, key on the
        // tunnel-injected CF-Connecting-IP header instead (see SecurityProperties) — that
        // header is not spoofable by a client going through the tunnel, unlike XFF.
        String clientIp = rateLimitKey(request, securityProperties);

        long retryAfter = rateLimiter.checkRateLimit(clientIp);
        if (retryAfter > 0) {
            response.setIntHeader("Retry-After", (int) retryAfter);
            response.sendError(429);
            return;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization == null
                || authorization.length() < BEARER_PREFIX.length()
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            sendUnauthorized(request, response, clientIp);
            return;
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            sendUnauthorized(request, response, clientIp);
            return;
        }

        if (tokenService.isEmpty()) {
            sendUnauthorized(request, response, clientIp);
            return;
        }

        // Try the api_tokens table first (the long-lived bearer tokens used by Claude Code,
        // CLI scripts, and the legacy MCP integration).
        Optional<AuthPrincipal> principal = tokenService.orElseThrow().validateToken(token);

        // Fall back to OAuth-issued access tokens (Claude.ai/ChatGPT Custom Connector).
        if (principal.isEmpty() && oauthRepository.isPresent()) {
            principal = resolveOauthPrincipal(token);
        }

        if (principal.isEmpty()) {
            sendUnauthorized(request, response, clientIp);
            return;
        }

        rateLimiter.clearFailures(clientIp);
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal.get());
        filterChain.doFilter(request, response);
    }

    /**
     * Emit a 401. For the {@code /mcp} resource, when OAuth is enabled with a configured
     * issuer, include the RFC 9728 {@code WWW-Authenticate: Bearer resource_metadata="…"}
     * header so MCP clients (Claude.ai, ChatGPT) can auto-discover the authorization server
     * via the protected-resource metadata document. Other guarded paths get a bare 401.
     */
    private void sendUnauthorized(HttpServletRequest request, HttpServletResponse response, String clientIp)
            throws IOException {
        rateLimiter.recordFailure(clientIp);
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        if (requestPath.startsWith("/mcp") && oauthProperties.isPresent()) {
            OAuthProperties props = oauthProperties.get();
            if (props.isEnabled() && props.getIssuer() != null && !props.getIssuer().isBlank()) {
                response.setHeader("WWW-Authenticate",
                        "Bearer resource_metadata=\"" + props.getIssuer()
                                + "/.well-known/oauth-protected-resource\"");
            }
        }
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    /**
     * Returns the actual TCP peer remote address by unwrapping any servlet request
     * wrappers (e.g. Spring's {@code ForwardedHeaderFilter} wrapper that rewrites
     * {@code getRemoteAddr()} to the X-Forwarded-For value). The unwrapped underlying
     * request returns the real socket peer IP, which we use for rate-limit bucketing
     * so that attackers cannot evade per-IP limits by rotating XFF headers.
     *
     * <p>Public so other rate-limited entry points (e.g. {@link LoginController})
     * bucket on the same unspoofable address.
     */
    public static String tcpPeerAddress(HttpServletRequest request) {
        ServletRequest underlying = request;
        while (underlying instanceof HttpServletRequestWrapper w) {
            underlying = w.getRequest();
        }
        return underlying.getRemoteAddr();
    }

    /**
     * Resolve the rate-limit bucket key for a request: when {@link SecurityProperties#isTrustedProxy()}
     * is on and the request carries a {@code CF-Connecting-IP} header, key on that
     * (single) header value — it is injected/overwritten by the Cloudflare Tunnel, so a
     * client going through the tunnel cannot spoof it. Otherwise fall back to
     * {@link #tcpPeerAddress(HttpServletRequest)}, which preserves the existing
     * anti-XFF-spoofing property for direct (non-tunnel) access such as the LAN.
     *
     * <p>Public so other rate-limited entry points (e.g. {@link LoginController}) bucket
     * on the same key.
     */
    public static String rateLimitKey(HttpServletRequest request, SecurityProperties securityProperties) {
        if (securityProperties.isTrustedProxy()) {
            String cfConnectingIp = request.getHeader(CF_CONNECTING_IP_HEADER);
            if (cfConnectingIp != null && !cfConnectingIp.isBlank()) {
                return cfConnectingIp.trim();
            }
        }
        return tcpPeerAddress(request);
    }

    /**
     * Resolve a bearer string against the {@code oauth_tokens} table. If the token is a
     * valid (active, non-revoked, non-expired) {@code access} token, look up the
     * underlying api_tokens row and return a principal whose effective role is the
     * <em>minimum</em> of the granted OAuth scope and the backing token's own role
     * (see {@link #effectiveOauthRole}).
     *
     * <p>Results are cached for {@link #OAUTH_CACHE_TTL}, keyed by token hash.
     */
    private Optional<AuthPrincipal> resolveOauthPrincipal(String token) {
        String tokenHash = TokenHasher.sha256(token);
        return oauthPrincipalCache.get(tokenHash, this::lookupOauthPrincipal);
    }

    private Optional<AuthPrincipal> lookupOauthPrincipal(String tokenHash) {
        Optional<OAuthRepository.TokenLookup> lookup = oauthRepository.get().lookupActiveToken(tokenHash);
        if (lookup.isEmpty()) return Optional.empty();
        OAuthRepository.TokenLookup t = lookup.get();
        if (!"access".equals(t.kind())) return Optional.empty();

        Optional<AuthPrincipal> backing = tokenService.get().findById(t.userTokenId());
        if (backing.isEmpty()) return Optional.empty();

        AuthRole effective = effectiveOauthRole(backing.get().role(), t.scope());
        return Optional.of(new AuthPrincipal(backing.get().name(), effective, backing.get().tokenId()));
    }

    static AuthRole scopeToRole(String scope) {
        if (scope == null || scope.isBlank()) return AuthRole.READER;
        List<String> parts = Arrays.asList(scope.trim().split("\\s+"));
        if (parts.contains("write")) return AuthRole.WRITER;
        if (parts.contains("read"))  return AuthRole.READER;
        return AuthRole.READER;
    }

    /**
     * Effective role of an OAuth-issued access token: the minimum of the scope-derived
     * role and the backing {@code api_tokens} row's role. The scope can only ever
     * <em>narrow</em> the backing role — a {@code reader} token cannot escalate to
     * WRITER by requesting {@code scope=write}.
     *
     * <p><b>Role capping:</b> ADMIN is additionally capped at {@link AuthRole#WRITER} —
     * OAuth sessions can never perform admin operations (create tokens, manage agents,
     * admin endpoints), limiting blast radius if a connector session is compromised.
     * An AGENT backing token keeps its pending-write semantics (AGENT, never WRITER),
     * and a read-only scope narrows it to READER.
     */
    static AuthRole effectiveOauthRole(AuthRole backingRole, String scope) {
        AuthRole scopeRole = scopeToRole(scope); // READER or WRITER
        return switch (backingRole) {
            case READER -> AuthRole.READER;
            case AGENT -> scopeRole == AuthRole.WRITER ? AuthRole.AGENT : AuthRole.READER;
            case ADMIN, WRITER -> scopeRole;
        };
    }
}
