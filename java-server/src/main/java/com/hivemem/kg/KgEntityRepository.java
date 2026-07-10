package com.hivemem.kg;

import java.util.List;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** Read/write access to the kg_entity alias registry backing subject canonicalization. */
@Repository
public class KgEntityRepository {

    private final DSLContext dslContext;

    public KgEntityRepository(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    /**
     * Resolve a subject to its canonical name via the registry. Returns the canonical name if the
     * normalized subject matches any stored (normalized) alias entry (the normalized canonical name
     * is itself stored as an alias); otherwise returns the trimmed original subject (unknown
     * subjects are their own canonical). The single array-contains lookup is served by the GIN
     * index on aliases.
     */
    public String resolve(String subject) {
        if (subject == null) {
            return null;
        }
        String normalized = KgEntityNormalizer.normalize(subject);
        Record row = dslContext.fetchOne("""
                SELECT canonical_name FROM kg_entity
                WHERE aliases @> ARRAY[?]::text[]
                ORDER BY canonical_name
                LIMIT 1
                """, normalized);
        return row == null ? subject.trim() : row.get("canonical_name", String.class);
    }

    /**
     * Register (or extend) a canonical entity. The normalized canonical name is stored as an alias
     * entry alongside the normalized incoming aliases (so a lookup of the canonical name resolves to
     * itself); on conflict the new normalized aliases are unioned into the existing array.
     *
     * <p>Conflicts are arbitrated on the NORMALIZED canonical name (the unique expression index
     * from V0040, matching {@link KgEntityNormalizer#normalize}), so casing/whitespace variants of
     * an existing canonical merge into the existing row (its exact spelling wins) instead of
     * creating duplicate canonicals.
     */
    public void upsert(String canonical, List<String> aliases, String createdBy) {
        List<String> safeAliases = aliases == null ? List.of() : aliases;
        String[] normalized = Stream.concat(Stream.of(canonical), safeAliases.stream())
                .map(KgEntityNormalizer::normalize)
                .distinct()
                .toArray(String[]::new);
        dslContext.execute("""
                INSERT INTO kg_entity (canonical_name, aliases, created_by)
                VALUES (?, ?, ?)
                ON CONFLICT (lower(regexp_replace(btrim(canonical_name), '\\s+', ' ', 'g'))) DO UPDATE
                SET aliases = (
                    SELECT array_agg(DISTINCT x)
                    FROM unnest(kg_entity.aliases || excluded.aliases) AS x
                )
                """, canonical, normalized, createdBy);
    }
}
