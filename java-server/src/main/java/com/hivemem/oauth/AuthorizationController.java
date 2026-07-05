package com.hivemem.oauth;

import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.LoginController;
import com.hivemem.auth.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The OAuth 2.0 authorization endpoint. The user-agent (browser) lands here after
 * the MCP client redirects with {@code client_id}, {@code redirect_uri},
 * {@code response_type=code}, {@code scope}, {@code state}, and a PKCE
 * {@code code_challenge}.
 *
 * <p>Behavior:
 * <ol>
 *   <li>Validate {@code client_id} against {@code oauth_clients} and confirm the supplied
 *       {@code redirect_uri} is registered. Errors here return 400 directly — we don't
 *       redirect to an unverified URI (RFC 6749 §3.1.2).</li>
 *   <li>Validate PKCE — {@code code_challenge} required, method must be {@code S256}.
 *       Errors here redirect with {@code error=invalid_request}.</li>
 *   <li>Resolve the current user from the session (via the standard {@code AuthFilter}
 *       pipeline). If unauthenticated, redirect to {@code /login?next=...} so login bounces
 *       back to this same authorization request.</li>
 *   <li>Issue an authorization code bound to the PKCE challenge and the user's
 *       {@code api_tokens.id}, then redirect to the client's {@code redirect_uri} with
 *       {@code code} and {@code state}.</li>
 * </ol>
 */
@RestController
public class AuthorizationController {

    private final OAuthProperties props;
    private final OAuthRepository repo;
    private final AuthorizationCodeService codes;
    private final TokenService tokenService;

    public AuthorizationController(OAuthProperties props, OAuthRepository repo,
                                    AuthorizationCodeService codes, TokenService tokenService) {
        this.props = props;
        this.repo = repo;
        this.codes = codes;
        this.tokenService = tokenService;
    }

    @GetMapping("/oauth/authorize")
    public ResponseEntity<?> authorize(
            @RequestParam(value = "response_type", required = false) String responseType,
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "code_challenge", required = false) String codeChallenge,
            @RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod,
            HttpServletRequest request
    ) {
        if (!props.isEnabled()) return ResponseEntity.notFound().build();

        // Stage 1 — validate client_id and redirect_uri *before* any redirect (RFC 6749 §3.1.2).
        if (clientId == null || clientId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_request",
                    "error_description", "client_id required"));
        }
        Optional<OAuthRepository.OAuthClient> client = repo.findClient(clientId);
        if (client.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_client",
                    "error_description", "Unknown client_id"));
        }
        if (redirectUri == null || !client.get().redirectUris().contains(redirectUri)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_request",
                    "error_description", "redirect_uri does not match a registered URI"));
        }

        // Stage 2 — from here on, errors redirect back with error=... per RFC 6749 §4.1.2.1.
        if (!"code".equals(responseType)) {
            return redirectError(redirectUri, "unsupported_response_type", state, null);
        }
        if (codeChallenge == null || codeChallenge.isBlank()) {
            return redirectError(redirectUri, "invalid_request", state, "code_challenge required");
        }
        if (!"S256".equals(codeChallengeMethod)) {
            return redirectError(redirectUri, "invalid_request", state,
                    "code_challenge_method must be S256");
        }

        // Stage 3 — resolve current user. The AuthFilter pipeline populates this attribute
        // when a valid session cookie or bearer token is present.
        UUID userTokenId = resolveUserTokenId(request);
        if (userTokenId == null) {
            String fullUrl = request.getRequestURI()
                    + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            String encodedNext = UriUtils.encode(fullUrl, java.nio.charset.StandardCharsets.UTF_8);
            return ResponseEntity.status(302).location(URI.create("/login?next=" + encodedNext)).build();
        }

        String resolvedScope = scope == null || scope.isBlank() ? client.get().scope() : scope;
        String code = codes.issue(clientId, redirectUri, resolvedScope,
                codeChallenge, codeChallengeMethod, userTokenId);

        UriComponentsBuilder cb = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("code", code);
        if (state != null) cb.queryParam("state", state);
        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, cb.build().toUriString())
                .build();
    }

    /**
     * Resolve the user's api_tokens.id from the current request.
     *
     * <p>Three sources, in priority order:
     * <ol>
     *   <li>Test-injected attribute {@code oauth.user_token_id} (used by integration tests
     *       to bypass the session/login flow).</li>
     *   <li>The {@link AuthPrincipal} populated by {@code SessionAuthFilter} or
     *       {@code AuthFilter} on the standard request attribute.</li>
     * </ol>
     */
    static final String TEST_USER_TOKEN_ATTR = "oauth.user_token_id";

    private UUID resolveUserTokenId(HttpServletRequest request) {
        Object testInjected = request.getAttribute(TEST_USER_TOKEN_ATTR);
        if (testInjected instanceof UUID id) return id;
        Object principal = request.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
        if (principal instanceof AuthPrincipal p) return p.tokenId();
        // Browser session fallback: neither AuthFilter nor SessionAuthFilter populates the
        // principal for /oauth/ paths, so resolve the login session cookie directly here.
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object token = session.getAttribute(LoginController.SESSION_TOKEN_KEY);
            if (token instanceof String t) {
                Optional<AuthPrincipal> sp = tokenService.validateToken(t);
                if (sp.isPresent()) return sp.get().tokenId();
            }
        }
        return null;
    }

    private static ResponseEntity<Void> redirectError(String redirectUri, String error,
                                                       String state, String desc) {
        UriComponentsBuilder cb = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("error", error);
        if (desc != null) cb.queryParam("error_description", desc);
        if (state != null) cb.queryParam("state", state);
        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, cb.build().toUriString())
                .build();
    }
}
