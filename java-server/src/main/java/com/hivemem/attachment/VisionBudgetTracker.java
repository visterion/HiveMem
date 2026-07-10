package com.hivemem.attachment;

import org.jooq.DSLContext;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/** Daily-cost-cap tracker for Vision-API calls. Mirrors SummarizeBudgetTracker. */
public class VisionBudgetTracker {

    // Claude Haiku 4.5 pricing (USD per 1M tokens). Update when pricing changes.
    private static final double INPUT_USD_PER_M  = 1.0;
    private static final double OUTPUT_USD_PER_M = 5.0;

    // Estimated worst-case cost of a single vision call, reserved while a call is in
    // flight so concurrent callers cannot all pass canSpend() before any cost has been
    // recorded (check-then-act overshoot).
    private static final double EST_CALL_COST_USD = 0.02;

    private final DSLContext dsl;
    private final double dailyBudgetUsd;
    private final AtomicInteger inFlightCalls = new AtomicInteger();

    public VisionBudgetTracker(DSLContext dsl, double dailyBudgetUsd) {
        this.dsl = dsl;
        this.dailyBudgetUsd = dailyBudgetUsd;
    }

    public boolean canSpend() {
        if (dailyBudgetUsd <= 0) return false;
        double reserved = inFlightCalls.get() * EST_CALL_COST_USD;
        var rec = dsl.fetchOptional(
                "SELECT total_cost_usd FROM vision_usage WHERE day = ?", LocalDate.now());
        if (rec.isEmpty()) return reserved < dailyBudgetUsd;
        java.math.BigDecimal spent = rec.get().get(0, java.math.BigDecimal.class);
        return spent == null || spent.doubleValue() + reserved < dailyBudgetUsd;
    }

    /** Mark a vision call as in flight; MUST be paired with {@link #endCall()} in a finally. */
    public void beginCall() {
        inFlightCalls.incrementAndGet();
    }

    /** Release the in-flight reservation taken by {@link #beginCall()}. */
    public void endCall() {
        inFlightCalls.decrementAndGet();
    }

    public void recordCall(int inputTokens, int outputTokens) {
        double cost = (inputTokens / 1_000_000.0) * INPUT_USD_PER_M
                + (outputTokens / 1_000_000.0) * OUTPUT_USD_PER_M;
        dsl.execute(
                "INSERT INTO vision_usage (day, total_calls, total_input_tokens, total_output_tokens, total_cost_usd) "
                        + "VALUES (?, 1, ?, ?, ?) "
                        + "ON CONFLICT (day) DO UPDATE SET "
                        + "  total_calls = vision_usage.total_calls + 1, "
                        + "  total_input_tokens = vision_usage.total_input_tokens + EXCLUDED.total_input_tokens, "
                        + "  total_output_tokens = vision_usage.total_output_tokens + EXCLUDED.total_output_tokens, "
                        + "  total_cost_usd = vision_usage.total_cost_usd + EXCLUDED.total_cost_usd",
                LocalDate.now(), inputTokens, outputTokens, cost);
    }
}
