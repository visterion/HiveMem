package com.hivemem.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * Resolves the human behind a request. Exactly one implementation is active per
 * deployment mode: AccessJwtResolver (Cloudflare Access) or SessionResolver (legacy
 * session login). Both return the same principal, read from api_tokens.
 */
public interface HumanPrincipalResolver {

    /**
     * @return the principal, or empty when the request carries no valid human identity.
     *         Empty does not distinguish "no credential" from "credential valid but no
     *         api_tokens row" — see AccessJwtResolver#resolve for that distinction.
     */
    Optional<AuthPrincipal> resolve(HttpServletRequest request);
}
