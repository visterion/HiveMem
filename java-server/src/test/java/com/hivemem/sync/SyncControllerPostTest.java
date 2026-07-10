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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SyncControllerPostTest.TestConfig.class)
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
class SyncControllerPostTest {

    @Autowired WebApplicationContext context;
    @Autowired AuthFilter authFilter;
    @Autowired OpReplayer opReplayer;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Mockito.reset(opReplayer);
        Mockito.when(opReplayer.replayAll(any(), any()))
                .thenReturn(new OpReplayer.BatchResult(0, 0, 0));
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(authFilter).build();
    }

    @Test
    void postOpsRequiresAuth() throws Exception {
        mockMvc.perform(post("/sync/ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourcePeer\":\"" + UUID.randomUUID() + "\",\"ops\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postOpsCallsReplayerAndReturnsCount() throws Exception {
        UUID sourcePeer = UUID.randomUUID();

        Mockito.when(opReplayer.replayAll(eq(sourcePeer), any()))
                .thenReturn(new OpReplayer.BatchResult(2, 1, 0));

        mockMvc.perform(post("/sync/ops")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourcePeer\":\"" + sourcePeer + "\",\"ops\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(2))
                .andExpect(jsonPath("$.skipped").value(1));
    }

    @Test
    void postOpsWithMissingOpsFieldReturnsZeroCounts() throws Exception {
        mockMvc.perform(post("/sync/ops")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourcePeer\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(0))
                .andExpect(jsonPath("$.skipped").value(0));
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
