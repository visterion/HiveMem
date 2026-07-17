package com.hivemem.oauth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.util.Date;
import java.util.UUID;

/**
 * Shared RSA keypair + JWT-signing helpers for tests that need a Cloudflare Access JWT
 * accepted by an {@code AccessJwtResolver} built via its {@code forTesting} seam — mirrors
 * {@code AccessJwtResolverTest}'s fixture so both point at the same key material.
 */
final class AccessJwtTestFixtures {

    static final String TEAM_DOMAIN = "https://example.cloudflareaccess.com";
    static final String AUDIENCE = "test-aud";

    private static final RSAKey RSA_KEY;
    private static final String REGISTERED_CLIENT_ID = "test-client-" + UUID.randomUUID();

    static {
        try {
            RSA_KEY = new RSAKeyGenerator(2048).keyID("k1").generate();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private AccessJwtTestFixtures() {
    }

    static RSAKey rsaKey() {
        return RSA_KEY;
    }

    /** A stable client_id string tests can register in oauth_clients before use. */
    static String registeredClientId() {
        return REGISTERED_CLIENT_ID;
    }

    /** A valid, signed Access JWT for the given email, ready for the Cf-Access-Jwt-Assertion header. */
    static String signedFor(String email) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(TEAM_DOMAIN)
                .audience(AUDIENCE)
                .claim("email", email)
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(RSA_KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(RSA_KEY));
        return jwt.serialize();
    }
}
