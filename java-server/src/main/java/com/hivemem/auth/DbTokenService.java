package com.hivemem.auth;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class DbTokenService implements TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final DSLContext dslContext;

    public DbTokenService(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    @Override
    public Optional<AuthPrincipal> validateToken(String token) {
        String tokenHash = sha256(token);
        Record row = dslContext.fetchOne("""
                SELECT id, name, role, read_realms, write_realms
                FROM api_tokens
                WHERE token_hash = ?
                  AND revoked_at IS NULL
                  AND (expires_at IS NULL OR expires_at > now())
                """, tokenHash);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(principalOf(row));
    }

    @Override
    public Optional<AuthPrincipal> findById(UUID tokenId) {
        Record row = dslContext.fetchOne("""
                SELECT id, name, role, read_realms, write_realms
                FROM api_tokens
                WHERE id = ?
                  AND revoked_at IS NULL
                  AND (expires_at IS NULL OR expires_at > now())
                """, tokenId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(principalOf(row));
    }

    @Override
    public Optional<AuthPrincipal> findByEmail(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        Record row = dslContext.fetchOne("""
                SELECT id, name, role, read_realms, write_realms
                FROM api_tokens
                WHERE lower(email) = lower(?)
                  AND revoked_at IS NULL
                  AND (expires_at IS NULL OR expires_at > now())
                """, email);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(principalOf(row));
    }

    private static List<String> toRealmList(String[] arr) {
        return arr == null ? null : List.of(arr);
    }

    private static AuthPrincipal principalOf(Record row) {
        return new AuthPrincipal(
                row.get("name", String.class),
                AuthRole.valueOf(row.get("role", String.class).toUpperCase(Locale.ROOT)),
                row.get("id", UUID.class),
                toRealmList(row.get("read_realms", String[].class)),
                toRealmList(row.get("write_realms", String[].class)));
    }

    @Override
    public String createToken(String name, AuthRole role, Integer expiresInDays,
                              List<String> readRealms, List<String> writeRealms) {
        String plaintext = generatePlaintext();
        String tokenHash = sha256(plaintext);
        OffsetDateTime expiresAt = expiresInDays == null
                ? null
                : OffsetDateTime.now().plus(expiresInDays, ChronoUnit.DAYS);

        try {
            dslContext.execute("""
                    INSERT INTO api_tokens (token_hash, name, role, expires_at, read_realms, write_realms)
                    VALUES (?, ?, ?, ?::timestamptz, ?::text[], ?::text[])
                    """,
                    tokenHash,
                    name,
                    role.name().toLowerCase(Locale.ROOT),
                    expiresAt,
                    normalizeRealms(readRealms),
                    normalizeRealms(writeRealms));
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("Token '" + name + "' already exists", e);
        }
        return plaintext;
    }

    /** Realm allowlist mirrored from the {@code hivemem-token} CLI: post-normalization each realm
     *  may contain only lowercase letters, digits, and dashes. */
    private static final java.util.regex.Pattern REALM_PATTERN =
            java.util.regex.Pattern.compile("^[a-z0-9-]+$");

    /**
     * null-safe; lowercases + collapses whitespace to dashes; returns a String[] for the ?::text[]
     * bind, or null. After normalization each element is validated against {@link #REALM_PATTERN}
     * (same allowlist the CLI applies) so API- and CLI-created tokens are consistent and odd/empty
     * realm strings are rejected up front.
     */
    static String[] normalizeRealms(List<String> realms) {
        if (realms == null) return null;
        return realms.stream()
                .map(r -> {
                    String norm = r.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
                    if (!REALM_PATTERN.matcher(norm).matches()) {
                        throw new IllegalArgumentException("invalid realm '" + norm
                                + "' — realms may contain only lowercase letters, digits, and dashes");
                    }
                    return norm;
                })
                .toArray(String[]::new);
    }

    @Override
    public List<TokenSummary> listTokens(boolean includeRevoked, int limit) {
        String sql = includeRevoked
                ? """
                        SELECT name, role, created_at, expires_at, revoked_at
                        FROM api_tokens
                        ORDER BY created_at
                        LIMIT ?
                        """
                : """
                        SELECT name, role, created_at, expires_at, revoked_at
                        FROM api_tokens
                        WHERE revoked_at IS NULL
                        ORDER BY created_at
                        LIMIT ?
                        """;
        return dslContext.fetch(sql, limit)
                .map(DbTokenService::rowToSummary);
    }

    @Override
    public void revokeToken(String name) {
        int updated = dslContext.execute("""
                UPDATE api_tokens
                SET revoked_at = now()
                WHERE name = ?
                  AND revoked_at IS NULL
                """, name);
        if (updated == 0) {
            throw new IllegalStateException("Token '" + name + "' not found or already revoked");
        }
    }

    @Override
    public Optional<TokenSummary> getTokenInfo(String name) {
        Record row = dslContext.fetchOne("""
                SELECT name, role, created_at, expires_at, revoked_at
                FROM api_tokens
                WHERE name = ?
                """, name);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(rowToSummary(row));
    }

    private static TokenSummary rowToSummary(Record row) {
        OffsetDateTime expiresAt = row.get("expires_at", OffsetDateTime.class);
        OffsetDateTime revokedAt = row.get("revoked_at", OffsetDateTime.class);
        return new TokenSummary(
                row.get("name", String.class),
                AuthRole.valueOf(row.get("role", String.class).toUpperCase(Locale.ROOT)),
                row.get("created_at", OffsetDateTime.class),
                expiresAt,
                revokedAt,
                TokenSummary.Status.derive(expiresAt, revokedAt)
        );
    }

    private static String generatePlaintext() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
