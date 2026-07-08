package com.hivemem.kg;

import java.util.List;
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
     * normalized subject matches any stored (normalized) alias or the normalized canonical name;
     * otherwise returns the trimmed original subject (unknown subjects are their own canonical).
     */
    public String resolve(String subject) {
        if (subject == null) {
            return null;
        }
        String normalized = KgEntityNormalizer.normalize(subject);
        Record row = dslContext.fetchOne("""
                SELECT canonical_name FROM kg_entity
                WHERE ? = ANY(aliases)
                   OR lower(regexp_replace(btrim(canonical_name), '\\s+', ' ', 'g')) = ?
                LIMIT 1
                """, normalized, normalized);
        return row == null ? subject.trim() : row.get("canonical_name", String.class);
    }

    /**
     * Register (or extend) a canonical entity. Aliases are stored normalized; on conflict the new
     * normalized aliases are unioned into the existing array.
     */
    public void upsert(String canonical, List<String> aliases, String createdBy) {
        String[] normalized = aliases.stream()
                .map(KgEntityNormalizer::normalize)
                .distinct()
                .toArray(String[]::new);
        dslContext.execute("""
                INSERT INTO kg_entity (canonical_name, aliases, created_by)
                VALUES (?, ?, ?)
                ON CONFLICT (canonical_name) DO UPDATE
                SET aliases = (
                    SELECT array_agg(DISTINCT x)
                    FROM unnest(kg_entity.aliases || excluded.aliases) AS x
                )
                """, canonical, normalized, createdBy);
    }
}
