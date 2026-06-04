# OCR for Scanned PDFs

HiveMem extracts text from scanned PDFs (no text layer) via Tesseract OCR. The
extracted text is written back to the cell, where the auto-summarizer (feature B)
turns it into a curated summary that is embedded for semantic search.

## Why OCR matters

Most PDFs that arrive in HiveMem from a personal-document workflow (Briefkasten-Scans,
Steuerbescheide, Verträge, Rechnungsfotos) have **no text layer** — they're images
inside a PDF wrapper. Without OCR, `PDFTextStripper` returns an empty string, the
cell's content falls back to the filename, and the document becomes invisible to
search. OCR fixes that structurally.

## Pipeline

1. PDF is uploaded; `PdfAttachmentParser` runs `PDFTextStripper`.
2. `ScanDetector` flags the PDF as scan-like when avg chars per page < 50.
3. The cell is created with the filename as content and tagged `ocr_pending`.
4. `OcrRequestedEvent` fires after commit. `OcrService` (async) downloads the PDF,
   rasterizes each page at the configured DPI (`render-dpi`, default 300), runs
   `tesseract -l deu+eng --psm 1` per page, and aggregates the text with `[page=N]`
   markers. `--psm 1` enables OSD (orientation & script detection), so rotated or
   upside-down scans are auto-oriented before recognition (`osd` traineddata ships
   in the image).
5. `WriteToolService.reviseCell` writes the OCR text into the cell — and carries the
   `cell_attachments` link to the new revision, so the OCR'd current cell stays linked
   to its source PDF. The summarizer then takes over (it sees long content + no
   summary, fires Claude Haiku, writes a summary, and the embedding is recomputed
   against the summary).

## Enabling

Both features should be enabled together for the full pipeline:

    HIVEMEM_OCR_ENABLED=true
    HIVEMEM_SUMMARIZE_ENABLED=true
    ANTHROPIC_API_KEY=sk-ant-...

OCR alone (without the summarizer) gives you searchable raw text via the keyword
index but not via semantic embedding for long cells. Both together is the full
experience.

## Configuration reference

| Property | Default | Purpose |
|----------|---------|---------|
| `hivemem.ocr.enabled` | `false` | Master switch |
| `hivemem.ocr.tesseract-path` | `tesseract` | Path to the binary (env-PATH lookup) |
| `hivemem.ocr.languages` | `deu+eng` | Tesseract `-l` argument |
| `hivemem.ocr.scan-detection-threshold` | `50` | Min avg chars/page to be "not a scan" |
| `hivemem.ocr.render-dpi` | `300` | DPI for page rasterization |
| `hivemem.ocr.call-timeout-seconds` | `60` | Per-page tesseract timeout |
| `hivemem.ocr.backfill-interval` | `PT1H` | Documentation only — see note below |
| `hivemem.ocr.backfill-batch-size` | `5` | Cells per backfill run |
| `hivemem.ocr.max-pages` | `50` | Hard cap on pages OCR'd per PDF |
| `hivemem.ocr.vision-fallback-enabled` | `false` | Use Claude Haiku 4.5 to re-OCR pages where Tesseract output is sparse |
| `hivemem.ocr.vision-fallback-min-chars-per-page` | `30` | Threshold below which a page is sent to Vision |
| `hivemem.ocr.vision-fallback-max-pages-per-doc` | `20` | Hard cap on Vision-OCR'd pages per document |

The actual scheduler interval is set via `HIVEMEM_OCR_BACKFILL_INTERVAL_MS`
(milliseconds). Default is `3600000` (1 hour).

## Adding more languages

The container image installs `deu` and `eng` by default. To add more:

    docker exec <container> apt-get install -y tesseract-ocr-fra

Or extend the Dockerfile in your fork. Then set `HIVEMEM_OCR_LANGUAGES=deu+eng+fra`.

## Performance expectations

- Per page: 1-3 s on modest hardware (CPU-bound).
- Backfill of an existing 1000-page archive: hours, not seconds. The hourly
  scheduler with `backfill-batch-size=5` processes ~120 pages/hour at the default
  DPI.

## Monitoring

Cells waiting for OCR:

    SELECT count(*) FROM cells WHERE 'ocr_pending' = ANY(tags);

Cells where OCR failed:

    SELECT count(*) FROM cells WHERE 'ocr_failed' = ANY(tags);

The backfill retries `ocr_failed` cells older than 1 hour automatically.

## Troubleshooting

**OCR produces gibberish:** language pack missing or wrong. Check
`tesseract --list-langs` inside the container; install missing packs.

**OCR returns empty text:** image quality too low (faded scan, dark photo) or
DPI too low. Try `HIVEMEM_OCR_DPI=400`. If still empty, enable the Vision-OCR
fallback (see below).

**`ocr_pending` stuck on many cells:** check application logs for tesseract
errors, or whether the attachment can be downloaded from SeaweedFS.

**PDF is password-protected:** PDFBox throws on load; cell gets `ocr_failed`.
Operator must remove the password externally.

## Vision-OCR fallback (Phase 2)

For pages where Tesseract returns sparse text (tables, whiteboard photos,
handwritten notes, skewed scans), `OcrService` can re-run the rasterized PNG
through Claude Haiku 4.5 via `VisionClient.transcribe()`. The Vision result
replaces the Tesseract output for that page only — strong Tesseract pages stay
on the local engine.

**Enabling:**

    HIVEMEM_OCR_ENABLED=true
    HIVEMEM_OCR_VISION_FALLBACK_ENABLED=true
    ANTHROPIC_API_KEY=sk-ant-...

**Decision logic, per page:**

1. Tesseract runs first (always).
2. If the page text length is below `vision-fallback-min-chars-per-page`
   (default 30) AND the per-document Vision cap (`vision-fallback-max-pages-per-doc`,
   default 20) is not yet reached AND the daily Vision budget
   (`hivemem.attachment.vision-daily-budget-usd`, default $1.00) still has room,
   the page is re-transcribed with Vision.
3. On Vision error (oversize image, 4xx, network), the original Tesseract
   output is kept.

**Cost:** Claude Haiku 4.5 is ~$0.002–0.005 per page depending on image size.
The shared `vision_usage` table (also used by image-description) enforces the
daily cap — once exhausted, fallback is silently skipped until the next day.

**When to enable:** keep it off for archives where Tesseract works well
(typed-text scans). Enable for receipts, tax notices, table-heavy invoices,
and whiteboard photos where Tesseract regularly returns near-empty pages.

## Limits and what's next

Phase 2 covers the Tesseract→Vision page-level fallback for PDFs (this
document). Per-realm provider routing (e.g., `legal` realm forced to local
Ollama, never to anthropic-api) still depends on item I (provider abstraction).
Until then, enabling the Vision fallback routes all eligible pages to the
configured Anthropic API key — do not enable it on instances that hold
data which must stay local.
