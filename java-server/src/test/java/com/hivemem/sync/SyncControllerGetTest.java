package com.hivemem.sync;

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

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SyncControllerGetTest.TestConfig.class)
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
class SyncControllerGetTest {

    @Autowired WebApplicationContext context;
    @Autowired AuthFilter authFilter;
    @Autowired SyncOpsRepository syncOpsRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Mockito.reset(syncOpsRepository);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(authFilter).build();
    }

    @Test
    void getOpsRequiresAuth() throws Exception {
        mockMvc.perform(get("/sync/ops").param("since", "0"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getOpsReturnsEmptyListWhenNoOps() throws Exception {
        Mockito.when(syncOpsRepository.findOpsAfter(anyLong())).thenReturn(List.of());

        mockMvc.perform(get("/sync/ops").param("since", "0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ops").isArray())
                .andExpect(jsonPath("$.ops.length()").value(0))
                .andExpect(jsonPath("$.max_seq").value(0));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({AuthFilter.class, com.hivemem.auth.RateLimiter.class, com.hivemem.auth.SecurityProperties.class,
            SyncController.class})
    static class TestConfig {
        @Bean
        SyncOpsRepository syncOpsRepository() { return Mockito.mock(SyncOpsRepository.class); }

        @Bean
        OpReplayer opReplayer() { return Mockito.mock(OpReplayer.class); }

        @Bean
        @org.springframework.context.annotation.Primary
        TokenService tokenService() {
            return new com.hivemem.auth.support.FixedTokenService(token -> switch (token) {
                case "good-token" -> Optional.of(new AuthPrincipal("token-1", AuthRole.ADMIN));
                default -> Optional.empty();
            });
        }
    }
}
