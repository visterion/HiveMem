package com.hivemem.auth;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression coverage for the /mcp rate-limit guard: a request with no
 * {@code Authorization} header presents no credential to guess, so it must never count
 * toward the ban ({@link RateLimiter#MAX_FAILED_ATTEMPTS}); a request that presents an
 * invalid bearer is a real guessing attempt and must still trip the ban.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthFilterRateLimitTest.TestConfig.class)
@Testcontainers
class AuthFilterRateLimitTest {

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
    MockMvc mockMvc;

    @Autowired
    RateLimiter rateLimiter;

    @BeforeEach
    void resetRateLimiter() {
        rateLimiter.clearAll();
    }

    /**
     * A stale PWA shell polling /mcp without any Authorization header must not ban the
     * user's IP — that would lock out Claude Code from the same address.
     */
    @Test
    void headerlessRequestsDoNotTriggerTheBan() throws Exception {
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(post("/mcp").header("CF-Connecting-IP", "203.0.113.9"))
                    .andExpect(status().isUnauthorized());
        }
        // Still 401 (not 429): the ban never engaged.
        mockMvc.perform(post("/mcp").header("CF-Connecting-IP", "203.0.113.9"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidBearerStillTriggersTheBan() throws Exception {
        // RateLimiter.MAX_FAILED_ATTEMPTS is 5: the 5 calls below each record a failure
        // (count reaches 5), so the 6th call already finds the bucket banned.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/mcp")
                            .header("CF-Connecting-IP", "203.0.113.10")
                            .header("Authorization", "Bearer wrong-" + i))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/mcp")
                        .header("CF-Connecting-IP", "203.0.113.10")
                        .header("Authorization", "Bearer wrong-again"))
                .andExpect(status().isTooManyRequests());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        EmbeddingClient embeddingClient() {
            return new FixedEmbeddingClient();
        }
    }
}
