# HiveMem 9.1.4

Patch release. Makes the embedding service handle concurrent requests.

## Fix

- **Embedding service is now multi-threaded (`ThreadingHTTPServer`).** The previous
  single-threaded `HTTPServer` served one request at a time, so a burst — several
  sub-document embeds during one consumption separation `apply()`, plus the OCR backfill —
  queued past the Java client's read timeout. That produced `BrokenPipe` on the server and a
  truncated/`octet-stream` response on the client, which failed the ingest and marked the
  separation job `failed`. onnxruntime `Run()` is thread-safe for concurrent inference on a
  shared session, so serving requests concurrently is safe.

## Operational notes (config, no code)

- Raise the embedding client read timeout for busy deployments:
  `HIVEMEM_EMBEDDING_TIMEOUT=PT30S` (default `PT5S`).
- Consumption OCR DPI can be lowered for speed: `HIVEMEM_OCR_DPI=200` (default `300`).
- Keep the OCR backfill at its default interval (`HIVEMEM_OCR_BACKFILL_INTERVAL_MS=3600000`);
  a very short interval races the post-ingest async OCR and causes "cell already revised".

## Upgrade

No schema changes (still at V0030). Rebuild/redeploy the `hivemem-embeddings` image.
