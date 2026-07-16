package com.hivemem.auth.support;

import com.hivemem.auth.AuthPrincipal;
import com.hivemem.auth.AuthRole;
import com.hivemem.auth.TokenService;
import com.hivemem.auth.TokenSummary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Test-only {@link TokenService} that only implements {@link #validateToken(String)}.
 * CRUD operations throw {@link UnsupportedOperationException} — tests that need a
 * working CRUD surface should autowire the real {@code DbTokenService} instead.
 */
public class FixedTokenService implements TokenService {

    private final Function<String, Optional<AuthPrincipal>> validator;

    public FixedTokenService(Function<String, Optional<AuthPrincipal>> validator) {
        this.validator = validator;
    }

    @Override
    public Optional<AuthPrincipal> validateToken(String token) {
        return validator.apply(token);
    }

    @Override
    public Optional<AuthPrincipal> findById(UUID tokenId) {
        throw new UnsupportedOperationException("FixedTokenService is validate-only");
    }

    @Override
    public String createToken(String name, AuthRole role, Integer expiresInDays,
                              List<String> readRealms, List<String> writeRealms) {
        throw new UnsupportedOperationException("FixedTokenService is validate-only");
    }

    @Override
    public List<TokenSummary> listTokens(boolean includeRevoked, int limit) {
        throw new UnsupportedOperationException("FixedTokenService is validate-only");
    }

    @Override
    public void revokeToken(String name) {
        throw new UnsupportedOperationException("FixedTokenService is validate-only");
    }

    @Override
    public Optional<TokenSummary> getTokenInfo(String name) {
        throw new UnsupportedOperationException("FixedTokenService is validate-only");
    }
}
