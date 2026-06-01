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
        AuthFilter filter = new AuthFilter(Optional.empty(), new RateLimiter(), Optional.empty());
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/vistierie/tools/find_isolated_cells");
        assertThat(filter.shouldNotFilter(req)).isTrue();
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
