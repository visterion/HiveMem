package com.hivemem.auth;

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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = AuthPrincipal.class.getName();
    private static final String BEARER_PREFIX = "Bearer ";

    private final Optional<TokenService> tokenService;
    private final RateLimiter rateLimiter;
    private final Optional<OAuthRepository> oauthRepository;

    public AuthFilter(Optional<TokenService> tokenService,
                      RateLimiter rateLimiter,
                      Optional<OAuthRepository> oauthRepository) {
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
        this.oauthRepository = oauthRepository;
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
        return !requestPath.startsWith("/mcp") && !requestPath.startsWith("/hooks")
                && !requestPath.startsWith("/sync") && !requestPath.startsWith("/admin")
                && !requestPath.startsWith("/api/attachments");
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
        // Otherwise an attacker can spoof XFF to evade per-IP rate limits.
        String clientIp = tcpPeerAddress(request);

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
            rateLimiter.recordFailure(clientIp);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            rateLimiter.recordFailure(clientIp);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        if (tokenService.isEmpty()) {
            rateLimiter.recordFailure(clientIp);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
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
            rateLimiter.recordFailure(clientIp);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        rateLimiter.clearFailures(clientIp);
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal.get());
        filterChain.doFilter(request, response);
    }

    /**
     * Returns the actual TCP peer remote address by unwrapping any servlet request
     * wrappers (e.g. Spring's {@code ForwardedHeaderFilter} wrapper that rewrites
     * {@code getRemoteAddr()} to the X-Forwarded-For value). The unwrapped underlying
     * request returns the real socket peer IP, which we use for rate-limit bucketing
     * so that attackers cannot evade per-IP limits by rotating XFF headers.
     */
    private static String tcpPeerAddress(HttpServletRequest request) {
        ServletRequest underlying = request;
        while (underlying instanceof HttpServletRequestWrapper w) {
            underlying = w.getRequest();
        }
        return underlying.getRemoteAddr();
    }

    /**
     * Resolve a bearer string against the {@code oauth_tokens} table. If the token is a
     * valid (active, non-revoked, non-expired) {@code access} token, look up the
     * underlying api_tokens row and return a principal whose role is the OAuth scope
     * mapped down to {@link AuthRole}.
     *
     * <p><b>Role capping:</b> OAuth-issued tokens are intentionally capped at
     * {@link AuthRole#WRITER} — they cannot perform admin operations even when the
     * underlying api_tokens row has role {@code admin}. This limits blast radius if a
     * connector session is compromised: an attacker with a stolen access token can
     * still read/write knowledge but cannot create new tokens, manage agents, or
     * touch admin-only endpoints.
     */
    private Optional<AuthPrincipal> resolveOauthPrincipal(String token) {
        String tokenHash = TokenHasher.sha256(token);
        Optional<OAuthRepository.TokenLookup> lookup = oauthRepository.get().lookupActiveToken(tokenHash);
        if (lookup.isEmpty()) return Optional.empty();
        OAuthRepository.TokenLookup t = lookup.get();
        if (!"access".equals(t.kind())) return Optional.empty();

        Optional<AuthPrincipal> backing = tokenService.get().findById(t.userTokenId());
        if (backing.isEmpty()) return Optional.empty();

        AuthRole scopeRole = scopeToRole(t.scope());
        AuthRole effective = capAtWriter(scopeRole);
        return Optional.of(new AuthPrincipal(backing.get().name(), effective, backing.get().tokenId()));
    }

    static AuthRole scopeToRole(String scope) {
        if (scope == null || scope.isBlank()) return AuthRole.READER;
        List<String> parts = Arrays.asList(scope.trim().split("\\s+"));
        if (parts.contains("write")) return AuthRole.WRITER;
        if (parts.contains("read"))  return AuthRole.READER;
        return AuthRole.READER;
    }

    private static AuthRole capAtWriter(AuthRole role) {
        // ADMIN and AGENT are administrative roles — never grant via OAuth.
        return switch (role) {
            case ADMIN, AGENT -> AuthRole.WRITER;
            default -> role;
        };
    }
}
