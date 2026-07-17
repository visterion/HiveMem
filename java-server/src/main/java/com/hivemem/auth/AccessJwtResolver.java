package com.hivemem.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Validates the Cf-Access-Jwt-Assertion header against the team's JWKS and maps the
 * email claim onto an api_tokens row.
 *
 * <p>The header is worthless without signature verification: anything that reaches the
 * origin directly (the LAN address, bypassing the tunnel) could otherwise set it and
 * become admin. RS256 is pinned so alg:none and HMAC key-confusion cannot apply. The
 * issuer is also pinned (unlike Dracul's CloudflareAccessFilter, which only checks aud) —
 * without it a JWT correctly signed by an unrelated Access team using the same JWKS
 * endpoint pattern could still pass.
 */
public class AccessJwtResolver implements HumanPrincipalResolver {

    private static final Logger log = LoggerFactory.getLogger(AccessJwtResolver.class);
    private static final String HEADER = "Cf-Access-Jwt-Assertion";

    private final ConfigurableJWTProcessor<SecurityContext> processor;
    private final TokenService tokenService;

    public AccessJwtResolver(AccessProperties props, TokenService tokenService) {
        this(jwksFromTeamDomain(props), props.getTeamDomain(), props.getAudience(), tokenService);
    }

    /** Test seam: inject a fixed public key instead of fetching a remote JWKS. */
    static AccessJwtResolver forTesting(String teamDomain, String audience, JWK publicKey,
                                        TokenService tokenService) {
        return new AccessJwtResolver(new ImmutableJWKSet<>(new JWKSet(publicKey)),
                teamDomain, audience, tokenService);
    }

    private AccessJwtResolver(JWKSource<SecurityContext> jwks, String teamDomain, String audience,
                              TokenService tokenService) {
        this.tokenService = tokenService;
        this.processor = new DefaultJWTProcessor<>();
        this.processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwks));
        DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier = new DefaultJWTClaimsVerifier<>(
                audience,
                new JWTClaimsSet.Builder().issuer(teamDomain).build(),
                Set.of("email", "exp"));
        // Tolerate a small clock drift between the Cloudflare edge and this origin host
        // (NTP skew, typically low single-digit seconds) so a token doesn't get rejected
        // in the last second of its life just because our clock runs slightly ahead.
        // Access tokens are long-lived (minutes to hours), so 30s is negligible.
        claimsVerifier.setMaxClockSkew(30);
        this.processor.setJWTClaimsSetVerifier(claimsVerifier);
    }

    private static JWKSource<SecurityContext> jwksFromTeamDomain(AccessProperties props) {
        URI certs = URI.create(props.getTeamDomain() + "/cdn-cgi/access/certs");
        try {
            return JWKSourceBuilder.<SecurityContext>create(certs.toURL())
                    .cache(props.getJwksCacheTtl().toMillis(), 30_000)
                    .refreshAheadCache(true)
                    .build();
        } catch (MalformedURLException e) {
            // A bad team-domain is a fatal misconfiguration — fail startup, don't silently
            // authenticate nobody.
            throw new IllegalStateException(
                    "hivemem.access.team-domain does not form a valid JWKS URL: "
                            + props.getTeamDomain(), e);
        }
    }

    @Override
    public Optional<AuthPrincipal> resolve(HttpServletRequest request) {
        String jwt = request.getHeader(HEADER);
        if (jwt == null || jwt.isBlank()) return Optional.empty();
        try {
            JWTClaimsSet claims = processor.process(jwt, null);
            String email = claims.getStringClaim("email");
            if (email == null || email.isBlank()) return Optional.empty();
            // Belt-and-suspenders: TokenService#findByEmail already matches case-insensitively
            // in the DB; normalizing here too costs nothing and keeps behavior obvious to a
            // reader who only sees this call site.
            return tokenService.findByEmail(email.toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            log.warn("Rejected Access JWT: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
