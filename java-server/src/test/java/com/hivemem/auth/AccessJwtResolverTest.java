package com.hivemem.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccessJwtResolverTest {

    private static final String TEAM_DOMAIN = "https://example.cloudflareaccess.com";
    private static final String AUD = "test-aud";
    private static final AuthPrincipal ADMIN =
            new AuthPrincipal("admin", AuthRole.ADMIN, UUID.randomUUID());

    private RSAKey rsaKey;
    private TokenService tokenService;
    private AccessJwtResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID("k1").generate();
        tokenService = mock(TokenService.class);
        when(tokenService.findByEmail("viktor@example.com")).thenReturn(Optional.of(ADMIN));
        // Constructor variant taking a fixed JWKSource — keeps the test off the network.
        resolver = AccessJwtResolver.forTesting(TEAM_DOMAIN, AUD, rsaKey.toPublicJWK(), tokenService);
    }

    private HttpServletRequest requestWith(String jwt) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (jwt != null) request.addHeader("Cf-Access-Jwt-Assertion", jwt);
        return request;
    }

    private String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }

    private JWTClaimsSet.Builder validClaims() {
        return new JWTClaimsSet.Builder()
                .issuer(TEAM_DOMAIN)
                .audience(AUD)
                .claim("email", "viktor@example.com")
                .expirationTime(new Date(System.currentTimeMillis() + 60_000));
    }

    @Test
    void validJwtResolvesPrincipal() throws Exception {
        assertThat(resolver.resolve(requestWith(sign(validClaims().build())))).contains(ADMIN);
    }

    @Test
    void noHeaderResolvesEmpty() {
        assertThat(resolver.resolve(requestWith(null))).isEmpty();
    }

    @Test
    void expiredJwtIsRejected() throws Exception {
        var claims = validClaims()
                .expirationTime(new Date(System.currentTimeMillis() - 1_000)).build();
        assertThat(resolver.resolve(requestWith(sign(claims)))).isEmpty();
    }

    @Test
    void wrongAudienceIsRejected() throws Exception {
        assertThat(resolver.resolve(requestWith(sign(validClaims().audience("other").build())))).isEmpty();
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        assertThat(resolver.resolve(requestWith(sign(validClaims().issuer("https://evil.example").build())))).isEmpty();
    }

    @Test
    void tamperedSignatureIsRejected() throws Exception {
        String jwt = sign(validClaims().build());
        String tampered = jwt.substring(0, jwt.length() - 3) + "aaa";
        assertThat(resolver.resolve(requestWith(tampered))).isEmpty();
    }

    @Test
    void hs256TokenIsRejected() throws Exception {
        // Key-confusion attempt: algorithm is pinned to RS256, so an HMAC token must fail.
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), validClaims().build());
        jwt.sign(new MACSigner("0123456789012345678901234567890123456789"));
        assertThat(resolver.resolve(requestWith(jwt.serialize()))).isEmpty();
    }

    @Test
    void missingEmailClaimIsRejected() throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer(TEAM_DOMAIN).audience(AUD)
                .expirationTime(new Date(System.currentTimeMillis() + 60_000)).build();
        assertThat(resolver.resolve(requestWith(sign(claims)))).isEmpty();
    }

    @Test
    void emailWithoutTokenRowResolvesEmpty() throws Exception {
        when(tokenService.findByEmail("stranger@example.com")).thenReturn(Optional.empty());
        var claims = validClaims().claim("email", "stranger@example.com").build();
        assertThat(resolver.resolve(requestWith(sign(claims)))).isEmpty();
    }

    @Test
    void emailIsMatchedCaseInsensitively() throws Exception {
        var claims = validClaims().claim("email", "Viktor@Example.com").build();
        assertThat(resolver.resolve(requestWith(sign(claims)))).contains(ADMIN);
    }
}
