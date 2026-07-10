package com.hivemem.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentMap;

@Component
public class RateLimiter {

    static final int MAX_FAILED_ATTEMPTS = 5;
    static final long BAN_SECONDS = 900;
    private static final int MAX_TRACKED_IPS = 100_000;

    /**
     * Caffeine-backed so the per-IP tracker is bounded: entries expire
     * {@link #BAN_SECONDS} after the last recorded failure (exactly the ban window)
     * and the total size is capped, so high-cardinality source IPs cannot grow the
     * heap without bound. Same pattern as {@code CachedTokenService}.
     */
    private final Cache<String, FailedAttempts> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(BAN_SECONDS))
            .maximumSize(MAX_TRACKED_IPS)
            .build();
    private final ConcurrentMap<String, FailedAttempts> tracker = cache.asMap();

    record FailedAttempts(int count, Instant lastAttempt) {}

    /** Returns remaining ban seconds, or 0 if not banned. */
    public long checkRateLimit(String ip) {
        long now = Instant.now().getEpochSecond();
        // Atomic check-and-expire: an expired ban is removed inside the compute so a
        // concurrent recordFailure can never interleave between check and removal.
        FailedAttempts attempts = tracker.computeIfPresent(ip, (key, existing) -> {
            if (existing.count() < MAX_FAILED_ATTEMPTS) return existing;
            long elapsed = now - existing.lastAttempt().getEpochSecond();
            return elapsed >= BAN_SECONDS ? null : existing;
        });
        if (attempts == null || attempts.count() < MAX_FAILED_ATTEMPTS) {
            return 0L;
        }
        return BAN_SECONDS - (now - attempts.lastAttempt().getEpochSecond());
    }

    public void recordFailure(String ip) {
        tracker.compute(ip, (key, existing) -> {
            int count = existing == null ? 1 : existing.count() + 1;
            return new FailedAttempts(count, Instant.now());
        });
    }

    public void clearFailures(String ip) {
        tracker.remove(ip);
    }

    /** Clears all tracked failures. Intended for test setup. */
    public void clearAll() {
        cache.invalidateAll();
    }
}
