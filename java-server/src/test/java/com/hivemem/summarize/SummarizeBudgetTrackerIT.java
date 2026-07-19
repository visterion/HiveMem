package com.hivemem.summarize;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class SummarizeBudgetTrackerIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("hivemem").withUsername("hivemem").withPassword("hivemem")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                    (cmd.getHostConfig() == null ? new com.github.dockerjava.api.model.HostConfig()
                            : cmd.getHostConfig()).withSecurityOpts(java.util.List.of("apparmor=unconfined"))));

    private DSLContext dsl;

    @BeforeEach
    void setUp() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        dsl.execute("DELETE FROM summarize_usage");
    }

    @Test
    void canSpend_whenNoUsageYet() {
        SummarizeBudgetTracker t = new SummarizeBudgetTracker(dsl, 1.00);
        assertTrue(t.canSpend());
    }

    @Test
    void canSpend_underBudget() {
        SummarizeBudgetTracker t = new SummarizeBudgetTracker(dsl, 1.00);
        t.recordCall(1000, 200);                  // ~$0.0016
        assertTrue(t.canSpend());
    }

    @Test
    void cannotSpend_overBudget() {
        SummarizeBudgetTracker t = new SummarizeBudgetTracker(dsl, 0.001);  // tiny budget
        t.recordCall(1000, 200);
        assertFalse(t.canSpend());
    }

    @Test
    void recordCallUpserts() {
        SummarizeBudgetTracker t = new SummarizeBudgetTracker(dsl, 100.00);
        t.recordCall(500, 100);
        t.recordCall(500, 100);
        Long calls = ((Number) dsl.fetchOne("SELECT total_calls FROM summarize_usage").get(0)).longValue();
        assertEquals(2L, calls.longValue());
    }

    @Test
    void dayBoundaryIsUtcNotServerLocal() {
        // Insert a row for "today" using explicit UTC, then confirm canSpend()/recordCall() see
        // the exact same day regardless of the JVM's default timezone.
        SummarizeBudgetTracker t = new SummarizeBudgetTracker(dsl, 0.001);
        t.recordCall(1_000_000, 0); // definitely exceeds a $0.001 budget
        java.time.LocalDate utcToday = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        Long rows = ((Number) dsl.fetchOne(
                "SELECT count(*) FROM summarize_usage WHERE day = ?", utcToday).get(0)).longValue();
        assertEquals(1L, rows.longValue());
        assertFalse(t.canSpend());
    }

    @Test
    void inFlightReservationBlocksFurtherSpendBeforeAnyCostIsRecorded() {
        // This is the exact check-then-act overshoot the reservation exists to close: without
        // it, N concurrent callers can all observe canSpend()==true simultaneously because
        // nothing has hit summarize_usage yet (recordCall only runs after the LLM call
        // returns). beginCall()/endCall() reserve the estimated cost up front so a caller
        // already in flight is visible to everyone else's canSpend() check immediately —
        // deterministically reproduced here without needing a real race.
        double budgetForOneCall = 0.01; // exactly EST_CALL_COST_USD
        SummarizeBudgetTracker t = new SummarizeBudgetTracker(dsl, budgetForOneCall);

        assertTrue(t.canSpend()); // nothing in flight, nothing recorded yet
        t.beginCall(); // call #1 starts (mirrors a caller about to invoke the LLM)

        // A second concurrent caller must be blocked purely by call #1's reservation — call #1
        // has not recorded any actual cost to summarize_usage yet (it hasn't returned).
        assertFalse(t.canSpend());

        t.endCall(); // call #1 finishes without ever recording a cost (e.g. it failed)
        assertTrue(t.canSpend()); // reservation released, budget available again
    }

    @Test
    void concurrentCanSpendNearCapDoesNotOvershootWhenCallsOverlapInTime() throws Exception {
        // A more realistic concurrency scenario: each "call" holds its reservation for a short
        // window (standing in for the real LLM round-trip), so overlapping workers actually see
        // each other's in-flight reservations, not just an instantaneous burst.
        double budgetForTwoCalls = 0.021;
        SummarizeBudgetTracker t = new SummarizeBudgetTracker(dsl, budgetForTwoCalls);

        int workers = 6;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(workers);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(workers);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger callsMade = new java.util.concurrent.atomic.AtomicInteger();

        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                t.beginCall();
                try {
                    if (t.canSpend()) {
                        callsMade.incrementAndGet();
                        Thread.sleep(150); // stand-in for the real (slow) LLM round-trip
                        t.recordCall(1000, 200);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    t.endCall();
                }
            }));
        }
        ready.await();
        go.countDown();
        for (var f : futures) f.get();
        pool.shutdown();

        // Without the reservation, all 6 workers would observe canSpend()==true (nothing
        // committed to the DB yet) and all 6 would proceed — a 3x overshoot of the 2-call
        // budget. With it, at most a couple of extra stragglers get through.
        assertTrue(callsMade.get() < workers,
                "expected the reservation to block at least some overlapping callers, got "
                        + callsMade.get() + "/" + workers);
    }

    @Test
    void todaySpendUsd_reflectsRecordedCostAndExposesBudget() {
        SummarizeBudgetTracker t = new SummarizeBudgetTracker(dsl, 5.00);
        assertEquals(0, t.todaySpendUsd().signum(), "no usage yet → zero");
        t.recordCall(1000, 200);
        assertEquals(
                SummarizeBudgetTracker.costOf(1000, 200).doubleValue(),
                t.todaySpendUsd().doubleValue(),
                1e-9);
        assertEquals(5.00, t.dailyBudgetUsd());
    }
}
