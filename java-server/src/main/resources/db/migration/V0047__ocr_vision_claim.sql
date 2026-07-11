-- M13: OCR/vision reliability bookkeeping, mirroring V0045's summarize_claimed_at pattern.
--
-- ocr_claimed_at / vision_claimed_at — atomic claim markers so the @Async AFTER_COMMIT event
-- worker and the scheduled hourly backfill cannot process the same cell concurrently (each
-- paying an OCR/Vision-LLM call and producing a duplicate revision). Stale claims (> 30 minutes,
-- e.g. a crashed worker) are reclaimable.

ALTER TABLE cells ADD COLUMN ocr_claimed_at TIMESTAMPTZ;
ALTER TABLE cells ADD COLUMN vision_claimed_at TIMESTAMPTZ;
