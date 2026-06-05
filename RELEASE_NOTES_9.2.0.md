# HiveMem 9.2.0

Content-based **page reassembly** for scanned batches — regroup shuffled, non-contiguous
pages of a single batch PDF into their constituent documents, fully automatically, with no
separator sheets or markers. Off by default; today's contiguous separation stays the default.

## Changes

- **Reassembly pipeline (`com.hivemem.consumption`).** When `reassembly-enabled=true`, a
  multi-page batch PDF is regrouped by content instead of split contiguously:
  - **`ReassemblyOrchestrator`** rasterizes every page (`reassembly-render-dpi`, default 150),
    splits the scan order into blocks of `block-size` (default 15, leaving headroom under
    Bedrock's hard 20-image/request limit), and runs **sequential carry-over grouping**: each
    block is one `/llm/vision-multi` call carrying the block's page images plus the text
    descriptors of the documents discovered so far, so a page in block N can attach to a
    document opened in block 1 — globally coherent, no separate error-prone merge step
    (~⌈100/15⌉ ≈ 7 vision calls per 100-page batch).
  - **`VisionMultiClient`** calls Vistierie `POST /llm/vision-multi` (`@ConditionalOnProperty`
    on `hivemem.queen.enabled`).
  - **`PageGrouper`** builds the prompt, parses the model's JSON assignments (tolerant of
    markdown fences), and updates the running `List<DocGroup>`.
  - **`PageReassembler`** (deterministic, no LLM) finalizes groups: a page missing from the
    response becomes its own `pending` 1-page document, an unknown/hallucinated `docId` becomes
    a new document, pages are ordered by global page number, and a group is `committed` when its
    minimum confidence ≥ `reassembly-confidence-threshold` (default 0.5, aggressive), else
    `pending`.
  - **`BatchSplitter.assemble(byte[], List<List<Integer>>)`** builds one sub-PDF per group from
    arbitrary, ordered 1-based page indices (PDFBox `importPage`); the existing contiguous
    `split` is unchanged.
- **Degrade-safe.** Any vision/parse/assemble error degrades to a single `pending` document via
  the existing reconcile path — but only if nothing was ingested yet, so a mid-batch failure can
  never duplicate documents. Batches exceeding `max-pages` are rejected to `failed/` rather than
  silently truncated.

## Config (`hivemem.consumption.*`, all default-off / conservative)

- `reassembly-enabled` (default `false`) — master switch; contiguous separation remains default.
- `block-size` (default `15`)
- `reassembly-confidence-threshold` (default `0.5`)
- `reassembly-render-dpi` (default `150`)
- `reassembly-purpose` (default `separator`)
- `reassembly-max-tokens` (default `4096`)

## Requires

- Vistierie ≥ 1.3.0 (`POST /llm/vision-multi`), reachable via `hivemem.queen.*`.

## Upgrade

No schema changes (still at V0030). Drop-in over 9.1.5. Feature stays inert until
`HIVEMEM_CONSUMPTION_REASSEMBLY_ENABLED=true`.
