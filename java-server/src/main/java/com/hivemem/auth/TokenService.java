package com.hivemem.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TokenService {

    /**
     * Validate a plaintext bearer token. Returns the authenticated principal if the
     * token exists, is not revoked, and has not expired. Returns empty otherwise.
     */
    Optional<AuthPrincipal> validateToken(String token);

    /**
     * Look up a principal by the {@code api_tokens.id} (used by OAuth flows that
     * carry the token id in their persisted state). Returns empty if the token is
     * unknown, revoked, or expired.
     */
    Optional<AuthPrincipal> findById(UUID tokenId);

    /**
     * Create a new API token. Returns the plaintext value, which is shown to the
     * caller exactly once and never stored again — only the SHA-256 hash persists.
     *
     * @param name            unique token identifier
     * @param role            authorisation role
     * @param expiresInDays   optional validity window in days; null for no expiry
     * @param readRealms      optional realm ACL for reads; null for unrestricted
     * @param writeRealms     optional realm ACL for writes; null for unrestricted
     * @throws IllegalStateException if the name is already taken
     */
    String createToken(String name, AuthRole role, Integer expiresInDays,
                       List<String> readRealms, List<String> writeRealms);

    /**
     * List tokens ordered by creation time. Never includes the plaintext or hash.
     *
     * @param includeRevoked whether to include revoked tokens in the result
     * @param limit          maximum number of rows to return
     */
    List<TokenSummary> listTokens(boolean includeRevoked, int limit);

    /**
     * Revoke a token by name. Atomic — sets revoked_at to now() only if the token
     * exists and is not already revoked.
     *
     * @throws IllegalStateException if the token does not exist or is already revoked
     */
    void revokeToken(String name);

    /**
     * Look up a token's metadata by name. Returns empty if no token by that name exists.
     */
    Optional<TokenSummary> getTokenInfo(String name);
}
