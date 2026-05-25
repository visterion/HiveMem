package com.hivemem.hooks;

import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextBeforeModesTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.test.context.web.ServletTestExecutionListener;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = HooksControllerTest.TestConfig.class)
@TestExecutionListeners(
        listeners = {
                ServletTestExecutionListener.class,
                DirtiesContextBeforeModesTestExecutionListener.class,
                DependencyInjectionTestExecutionListener.class,
                DirtiesContextTestExecutionListener.class
        },
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS
)
@WebAppConfiguration
class HooksControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AuthFilter authFilter;

    @Autowired
    private HookContextService hookContextService;

    private MockMvc mockMvc;

    private static final String BODY = """
            {"hook_event_name":"UserPromptSubmit","prompt":"What is the project plan?","session_id":"s-1","cwd":"/x"}
            """;

    @BeforeEach
    void setUp() {
        Mockito.reset(hookContextService);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(authFilter)
                .build();
    }

    @Test
    void returnsFormattedContextOnHit() throws Exception {
        Mockito.when(hookContextService.contextFor(any(), any(), any()))
                .thenReturn(new ContextResult("<hivemem_context turn=\"1\">hello</hivemem_context>", java.util.List.of()));

        mockMvc.perform(post("/hooks/context")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hookSpecificOutput.hookEventName").value("UserPromptSubmit"))
                .andExpect(jsonPath("$.hookSpecificOutput.additionalContext")
                        .value(containsString("hivemem_context")));
    }

    @Test
    void returnsEmptyContextOnSkip() throws Exception {
        Mockito.when(hookContextService.contextFor(any(), any(), any())).thenReturn(ContextResult.empty());

        mockMvc.perform(post("/hooks/context")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hookSpecificOutput.hookEventName").value("UserPromptSubmit"))
                .andExpect(jsonPath("$.hookSpecificOutput.additionalContext").value(""));
    }

    @Test
    void returnsEmptyContextOnInternalFailure() throws Exception {
        Mockito.when(hookContextService.contextFor(any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        mockMvc.perform(post("/hooks/context")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hookSpecificOutput.additionalContext").value(""));
    }

    @Test
    void returnsUnauthorizedWithoutBearerToken() throws Exception {
        mockMvc.perform(post("/hooks/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({
            AuthFilter.class,
            com.hivemem.auth.RateLimiter.class,
            HooksController.class
    })
    static class TestConfig {

        @Bean
        HookContextService hookContextService() {
            return Mockito.mock(HookContextService.class);
        }

        @Bean
        @org.springframework.context.annotation.Primary
        TokenService tokenService() {
            return new com.hivemem.auth.support.FixedTokenService(token -> switch (token) {
                case "good-token" -> Optional.of(new AuthPrincipal("token-1", AuthRole.WRITER));
                default -> Optional.empty();
            });
        }
    }
}
