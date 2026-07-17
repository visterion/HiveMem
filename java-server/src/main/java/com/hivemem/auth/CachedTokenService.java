package com.hivemem.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class CachedTokenService implements TokenService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final int CACHE_MAX_SIZE = 1000;

    private final DbTokenService delegate;
    private final Cache<String, Optional<AuthPrincipal>> cache;
    /**
     * Separate instance on purpose — never share the keyspace with {@link #cache}.
     * That cache is keyed by plaintext token; an email key in the same map would make
     * "Authorization: Bearer <email>" a cache hit and authenticate without a secret.
     */
    private final Cache<String, Optional<AuthPrincipal>> emailCache;

    public CachedTokenService(DbTokenService delegate) {
        this.delegate = delegate;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL)
                .maximumSize(CACHE_MAX_SIZE)
                .build();
        this.emailCache = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL)
                .maximumSize(CACHE_MAX_SIZE)
                .build();
    }

    @Override
    public Optional<AuthPrincipal> validateToken(String token) {
        return cache.get(token, delegate::validateToken);
    }

    @Override
    public Optional<AuthPrincipal> findByEmail(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        String key = email.toLowerCase(java.util.Locale.ROOT);
        return emailCache.get(key, delegate::findByEmail);
    }

    @Override
    public Optional<AuthPrincipal> findById(UUID tokenId) {
        // Not cached — id-based lookup is rarer (OAuth-only path) and the cache is keyed by plaintext.
        return delegate.findById(tokenId);
    }

    @Override
    public String createToken(String name, AuthRole role, Integer expiresInDays,
                              List<String> readRealms, List<String> writeRealms) {
        return delegate.createToken(name, role, expiresInDays, readRealms, writeRealms);
    }

    @Override
    public List<TokenSummary> listTokens(boolean includeRevoked, int limit) {
        return delegate.listTokens(includeRevoked, limit);
    }

    @Override
    public void revokeToken(String name) {
        delegate.revokeToken(name);
        // Invalidate the full cache: we don't know which plaintext maps to the revoked name,
        // and the cache is keyed by plaintext, not by name. Simpler and safer than partial invalidation.
        cache.invalidateAll();
        emailCache.invalidateAll();
    }

    @Override
    public Optional<TokenSummary> getTokenInfo(String name) {
        return delegate.getTokenInfo(name);
    }
}
