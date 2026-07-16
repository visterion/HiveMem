package com.hivemem.auth;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Authenticated principal for a request. {@code tokenId} references the underlying
 * {@code api_tokens.id} when known — used by OAuth flows that need to bind issued
 * authorization codes / access tokens to the originating identity. May be {@code null}
 * for legacy/test contexts where the token row id was not resolved.
 *
 * <p>{@code readRealms}/{@code writeRealms} carry the optional per-token realm ACL:
 * {@code null} means unrestricted (today's default behavior); a non-null list confines
 * the token to those realms for the respective dimension.
 */
public record AuthPrincipal(String name, AuthRole role, UUID tokenId,
                            List<String> readRealms, List<String> writeRealms) {

    public AuthPrincipal {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(role, "role");
    }

    /** Backward-compat: tokenId only, no realm scope (legacy & tests). */
    public AuthPrincipal(String name, AuthRole role, UUID tokenId) {
        this(name, role, tokenId, null, null);
    }

    /** Backward-compat: no tokenId, no realm scope (legacy & tests). */
    public AuthPrincipal(String name, AuthRole role) {
        this(name, role, null, null, null);
    }

    /** True when this token is confined to a realm set (either dimension). */
    public boolean isRealmScoped() {
        return readRealms != null || writeRealms != null;
    }
}
