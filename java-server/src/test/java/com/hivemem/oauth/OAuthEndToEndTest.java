package com.hivemem.oauth;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.hivemem.auth.LoginController;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end OAuth flow:
 * <ol>
 *   <li>Discovery (.well-known)</li>
 *   <li>Dynamic Client Registration (POST /oauth/register)</li>
 *   <li>Authorization (GET /oauth/authorize renders consent, POST issues the code) —
 *       using a test-injected user_token_id to bypass the interactive login flow</li>
 *   <li>Token exchange (POST /oauth/token, grant_type=authorization_code, with PKCE)</li>
 *   <li>Refresh (POST /oauth/token, grant_type=refresh_token)</li>
 *   <li>Replay-detection — re-using the rotated refresh token revokes the chain</li>
 *   <li>Reuse-of-rotated — even the new (post-replay) refresh is invalidated</li>
 * </ol>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(OAuthEndToEndTest.TestConfig.class)
@TestPropertySource(properties = {
        "hivemem.oauth.enabled=true",
        "hivemem.oauth.issuer=https://hivemem.example.com"
})
class OAuthEndToEndTest {

    // RFC 7636 Appendix B PKCE test vector
    private static final String VERIFIER  = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    private static final String REDIRECT_URI = "https://claude.ai/api/mcp/auth_callback";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean @Primary
        EmbeddingClient testEmbeddingClient() { return new FixedEmbeddingClient(); }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null
                            ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig())
                            .withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired MockMvc mvc;
    @Autowired DSLContext dsl;
    private static final ObjectMapper json = new ObjectMapper();

    private UUID userTokenId;

    @BeforeEach
    void seed() {
        dsl.execute("TRUNCATE TABLE oauth_tokens, oauth_authorization_codes, oauth_clients CASCADE");
        dsl.execute("DELETE FROM api_tokens WHERE name LIKE 'oauth-e2e-%'");
        userTokenId = dsl.fetchOne("""
                INSERT INTO api_tokens (token_hash, name, role)
                VALUES (?, ?, 'admin')
                RETURNING id
                """, "test-hash-" + UUID.randomUUID(), "oauth-e2e-" + UUID.randomUUID())
                .get("id", UUID.class);
    }

    @Test
    void discoveryReturnsAuthorizationServerMetadata() throws Exception {
        mvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("https://hivemem.example.com"))
                .andExpect(jsonPath("$.authorization_endpoint").value("https://hivemem.example.com/oauth/authorize"))
                .andExpect(jsonPath("$.token_endpoint").value("https://hivemem.example.com/oauth/token"))
                .andExpect(jsonPath("$.registration_endpoint").value("https://hivemem.example.com/oauth/register"))
                .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"))
                .andExpect(jsonPath("$.token_endpoint_auth_methods_supported[0]").value("none"));
    }

    @Test
    void fullFlow_register_authorize_exchange_refresh_replayDetect() throws Exception {
        // 1. Register a client (DCR)
        Map<String, Object> regBody = Map.of(
                "client_name", "E2E Test Connector",
                "redirect_uris", List.of(REDIRECT_URI),
                "token_endpoint_auth_method", "none"
        );
        MvcResult regResult = mvc.perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(regBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_id").isNotEmpty())
                .andReturn();
        String clientId = json.readTree(regResult.getResponse().getContentAsString())
                .get("client_id").asText();

        // 2. Authorize — consent flow: GET renders the consent page, POST issues the code.
        //    Inject user_token_id directly to bypass session login.
        URI redirect = URI.create(authorizeWithConsent(
                clientId, "read write", "e2e-state", new MockHttpSession(), userTokenId));
        assertEquals("claude.ai", redirect.getHost());
        String code  = extractParam(redirect.getQuery(), "code");
        String state = extractParam(redirect.getQuery(), "state");
        assertEquals("e2e-state", state);
        assertNotNull(code, "authorization code must be present in redirect");

        // 3. Token exchange
        MvcResult tokenResult = mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("client_id", clientId)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_verifier", VERIFIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andReturn();
        JsonNode tokens = json.readTree(tokenResult.getResponse().getContentAsString());
        String accessToken1  = tokens.get("access_token").asText();
        String refreshToken1 = tokens.get("refresh_token").asText();
        assertNotEquals(accessToken1, refreshToken1, "access and refresh must differ");

        // 4. Replay code — must fail (atomic consume)
        mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("client_id", clientId)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_verifier", VERIFIER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));

        // 5. Refresh once — succeeds, issues new pair
        MvcResult refreshResult = mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken1)
                        .param("client_id", clientId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode refreshed = json.readTree(refreshResult.getResponse().getContentAsString());
        String refreshToken2 = refreshed.get("refresh_token").asText();
        assertNotEquals(refreshToken1, refreshToken2, "refresh rotation must produce a fresh token");

        // 6. Reuse OLD refresh — must fail and revoke entire chain
        mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken1)
                        .param("client_id", clientId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));

        // 7. The previously-valid second refresh must now ALSO be revoked (chain compromise)
        mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken2)
                        .param("client_id", clientId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void unauthenticatedUserRedirectsToLogin() throws Exception {
        Map<String, Object> regBody = Map.of(
                "client_name", "Login Redirect Test",
                "redirect_uris", List.of(REDIRECT_URI),
                "token_endpoint_auth_method", "none"
        );
        MvcResult regResult = mvc.perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(regBody)))
                .andExpect(status().isCreated())
                .andReturn();
        String clientId = json.readTree(regResult.getResponse().getContentAsString())
                .get("client_id").asText();

        // No requestAttr → unauthenticated → must redirect to /login
        mvc.perform(get("/oauth/authorize")
                        .param("response_type", "code")
                        .param("client_id", clientId)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_challenge", CHALLENGE)
                        .param("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/login")));
    }

    @Test
    void registerRejectsHttpNonLoopbackRedirect() throws Exception {
        Map<String, Object> body = Map.of(
                "client_name", "Bad Client",
                "redirect_uris", List.of("http://evil.example.com/cb"),
                "token_endpoint_auth_method", "none"
        );
        mvc.perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_redirect_uri"));
    }

    @Test
    void registerRejectsConfidentialClient() throws Exception {
        Map<String, Object> body = Map.of(
                "client_name", "Confidential Client",
                "redirect_uris", List.of(REDIRECT_URI),
                "token_endpoint_auth_method", "client_secret_basic"
        );
        mvc.perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_client_metadata"));
    }

    @Test
    void wrongPkceVerifierRejectsExchange() throws Exception {
        Map<String, Object> regBody = Map.of(
                "client_name", "PKCE Test",
                "redirect_uris", List.of(REDIRECT_URI),
                "token_endpoint_auth_method", "none"
        );
        String clientId = json.readTree(mvc.perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(regBody)))
                .andReturn().getResponse().getContentAsString())
                .get("client_id").asText();

        URI redirect = URI.create(authorizeWithConsent(
                clientId, null, null, new MockHttpSession(), userTokenId));
        String code = extractParam(redirect.getQuery(), "code");

        mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("client_id", clientId)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_verifier", "wrong-verifier"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    private String seedLoginableToken() throws Exception {
        String raw = "login-raw-" + UUID.randomUUID();
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        dsl.execute("INSERT INTO api_tokens (token_hash, name, role) VALUES (?, ?, 'admin')",
                hash, "oauth-e2e-login-" + UUID.randomUUID());
        return raw;
    }

    private String registerClient(String name) throws Exception {
        Map<String, Object> regBody = Map.of(
                "client_name", name,
                "redirect_uris", List.of(REDIRECT_URI),
                "token_endpoint_auth_method", "none"
        );
        MvcResult regResult = mvc.perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(regBody)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(regResult.getResponse().getContentAsString())
                .get("client_id").asText();
    }

    @Test
    void authenticatedSessionAuthorizeIssuesCode() throws Exception {
        String clientId = registerClient("Session Authorize Test");
        String rawToken = seedLoginableToken();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(LoginController.SESSION_TOKEN_KEY, rawToken);

        URI redirect = URI.create(authorizeWithConsent(
                clientId, "read write", "sess-state", session, null));
        assertEquals("claude.ai", redirect.getHost());   // must go to the client, NOT /login
        assertNotNull(extractParam(redirect.getQuery(), "code"));
    }

    @Test
    void getAuthorizeRendersConsentWithoutIssuingCode() throws Exception {
        String clientId = registerClient("Consent Render Test");
        MvcResult res = mvc.perform(get("/oauth/authorize")
                        .param("response_type", "code").param("client_id", clientId)
                        .param("redirect_uri", REDIRECT_URI).param("scope", "read write")
                        .param("code_challenge", CHALLENGE).param("code_challenge_method", "S256")
                        .session(new MockHttpSession())
                        .requestAttr(AuthorizationController.TEST_USER_TOKEN_ATTR, userTokenId))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        assertTrue(body.contains("name=\"csrf\""), "consent page must embed the CSRF token");
        assertTrue(body.contains("value=\"approve\""), "consent page must offer approval");
        assertNull(res.getResponse().getHeader("Location"), "GET must not redirect with a code");
        Integer codeCount = dsl.fetchOne("SELECT count(*) AS c FROM oauth_authorization_codes")
                .get("c", Integer.class);
        assertEquals(0, codeCount.intValue(), "GET must not issue an authorization code");
    }

    @Test
    void consentPostWithoutCsrfIsRejected() throws Exception {
        String clientId = registerClient("CSRF Reject Test");
        // Render consent so a CSRF token exists in the session — then POST with a wrong one.
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/oauth/authorize")
                        .param("response_type", "code").param("client_id", clientId)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_challenge", CHALLENGE).param("code_challenge_method", "S256")
                        .session(session)
                        .requestAttr(AuthorizationController.TEST_USER_TOKEN_ATTR, userTokenId))
                .andExpect(status().isOk());

        MvcResult res = mvc.perform(post("/oauth/authorize")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("response_type", "code").param("client_id", clientId)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_challenge", CHALLENGE).param("code_challenge_method", "S256")
                        .param("csrf", "wrong-token").param("action", "approve")
                        .session(session)
                        .requestAttr(AuthorizationController.TEST_USER_TOKEN_ATTR, userTokenId))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        URI redirect = URI.create(res.getResponse().getHeader("Location"));
        assertEquals("access_denied", extractParam(redirect.getQuery(), "error"));
        assertNull(extractParam(redirect.getQuery(), "code"));
    }

    @Test
    void consentDenyRedirectsWithAccessDenied() throws Exception {
        String clientId = registerClient("Deny Test");
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/oauth/authorize")
                        .param("response_type", "code").param("client_id", clientId)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_challenge", CHALLENGE).param("code_challenge_method", "S256")
                        .session(session)
                        .requestAttr(AuthorizationController.TEST_USER_TOKEN_ATTR, userTokenId))
                .andExpect(status().isOk());
        String csrf = (String) session.getAttribute(AuthorizationController.CSRF_SESSION_ATTR);

        MvcResult res = mvc.perform(post("/oauth/authorize")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("response_type", "code").param("client_id", clientId)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_challenge", CHALLENGE).param("code_challenge_method", "S256")
                        .param("csrf", csrf).param("action", "deny")
                        .session(session)
                        .requestAttr(AuthorizationController.TEST_USER_TOKEN_ATTR, userTokenId))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        URI redirect = URI.create(res.getResponse().getHeader("Location"));
        assertEquals("access_denied", extractParam(redirect.getQuery(), "error"));
        assertNull(extractParam(redirect.getQuery(), "code"));
    }

    @Test
    void readerTokenCannotEscalateToWriteScope() throws Exception {
        String clientId = registerClient("Reader Scope Test");
        UUID readerTokenId = dsl.fetchOne("""
                INSERT INTO api_tokens (token_hash, name, role)
                VALUES (?, ?, 'reader')
                RETURNING id
                """, "test-hash-" + UUID.randomUUID(), "oauth-e2e-reader-" + UUID.randomUUID())
                .get("id", UUID.class);

        // Requesting "read write" with a reader-backed identity must grant "read" only.
        URI redirect = URI.create(authorizeWithConsent(
                clientId, "read write", "reader-state", new MockHttpSession(), readerTokenId));
        String code = extractParam(redirect.getQuery(), "code");
        assertNotNull(code);

        MvcResult tokenResult = mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("client_id", clientId)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_verifier", VERIFIER))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tokens = json.readTree(tokenResult.getResponse().getContentAsString());
        assertEquals("read", tokens.get("scope").asText(),
                "granted scope must be constrained to the reader role");
    }

    /**
     * Drives the two-step consent flow: GET /oauth/authorize (renders the consent page
     * and seeds the session CSRF token), then POST /oauth/authorize with
     * {@code action=approve}. Returns the redirect Location containing the code.
     */
    private String authorizeWithConsent(String clientId, String scope, String state,
                                        MockHttpSession session, UUID injectedTokenId) throws Exception {
        var getReq = get("/oauth/authorize")
                .param("response_type", "code").param("client_id", clientId)
                .param("redirect_uri", REDIRECT_URI)
                .param("code_challenge", CHALLENGE).param("code_challenge_method", "S256")
                .session(session);
        if (scope != null) getReq = getReq.param("scope", scope);
        if (state != null) getReq = getReq.param("state", state);
        if (injectedTokenId != null) {
            getReq = getReq.requestAttr(AuthorizationController.TEST_USER_TOKEN_ATTR, injectedTokenId);
        }
        mvc.perform(getReq).andExpect(status().isOk()).andReturn();
        String csrf = (String) session.getAttribute(AuthorizationController.CSRF_SESSION_ATTR);
        assertNotNull(csrf, "consent page must seed a session CSRF token");

        var postReq = post("/oauth/authorize")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("response_type", "code").param("client_id", clientId)
                .param("redirect_uri", REDIRECT_URI)
                .param("code_challenge", CHALLENGE).param("code_challenge_method", "S256")
                .param("csrf", csrf).param("action", "approve")
                .session(session);
        if (scope != null) postReq = postReq.param("scope", scope);
        if (state != null) postReq = postReq.param("state", state);
        if (injectedTokenId != null) {
            postReq = postReq.requestAttr(AuthorizationController.TEST_USER_TOKEN_ATTR, injectedTokenId);
        }
        MvcResult res = mvc.perform(postReq)
                .andExpect(status().is3xxRedirection())
                .andReturn();
        return res.getResponse().getHeader("Location");
    }

    @Test
    void loginRedirectsToSafeNextAfterSuccess() throws Exception {
        String rawToken = seedLoginableToken();
        String next = "/oauth/authorize?response_type=code&client_id=abc";
        mvc.perform(post("/login").param("v", rawToken).param("next", next))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", next));
    }

    @Test
    void loginIgnoresUnsafeNext() throws Exception {
        String rawToken = seedLoginableToken();
        mvc.perform(post("/login").param("v", rawToken).param("next", "//evil.example.com/x"))
                .andExpect(header().string("Location", "/"));
        mvc.perform(post("/login").param("v", rawToken).param("next", "https://evil.example.com"))
                .andExpect(header().string("Location", "/"));
        mvc.perform(post("/login").param("v", rawToken).param("next", "/\\evil.com"))
                .andExpect(header().string("Location", "/"));
    }

    @Test
    void loginFallsBackWhenNextIsMalformed() throws Exception {
        String rawToken = seedLoginableToken();
        mvc.perform(post("/login").param("v", rawToken).param("next", "/oauth/authorize?x=a b"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/"));
    }

    @Test
    void loginPageEmbedsEscapedNext() throws Exception {
        mvc.perform(get("/login").param("next", "/oauth/authorize?a=1&b=2"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "name=\"next\" value=\"/oauth/authorize?a=1&amp;b=2\"")));
    }

    @Test
    void authorizeLoginRedirectPreservesFullQueryInNext() throws Exception {
        String clientId = registerClient("Next Preservation Test");
        // Use queryParam (not param) so MockMvc populates request.getQueryString() the way a
        // real browser request does — that is the value the controller reflects into `next`.
        MvcResult res = mvc.perform(get("/oauth/authorize")
                .queryParam("response_type", "code").queryParam("client_id", clientId)
                .queryParam("redirect_uri", REDIRECT_URI).queryParam("scope", "read write")
                .queryParam("state", "st").queryParam("code_challenge", CHALLENGE)
                .queryParam("code_challenge_method", "S256"))   // unauthenticated -> redirect to /login
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String location = res.getResponse().getHeader("Location");
        UriComponents uc = UriComponentsBuilder.fromUriString(location).build();
        String nextRaw = uc.getQueryParams().getFirst("next");
        org.junit.jupiter.api.Assertions.assertNotNull(nextRaw, "next param must be present");
        String nextDecoded = URLDecoder.decode(nextRaw, StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(nextDecoded.startsWith("/oauth/authorize"),
                "next must point back at the authorize endpoint: " + nextDecoded);
        org.junit.jupiter.api.Assertions.assertTrue(nextDecoded.contains("client_id=" + clientId),
                "next must retain client_id after decoding: " + nextDecoded);
        org.junit.jupiter.api.Assertions.assertTrue(nextDecoded.contains("code_challenge="),
                "next must retain the PKCE challenge: " + nextDecoded);
    }

    private static String extractParam(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return kv[1];
        }
        return null;
    }
}
