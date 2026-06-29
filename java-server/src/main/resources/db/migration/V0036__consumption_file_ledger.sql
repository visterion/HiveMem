-- V0036: Per-source-file ledger for the consumption pipeline.
-- Tracks each scanned file through its lifecycle so the recovery sweep can re-stage
-- crash-stranded (processing) files and bounded-retry failed files. Keyed on content hash.

CREATE TABLE consumption_file (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sha256      TEXT NOT NULL UNIQUE,
    filename    TEXT NOT NULL,
    state       TEXT NOT NULL DEFAULT 'processing',  -- processing | done | failed
    attempts    INTEGER NOT NULL DEFAULT 1,
    last_error  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_consumption_file_state ON consumption_file (state, updated_at);
