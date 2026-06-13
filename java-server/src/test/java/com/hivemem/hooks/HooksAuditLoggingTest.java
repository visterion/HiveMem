package com.hivemem.hooks;

import tools.jackson.databind.ObjectMapper;
import com.hivemem.auth.AuthFilter;
import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith({SpringExtension.class, OutputCaptureExtension.class})
@ContextConfiguration(classes = HooksAuditLoggingTest.TestConfig.class)
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
class HooksAuditLoggingTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AuthFilter authFilter;

    @Autowired
    private HookContextService hookContextService;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Mockito.reset(hookContextService);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(authFilter)
                .build();
    }

    @Test
    void hookCallEmitsAuditLogLineWithoutPromptContent(CapturedOutput output) throws Exception {
        Mockito.when(hookContextService.contextFor(any())).thenReturn(ContextResult.empty());
        Mockito.when(hookContextService.contextFor(any(), any(), any())).thenReturn(ContextResult.empty());

        String secret = "super secret prompt that must not appear in logs";
        String body = om.writeValueAsString(Map.of(
                "hook_event_name", "UserPromptSubmit",
                "prompt", secret,
                "session_id", "audit-1",
                "cwd", "/x"));

        mockMvc.perform(post("/hooks/context")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertThat(output).contains("HOOK_CALL");
        assertThat(output).contains("session=audit-1");
        assertThat(output).contains("event=UserPromptSubmit");
        assertThat(output).contains("promptLen=" + secret.length());
        assertThat(output).doesNotContain(secret);
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
