package com.hivemem.auth;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CachedTokenServiceEmailTest {

    private static final AuthPrincipal ADMIN =
            new AuthPrincipal("admin", AuthRole.ADMIN, UUID.randomUUID());

    /**
     * Security regression: the email cache must never share a keyspace with the
     * plaintext-token cache. Otherwise "Bearer <email>" would hit the cached email
     * entry and authenticate as ADMIN without any secret.
     */
    @Test
    void emailCacheDoesNotLeakIntoTokenValidation() {
        DbTokenService delegate = mock(DbTokenService.class);
        when(delegate.findByEmail("viktor@example.com")).thenReturn(Optional.of(ADMIN));
        when(delegate.validateToken(anyString())).thenReturn(Optional.empty());

        CachedTokenService service = new CachedTokenService(delegate);

        assertThat(service.findByEmail("viktor@example.com")).contains(ADMIN);
        // The email is not a secret — presenting it as a bearer token must fail.
        assertThat(service.validateToken("viktor@example.com")).isEmpty();
    }

    @Test
    void tokenCacheDoesNotPoisonEmailLookup() {
        DbTokenService delegate = mock(DbTokenService.class);
        when(delegate.validateToken("viktor@example.com")).thenReturn(Optional.empty());
        when(delegate.findByEmail("viktor@example.com")).thenReturn(Optional.of(ADMIN));

        CachedTokenService service = new CachedTokenService(delegate);

        assertThat(service.validateToken("viktor@example.com")).isEmpty();
        assertThat(service.findByEmail("viktor@example.com")).contains(ADMIN);
    }

    @Test
    void emailLookupIsCaseInsensitiveAndCached() {
        DbTokenService delegate = mock(DbTokenService.class);
        when(delegate.findByEmail("viktor@example.com")).thenReturn(Optional.of(ADMIN));

        CachedTokenService service = new CachedTokenService(delegate);

        assertThat(service.findByEmail("Viktor@Example.com")).contains(ADMIN);
        assertThat(service.findByEmail("viktor@example.com")).contains(ADMIN);
        org.mockito.Mockito.verify(delegate, org.mockito.Mockito.times(1))
                .findByEmail("viktor@example.com");
    }

    @Test
    void revokeInvalidatesBothCaches() {
        DbTokenService delegate = mock(DbTokenService.class);
        when(delegate.findByEmail("viktor@example.com"))
                .thenReturn(Optional.of(ADMIN))
                .thenReturn(Optional.empty());

        CachedTokenService service = new CachedTokenService(delegate);
        assertThat(service.findByEmail("viktor@example.com")).contains(ADMIN);

        service.revokeToken("admin");

        assertThat(service.findByEmail("viktor@example.com")).isEmpty();
    }
}
