package com.hivemem.auth;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.EmbeddingInfo;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(DbTokenServiceTest.TestConfig.class)
class DbTokenServiceTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        EmbeddingClient testEmbeddingClient() {
            return new FixedEmbeddingClient();
        }
    }


    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem")
            .withUsername("hivemem")
            .withPassword("hivemem")
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

    @Autowired
    @Qualifier("dbTokenService")
    private TokenService dbTokenService;

    @Autowired
    private DSLContext dslContext;

    @MockitoBean(name = "httpEmbeddingClient")
    private EmbeddingClient embeddingClient;

    @BeforeEach
    void resetDatabase() {
        dslContext.execute("TRUNCATE TABLE api_tokens CASCADE");
        when(embeddingClient.getInfo()).thenReturn(new EmbeddingInfo("test-model", 1024));
    }

    @Test
    void validatesCommittedTokenFromDatabase() throws Exception {
        dslContext.execute("""
                INSERT INTO api_tokens (token_hash, name, role)
                VALUES (?, ?, ?)
                """, sha256("good-token"), "admin-user", "admin");

        var principal = dbTokenService.validateToken("good-token");

        assertThat(principal).isPresent();
        assertThat(principal.orElseThrow().name()).isEqualTo("admin-user");
        assertThat(principal.orElseThrow().role()).isEqualTo(AuthRole.ADMIN);
    }

    @Test
    void rejectsUnknownToken() {
        assertThat(dbTokenService.validateToken("missing-token")).isEmpty();
    }

    @Test
    void rejectsRevokedToken() throws Exception {
        insertToken(
                "revoked-user",
                "revoked-token",
                "admin",
                OffsetDateTime.now().plusHours(1),
                OffsetDateTime.now()
        );

        assertThat(dbTokenService.validateToken("revoked-token")).isEmpty();
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        insertToken(
                "expired-user",
                "expired-token",
                "admin",
                OffsetDateTime.now().minusHours(1),
                null
        );

        assertThat(dbTokenService.validateToken("expired-token")).isEmpty();
    }

    private void insertToken(
            String name,
            String plaintext,
            String role,
            OffsetDateTime expiresAt,
            OffsetDateTime revokedAt
    ) throws Exception {
        dslContext.execute("""
                INSERT INTO api_tokens (token_hash, name, role, expires_at, revoked_at)
                VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz)
                """, sha256(plaintext), name, role, expiresAt, revokedAt);
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    // ── realm-scoped token round-trip (V0050) ─────────────────────────────

    @Test
    void scopedTokenRoundTripsRealmSetsThroughValidateAndFindById() {
        String plaintext = dbTokenService.createToken(
                "dracul-research-agent", AuthRole.WRITER, null,
                List.of("dracul-research", "dracul"),   // read_realms
                List.of("dracul-research"));             // write_realms

        Optional<AuthPrincipal> byToken = dbTokenService.validateToken(plaintext);
        assertThat(byToken).isPresent();
        assertThat(byToken.get().readRealms()).containsExactly("dracul-research", "dracul");
        assertThat(byToken.get().writeRealms()).containsExactly("dracul-research");

        Optional<AuthPrincipal> byId = dbTokenService.findById(byToken.get().tokenId());
        assertThat(byId).isPresent();
        assertThat(byId.get().readRealms()).containsExactly("dracul-research", "dracul");
        assertThat(byId.get().writeRealms()).containsExactly("dracul-research");
    }

    @Test
    void unscopedTokenHasNullRealmSets_backwardCompat() {
        String plaintext = dbTokenService.createToken("legacy-writer", AuthRole.WRITER, null, null, null);
        AuthPrincipal p = dbTokenService.validateToken(plaintext).orElseThrow();
        assertThat(p.readRealms()).isNull();
        assertThat(p.writeRealms()).isNull();
    }

    @Test
    void createTokenNormalizesRealmsToLowercaseDashes() {
        String plaintext = dbTokenService.createToken("norm", AuthRole.WRITER, null,
                List.of("Dracul Research"), List.of("Dracul Research"));
        AuthPrincipal p = dbTokenService.validateToken(plaintext).orElseThrow();
        assertThat(p.writeRealms()).containsExactly("dracul-research");
    }
}
