-- canonical_name was only unique on the raw spelling, so casing/whitespace
-- variants created duplicate canonicals and alias resolution silently picked
-- the alphabetically-first row. Merge existing duplicates (oldest row keeps
-- its spelling; aliases are unioned), then enforce uniqueness on the
-- normalized form (must match KgEntityNormalizer: trim, lowercase, collapse
-- internal whitespace).

WITH norm AS (
    SELECT id,
           lower(regexp_replace(btrim(canonical_name), '\s+', ' ', 'g')) AS norm_name,
           row_number() OVER (
               PARTITION BY lower(regexp_replace(btrim(canonical_name), '\s+', ' ', 'g'))
               ORDER BY created_at, id
           ) AS rn
    FROM kg_entity
),
merged AS (
    SELECT n.norm_name,
           (SELECT array_agg(DISTINCT a)
            FROM norm n2
            JOIN kg_entity k2 ON k2.id = n2.id
            CROSS JOIN LATERAL unnest(k2.aliases) AS a
            WHERE n2.norm_name = n.norm_name) AS all_aliases
    FROM norm n
    WHERE n.rn = 1
)
UPDATE kg_entity k
SET aliases = COALESCE(m.all_aliases, k.aliases)
FROM norm n
JOIN merged m ON m.norm_name = n.norm_name
WHERE k.id = n.id AND n.rn = 1;

DELETE FROM kg_entity k
USING (
    SELECT id,
           row_number() OVER (
               PARTITION BY lower(regexp_replace(btrim(canonical_name), '\s+', ' ', 'g'))
               ORDER BY created_at, id
           ) AS rn
    FROM kg_entity
) n
WHERE k.id = n.id AND n.rn > 1;

CREATE UNIQUE INDEX idx_kg_entity_canonical_norm
    ON kg_entity (lower(regexp_replace(btrim(canonical_name), '\s+', ' ', 'g')));
