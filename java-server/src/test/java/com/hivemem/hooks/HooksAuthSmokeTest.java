package com.hivemem.hooks;

import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test verifying that {@link AuthFilter} runs against {@code /hooks/*} paths.
 *
 * <p>Even though the real {@code HooksController} does not exist yet, we install a
 * dummy controller mapped to {@code POST /hooks/context}. The filter must reject the
 * unauthenticated request with HTTP 401 before any controller dispatch — proving the
 * upcoming hook endpoint will inherit the existing token-auth pipeline.
 */
@WebMvcTest(controllers = HooksAuthSmokeTest.StubHooksController.class)
@Import({AuthFilter.class, RateLimiter.class, com.hivemem.auth.SecurityProperties.class,
        HooksAuthSmokeTest.HooksAuthSmokeTestConfig.class})
class HooksAuthSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void postHooksContextWithoutBearerTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/hooks/context"))
                .andExpect(status().isUnauthorized());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HooksAuthSmokeTestConfig {

        @Bean
        @Primary
        TokenService tokenService() {
            return new FixedTokenService(token -> Optional.<AuthPrincipal>empty());
        }
    }

    @RestController
    static class StubHooksController {

        @PostMapping("/hooks/context")
        String handle() {
            return "should-not-reach";
        }
    }
}
