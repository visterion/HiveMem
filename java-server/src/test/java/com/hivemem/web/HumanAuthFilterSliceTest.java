package com.hivemem.web;

import com.hivemem.auth.AccessProperties;
import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.HumanPrincipalResolver;
import com.hivemem.auth.LoginController;
import com.hivemem.auth.RateLimiter;
import com.hivemem.auth.SessionResolver;
import com.hivemem.auth.TokenService;
import com.hivemem.auth.support.FixedTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = HumanAuthFilterSliceTest.TestController.class)
@Import({HumanAuthFilter.class, AuthFilter.class, RateLimiter.class, com.hivemem.auth.SecurityProperties.class,
        HumanAuthFilterSliceTest.TestConfig.class})
class HumanAuthFilterSliceTest {

    @Autowired
    MockMvc mockMvc;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        TokenService tokenService() {
            return new FixedTokenService(token ->
                    "valid-token".equals(token)
                            ? Optional.of(new AuthPrincipal("alice", AuthRole.READER))
                            : Optional.empty());
        }

        // Legacy mode (the only mode this WebMvcTest exercises): SessionResolver is the
        // active HumanPrincipalResolver, backed by the fixed tokenService() above.
        @Bean
        HumanPrincipalResolver humanPrincipalResolver(TokenService tokenService) {
            return new SessionResolver(tokenService);
        }

        @Bean
        AccessProperties accessProperties() {
            return new AccessProperties();
        }

        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @Test
    void requestWithoutSessionRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/some-page"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void requestWithValidSessionPasses() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(LoginController.SESSION_TOKEN_KEY, "valid-token");

        mockMvc.perform(get("/some-page").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void requestWithInvalidTokenInSessionRedirectsToLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(LoginController.SESSION_TOKEN_KEY, "bad-token");

        mockMvc.perform(get("/some-page").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void loginPathIsNotFiltered() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    void mcpPathWithoutSessionContinuesChain() throws Exception {
        // Without session, /mcp passes through to AuthFilter which returns 401 (no Bearer token)
        mockMvc.perform(get("/mcp"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mcpPathWithInvalidSessionContinuesChain() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(LoginController.SESSION_TOKEN_KEY, "bad-token");

        // Invalid session token on /mcp: HumanAuthFilter invalidates session and passes to AuthFilter,
        // which returns 401 (no Bearer token present).
        mockMvc.perform(get("/mcp").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void vistieriePathWithoutSessionContinuesChain() throws Exception {
        // Unauthenticated /vistierie requests must pass through HumanAuthFilter
        // (mirrors /hooks behaviour) — NOT redirected to /login.
        mockMvc.perform(get("/vistierie/tools/find_isolated_cells"))
                .andExpect(status().isOk());
    }

    @Test
    void syncPathWithoutSessionContinuesChain() throws Exception {
        // Peer sync is bearer-authed: without a session, /sync must pass through to
        // AuthFilter (which 401s absent a Bearer token) — NOT redirect to /login.
        // A redirect would feed the peer's RestClient the login page and silently
        // no-op replication.
        mockMvc.perform(get("/sync/ops"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void syncPathWithBearerTokenReachesController() throws Exception {
        mockMvc.perform(get("/sync/ops").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void adminPathWithoutSessionOrBearerRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin/peers"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void adminPathWithBearerTokenContinuesToAuthFilter() throws Exception {
        // Bearer-authed /admin (CLI/scripts) must defer to AuthFilter, which validates
        // the token — valid tokens reach the controller, invalid ones get 401.
        mockMvc.perform(get("/admin/peers").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
        mockMvc.perform(get("/admin/peers").header("Authorization", "Bearer bad-token"))
                .andExpect(status().isUnauthorized());
    }

    // Unit tests for PWA asset exemption (new tests covering Task 1)
    private static class UnitTestHelper {
        private final HumanAuthFilter filter =
                new HumanAuthFilter(mock(HumanPrincipalResolver.class), new AccessProperties());

        private boolean skip(String uri) {
            return filter.shouldNotFilter(new MockHttpServletRequest("GET", uri));
        }
    }

    @Test
    void pwaShellAssetsBypassAuthWithoutSession() {
        UnitTestHelper helper = new UnitTestHelper();
        for (String p : List.of(
                "/manifest.webmanifest", "/sw.js",
                "/pwa-64x64.png", "/pwa-192x192.png", "/pwa-512x512.png",
                "/maskable-icon-512x512.png", "/apple-touch-icon-180x180.png")) {
            assertThat(helper.skip(p)).as("PWA asset %s must skip the session filter", p).isTrue();
        }
    }

    @Test
    void apiStillFilteredWithoutSession() {
        UnitTestHelper helper = new UnitTestHelper();
        assertThat(helper.skip("/api/attachments")).isFalse();
        assertThat(helper.skip("/api/status")).isFalse();
    }

    @Test
    void spaRootStillFiltered() {
        UnitTestHelper helper = new UnitTestHelper();
        assertThat(helper.skip("/")).isFalse();
        assertThat(helper.skip("/hive")).isFalse();
    }

    @RestController
    static class TestController {
        @GetMapping({"/some-page", "/login", "/mcp", "/vistierie/tools/find_isolated_cells",
                "/sync/ops", "/admin/peers"})
        String index() { return "ok"; }
    }
}
