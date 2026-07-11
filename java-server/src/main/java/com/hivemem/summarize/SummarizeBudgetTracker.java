package com.hivemem.summarize;

import org.jooq.DSLContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks per-day Anthropic call cost in the {@code summarize_usage} table and gates
 * further calls when the configured daily budget is exhausted.
 *
 * <p>Pricing for Claude Haiku 4.5 (pinned for Phase 1; later configurable via Item I):
 * <ul>
 *   <li>$0.80 per 1M input tokens</li>
 *   <li>$4.00 per 1M output tokens</li>
 * </ul>
 */
public class SummarizeBudgetTracker {

    private static final BigDecimal INPUT_PRICE_PER_1M = new BigDecimal("0.80");
    private static final BigDecimal OUTPUT_PRICE_PER_1M = new BigDecimal("4.00");
    private static final BigDecimal MILLION = new BigDecimal(1_000_000);

    // Estimated worst-case cost of a single summarize call, reserved while a call is in flight
    // so concurrent workers cannot all pass canSpend() before any cost has actually been
    // recorded (check-then-act overshoot). Mirrors VisionBudgetTracker.
    private static final double EST_CALL_COST_USD = 0.01;

    private final DSLContext dsl;
    private final double dailyBudgetUsd;
    private final AtomicInteger inFlightCalls = new AtomicInteger();

    public SummarizeBudgetTracker(DSLContext dsl, double dailyBudgetUsd) {
        this.dsl = dsl;
        this.dailyBudgetUsd = dailyBudgetUsd;
    }

    public boolean canSpend() {
        double reserved = inFlightCalls.get() * EST_CALL_COST_USD;
        BigDecimal todaySpent = dsl.fetchOptional(
                "SELECT total_cost_usd FROM summarize_usage WHERE day = ?", today())
                .map(r -> r.get(0, BigDecimal.class))
                .orElse(BigDecimal.ZERO);
        return todaySpent.doubleValue() + reserved < dailyBudgetUsd;
    }

    /** Mark a summarize call as in flight; MUST be paired with {@link #endCall()} in a finally. */
    public void beginCall() {
        inFlightCalls.incrementAndGet();
    }

    /** Release the in-flight reservation taken by {@link #beginCall()}. */
    public void endCall() {
        inFlightCalls.decrementAndGet();
    }

    public void recordCall(int inputTokens, int outputTokens) {
        BigDecimal cost = costOf(inputTokens, outputTokens);
        dsl.execute(
                "INSERT INTO summarize_usage (day, total_calls, total_input_tokens, total_output_tokens, total_cost_usd) "
                + "VALUES (?, 1, ?, ?, ?) "
                + "ON CONFLICT (day) DO UPDATE SET "
                + "  total_calls = summarize_usage.total_calls + 1, "
                + "  total_input_tokens = summarize_usage.total_input_tokens + EXCLUDED.total_input_tokens, "
                + "  total_output_tokens = summarize_usage.total_output_tokens + EXCLUDED.total_output_tokens, "
                + "  total_cost_usd = summarize_usage.total_cost_usd + EXCLUDED.total_cost_usd",
                today(), inputTokens, outputTokens, cost);
    }

    static BigDecimal costOf(int inputTokens, int outputTokens) {
        BigDecimal inCost = new BigDecimal(inputTokens).multiply(INPUT_PRICE_PER_1M)
                .divide(MILLION, 6, RoundingMode.HALF_UP);
        BigDecimal outCost = new BigDecimal(outputTokens).multiply(OUTPUT_PRICE_PER_1M)
                .divide(MILLION, 6, RoundingMode.HALF_UP);
        return inCost.add(outCost);
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
