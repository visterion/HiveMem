-- Curated entity alias registry for subject canonicalization (backlog #1).
-- aliases holds normalized (trim+lower+ws-collapse) forms for lookup; canonical_name keeps exact spelling.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE kg_entity (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    canonical_name text UNIQUE NOT NULL,
    aliases        text[] NOT NULL DEFAULT '{}',
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     text
);

CREATE INDEX idx_kg_entity_aliases ON kg_entity USING gin (aliases);
