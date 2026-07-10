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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PeerAdminControllerTest.TestConfig.class)
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
class PeerAdminControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired AuthFilter authFilter;
    @Autowired SyncPeerRepository syncPeerRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Mockito.reset(syncPeerRepository);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(authFilter).build();
    }

    @Test
    void listPeersRequiresAuth() throws Exception {
        mockMvc.perform(get("/admin/peers")).andExpect(status().isUnauthorized());
    }

    @Test
    void listPeersForbiddenForWriter() throws Exception {
        mockMvc.perform(get("/admin/peers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listPeersReturnsListForAdmin() throws Exception {
        UUID peerId = UUID.randomUUID();
        Mockito.when(syncPeerRepository.listPeers()).thenReturn(List.of(
                Map.of("peer_uuid", peerId.toString(), "peer_url", "https://peer.example.com",
                        "last_seen_seq", 0L, "last_synced_at", "null")));

        mockMvc.perform(get("/admin/peers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.peers").isArray())
                .andExpect(jsonPath("$.peers.length()").value(1));
    }

    @Test
    void addPeerRequiresAdmin() throws Exception {
        mockMvc.perform(post("/admin/peers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"peerUuid":"00000000-0000-0000-0000-000000000001",
                                 "peerUrl":"https://p.example.com","outboundToken":"tok"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void addPeerDelegatesToRepository() throws Exception {
        UUID peerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Mockito.when(syncPeerRepository.addPeer(eq(peerId), any(), any()))
                .thenReturn(Map.of("peer_uuid", peerId.toString(), "peer_url", "https://p.example.com"));

        mockMvc.perform(post("/admin/peers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"peerUuid":"00000000-0000-0000-0000-000000000002",
                                 "peerUrl":"https://p.example.com","outboundToken":"tok"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.peer_uuid").value(peerId.toString()));
    }

    @Test
    void removePeerRequiresAdmin() throws Exception {
        mockMvc.perform(delete("/admin/peers/00000000-0000-0000-0000-000000000003")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer writer-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void removePeerDelegatesToRepository() throws Exception {
        UUID peerId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        Mockito.when(syncPeerRepository.removePeer(peerId)).thenReturn(true);

        mockMvc.perform(delete("/admin/peers/" + peerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(true));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({AuthFilter.class, com.hivemem.auth.RateLimiter.class, com.hivemem.auth.SecurityProperties.class,
            PeerAdminController.class})
    static class TestConfig {
        @Bean
        SyncPeerRepository syncPeerRepository() { return Mockito.mock(SyncPeerRepository.class); }

        @Bean
        @org.springframework.context.annotation.Primary
        TokenService tokenService() {
            return new com.hivemem.auth.support.FixedTokenService(token -> switch (token) {
                case "admin-token" -> Optional.of(new AuthPrincipal("admin", AuthRole.ADMIN));
                case "writer-token" -> Optional.of(new AuthPrincipal("writer", AuthRole.WRITER));
                default -> Optional.empty();
            });
        }
    }
}
