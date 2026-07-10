package com.hivemem.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentMap;

@Component
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 5;
    private static final long BLOCK_DURATION_SECONDS = 15 * 60;
    private static final int MAX_TRACKED_IPS = 100_000;

    private record Failure(int count, Instant blockedUntil) {}

    private final Clock clock;

    /**
     * Caffeine-backed so the per-IP tracker is bounded: entries expire
     * {@code BLOCK_DURATION_SECONDS} after the last recorded failure (the block
     * itself is enforced via {@code blockedUntil} against the injectable clock)
     * and the total size is capped, so high-cardinality source IPs cannot grow
     * the heap without bound. Same pattern as {@code CachedTokenService}.
     */
    private final Cache<String, Failure> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(BLOCK_DURATION_SECONDS))
            .maximumSize(MAX_TRACKED_IPS)
            .build();
    private final ConcurrentMap<String, Failure> failures = cache.asMap();

    public LoginRateLimiter() {
        this(Clock.systemUTC());
    }

    LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean isBlocked(String ip) {
        Instant now = clock.instant();
        // Atomic check-and-expire: an expired block is removed inside the compute so a
        // concurrent recordFailure can never interleave between check and removal.
        Failure f = failures.computeIfPresent(ip, (key, existing) ->
                existing.blockedUntil() != null && now.isAfter(existing.blockedUntil())
                        ? null
                        : existing);
        return f != null && f.blockedUntil() != null;
    }

    public void recordFailure(String ip) {
        failures.merge(ip, new Failure(1, null), (existing, v) -> {
            int count = existing.count() + 1;
            Instant blocked = count >= MAX_ATTEMPTS
                    ? clock.instant().plusSeconds(BLOCK_DURATION_SECONDS)
                    : null;
            return new Failure(count, blocked);
        });
    }

    public void clearFailures(String ip) {
        failures.remove(ip);
    }

    /** Clears all tracked failures. Intended for test setup. */
    public void clearAll() {
        cache.invalidateAll();
    }
}
