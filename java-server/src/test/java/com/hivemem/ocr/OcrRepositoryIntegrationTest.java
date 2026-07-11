package com.hivemem.ocr;

import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(OcrRepositoryIntegrationTest.TestConfig.class)
class OcrRepositoryIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean @Primary
        EmbeddingClient testEmbeddingClient() { return new FixedEmbeddingClient(); }
    }

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

    @Autowired DSLContext dsl;
    @Autowired OcrRepository repo;

    @Test
    void findCellsPendingOcrReturnsPendingAndRetryableFailuresOnly() {
        UUID pending = insertCell(new String[]{"ocr_pending"});
        UUID untagged = insertCell(new String[]{});
        UUID permanentlyFailed = insertCell(new String[]{"ocr_failed", "ocr_failed_2", "ocr_failed_permanent"});

        List<UUID> ids = repo.findCellsPendingOcr(100);

        assertThat(ids).contains(pending);
        assertThat(ids).doesNotContain(untagged);
        assertThat(ids).doesNotContain(permanentlyFailed);
    }

    /** D2/M13: exactly one of two concurrent claims on the same cell must win. */
    @Test
    void onlyOneConcurrentClaimSucceeds() throws Exception {
        UUID cellId = insertCell(new String[]{"ocr_pending"});

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> attempt = () -> repo.tryClaim(cellId);
            List<java.util.concurrent.Future<Boolean>> futures = pool.invokeAll(List.of(attempt, attempt));
            long wins = futures.stream().mapToInt(f -> {
                try {
                    return f.get() ? 1 : 0;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).sum();
            assertThat(wins).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void claimIsReleasedByClearClaim() {
        UUID cellId = insertCell(new String[]{"ocr_pending"});

        assertThat(repo.tryClaim(cellId)).isTrue();
        assertThat(repo.tryClaim(cellId)).isFalse(); // still held

        repo.clearClaim(cellId);

        assertThat(repo.tryClaim(cellId)).isTrue(); // reclaimable after clear
    }

    @Test
    void staleClaimOlderThan30MinutesIsReclaimable() {
        UUID cellId = insertCell(new String[]{"ocr_pending"});
        dsl.execute("UPDATE cells SET ocr_claimed_at = now() - interval '31 minutes' WHERE id = ?", cellId);

        assertThat(repo.tryClaim(cellId)).isTrue();
    }

    private UUID insertCell(String[] tags) {
        UUID id = UUID.randomUUID();
        dsl.execute("""
                INSERT INTO cells (id, content, embedding, realm, signal, topic, status, tags, valid_from)
                VALUES (?::uuid, 'x', array_fill(0, ARRAY[1024])::vector, 'eng', 'facts', 'test', 'committed', ?, now())
                """, id, tags);
        return id;
    }
}
