# Document & Scan Pipeline — End to End

This page is the **big picture**: how a file becomes searchable knowledge in
HiveMem, from the moment it arrives to the moment its text, summary, and typed
facts are embedded and findable. The individual stages each have their own
deep-dive page; this one stitches them together and links out.

> **TL;DR** — A file enters from one of two doors (a watched folder or the REST
> upload API), flows through one shared ingest core (hash → parse → dedup →
> store → create cell), and is then routed to **exactly one** asynchronous
> enrichment path based on its content (OCR · Vision · Kroki · Summarizer).
> Multi-page scans are additionally split into individual documents before
> ingest. Everything downstream is async, idempotent, and self-healing.

## The two entry points

| Door | Trigger | Auth | Initial cell status | `source` prefix | Deep dive |
|---|---|---|---|---|---|
| **Consumption folder** | `ConsumptionWatcher` polls a watched directory and ingests stable files | none (filesystem) | `committed` | `consumption:` | [consumption.md](consumption.md) |
| **REST upload** | `POST /api/attachments` (multipart) | WRITER role | `pending` | `attachment:` | [tools.md](tools.md#attachments) |

Both doors converge on the same method — `AttachmentService.ingest(...)` — so
everything from step 1 onward is identical regardless of how the file arrived.

## The shared ingest core

`AttachmentService.ingest` runs as a single transaction:

1. **Stream + hash.** The bytes are streamed to a temp file while a SHA-256 is
   computed in one pass.
2. **Resolve MIME type** from the declared type and the filename
   (`MimeTypeResolver`).
3. **Parse.** A MIME-matched parser extracts text + an optional thumbnail. A
   parse failure is non-fatal — ingest continues with an empty result.
   | MIME | Parser | Produces |
   |---|---|---|
   | `application/pdf` | `PdfAttachmentParser` | extracted text · first-page thumbnail · **`scanLikely`** flag |
   | `image/*` | `ImageAttachmentParser` | scaled JPEG thumbnail (no text) |
   | `text/*` | `TextAttachmentParser` | UTF-8 text as-is |
   | `message/rfc822` | `EmlAttachmentParser` | subject · from · text/plain body |
   | `text/x-mermaid`, `text/x-plantuml`, `text/vnd.graphviz`, `text/x-d2` | `KrokiAttachmentParser` | diagram source text |
4. **Deduplicate by hash.** If the same hash already exists, the existing
   attachment row is **reactivated** (idempotent — clears any soft-delete, fills
   in a missing thumbnail) and the original upload is skipped. Otherwise the
   original + thumbnail are uploaded to **SeaweedFS** and a new `attachments`
   row is inserted.
5. **Create the extraction cell.** Cell content is the extracted text, or the
   filename as a fallback. The content is embedded (`EmbeddingClient`) and a
   `cells` row is created in the target realm with the entry-point status/source
   above.
6. **Route for enrichment** (see next section).
7. **Link** the cell to the attachment (`cell_attachments`, `extraction_source = true`)
   and, if a `cell_id` was supplied on upload, open a `related_to` tunnel to it.

## Enrichment routing — exactly one path

After the cell is created, `AttachmentService.ingest` picks **one** enrichment
path by testing these conditions **in order** (first match wins) and publishing
a Spring event that an async listener consumes. The cell is tagged with a
`*_pending` marker so the work is recoverable if the process restarts.

| # | Condition | Tag | Event → listener | What it does | Deep dive |
|---|---|---|---|---|---|
| 1 | `scanLikely` is true | `ocr_pending` | `OcrRequestedEvent` → `OcrService` | Rasterize pages → Tesseract OCR (→ optional Vision fallback) → revise cell with extracted text | [ocr.md](ocr.md) |
| 2 | MIME is `image/*` | `vision_pending` | `VisionDescriptionRequestedEvent` → `AttachmentEnrichmentService` | LLM describes the image, classifies its sub-type, revises the cell | [kroki-vision.md](kroki-vision.md) |
| 3 | MIME is a Kroki diagram type | `kroki_pending` | `ThumbnailRequestedEvent` → `AttachmentEnrichmentService` | Render the diagram to a PNG thumbnail | [kroki-vision.md](kroki-vision.md) |
| 4 | Content is long enough to need a summary | `needs_summary` | `CellNeedsSummaryEvent` → summarizer | Summarize + extract typed facts | [summarizer.md](summarizer.md) · [extraction.md](extraction.md) |
| — | none of the above | _(none)_ | — | Cell is already complete | — |

**`scanLikely`** is decided by `ScanDetector`: a PDF is treated as a scan when
its average extracted characters per page falls below
`hivemem.ocr.scan-detection-threshold` (default **50**). Born-digital PDFs with
real text skip OCR and go straight to summarization if they are long.

After OCR or Vision revises the cell, the revised (and now text-rich) content is
itself eligible for summarization, so a scanned invoice ends up with text,
summary, key points, and typed facts — all embedded for
[6-signal search](tools.md#search-signals).

## Multi-page scans: split before ingest

The consumption folder adds one stage **before** the shared core: a multi-page
PDF is split into individual documents — **by content**, no separator or barcode
sheets — and each document is ingested separately. Two strategies exist:

- **Contiguous separation (default):** an LLM agent reads per-page OCR digests
  and returns cut points; high-confidence parts land `committed`, low-confidence
  parts `pending`.
- **Reassembly mode (opt-in):** a vision model regroups non-contiguous /
  shuffled pages into documents even when their pages are interleaved.

Both, plus the stale-job reconcile sweep and the full `consumption_jobs`
lifecycle, are documented in **[consumption.md](consumption.md)**.

## Async, idempotent, self-healing

- **Tag state machine.** Each path uses `*_pending` while in flight and `*_failed`
  on a terminal failure; success removes the pending tag. The tags are the
  source of truth for what still needs work.
- **Backfill loops.** OCR, Vision, and Kroki each run a scheduled backfill (hourly
  by default) that re-scans for `*_pending` cells the event listener missed — so
  enrichment is eventually consistent even across restarts or outages.
- **Reconcile sweep.** Stale consumption separation jobs degrade to a single
  `pending` document rather than getting stuck (see consumption.md).
- **Budget caps.** Vision/LLM calls are bounded by a daily USD budget so a large
  import can't run up an unbounded bill.

## Where it all lands (persistence)

| Table | Holds |
|---|---|
| `attachments` | one row per unique file (hash, MIME, size, SeaweedFS keys, soft-delete) |
| `cell_attachments` | cell ↔ attachment links (`extraction_source` marks the auto-created cell) |
| `cells` | the searchable knowledge cell (content, embedding, realm, status, tags) |
| `consumption_jobs` | multi-page separation batch lifecycle (`awaiting` → `done`/`failed`) |

Binary originals and thumbnails live in **SeaweedFS** (S3-compatible); the
database stores only metadata and the object keys.

## Related pages

- [Consumption Folder](consumption.md) — folder ingest, multi-page split, reassembly, reconcile
- [OCR](ocr.md) — Tesseract pipeline, Vision fallback, backfill
- [Kroki + Vision](kroki-vision.md) — image description, diagram thumbnails, budget caps
- [Document-Type Extraction](extraction.md) — typed facts from invoices/contracts/etc.
- [Auto-Summarizer](summarizer.md) — long cells become embedded summaries
- [Tools](tools.md#attachments) — the REST attachment API and the search signals
