-- V0030: Correlate separation callbacks by Vistierie run id.
-- Vistierie's completion webhook delivers {run_id, status, output, ...} with no top-level
-- correlation id, so HiveMem stores the run id returned at dispatch and matches the callback
-- on it. This is deterministic and independent of whatever the LLM echoes back.

ALTER TABLE consumption_jobs ADD COLUMN vistierie_run_id TEXT;

CREATE INDEX idx_consumption_jobs_run_id ON consumption_jobs (vistierie_run_id);
