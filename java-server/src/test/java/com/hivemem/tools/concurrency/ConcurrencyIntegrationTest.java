package com.hivemem.tools.concurrency;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.RateLimiter;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.search.CellSelectorRepository;
import com.hivemem.search.CellSearchRepository;
import com.hivemem.write.WriteToolRepository;
import com.hivemem.sync.InstanceConfig;
import com.hivemem.sync.OpLogWriter;
import com.hivemem.sync.PushDispatcher;
import com.hivemem.kg.KgEntityRepository;
import com.hivemem.write.WriteToolService;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ConcurrencyIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Testcontainers
class ConcurrencyIntegrationTest {

    private static final AuthPrincipal WRITER = new AuthPrincipal("writer-1", AuthRole.WRITER);
    private static final OffsetDateTime BASE_TIME = OffsetDateTime.parse("2026-04-14T09:00:00Z");

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
    private WriteToolService writeToolService;

    @Autowired
    private DSLContext dslContext;

    @BeforeEach
    void resetDatabase() {
        dslContext.execute("TRUNCATE TABLE agent_diary, cell_references, references_, blueprints, identity, agents, facts, tunnels, cells CASCADE");
    }

    @Test
    void concurrentDrawerWritesCreateDistinctRows() throws Exception {
        List<Map<String, Object>> results = runConcurrently(6, index -> writeToolService.addCell(
                WRITER,
                "Concurrent drawer " + index,
                "test",
                "facts",
                "concurrency",
                "system",
                List.of("concurrency"),
                1,
                "Concurrent summary " + index,
                List.of("drawer", Integer.toString(index)),
                null,
                null,
                "committed",
                BASE_TIME.plusSeconds(index),
                null
        ));

        assertThat(results)
                .extracting(result -> result.get("id"))
                .doesNotHaveDuplicates()
                .hasSize(6);
        assertThat(countRows("SELECT count(*) AS cnt FROM cells WHERE realm = ? AND signal = ?", "test", "facts"))
                .isEqualTo(6L);
    }

    @Test
    void concurrentFactWritesDoNotLoseRows() throws Exception {
        List<Map<String, Object>> results = runConcurrently(6, index -> writeToolService.kgAdd(
                WRITER,
                "Concurrent entity " + index,
                "has_property",
                "value-" + index,
                1.0d,
                null,
                "committed",
                BASE_TIME.plusSeconds(index),
                null
        ));

        assertThat(results)
                .extracting(result -> result.get("id"))
                .doesNotHaveDuplicates()
                .hasSize(6);
        assertThat(countRows("SELECT count(*) AS cnt FROM facts WHERE predicate = ?", "has_property"))
                .isEqualTo(6L);
    }

    @Test
    void concurrentApprovalOnSameIdsIsIdempotent() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            Map<String, Object> row = writeToolService.addCell(
                    WRITER,
                    "Pending drawer " + index,
                    "test",
                    "facts",
                    "approve",
                    "system",
                    List.of(),
                    1,
                    null,
                    List.of(),
                    null,
                    null,
                    "pending",
                    BASE_TIME.plusSeconds(index),
                    null
            );
            ids.add(UUID.fromString((String) row.get("id")));
        }

        List<Map<String, Object>> results = runConcurrently(2, ignored -> writeToolService.approvePending(ids, "committed"));

        assertThat(results)
                .extracting(result -> ((Number) result.get("count")).intValue())
                .containsExactlyInAnyOrder(5, 0);
        assertThat(countRows(
                "SELECT count(*) AS cnt FROM cells WHERE realm = ? AND signal = ? AND status = 'committed'",
                "test",
                "facts"
        ))
                .isEqualTo(5L);
    }

    @Test
    void concurrentBlueprintUpdatesLeaveOneActiveVersion() throws Exception {
        List<Map<String, Object>> results = runConcurrently(2, index -> writeToolService.updateBlueprint(
                WRITER,
                "race-wing",
                "Map v" + (index + 1),
                "Narrative " + (index + 1),
                List.of("hall-" + (index + 1)),
                List.of()
        ));

        assertThat(results)
                .extracting(result -> result.get("realm"))
                .containsOnly("race-wing");
        assertThat(countRows("SELECT count(*) AS cnt FROM blueprints WHERE realm = ?", "race-wing"))
                .isEqualTo(2L);
        assertThat(countRows("SELECT count(*) AS cnt FROM blueprints WHERE realm = ? AND valid_until IS NULL", "race-wing"))
                .isEqualTo(1L);
    }

    @Test
    void concurrentReviseOfSameDrawerLeavesOneActiveChild() throws Exception {
        Map<String, Object> original = writeToolService.addCell(
                WRITER,
                "Original content",
                "test",
                "facts",
                "revise",
                "system",
                List.of(),
                1,
                "Original summary",
                List.of(),
                null,
                null,
                "committed",
                BASE_TIME,
                null
        );
        UUID originalId = UUID.fromString((String) original.get("id"));

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        List<Object> results = runConcurrentlyTolerant(2, index -> {
            writeToolService.reviseCell(
                    WRITER,
                    originalId,
                    "Revised content v" + index,
                    "Summary v" + index
            );
            successes.incrementAndGet();
            return "ok";
        }, failures);

        // At least one must succeed; the other may fail (already revised / FOR UPDATE conflict)
        assertThat(successes.get()).isGreaterThanOrEqualTo(1);

        // Exactly one child with valid_until IS NULL
        long activeChildren = countRows(
                "SELECT count(*) AS cnt FROM cells WHERE parent_id = ? AND valid_until IS NULL",
                originalId
        );
        assertThat(activeChildren).isEqualTo(1L);
    }

    @Test
    void concurrentReviseOfSameFactLeavesOneActiveChild() throws Exception {
        Map<String, Object> original = writeToolService.kgAdd(
                WRITER,
                "test-entity",
                "has_color",
                "blue",
                1.0d,
                null,
                "committed",
                BASE_TIME,
                null
        );
        UUID originalId = UUID.fromString((String) original.get("id"));

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        List<Object> results = runConcurrentlyTolerant(2, index -> {
            writeToolService.reviseFact(
                    WRITER,
                    originalId,
                    "revised-value-" + index
            );
            successes.incrementAndGet();
            return "ok";
        }, failures);

        assertThat(successes.get()).isGreaterThanOrEqualTo(1);

        // Exactly one child with valid_until IS NULL
        long activeChildren = countRows(
                "SELECT count(*) AS cnt FROM facts WHERE parent_id = ? AND valid_until IS NULL",
                originalId
        );
        assertThat(activeChildren).isEqualTo(1L);
    }

    @Test
    void concurrentRateLimitRecordFailureNoConcurrentModificationException() throws Exception {
        RateLimiter rateLimiter = new RateLimiter();
        String ip = "10.0.0.99";

        // 20 concurrent failure recordings from the same IP
        runConcurrently(20, index -> {
            rateLimiter.recordFailure(ip);
            return rateLimiter.checkRateLimit(ip);
        });

        // IP must be banned (>= MAX_FAILED_ATTEMPTS)
        assertThat(rateLimiter.checkRateLimit(ip)).isGreaterThan(0L);

        // Clear and verify unbanned
        rateLimiter.clearFailures(ip);
        assertThat(rateLimiter.checkRateLimit(ip)).isEqualTo(0L);
    }

    @Test
    void aggressiveConcurrentBlueprintUpdatesNoLostWrites() throws Exception {
        int threadCount = 10;

        List<Map<String, Object>> results = runConcurrently(threadCount, index -> writeToolService.updateBlueprint(
                WRITER,
                "aggressive-wing",
                "Map v" + (index + 1),
                "Narrative " + (index + 1),
                List.of("hall-" + (index + 1)),
                List.of()
        ));

        assertThat(results)
                .extracting(result -> result.get("realm"))
                .containsOnly("aggressive-wing");

        // All 10 writes persisted (no lost writes)
        long totalRows = countRows("SELECT count(*) AS cnt FROM blueprints WHERE realm = ?", "aggressive-wing");
        assertThat(totalRows).isEqualTo(10L);

        // Exactly 1 active row (the last writer wins, all others closed)
        long activeRows = countRows("SELECT count(*) AS cnt FROM blueprints WHERE realm = ? AND valid_until IS NULL", "aggressive-wing");
        assertThat(activeRows).isEqualTo(1L);
    }

    @Test
    void concurrentKgInvalidateSameFactIsIdempotent() throws Exception {
        Map<String, Object> fact = writeToolService.kgAdd(
                WRITER,
                "test-entity",
                "has_size",
                "large",
                1.0d,
                null,
                "committed",
                BASE_TIME,
                null
        );
        UUID factId = UUID.fromString((String) fact.get("id"));

        // Two threads try to invalidate the same fact
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        runConcurrentlyTolerant(2, index -> {
            writeToolService.kgInvalidate(factId);
            successes.incrementAndGet();
            return "ok";
        }, failures);

        // Both calls return without error (invalidateFact uses UPDATE...WHERE valid_until IS NULL,
        // so the second call simply updates zero rows)
        assertThat(successes.get()).isEqualTo(2);

        // valid_until is set exactly once
        long invalidated = countRows(
                "SELECT count(*) AS cnt FROM facts WHERE id = ? AND valid_until IS NOT NULL",
                factId
        );
        assertThat(invalidated).isEqualTo(1L);
    }

    @Test
    void concurrentAddTunnelWithIdenticalEndpointsBothSucceed() throws Exception {
        // Create two drawers to link
        Map<String, Object> drawerA = writeToolService.addCell(
                WRITER, "Drawer A", "test", "facts", "a", "system",
                List.of(), 1, null, List.of(), null, null, "committed", BASE_TIME, null
        );
        Map<String, Object> drawerB = writeToolService.addCell(
                WRITER, "Drawer B", "test", "facts", "b", "system",
                List.of(), 1, null, List.of(), null, null, "committed", BASE_TIME.plusSeconds(1), null
        );
        UUID idA = UUID.fromString((String) drawerA.get("id"));
        UUID idB = UUID.fromString((String) drawerB.get("id"));

        // Two threads add A->B with the same relation
        List<Map<String, Object>> results = runConcurrently(2, index -> writeToolService.addTunnel(
                WRITER, idA, idB, "related_to", "concurrent note " + index, "committed"
        ));

        // No unique constraint on (from_cell, to_cell, relation), so both succeed
        assertThat(results).hasSize(2);
        assertThat(results)
                .extracting(result -> result.get("id"))
                .doesNotHaveDuplicates();

        // active_tunnels should reflect both
        long activeTunnels = countRows(
                "SELECT count(*) AS cnt FROM active_tunnels WHERE from_cell = ? AND to_cell = ?",
                idA, idB
        );
        assertThat(activeTunnels).isEqualTo(2L);
    }

    // Not ported: test_pool_lock_exists / cache stampede on pool init — Python-specific test
    //     (asyncio.Lock on module-level _pool_lock). Spring manages DataSource lifecycle via
    //     HikariCP; CachedTokenService uses Caffeine which is inherently thread-safe (computeIfAbsent).
    //     No user-facing pool lock or stampede-prone code path to test.

    // Not ported: test_concurrent_token_creation_different_names — tests Python security.create_token;
    //     Java token management is a separate service not wired in this test config.

    // Not ported: test_concurrent_token_creation_same_name — same reason as above.

    // Not ported: test_concurrent_validate_same_token — tests Python validate_token + LRU cache;
    //     Java uses Caffeine-backed CachedTokenService, tested separately.

    // Not ported: test_concurrent_cache_eviction — tests Python AuthMiddleware._validate_cached with tiny LRU;
    //     Java uses Caffeine with built-in thread-safe eviction, no meaningful race to test.

    // Not ported: test_concurrent_revoke_same_token — tests Python security.revoke_token;
    //     Java token management is a separate service not wired in this test config.

    private long countRows(String sql, Object... bindings) {
        return dslContext.fetchOne(sql, bindings).get("cnt", Long.class);
    }

    private <T> List<T> runConcurrentlyTolerant(int taskCount, IndexedTask<T> task, AtomicInteger failureCounter) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>(taskCount);
            for (int index = 0; index < taskCount; index++) {
                final int taskIndex = index;
                futures.add(executor.submit(awaitAndRun(ready, start, () -> task.run(taskIndex))));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<T> results = new ArrayList<>(taskCount);
            for (Future<T> future : futures) {
                try {
                    results.add(future.get(15, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    failureCounter.incrementAndGet();
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private <T> List<T> runConcurrently(int taskCount, IndexedTask<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>(taskCount);
            for (int index = 0; index < taskCount; index++) {
                final int taskIndex = index;
                futures.add(executor.submit(awaitAndRun(ready, start, () -> task.run(taskIndex))));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<T> results = new ArrayList<>(taskCount);
            for (Future<T> future : futures) {
                results.add(getResult(future));
            }
            return results;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static <T> Callable<T> awaitAndRun(CountDownLatch ready, CountDownLatch start, Callable<T> task) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start concurrent task");
            }
            return task.call();
        };
    }

    private static <T> T getResult(Future<T> future) throws Exception {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw new RuntimeException(cause);
        }
    }

    @FunctionalInterface
    interface IndexedTask<T> {
        T run(int index) throws Exception;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            WriteToolService.class,
            KgEntityRepository.class,
            CellSelectorRepository.class,
            WriteToolRepository.class,
            CellSearchRepository.class,
            OpLogWriter.class,
            InstanceConfig.class,
            TestConfig.class
    })
    static class TestApplication {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        EmbeddingClient embeddingClient() {
            return new FixedEmbeddingClient();
        }

        @Bean
        PushDispatcher pushDispatcher() {
            return org.mockito.Mockito.mock(PushDispatcher.class);
        }
    }
}
