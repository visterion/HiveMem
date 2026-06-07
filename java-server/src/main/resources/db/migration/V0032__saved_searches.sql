-- Saved searches for the Scans explorer (SP-C2).
CREATE TABLE saved_searches (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner       TEXT        NOT NULL,
    name        TEXT        NOT NULL,
    filter      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until TIMESTAMPTZ
);

CREATE INDEX idx_saved_searches_owner ON saved_searches(owner) WHERE valid_until IS NULL;
