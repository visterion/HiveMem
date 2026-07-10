package com.hivemem.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import com.hivemem.oauth.OAuthProperties;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@WebMvcTest(controllers = AuthFilterTest.TestMcpController.class)
@Import({AuthFilter.class, RateLimiter.class, AuthFilterTest.AuthFilterTestConfig.class})
class AuthFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void postMcpWithoutBearerTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/mcp"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postMcpWithBearerTokenAttachesPrincipal() throws Exception {
        mockMvc.perform(post("/mcp").header(HttpHeaders.AUTHORIZATION, "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("token-1:writer"));
    }

    @Test
    void postMcpWithLowercaseBearerTokenAttachesPrincipal() throws Exception {
        mockMvc.perform(post("/mcp").header(HttpHeaders.AUTHORIZATION, "bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("token-1:writer"));
    }

    @Test
    void vistieriePathIsExemptFromAuthFilter() throws Exception {
        // shouldNotFilter must return true for /vistierie/** so the controller's own
        // webhook-token check runs instead of the api_tokens bearer check.
        AuthFilter filter = new AuthFilter(Optional.empty(), new RateLimiter(), Optional.empty(), Optional.empty());
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/vistierie/tools/find_isolated_cells");
        assertThat(filter.shouldNotFilter(req)).isTrue();
    }

    private static OAuthProperties oauthProps(boolean enabled, String issuer) {
        OAuthProperties p = new OAuthProperties();
        p.setEnabled(enabled);
        p.setIssuer(issuer);
        return p;
    }

    private static int invokeUnauthenticated(AuthFilter filter, String uri, MockHttpServletResponse res) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        filter.doFilterInternal(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void mcp401WithOAuthEnabledCarriesWwwAuthenticateHeader() throws Exception {
        AuthFilter filter = new AuthFilter(Optional.empty(), new RateLimiter(), Optional.empty(),
                Optional.of(oauthProps(true, "https://hivemem.example.com")));
        MockHttpServletResponse res = new MockHttpServletResponse();
        int status = invokeUnauthenticated(filter, "/mcp", res);
        assertThat(status).isEqualTo(401);
        assertThat(res.getHeader("WWW-Authenticate"))
                .isEqualTo("Bearer resource_metadata=\"https://hivemem.example.com/.well-known/oauth-protected-resource\"");
    }

    @Test
    void mcp401WithOAuthDisabledHasNoWwwAuthenticateHeader() throws Exception {
        AuthFilter filter = new AuthFilter(Optional.empty(), new RateLimiter(), Optional.empty(),
                Optional.of(oauthProps(false, "https://hivemem.example.com")));
        MockHttpServletResponse res = new MockHttpServletResponse();
        int status = invokeUnauthenticated(filter, "/mcp", res);
        assertThat(status).isEqualTo(401);
        assertThat(res.getHeader("WWW-Authenticate")).isNull();
    }

    @Test
    void mcp401WithBlankIssuerHasNoWwwAuthenticateHeader() throws Exception {
        AuthFilter filter = new AuthFilter(Optional.empty(), new RateLimiter(), Optional.empty(),
                Optional.of(oauthProps(true, "")));
        MockHttpServletResponse res = new MockHttpServletResponse();
        int status = invokeUnauthenticated(filter, "/mcp", res);
        assertThat(status).isEqualTo(401);
        assertThat(res.getHeader("WWW-Authenticate")).isNull();
    }

    @Test
    void nonMcp401WithOAuthEnabledHasNoMcpHeader() throws Exception {
        AuthFilter filter = new AuthFilter(Optional.empty(), new RateLimiter(), Optional.empty(),
                Optional.of(oauthProps(true, "https://hivemem.example.com")));
        MockHttpServletResponse res = new MockHttpServletResponse();
        int status = invokeUnauthenticated(filter, "/sync/ops", res);
        assertThat(status).isEqualTo(401);
        assertThat(res.getHeader("WWW-Authenticate")).isNull();
    }

    @Test
    void syncPathIsBearerGuarded() {
        // Peer sync authenticates with a bearer token; AuthFilter must filter it.
        AuthFilter filter = new AuthFilter(Optional.empty(), new RateLimiter(), Optional.empty(), Optional.empty());
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/sync/ops");
        assertThat(filter.shouldNotFilter(req)).isFalse();
    }

    @Test
    void adminIsBearerGuardedButApiAttachmentsIsSessionOnly() {
        AuthFilter filter = new AuthFilter(Optional.empty(), new RateLimiter(), Optional.empty(), Optional.empty());
        // /admin stays bearer-guarded — used by CLI/scripts (connect-peers.sh -> /admin/peers).
        MockHttpServletRequest admin = new MockHttpServletRequest();
        admin.setRequestURI("/admin/tokens");
        assertThat(filter.shouldNotFilter(admin)).isFalse();
        // /api/** is session-cookie-only: SessionAuthFilter rejects it before this filter
        // could ever see a bearer, so it must not be in the bearer set (dead config).
        MockHttpServletRequest attachments = new MockHttpServletRequest();
        attachments.setRequestURI("/api/attachments/abc/thumbnail");
        assertThat(filter.shouldNotFilter(attachments)).isTrue();
    }

    @Test
    void oauthEffectiveRoleIsMinimumOfScopeAndBackingRole() {
        // A reader-backed token can never escalate via scope=write.
        assertThat(AuthFilter.effectiveOauthRole(AuthRole.READER, "write")).isEqualTo(AuthRole.READER);
        assertThat(AuthFilter.effectiveOauthRole(AuthRole.READER, "read write")).isEqualTo(AuthRole.READER);
        assertThat(AuthFilter.effectiveOauthRole(AuthRole.READER, "read")).isEqualTo(AuthRole.READER);
        // Scope can narrow a writer/admin to read-only.
        assertThat(AuthFilter.effectiveOauthRole(AuthRole.WRITER, "read")).isEqualTo(AuthRole.READER);
        assertThat(AuthFilter.effectiveOauthRole(AuthRole.WRITER, "read write")).isEqualTo(AuthRole.WRITER);
        // ADMIN is capped at WRITER — OAuth sessions never get admin powers.
        assertThat(AuthFilter.effectiveOauthRole(AuthRole.ADMIN, "read write")).isEqualTo(AuthRole.WRITER);
        assertThat(AuthFilter.effectiveOauthRole(AuthRole.ADMIN, "read")).isEqualTo(AuthRole.READER);
        // AGENT keeps its pending-write semantics; it is never widened to WRITER.
        assertThat(AuthFilter.effectiveOauthRole(AuthRole.AGENT, "read write")).isEqualTo(AuthRole.AGENT);
        assertThat(AuthFilter.effectiveOauthRole(AuthRole.AGENT, "read")).isEqualTo(AuthRole.READER);
        // Absent/blank scope defaults to read-only.
        assertThat(AuthFilter.effectiveOauthRole(AuthRole.ADMIN, null)).isEqualTo(AuthRole.READER);
        assertThat(AuthFilter.effectiveOauthRole(AuthRole.WRITER, "")).isEqualTo(AuthRole.READER);
    }

    @Test
    void rateLimitBlocksAfterFiveFailedAttempts() {
        RateLimiter limiter = new RateLimiter();
        String ip = "192.168.1.100";

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.checkRateLimit(ip)).isEqualTo(0L);
            limiter.recordFailure(ip);
        }

        long remaining = limiter.checkRateLimit(ip);
        assertThat(remaining).isGreaterThan(0L);
        assertThat(remaining).isLessThanOrEqualTo(900L);
    }

    @Test
    void rateLimitClearsOnSuccess() {
        RateLimiter limiter = new RateLimiter();
        String ip = "192.168.1.101";

        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(ip);
        }
        limiter.clearFailures(ip);

        // After clear, even adding one more failure should not trigger ban
        limiter.recordFailure(ip);
        assertThat(limiter.checkRateLimit(ip)).isEqualTo(0L);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(AuthFilter.class)
    static class AuthFilterTestConfig {

        @Bean
        @org.springframework.context.annotation.Primary
        TokenService tokenService() {
            return new com.hivemem.auth.support.FixedTokenService(token ->
                    "good-token".equals(token)
                            ? Optional.of(new AuthPrincipal("token-1", AuthRole.WRITER))
                            : Optional.empty());
        }

        @Bean
        TestMcpController testMcpController() {
            return new TestMcpController();
        }
    }

    @RestController
    static class TestMcpController {

        @PostMapping("/mcp")
        String handle(HttpServletRequest request) {
            AuthPrincipal principal = (AuthPrincipal) request.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
            return principal.name() + ":" + principal.role().wireValue();
        }
    }
}
