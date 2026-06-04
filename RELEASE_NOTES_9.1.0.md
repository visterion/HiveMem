# HiveMem 9.1.0

HiveMem grows an **autonomous curator** and an **automatic document intake**.
The Queen + Bees agent loop now runs on the Vistierie agent runtime, a new
`/queen` UI shows run history and the approval queue, and the consumption-folder
pipeline turns a stack of mixed scans into separated, searchable documents —
**with no separator sheets**, the deliberate USP over Paperless-ngx.

## Highlights

- **Queen + Bees on Vistierie** — HiveMem registers agents on the Vistierie runtime and drives them through tool/completion webhooks; HiveMem stays the sole writer, proposals flow through the approval queue
- **Queen-log UI** — a `/queen` page with a live run feed, per-run drill-down drawer, and the pending-approval queue
- **Consumption pipeline (Paperless, beaten)** — drop a stack of mixed sheets in a folder; HiveMem detects document boundaries **by content** and splits the batch into individual documents automatically
- **HiveMem → Vistierie task dispatch** — the first outbound run-creation contract, reconciled against Vistierie's real run API
- **Search confidence levels** — search results expose a threshold-based `confidence_level`
- **Hook source attribution** — injected context now carries cited sources and stable cell-ID prefixes
- **Two new admin MCP tools** — `queen_runs`, `queen_run_detail` (tool count 34 → 36)

---

## Queen + Bees (Vistierie agent runtime)

The Queen and the isolated-cell Bee are registered in and dispatched by the
Vistierie agent runtime (LXC 102), which owns scheduling, subagent dispatch,
cost accounting, and the per-tenant kill switch.

- Agent definitions as code, registered via an idempotent GET→POST/PUT upsert (`VistierieAgentClient`), with a tolerant startup bootstrap
- Inbound `/vistierie/**` webhooks: read-only tool calls (`find_isolated_cells`, `read_cell`, `search_similar_cells`) and completion (`/vistierie/runs/done`), exempt from the normal auth filter and guarded by per-purpose bearer tokens
- `QueenRepository` isolated-cell + dedup queries; `QueenWebhookService` validates relations and ingests Bee proposals as **`pending`** tunnels — HiveMem remains the sole writer
- `VistierieRunsClient` proxies run history/detail/events (admin token with tenant fallback)
- Gated behind `hivemem.queen.enabled=true` (default `false`)

## Queen-log UI

- New `/queen` page in `knowledge-ui/`: run feed, drill-down drawer (metadata + event timeline), and the approval queue
- New admin MCP tools `queen_runs` and `queen_run_detail`; `approve_pending` aligned to the backend ids/decision contract

## Consumption Pipeline — automatic document separation

The flagship feature. A Brother ADS-2400N (or any Scan-to-Network-Folder
scanner) writes to an SMB share bind-mounted into the container; HiveMem
ingests and separates automatically.

- `ConsumptionWatcher` polls the folder and picks up size+mtime-stable files; `processed/`/`failed/` handling, collision-safe moves, dotfile/subdir skipping
- Single files and non-multi-page PDFs are ingested directly as **`committed`** cells
- Multi-page PDFs are rasterized, OCR'd per page, reduced to compact `PageDigest`s (head/tail text + `blank` / `Seite X von Y` pre-signals), and dispatched to the Vistierie `document-separator` agent (Sonnet)
- On callback, `BatchSplitter` (PDFBox page-ranges) splits the PDF; boundaries with confidence ≥ `confidence-threshold` (default 0.80) become **`committed`**, the rest **`pending`** for review
- **Nothing is lost:** dispatch failures stay `awaiting`; a `SeparationReconcileSweep` degrades stale jobs into a single `pending` document after 10 minutes
- In-flight batches are staged to a `processing/` subdir before dispatch to guarantee exactly-once dispatch (regression-tested)
- Gated behind `hivemem.consumption.enabled=true` (default `false`); Queen must also be enabled for auto-split
- Operator guide: `documentation/consumption.md`

## HiveMem → Vistierie task dispatch (contract)

The consumption pipeline is the first time HiveMem **initiates** a Vistierie
run. The contract was reconciled against Vistierie's real `RunController`:

- `POST /agents/{name}/run` with `{payload, completion_webhook, completion_webhook_token}` — page digests + a correlation id ride inside `payload`
- The callback `{run_id, status, output, error}` carries no correlation id, so HiveMem stores the returned `run_id` and correlates on it
- The separator agent definition ships `tools: []` (Vistierie's `CreateAgentRequest` requires it)

## Search & Hooks

- `ConfidenceLevel` enum (threshold-based) exposed as `confidence_level` in search results
- Hook context now returns `citedSources` / source-attribution lines with 8-char cell-ID prefixes (`ContextResult`, `CellWithCitation`, `ReferenceInfo`)

## Schema Changes

| Migration | Change |
|---|---|
| V0029 | `consumption_jobs` table (batch separation jobs) |
| V0030 | `consumption_jobs.vistierie_run_id` column + index (callback correlation) |

Flyway parity test now expects 28 migrations.

## Configuration

- New namespaces: `hivemem.queen.*` (Vistierie base URL/token, webhook tokens, schedule, separator agent) and `hivemem.consumption.*` (`enabled`, `dir`, `realm`, `poll-interval`, `stable-seconds`, `confidence-threshold`, `max-pages`, `max-dispatch-retries`, `reconcile-interval-ms`)
- Both feature flags default to `false` — existing deployments are unaffected until explicitly enabled

## Operations — enabling auto-separation

Putting the consumption pipeline into operation requires, beyond enabling the flags:

1. A **Vistierie routing rule** mapping `purpose=separator` → a Bedrock model (Sonnet), or a wildcard rule — otherwise separator runs fail with "no routing rule"
2. A **Samba share** on the LXC, bind-mounted to the container's `/data/consumption`
3. A scanner **Scan-to-Network-Folder** profile pointing at that share
4. Queen enabled with a reachable Vistierie base URL/token and the separation webhook token + HiveMem callback base URL

## Breaking Changes

None. Both new subsystems are opt-in and default-off.
