# Auto-Summarizer

HiveMem's auto-summarizer turns long cells into richer knowledge by calling Claude Haiku
to produce key points, an insight, tags, and extracted knowledge-graph facts. For most
cells it is no longer what makes a cell *findable* — that's the embedding's job — but it
is what makes a cell well-understood. It's still the precondition for findability in the
one case below: content longer than the embedding backend can embed.

## Why summaries still matter

Cells are embedded from their own content, up to the configured embedding backend's
character cap (`EmbeddingClient.maxChars()` — 500 characters on the default ONNX backend,
up to 8000 on the optional GPU/Ollama backend; see [architecture](architecture.md#gpu-embedding-backend-optional)
for the backend options). A summary is the fallback *above* that cap: for content longer
than the backend can embed, HiveMem embeds the summary instead once one exists, so even a
whitepaper or a long scanned document stays findable by meaning rather than being silently
truncated.

Separately from embeddability, a cell without a summary whose content exceeds the fixed
`NeedsSummaryDecider` threshold (`DEFAULT_THRESHOLD_CHARS = 500` characters, hardcoded, not
the `summary-threshold-chars` property below) is tagged `needs_summary` at write time —
regardless of status (`pending` or `committed`) and regardless of whether that content
already has a usable embedding — so the summarizer can enrich it with key points, insight,
tags, and fact extraction. The `summary-threshold-chars` property governs only the one-shot
startup backfill sweep (see [Enabling](#enabling)), not this write-path tagging; changing it
does not change the 500-char threshold applied on every `add_cell`/`revise_cell`/sync-replay
call. The summarizer's own pickup — the scheduled backfill that actually processes tagged
cells — only looks at `committed` cells; a `pending` cell stays tagged until it is
committed.

## What gets written

Each successful run persists, on a new revision of the cell:

- `summary` — a curated 1–2 sentence summary (embedded as a fallback only when the cell's
  content exceeds the embedding backend's character cap)
- `key_points`, `insight`, `tags` — the curated metadata the LLM returns
- `document_type` — the inferred profile type (invoice / contract / other)
- `valid_from` — becomes the document's own date when one is found; otherwise the cell keeps
  its event time (the revision no longer moves it)
- extracted facts — written to the knowledge graph (see [extraction](extraction.md))

If the LLM returns no summary, the cell is **not** revised; the `needs_summary` tag is
cleared so it is not retried in a loop.

## Enabling

Set the env vars and restart (the summarizer calls Claude via the Vistierie gateway,
sharing the same base URL / token as the other Vistierie-backed features):

    HIVEMEM_SUMMARIZE_ENABLED=true
    HIVEMEM_VISTIERIE_BASE_URL=http://vistierie:8090
    HIVEMEM_VISTIERIE_TOKEN=<tenant token>

That's it. On boot, `SummarizeBackfillStartupRunner` tags `needs_summary` on every
committed, live cell (`valid_until IS NULL`) that has **no embedding at all**
(`embedding IS NULL`) and content longer than `summary-threshold-chars` (500 by default) —
this only catches cells that missed embedding entirely, not every long cell. A cell that
already has an embedding (e.g. any content that fit under the active backend's `maxChars()`
at write time — up to 8000 characters on the Ollama backend) is not tagged by this sweep,
even if its content is well over 500 characters. The backfill scheduler picks up tagged
cells over the next minutes. New cells trigger the summarizer within seconds via the
AFTER_COMMIT event.

## Cost model

**All amounts are EUR.** What a call costs is not computed by HiveMem: Vistierie reports
`cost_micros` for the call it actually routed, and HiveMem books that value unchanged
(EUR-micros → EUR) — but only when the reported `cost_micros` is strictly positive.
Otherwise: a call with no response body at all and a call Vistierie routed over the Claude
subscription (`provider=claude-subscription`) book zero silently — that is the true cost.
Every other case, including a *negative* `cost_micros`, falls back to an internal price table
and logs a WARN. The `> 0` guard is deliberate: booking a negative amount would credit the
daily budget and defeat the spend gate.

The daily budget is capped at `1.00` **EUR** (configurable via
`HIVEMEM_SUMMARIZE_DAILY_BUDGET`). When exceeded, cells stay tagged and resume the next
UTC day. The `usd` in `daily-budget-usd` and in the `summarize_usage.total_cost_usd`
column is a historical name kept for config and schema compatibility — the unit is EUR.

**Limitation — the daily gate does not bound subscription traffic.** A call Vistierie
routes over the Claude subscription (`provider=claude-subscription`) reports a cost of
zero, and HiveMem books `0.00` for it by design, because that is what the call actually
costs. Such calls therefore never move `total_cost_usd` and the daily budget never trips,
no matter how many of them run. The budget bounds pay-per-token routes only; volume on
the subscription route is limited by Vistierie's own quotas, not by this setting.

**Figures recorded before this version are not comparable.** Earlier rows were estimated
from the model HiveMem *requested* rather than the one Vistierie routed to, omitted cache
read/write tokens entirely, and were labelled USD. Old `summarize_usage` rows are left in
place (the gate only ever reads the current UTC day), so any trend across the upgrade date
compares two different measurements.

## Monitoring

Daily usage (`total_cost_usd` is EUR, see above):

    SELECT day, total_calls, total_cost_usd
    FROM summarize_usage
    ORDER BY day DESC LIMIT 7;

Cells still waiting:

    SELECT count(*) FROM cells WHERE 'needs_summary' = ANY(tags);

Cells throttled by the API:

    SELECT count(*) FROM cells WHERE 'summarize_throttled' = ANY(tags);

## Logging & cost visibility

Each summarize call emits one INFO line per Vistierie call plus one summary line, so
per-document model, token counts, cost, running daily spend vs. budget, and latency are
readable straight from the application logs without a DB query. A full document pass makes
several Vistierie calls (the main summarize plus the cheap `title_cell` / `classify_tax`
completions), each logging its own `Vistierie /llm/complete` line, followed by the single
`Summarize LLM call` summary line:

    Vistierie /llm/complete purpose=<purpose> provider=<provider> model=<model> in=<uncached> cacheW=<tokens> cacheR=<tokens> out=<tokens> took=<ms>ms
    Summarize LLM call cell=<uuid> provider=<provider> model=<model> in=<uncached> cacheW=<tokens> cacheR=<tokens> out=<tokens> cost=€<cost> day=€<spend>/<budget> took=<ms>ms

The first line comes from the Vistierie gateway client; the second from the
summarizer itself and includes the cost of that call plus the cumulative spend for
the current UTC day. `provider` and `model` are the ones Vistierie actually routed
to — not what HiveMem requested, so this may differ from the configured model. Amounts
are EUR, taken from Vistierie's `cost_micros` (the `daily-budget-usd` property name is
historical; the budget it configures is an EUR budget). Booked amounts are rendered at six
decimals, so a subscription-routed call logs `cost=€0.000000` — that is correct, not a bug;
see the
[cost model](#cost-model) for what it means for the daily cap. `in=` counts only the uncached
input tokens; `cacheW=`/`cacheR=` are the cache-write and cache-read tokens, which
are billed too. The vision path logs the same fields — see
[vision.md](vision.md#cost-logging).

Embedding failures caused by an unconvertible content type (e.g.
`application/octet-stream`) log a WARN line before the original exception is
rethrown:

    Embedding call failed for mode=<mode> textLen=<n>: <ExceptionClass> (content-type=<type>)

Log levels for both are independently tunable via `HIVEMEM_LOG_SUMMARIZE` and
`HIVEMEM_LOG_EMBEDDING` (both default `INFO`). Set either to `DEBUG` for retry
detail, or `WARN` to quiet the per-call lines during a large backfill batch.

## Configuration reference

| Property | Default | Purpose |
|----------|---------|---------|
| `hivemem.summarize.enabled` | `false` | Master switch |
| `hivemem.summarize.vistierie-token` (`HIVEMEM_VISTIERIE_TOKEN`) | empty | Tenant token for the Vistierie `/llm/complete` gateway — required to enable |
| `hivemem.summarize.model` | `claude-haiku-4-5` | The model HiveMem *requests*; Vistierie may route to a different one (see [Logging](#logging--cost-visibility)) |
| `hivemem.summarize.language` (`HIVEMEM_SUMMARIZE_LANGUAGE`) | `${HIVEMEM_LANGUAGE:de}` *(inherits global)* | Default output language (ISO 639-1) when the content's language is unclear; source language preserved otherwise |
| `hivemem.summarize.daily-budget-usd` | `1.00` | Hard cost cap per UTC day, **in EUR** — the `usd` in the key is historical (see [Cost model](#cost-model)) |
| `hivemem.summarize.backfill-interval` | `PT5M` | Documentation only — see note below |
| `hivemem.summarize.backfill-batch-size` | `10` | Cells per backfill run |
| `hivemem.summarize.summary-threshold-chars` | `500` | Min content length for the one-shot startup backfill sweep to tag an unembedded cell `needs_summary` (see [Enabling](#enabling)) — does **not** affect write-path tagging, which always uses the hardcoded 500-char `NeedsSummaryDecider` threshold regardless of this property |
| `hivemem.summarize.max-input-chars` | `8000` | Cap on prompt input length |

To change the actual scheduler interval, set `HIVEMEM_SUMMARIZE_BACKFILL_INTERVAL_MS`
(milliseconds). Default is `300000` (5 min).

## Disabling

Set `HIVEMEM_SUMMARIZE_ENABLED=false` and restart. No data is lost — cells keep the
tags they already had. Re-enabling resumes processing.

## Troubleshooting

**Cells stay tagged `summarize_throttled`:** Anthropic returned 429. The backfill
skips throttled cells for 15 minutes, then retries.

**Cells stuck `needs_summary` forever:** Check `summarize_usage` — daily budget might
be exhausted. Check application logs for stack traces from the Anthropic call.

**Disabling for sensitive realms:** the summarizer is a global on/off switch in
Phase 1. Realm-scoped routing comes with the planned Provider-Abstraction feature
(see SP3 backlog Item I). If you have realms that must never go through Claude
(e.g., `legal`, `medical`), keep the summarizer disabled until Item I lands —
or only enable it on a separate HiveMem instance for the realms that may use it.

## Language

The summarizer writes `summary`, `key_points`, `insight`, and `tags` in the **same language
as the cell content** (a German document stays German, an English one stays English). When the
content's language is unclear or too short to tell (e.g. a brief manual `add_cell` note), it
falls back to the backend default language.

- Configure with `HIVEMEM_SUMMARIZE_LANGUAGE` (`hivemem.summarize.language`, ISO 639-1).
  When unset it inherits the global `HIVEMEM_LANGUAGE` (default `de`), so one knob sets both
  the UI and the summarizer; set `HIVEMEM_SUMMARIZE_LANGUAGE` to override the summarizer
  independently of the UI.
- `document_type` and fact `predicate` keys stay in their controlled English vocabulary; fact
  `object` values are data and are unaffected.
- Applies to newly written and newly re-summarized cells; existing cells are not reprocessed.

## Verwandte Pipeline-Schritte

Siehe [Document-Type Extraction](extraction.md) — Profile-basierte Fakten-Extraktion läuft im selben Anthropic-Call wie der Summarizer.
