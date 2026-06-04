# HiveMem 9.1.5

Hardening of the consumption pipeline for large/multi-document scans.

## Changes

- **Async ingest (off the poll thread).** `ConsumptionWatcher.poll()` now only detects a
  stable file, stages it to `processing/` (which makes it invisible to the non-recursive poll
  → exactly-once), and submits processing to a **bounded worker pool**
  (`hivemem.consumption.worker-threads`, default 2; `CallerRunsPolicy` backpressure). A
  multi-page OCR no longer blocks the `@Scheduled` poll thread or serializes other scans. The
  single- and multi-page paths are unified onto `ConsumptionService.processStaged`.
- **reviseCell keeps the attachment link.** `WriteToolRepository.reviseCell` now copies the
  `cell_attachments` rows from the old cell to the new revision, so the current cell (e.g.
  after OCR revises a scanned document) stays linked to its source PDF instead of the link
  being stranded on the superseded version. Applies to every revision (also the summarizer).

## Config

- `worker-threads` / `HIVEMEM_CONSUMPTION_WORKER_THREADS` (default `2`).

## Upgrade

No schema changes (still at V0030). Drop-in over 9.1.4.
