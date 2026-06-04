# Consumption Folder — Automatic Scan Ingest

The consumption pipeline lets you drop a stack of scanned documents into a
watched folder and have HiveMem ingest them automatically — including splitting
a mixed batch into individual documents based on content, without any barcode
or separator sheets.

## Purpose

| | HiveMem consumption pipeline | Paperless-ngx |
|---|---|---|
| Document boundary detection | **Content-based** (LLM reads OCR text, detects letterhead/signature/page-counter changes) | Barcode / patch-code / ASN separator sheets only |
| Separator sheet required | No | Yes (unless using ASN barcodes) |
| LLM requirement | Vistierie `document-separator` agent (Sonnet via `model_purpose = "separator"`) | None |
| Low-confidence splits | Land as `pending` → approval queue | Not applicable |
| Works without Vistierie | Yes — multi-page PDFs are ingested as one `pending` document (graceful degradation) | Yes |

## Hardware / ingress setup

The typical setup uses a **Brother ADS-2400N** (or any network scanner that
supports Scan-to-Network-Folder / SMB):

1. On the HiveMem LXC, install Samba and export the consumption directory:

   ```ini
   # /etc/samba/smb.conf — minimal share stanza
   [scans]
       path = /data/consumption
       valid users = scanner
       read only = no
       create mask = 0644
   ```

2. Bind-mount the share directory into the container in `docker-compose.yml`:

   ```yaml
   services:
     hivemem:
       volumes:
         - /data/consumption:/data/consumption
   ```

   The default container path (`/data/consumption`) matches
   `hivemem.consumption.dir`. Change the right side of the bind-mount if you
   configured a different path.

3. Configure the scanner's Scan-to-Network-Folder destination to the Samba
   share (IP of the LXC, share name `scans`, credentials matching `valid users`
   above).

4. Enable the pipeline in your environment:

   ```
   HIVEMEM_CONSUMPTION_ENABLED=true
   ```

## How it works

### Polling and stability detection

`ConsumptionWatcher` polls the consumption directory every `poll-interval`
(default 10 s). A file is eligible for ingest only after it has been **stable**
for `stable-seconds` (default 5 s) — meaning its size and mtime were identical
across two consecutive polls and the mtime is at least `stable-seconds` old.
Dotfiles (`.*`) and the `processed/` / `failed/` / `processing/` subdirectories
are ignored (the watcher's directory scan is non-recursive and skips
non-regular files).

### Single-document path (M1)

Any file that is **not a multi-page PDF**, or any PDF whose page count is ≤ 1,
or any file when Vistierie is unavailable (Queen disabled), is ingested
directly:

- MIME type is guessed from the filename.
- `AttachmentService.ingest` creates a `committed` cell in the configured
  `realm` with `source = "consumption"`.
- The file moves to `processed/`.

### Multi-page PDF batch-separation path (M2)

If `hivemem.queen.enabled=true` AND the file is a multi-page PDF:

0. **Stage out of the watch path.** The source is moved into `processing/`
   **before** any work begins. Because the watcher's scan is non-recursive,
   the file is never re-scanned (and thus never re-dispatched) while in flight.
   The `consumption_jobs.source_path` records this staged path.
   If the real page count exceeds `max-pages`, the batch is **rejected**: it is
   logged and moved to `failed/` (no job, no dispatch) instead of being silently
   truncated and mis-split. Re-scan in smaller batches or raise `max-pages`.
1. **Rasterize + OCR.** Each page is rasterized at the configured DPI and run
   through Tesseract.
2. **Build page digests.** Each page is distilled into a `PageDigest`:
   `page` (1-based), `head` (first ~300 OCR chars), `tail` (last ~100 chars),
   `blank` (bool), `hasPageMarker` (`Seite X von Y` / `Page X of Y` found).
3. **Store batch.** The original PDF is uploaded to SeaweedFS under
   `consumption/batch-<correlationId>.pdf`. A row is inserted into
   `consumption_jobs` (status `awaiting`).
4. **Dispatch to Vistierie.** `VistierieSeparationClient` POSTs to
   `/agents/document-separator/run` with a body of
   `{payload, completion_webhook, completion_webhook_token}` — the page digests
   and the correlation id ride inside `payload`, and `completion_webhook` points
   back to `POST /vistierie/separation/done` on HiveMem. Vistierie returns a
   `run_id`, which HiveMem stores on the job (`vistierie_run_id`); the callback
   carries no correlation id, so the run id is what links it back.
5. **Webhook result.** When Vistierie finishes, it calls
   `POST /vistierie/separation/done` (authenticated with
   `hivemem.queen.separation-webhook-token`) with the envelope
   `{run_id, status, output, error, …}`, where the separator agent's
   `output_schema` shapes `output` as `{boundaries:[{afterPage,confidence}]}`.
   HiveMem looks up the awaiting job by `run_id` and:
   - Retrieves the batch PDF from SeaweedFS.
   - Applies the boundaries from `output` to split the PDF
     (`BatchSplitter` using PDFBox). An empty boundary list is a valid result:
     the whole stream becomes one document. A non-`done` status or missing
     `output` leaves the job awaiting for the reconcile sweep to degrade.
   - Ingests each part: the first part is always `committed`; subsequent
     parts are `committed` if the boundary confidence ≥ `confidence-threshold`
     (default 0.80), otherwise `pending` (lands in the approval queue).
   - Marks the job `done` (before the move), then moves the staged source from
     `processing/` to `processed/`. A move failure is logged only and does not
     re-fail the job — the sub-documents are already ingested.

If the dispatch call to Vistierie itself fails (Vistierie unreachable), the job
is left `awaiting` and the source stays in `processing/` with its batch already
in SeaweedFS; the reconcile sweep degrades it later. The file is **not** moved
to `failed/` in that case.

### File disposition

| Outcome | Destination |
|---|---|
| Single-doc ingest succeeded | `<dir>/processed/` |
| Multi-page PDF in flight (awaiting separation) | `<dir>/processing/` |
| Separation applied / degraded | `<dir>/processed/` |
| Read / single-doc-ingest / separation-prep error | `<dir>/failed/` |
| Page count > `max-pages` | `<dir>/failed/` |

Collision-safe: if a file with the same name already exists in the target
subdirectory, a monotonic counter suffix is appended (`scan-1.pdf`,
`scan-2.pdf`, …).

## Requirements for auto-split

Auto-split requires both pipelines to be active:

- `hivemem.consumption.enabled=true`
- `hivemem.queen.enabled=true` (with Vistierie base URL, `HIVEMEM_VISTIERIE_TOKEN`,
  `HIVEMEM_QUEEN_HIVEMEM_BASE_URL`, and `HIVEMEM_QUEEN_SEPARATION_WEBHOOK_TOKEN` set)

If the Queen is disabled, multi-page PDFs are ingested as a single document on
the direct path (no split attempted).

## Graceful degradation

If the Vistierie separation webhook never arrives (Vistierie down, agent
misconfigured, etc.), `SeparationReconcileSweep` runs every
`reconcile-interval-ms` (default 5 min) and picks up any `awaiting` job older
than 10 minutes. It ingests the whole batch PDF as a single `pending` document,
marks the job `done`, then moves the staged source from `processing/` to
`processed/` (a move failure is logged only). **Nothing is lost.**

Re-dispatch is not attempted because per-page digests are not persisted between
the initial dispatch and the sweep — the sweep degrades rather than retries.

## Configuration reference

### `hivemem.consumption.*`

| Property | Env var | Default | Description |
|---|---|---|---|
| `enabled` | `HIVEMEM_CONSUMPTION_ENABLED` | `false` | Master switch. Set to `true` to activate the watcher. |
| `dir` | `HIVEMEM_CONSUMPTION_DIR` | `/data/consumption` | Absolute path to the watched folder. |
| `realm` | `HIVEMEM_CONSUMPTION_REALM` | `documents` | HiveMem realm cells are created in. |
| `poll-interval` | `HIVEMEM_CONSUMPTION_POLL_INTERVAL` | `PT10S` | How often the watcher scans the directory (ISO 8601 duration). |
| `stable-seconds` | `HIVEMEM_CONSUMPTION_STABLE_SECONDS` | `5` | Seconds a file must be size+mtime-unchanged before ingest begins. |
| `max-pages` | `HIVEMEM_CONSUMPTION_MAX_PAGES` | `200` | Maximum pages rasterized + OCR'd per batch PDF. Pages beyond this limit are not included in the digest. |
| `confidence-threshold` | `HIVEMEM_CONSUMPTION_CONFIDENCE` | `0.80` | Minimum confidence for a split boundary to produce a `committed` cell. Below this value the part is `pending`. |
| `max-dispatch-retries` | `HIVEMEM_CONSUMPTION_MAX_RETRIES` | `3` | Reserved; re-dispatch is not implemented (see degradation note). |
| `reconcile-interval-ms` | `HIVEMEM_CONSUMPTION_RECONCILE_MS` | `300000` | Interval in ms for the stale-job reconcile sweep (default 5 min). |
| `worker-threads` | `HIVEMEM_CONSUMPTION_WORKER_THREADS` | `2` | Size of the bounded worker pool that runs ingest+OCR. The `@Scheduled` poll thread only detects a stable file, stages it to `processing/`, and submits it to this pool — so multi-page OCR never blocks the poll or other scans. Backpressure (`CallerRunsPolicy`) applies under a burst; nothing is dropped. |

### New `hivemem.queen.*` keys added by this feature

| Property | Env var | Default | Description |
|---|---|---|---|
| `separation-webhook-token` | `HIVEMEM_QUEEN_SEPARATION_WEBHOOK_TOKEN` | `""` | Bearer token HiveMem expects Vistierie to present on `POST /vistierie/separation/done`. Must be set when consumption + queen are both enabled. |
| `document-separator-agent` | `HIVEMEM_QUEEN_SEPARATOR_AGENT` | `document-separator` | Vistierie agent name to dispatch separation jobs to. |

## Known limitations and assumptions

1. **Vistierie run-creation contract (reconciled).** `VistierieSeparationClient`
   calls `POST /agents/{name}/run` (singular) with
   `{payload, completion_webhook, completion_webhook_token}`, matching
   Vistierie's `RunController#trigger`. The correlation id and page digests ride
   inside `payload`; the callback is correlated by the returned `run_id`
   (stored as `consumption_jobs.vistierie_run_id`), since Vistierie's completion
   webhook carries no correlation id of its own.

2. **`model_purpose = "separator"` requires a Vistierie routing rule.** The
   `document-separator` agent definition sets `model_purpose = "separator"`
   rather than a hard-coded model ID. Vistierie's `RoutingResolver` needs a
   routing rule mapping `purpose=separator` → a provider+model (intended:
   Bedrock Claude Sonnet), or a wildcard rule; otherwise runs fail with
   "no routing rule". This is Vistierie-side configuration.

3. **No barcode / separator-sheet support.** Boundary detection is purely
   content-based. If your scanner produces separator sheets, they will be
   treated as (likely blank) pages and may or may not trigger a boundary.

4. **No split/merge correction UI.** Low-confidence splits land in the approval
   queue as `pending` cells. Review and approval use the standard
   `approve_pending` workflow. A dedicated correction UI (merge/re-split) is not
   yet implemented.

5. **Page-digest truncation.** Batches larger than `max-pages` are rasterized
   only up to that limit. Boundaries beyond the limit are never detected; those
   pages are folded into the last split part.
