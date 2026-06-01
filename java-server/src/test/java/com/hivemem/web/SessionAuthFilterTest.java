package com.hivemem.web;

import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.LoginController;
import com.hivemem.auth.RateLimiter;
import com.hivemem.auth.TokenService;
import com.hivemem.auth.support.FixedTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = SessionAuthFilterTest.TestController.class)
@Import({SessionAuthFilter.class, AuthFilter.class, RateLimiter.class, SessionAuthFilterTest.TestConfig.class})
class SessionAuthFilterTest {

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

        // Invalid session token on /mcp: SessionAuthFilter invalidates session and passes to AuthFilter,
        // which returns 401 (no Bearer token present).
        mockMvc.perform(get("/mcp").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void vistieriePathWithoutSessionContinuesChain() throws Exception {
        // Unauthenticated /vistierie requests must pass through SessionAuthFilter
        // (mirrors /hooks behaviour) — NOT redirected to /login.
        mockMvc.perform(get("/vistierie/tools/find_isolated_cells"))
                .andExpect(status().isOk());
    }

    @RestController
    static class TestController {
        @GetMapping({"/some-page", "/login", "/mcp", "/vistierie/tools/find_isolated_cells"})
        String index() { return "ok"; }
    }
}
