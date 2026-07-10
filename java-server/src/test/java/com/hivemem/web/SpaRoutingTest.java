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
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = SpaController.class)
@Import({SessionAuthFilter.class, AuthFilter.class, RateLimiter.class, com.hivemem.auth.SecurityProperties.class,
        SpaRoutingTest.TestConfig.class})
class SpaRoutingTest {

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
        org.springframework.web.servlet.ViewResolver viewResolver() {
            return (viewName, locale) -> (model, request, response) -> {
                if (viewName.startsWith("forward:") && viewName.endsWith("index.html")) {
                    response.setContentType("text/html;charset=UTF-8");
                    response.getWriter().write("<!DOCTYPE html><html><body>app</body></html>");
                } else if ("index".equals(viewName) || "index.html".equals(viewName)) {
                    response.setContentType("text/html;charset=UTF-8");
                    response.getWriter().write("<!DOCTYPE html><html><body>app</body></html>");
                }
            };
        }
    }

    private MockHttpSession validSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(LoginController.SESSION_TOKEN_KEY, "valid-token");
        return session;
    }

    @Test
    void deepLinkForwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/some/deep/route").session(validSession()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("app")));
    }

    @Test
    void singleSegmentDeepLinkForwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/about").session(validSession()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("app")));
    }

    @Test
    void rootForwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/").session(validSession()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("app")));
    }

    @Test
    void deepLinkWithoutSessionRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/some/deep/route"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }
}
