package com.hivemem.auth;

import com.hivemem.auth.support.FixedTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Realm-scoped tokens are confined to {@code /mcp}: {@code /sync} (op replay) and
 * {@code /hooks} (all-realm search) have no per-realm enforcement, so a scoped token
 * there would be silently unrestricted. Verifies the fail-closed route-guard in
 * {@link AuthFilter#doFilterInternal}.
 */
class AuthFilterRealmScopeTest {

    private static final AuthPrincipal SCOPED_WRITER = new AuthPrincipal(
            "scoped-token", AuthRole.WRITER, java.util.UUID.randomUUID(),
            List.of("work"), List.of("work"));

    private static final AuthPrincipal UNSCOPED_WRITER =
            new AuthPrincipal("unscoped-token", AuthRole.WRITER, java.util.UUID.randomUUID());

    private static AuthFilter filterFor(String bearer, AuthPrincipal principal) {
        TokenService tokenService = new FixedTokenService(token ->
                bearer.equals(token) ? Optional.of(principal) : Optional.empty());
        return new AuthFilter(Optional.of(tokenService), new RateLimiter(), Optional.empty(),
                Optional.empty(), new SecurityProperties());
    }

    @Test
    void scopedTokenRejectedOnSync() throws Exception {
        AuthFilter filter = filterFor("scoped-bearer", SCOPED_WRITER);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/sync/ops");
        req.addHeader("Authorization", "Bearer scoped-bearer");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
        assertThat(req.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE)).isNull();
    }

    @Test
    void scopedTokenAllowedOnMcp() throws Exception {
        AuthFilter filter = filterFor("scoped-bearer", SCOPED_WRITER);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Bearer scoped-bearer");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);

        assertThat(res.getStatus()).isNotEqualTo(403);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(req.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE)).isEqualTo(SCOPED_WRITER);
    }

    @Test
    void unscopedTokenAllowedOnSync_backwardCompat() throws Exception {
        AuthFilter filter = filterFor("unscoped-bearer", UNSCOPED_WRITER);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/sync/ops");
        req.addHeader("Authorization", "Bearer unscoped-bearer");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);

        assertThat(res.getStatus()).isNotEqualTo(403);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(req.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE)).isEqualTo(UNSCOPED_WRITER);
    }
}
