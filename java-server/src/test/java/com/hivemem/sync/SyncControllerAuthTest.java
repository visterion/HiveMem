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

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /sync/ops} must require a WRITER (or ADMIN) role. AuthFilter authenticates
 * {@code /sync} but does no role check, so without the controller gate a READER or AGENT
 * bearer token could push committed ops, bypassing the approval/role model. See the code
 * comment in {@link SyncController} for why WRITER+ is the chosen (non-breaking) gate.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SyncControllerAuthTest.TestConfig.class)
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
class SyncControllerAuthTest {

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

    private void postOps(String token, int expectedStatus) throws Exception {
        mockMvc.perform(post("/sync/ops")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourcePeer\":\"" + UUID.randomUUID() + "\",\"ops\":[]}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void readerIsForbiddenAndReplayerNotInvoked() throws Exception {
        postOps("reader-token", 403);
        Mockito.verifyNoInteractions(opReplayer);
    }

    @Test
    void agentIsForbiddenAndReplayerNotInvoked() throws Exception {
        postOps("agent-token", 403);
        Mockito.verifyNoInteractions(opReplayer);
    }

    @Test
    void writerIsAllowed() throws Exception {
        postOps("writer-token", 200);
        Mockito.verify(opReplayer).replayAll(any(), any());
    }

    @Test
    void adminIsAllowed() throws Exception {
        postOps("admin-token", 200);
        Mockito.verify(opReplayer).replayAll(any(), any());
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
                case "reader-token" -> Optional.of(new AuthPrincipal("reader", AuthRole.READER));
                case "agent-token" -> Optional.of(new AuthPrincipal("agent", AuthRole.AGENT));
                case "writer-token" -> Optional.of(new AuthPrincipal("writer", AuthRole.WRITER));
                case "admin-token" -> Optional.of(new AuthPrincipal("admin", AuthRole.ADMIN));
                default -> Optional.empty();
            });
        }
    }
}
