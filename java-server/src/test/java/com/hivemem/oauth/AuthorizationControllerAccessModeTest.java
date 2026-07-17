package com.hivemem.oauth;

import com.hivemem.auth.AccessJwtResolverTestSupport;
import com.hivemem.auth.HumanPrincipalResolver;
import com.hivemem.auth.TokenService;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression coverage for Access-mode OAuth consent: with no session and no filter
 * populating the principal attribute on {@code /oauth/*}, the Access JWT presented on the
 * request must be the fourth {@code resolvePrincipal} source — otherwise every connector
 * pairing dead-ends in a redirect to a {@code /login} page that does not exist in this mode
 * (see {@code oauth-browser-authorize-flow-rca}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(AuthorizationControllerAccessModeTest.TestConfig.class)
@TestPropertySource(properties = {
        "hivemem.oauth.enabled=true",
        "hivemem.oauth.issuer=https://hivemem.example.com",
        "hivemem.access.enabled=true",
        "hivemem.access.team-domain=" + AccessJwtTestFixtures.TEAM_DOMAIN,
        "hivemem.access.audience=" + AccessJwtTestFixtures.AUDIENCE
})
class AuthorizationControllerAccessModeTest {

    private static final String REDIRECT_URI = "https://claude.ai/callback";
    private static final String KNOWN_EMAIL = "viktor@example.com";
    private static final String UNKNOWN_EMAIL = "stranger@example.com";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null
                            ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig())
                            .withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired MockMvc mockMvc;
    @Autowired DSLContext dsl;

    @BeforeEach
    void seed() {
        dsl.execute("TRUNCATE TABLE oauth_tokens, oauth_authorization_codes, oauth_clients CASCADE");
        dsl.execute("DELETE FROM api_tokens WHERE name LIKE 'access-mode-oauth-%'");
        dsl.execute("""
                INSERT INTO oauth_clients (client_id, client_name, redirect_uris)
                VALUES (?, ?, ARRAY[?]::TEXT[])
                ON CONFLICT (client_id) DO NOTHING
                """, AccessJwtTestFixtures.registeredClientId(), "Access Mode Test Client", REDIRECT_URI);
        dsl.execute("""
                INSERT INTO api_tokens (token_hash, name, role, email)
                VALUES (?, ?, 'admin', ?)
                """, "test-hash-" + UUID.randomUUID(), "access-mode-oauth-" + UUID.randomUUID(), KNOWN_EMAIL);
    }

    @Test
    void consentGetResolvesPrincipalFromAccessJwt() throws Exception {
        String jwt = AccessJwtTestFixtures.signedFor(KNOWN_EMAIL);

        mockMvc.perform(get("/oauth/authorize")
                        .queryParam("client_id", AccessJwtTestFixtures.registeredClientId())
                        .queryParam("redirect_uri", REDIRECT_URI)
                        .queryParam("response_type", "code")
                        .queryParam("code_challenge", "abc")
                        .queryParam("code_challenge_method", "S256")
                        .header("Cf-Access-Jwt-Assertion", jwt))
                .andExpect(status().isOk());
    }

    @Test
    void consentGetWithoutJwtIsForbiddenNotRedirected() throws Exception {
        mockMvc.perform(get("/oauth/authorize")
                        .queryParam("client_id", AccessJwtTestFixtures.registeredClientId())
                        .queryParam("redirect_uri", REDIRECT_URI)
                        .queryParam("response_type", "code")
                        .queryParam("code_challenge", "abc")
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().isForbidden());
    }

    @Test
    void consentGetWithJwtForUnknownEmailIsForbidden() throws Exception {
        String jwt = AccessJwtTestFixtures.signedFor(UNKNOWN_EMAIL);

        mockMvc.perform(get("/oauth/authorize")
                        .queryParam("client_id", AccessJwtTestFixtures.registeredClientId())
                        .queryParam("redirect_uri", REDIRECT_URI)
                        .queryParam("response_type", "code")
                        .queryParam("code_challenge", "abc")
                        .queryParam("code_challenge_method", "S256")
                        .header("Cf-Access-Jwt-Assertion", jwt))
                .andExpect(status().isForbidden());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        HumanPrincipalResolver testAccessJwtResolver(TokenService tokenService) {
            return AccessJwtResolverTestSupport.forTesting(
                    AccessJwtTestFixtures.TEAM_DOMAIN, AccessJwtTestFixtures.AUDIENCE,
                    AccessJwtTestFixtures.rsaKey().toPublicJWK(), tokenService);
        }

        @Bean
        @Primary
        EmbeddingClient testEmbeddingClient() {
            return new FixedEmbeddingClient();
        }
    }
}
