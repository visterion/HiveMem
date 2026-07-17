package com.hivemem.oauth;

import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.LoginController;
import com.hivemem.auth.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
 *   <li>{@code GET} renders an explicit consent page (client name + requested scope) with
 *       a session-bound anti-CSRF token. Only the consent form's {@code POST} — after the
 *       CSRF token verifies — issues an authorization code bound to the PKCE challenge and
 *       the user's {@code api_tokens.id}, then redirects to the client's
 *       {@code redirect_uri} with {@code code} and {@code state}. Denying redirects with
 *       {@code error=access_denied}.</li>
 * </ol>
 *
 * <p>The granted scope is constrained to the user's role at issue time: a {@code reader}
 * token can never authorize {@code write} scope (see {@link #constrainScopeToRole}).
 */
@RestController
public class AuthorizationController {

    /** Session attribute holding the one-time consent anti-CSRF token. */
    static final String CSRF_SESSION_ATTR = "hivemem.oauth.csrf";

    private static final SecureRandom RNG = new SecureRandom();

    private static final String CONSENT_HTML_HEAD = """
            <!DOCTYPE html><html><head><meta charset="UTF-8"><title>Authorize access</title><style>
            *{margin:0;padding:0;box-sizing:border-box}
            body{background:#000;color:#ccc;height:100vh;display:flex;align-items:center;justify-content:center;font-family:system-ui,sans-serif}
            main{display:flex;flex-direction:column;align-items:center;gap:16px;max-width:360px;text-align:center}
            h1{font-size:16px;font-weight:normal;color:#fff}
            p{font-size:13px;color:#888}
            .scope{font-family:monospace;color:#ccc}
            .actions{display:flex;gap:24px}
            button{background:transparent;border:1px solid #333;color:#ccc;cursor:pointer;font-size:13px;padding:8px 24px}
            button:hover{border-color:#666;color:#fff}
            </style></head><body><main>
            """;

    private static final String CONSENT_HTML_TAIL = """
            </main></body></html>
            """;

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

        Object validated = validateRequest(responseType, clientId, redirectUri, state,
                codeChallenge, codeChallengeMethod);
        if (validated instanceof ResponseEntity<?> error) return error;
        OAuthRepository.OAuthClient client = (OAuthRepository.OAuthClient) validated;

        // Stage 3 — resolve current user. The AuthFilter pipeline populates this attribute
        // when a valid session cookie or bearer token is present.
        AuthPrincipal principal = resolvePrincipal(request);
        if (principal == null) {
            String fullUrl = request.getRequestURI()
                    + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            String encodedNext = UriUtils.encode(fullUrl, java.nio.charset.StandardCharsets.UTF_8);
            return ResponseEntity.status(302).location(URI.create("/login?next=" + encodedNext)).build();
        }

        // Stage 4 — explicit consent. Do NOT issue a code on GET: render an approval form
        // carrying a session-bound one-time CSRF token; only the consent POST issues.
        String resolvedScope = scope == null || scope.isBlank() ? client.scope() : scope;
        String grantedScope = constrainScopeToRole(resolvedScope, principal.role());
        String csrf = issueCsrfToken(request.getSession(true));
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(consentPage(client, grantedScope, state, redirectUri,
                        codeChallenge, codeChallengeMethod, csrf));
    }

    @PostMapping("/oauth/authorize")
    public ResponseEntity<?> consent(
            @RequestParam(value = "response_type", required = false) String responseType,
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "code_challenge", required = false) String codeChallenge,
            @RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod,
            @RequestParam(value = "csrf", required = false) String csrf,
            @RequestParam(value = "action", required = false) String action,
            HttpServletRequest request
    ) {
        if (!props.isEnabled()) return ResponseEntity.notFound().build();

        Object validated = validateRequest(responseType, clientId, redirectUri, state,
                codeChallenge, codeChallengeMethod);
        if (validated instanceof ResponseEntity<?> error) return error;
        OAuthRepository.OAuthClient client = (OAuthRepository.OAuthClient) validated;

        AuthPrincipal principal = resolvePrincipal(request);
        if (principal == null) {
            return redirectError(redirectUri, "access_denied", state, "not authenticated");
        }

        // Anti-CSRF: the POST must carry the one-time token issued to this session by the
        // consent page. Consumed on use — a replayed form re-renders via GET first.
        if (!consumeCsrfToken(request.getSession(false), csrf)) {
            return redirectError(redirectUri, "access_denied", state, "invalid consent token");
        }

        if (!"approve".equals(action)) {
            return redirectError(redirectUri, "access_denied", state, null);
        }

        String resolvedScope = scope == null || scope.isBlank() ? client.scope() : scope;
        String grantedScope = constrainScopeToRole(resolvedScope, principal.role());
        String code = codes.issue(clientId, redirectUri, grantedScope,
                codeChallenge, codeChallengeMethod, principal.tokenId());

        UriComponentsBuilder cb = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("code", code);
        if (state != null) cb.queryParam("state", state);
        // encode() percent-escapes query values (error_description / state may contain
        // spaces or other reserved characters), otherwise the Location header is invalid.
        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, cb.build().encode().toUriString())
                .build();
    }

    /**
     * Shared stage 1+2 validation for GET and POST. Returns the resolved
     * {@link OAuthRepository.OAuthClient} on success, or a {@link ResponseEntity}
     * error/redirect on failure.
     */
    private Object validateRequest(String responseType, String clientId, String redirectUri,
                                   String state, String codeChallenge, String codeChallengeMethod) {
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
        return client.get();
    }

    /**
     * Constrain the granted scope to what the user's role allows: a {@code reader}
     * token can never be granted {@code write} scope, regardless of what the client
     * requested. Enforced again at access time by
     * {@code AuthFilter.effectiveOauthRole} (defense in depth).
     */
    static String constrainScopeToRole(String scope, AuthRole role) {
        String requested = scope == null || scope.isBlank() ? "read" : scope.trim();
        if (role != AuthRole.READER) return requested;
        String granted = Arrays.stream(requested.split("\\s+"))
                .filter(s -> !"write".equals(s))
                .collect(Collectors.joining(" "));
        return granted.isBlank() ? "read" : granted;
    }

    private static String issueCsrfToken(HttpSession session) {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute(CSRF_SESSION_ATTR, token);
        return token;
    }

    /** Constant-time compare against the session token; consumes it on match. */
    private static boolean consumeCsrfToken(HttpSession session, String submitted) {
        if (session == null || submitted == null || submitted.isEmpty()) return false;
        Object expected = session.getAttribute(CSRF_SESSION_ATTR);
        if (!(expected instanceof String e)) return false;
        boolean matches = MessageDigest.isEqual(
                e.getBytes(StandardCharsets.UTF_8),
                submitted.getBytes(StandardCharsets.UTF_8));
        if (matches) session.removeAttribute(CSRF_SESSION_ATTR);
        return matches;
    }

    private static String consentPage(OAuthRepository.OAuthClient client, String scope,
                                      String state, String redirectUri,
                                      String codeChallenge, String codeChallengeMethod,
                                      String csrf) {
        String clientName = client.clientName() == null ? client.clientId() : client.clientName();
        StringBuilder html = new StringBuilder(CONSENT_HTML_HEAD);
        html.append("<h1>").append(HtmlUtils.htmlEscape(clientName))
                .append(" requests access to HiveMem</h1>\n");
        html.append("<p>Requested scope: <span class=\"scope\">")
                .append(HtmlUtils.htmlEscape(scope)).append("</span></p>\n");
        html.append("<form method=\"POST\" action=\"/oauth/authorize\">\n");
        html.append(hidden("response_type", "code"));
        html.append(hidden("client_id", client.clientId()));
        html.append(hidden("redirect_uri", redirectUri));
        html.append(hidden("scope", scope));
        if (state != null) html.append(hidden("state", state));
        html.append(hidden("code_challenge", codeChallenge));
        html.append(hidden("code_challenge_method", codeChallengeMethod));
        html.append(hidden("csrf", csrf));
        html.append("""
                <div class="actions">
                <button type="submit" name="action" value="approve">Approve</button>
                <button type="submit" name="action" value="deny">Deny</button>
                </div>
                </form>
                """);
        return html.append(CONSENT_HTML_TAIL).toString();
    }

    private static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + HtmlUtils.htmlEscape(name)
                + "\" value=\"" + HtmlUtils.htmlEscape(value) + "\">\n";
    }

    /**
     * Resolve the current user's principal from the request.
     *
     * <p>Three sources, in priority order:
     * <ol>
     *   <li>Test-injected attribute {@code oauth.user_token_id} (used by integration tests
     *       to bypass the session/login flow) — resolved via {@code TokenService.findById}.</li>
     *   <li>The {@link AuthPrincipal} populated by {@code HumanAuthFilter} or
     *       {@code AuthFilter} on the standard request attribute.</li>
     *   <li>The login session cookie, resolved directly (neither filter populates the
     *       principal for {@code /oauth/} paths).</li>
     * </ol>
     */
    static final String TEST_USER_TOKEN_ATTR = "oauth.user_token_id";

    private AuthPrincipal resolvePrincipal(HttpServletRequest request) {
        Object testInjected = request.getAttribute(TEST_USER_TOKEN_ATTR);
        if (testInjected instanceof UUID id) {
            return tokenService.findById(id).orElse(null);
        }
        Object principal = request.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
        if (principal instanceof AuthPrincipal p) return p;
        // Browser session fallback: neither AuthFilter nor HumanAuthFilter populates the
        // principal for /oauth/ paths, so resolve the login session cookie directly here.
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object token = session.getAttribute(LoginController.SESSION_TOKEN_KEY);
            if (token instanceof String t) {
                Optional<AuthPrincipal> sp = tokenService.validateToken(t);
                if (sp.isPresent()) return sp.get();
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
        // encode() percent-escapes query values (error_description / state may contain
        // spaces or other reserved characters), otherwise the Location header is invalid.
        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, cb.build().encode().toUriString())
                .build();
    }
}
