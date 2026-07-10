package com.hivemem.hooks;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
public class SessionInjectionCache {

    private record Key(String sessionId, UUID cellId) {}

    private final Cache<Key, Integer> cache;
    private final int dedupWindowTurns;

    public SessionInjectionCache(HookProperties props) {
        this(Duration.ofHours(1), props.getDedupWindowTurns());
    }

    SessionInjectionCache() {
        this(Duration.ofHours(1), 5);
    }

    SessionInjectionCache(Duration ttl, int dedupWindowTurns) {
        this.dedupWindowTurns = dedupWindowTurns;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(50_000)
                .build();
    }

    public void recordInjection(String sessionId, UUID cellId, int turn) {
        cache.put(new Key(sessionId, cellId), turn);
    }

    public boolean recentlyInjected(String sessionId, UUID cellId, int currentTurn) {
        Integer recordedTurn = cache.getIfPresent(new Key(sessionId, cellId));
        return recordedTurn != null && (currentTurn - recordedTurn) < dedupWindowTurns;
    }

    /**
     * Atomically checks the dedup window and records the injection in one step,
     * so concurrent requests for the same session cannot both pass the check.
     *
     * @return true if the cell was not recently injected and has now been recorded
     */
    public boolean tryRecordInjection(String sessionId, UUID cellId, int currentTurn) {
        boolean[] injected = new boolean[1];
        cache.asMap().compute(new Key(sessionId, cellId), (key, recordedTurn) -> {
            if (recordedTurn != null && (currentTurn - recordedTurn) < dedupWindowTurns) {
                return recordedTurn; // still within the dedup window — keep the old entry
            }
            injected[0] = true;
            return currentTurn;
        });
        return injected[0];
    }
}
