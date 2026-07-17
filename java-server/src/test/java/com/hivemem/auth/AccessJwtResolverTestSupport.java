package com.hivemem.auth;

import com.nimbusds.jose.jwk.JWK;

/**
 * Test-only public seam onto {@link AccessJwtResolver#forTesting}, which is package-private
 * to {@code com.hivemem.auth}. Lets tests in other packages (e.g.
 * {@code com.hivemem.oauth.AuthorizationControllerAccessModeTest}) wire an
 * {@code AccessJwtResolver} against a local, fixed JWKS without touching production
 * visibility.
 */
public final class AccessJwtResolverTestSupport {

    private AccessJwtResolverTestSupport() {
    }

    public static AccessJwtResolver forTesting(String teamDomain, String audience, JWK publicKey,
                                               TokenService tokenService) {
        return AccessJwtResolver.forTesting(teamDomain, audience, publicKey, tokenService);
    }
}
