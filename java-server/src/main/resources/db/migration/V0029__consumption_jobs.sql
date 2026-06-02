-- V0029: Consumption batch-separation jobs.
-- Tracks a multi-page batch awaiting document-boundary detection from Vistierie.

CREATE TABLE consumption_jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    correlation_id  UUID NOT NULL UNIQUE,
    s3_key          TEXT NOT NULL,
    original_name   TEXT NOT NULL,
    source_path     TEXT NOT NULL,
    page_count      INTEGER NOT NULL,
    realm           TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'awaiting',  -- awaiting | done | failed
    dispatch_count  INTEGER NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_consumption_jobs_status ON consumption_jobs (status, updated_at);
