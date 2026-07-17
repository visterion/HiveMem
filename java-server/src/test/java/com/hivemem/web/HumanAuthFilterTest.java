package com.hivemem.web;

import com.hivemem.auth.AuthRole;
import com.hivemem.auth.LoginController;
import com.hivemem.auth.TokenService;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack regression coverage for {@link HumanAuthFilter}: the filter that decides
 * whether a browser session may authenticate a request at all. The point of the whole
 * auth-split is {@link #mcpNoLongerAcceptsASessionCookie()} — everything else here
 * pins down the surrounding behavior so the split doesn't regress the legacy paths.
 *
 * <p>Drives real requests against a real session, built the way {@link LoginController}
 * builds one (a plaintext token minted via the real {@link TokenService}, stored under
 * {@link LoginController#SESSION_TOKEN_KEY}). No auth entry point is stubbed — see the
 * {@code oauth-browser-authorize-flow-rca} cell for why stubbing the entry point once
 * made three broken paths look green.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(HumanAuthFilterTest.TestConfig.class)
class HumanAuthFilterTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem")
            .withUsername("hivemem")
            .withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null
                            ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig())
                            .withSecurityOpts(List.of("apparmor=unconfined"))));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        EmbeddingClient embeddingClient() {
            return new FixedEmbeddingClient();
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    @Qualifier("dbTokenService")
    TokenService tokenService;

    @Autowired
    DSLContext dslContext;

    @BeforeEach
    void resetTokens() {
        dslContext.execute("TRUNCATE TABLE api_tokens CASCADE");
    }

    /**
     * THE point of this whole change: a browser session must no longer authenticate the
     * machine endpoint. If this test is green, human and machine auth are truly split.
     */
    @Test
    void mcpNoLongerAcceptsASessionCookie() throws Exception {
        MockHttpSession session = loggedInSession();

        mockMvc.perform(post("/mcp").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void apiToolsCallAcceptsASessionCookieInLegacyMode() throws Exception {
        MockHttpSession session = loggedInSession();

        mockMvc.perform(post("/api/tools/call").session(session)
                        .contentType("application/json")
                        .content("""
                                {"id":1,"params":{"name":"status","arguments":{}}}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void apiWithoutSessionIs401() throws Exception {
        mockMvc.perform(post("/api/tools/call").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void configNeedsNoAuth() throws Exception {
        mockMvc.perform(get("/api/config")).andExpect(status().isOk());
    }

    @Test
    void spaRouteWithoutSessionRedirectsToLoginInLegacyMode() throws Exception {
        mockMvc.perform(get("/graph"))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * Closes a pre-existing hole: a realm-scoped human could read every realm through
     * /api/gui/stream, which has no realm filter at all.
     */
    @Test
    void realmScopedHumanIsConfinedToToolsCall() throws Exception {
        MockHttpSession session = realmScopedSession();

        mockMvc.perform(get("/api/gui/stream").session(session))
                .andExpect(status().isForbidden());
    }

    // Helpers: build a session the way LoginController does — mint a real token via the
    // real TokenService and store it under LoginController.SESSION_TOKEN_KEY.
    private MockHttpSession loggedInSession() {
        String plaintext = tokenService.createToken(
                "human-auth-filter-test-admin", AuthRole.ADMIN, null, null, null);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(LoginController.SESSION_TOKEN_KEY, plaintext);
        return session;
    }

    private MockHttpSession realmScopedSession() {
        String plaintext = tokenService.createToken(
                "human-auth-filter-test-realm-scoped", AuthRole.READER, null,
                List.of("some-realm"), List.of("some-realm"));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(LoginController.SESSION_TOKEN_KEY, plaintext);
        return session;
    }
}
