-- Summarizer reliability bookkeeping:
--
--  * summarize_throttled_until — 429 backoff deadline. The backfill finder skips a cell until
--    this timestamp passes. Previously the backoff was keyed on created_at, so any cell older
--    than 15 minutes was retried immediately and the throttle tag was never honored.
--
--  * summarize_claimed_at — atomic claim marker so the AFTER_COMMIT event worker and the
--    scheduled backfill cannot summarize the same cell concurrently (each paying an LLM call).
--    Stale claims (> 10 minutes, e.g. a crashed worker) are reclaimable.

ALTER TABLE cells ADD COLUMN summarize_throttled_until TIMESTAMPTZ;
ALTER TABLE cells ADD COLUMN summarize_claimed_at TIMESTAMPTZ;
