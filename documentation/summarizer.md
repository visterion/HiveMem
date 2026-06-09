# Auto-Summarizer

HiveMem's auto-summarizer turns long cells into semantically searchable knowledge by
calling Claude Haiku to produce a curated summary. The summary is what gets embedded —
so even very long cells (whitepapers, scanned letters, meeting transcripts) become
findable by meaning, not just by their first 500 characters.

## Why summaries are necessary

The embedding model has a token limit of ~128 tokens (≈ 500 characters). Without a
summary, long cells are silently truncated by the embedder; the resulting vector
represents only the first few sentences. That's why HiveMem now embeds the summary
when one is available, and falls back to the content only for short cells.

For long cells without a summary, the embedding is left NULL and the cell is tagged
`needs_summary`. The summarizer picks them up automatically.

## What gets written

Each successful run persists, on a new revision of the cell:

- `summary` — the embedded 1–2 sentence summary
- `key_points`, `insight`, `tags` — the curated metadata the LLM returns
- `document_type` — the inferred profile type (invoice / contract / other)
- extracted facts — written to the knowledge graph (see [extraction](extraction.md))

If the LLM returns no summary, the cell is **not** revised; the `needs_summary` tag is
cleared so it is not retried in a loop.

## Enabling

Set the env vars and restart (the summarizer calls Claude via the Vistierie gateway,
sharing the same base URL / token as the other Vistierie-backed features):

    HIVEMEM_SUMMARIZE_ENABLED=true
    HIVEMEM_VISTIERIE_BASE_URL=http://vistierie:8090
    HIVEMEM_VISTIERIE_TOKEN=<tenant token>

That's it. On boot, all existing cells without a summary and with content > 500 chars
are tagged `needs_summary`. The backfill scheduler picks them up over the next minutes.
New cells trigger the summarizer within seconds via the AFTER_COMMIT event.

## Cost model

Claude Haiku 4.5:
- Input: $0.80 / 1M tokens
- Output: $4.00 / 1M tokens

A typical cell (~2k input + ~400 output) costs about $0.0024.

The daily budget is capped at $1.00 (configurable via `HIVEMEM_SUMMARIZE_DAILY_BUDGET`).
When exceeded, cells stay tagged and resume the next UTC day.

## Monitoring

Daily usage:

    SELECT day, total_calls, total_cost_usd
    FROM summarize_usage
    ORDER BY day DESC LIMIT 7;

Cells still waiting:

    SELECT count(*) FROM cells WHERE 'needs_summary' = ANY(tags);

Cells throttled by the API:

    SELECT count(*) FROM cells WHERE 'summarize_throttled' = ANY(tags);

## Configuration reference

| Property | Default | Purpose |
|----------|---------|---------|
| `hivemem.summarize.enabled` | `false` | Master switch |
| `hivemem.summarize.vistierie-token` (`HIVEMEM_VISTIERIE_TOKEN`) | empty | Tenant token for the Vistierie `/llm/complete` gateway — required to enable |
| `hivemem.summarize.model` | `claude-haiku-4-5` | Which Claude model |
| `hivemem.summarize.language` (`HIVEMEM_SUMMARIZE_LANGUAGE`) | `${HIVEMEM_LANGUAGE:de}` *(inherits global)* | Default output language (ISO 639-1) when the content's language is unclear; source language preserved otherwise |
| `hivemem.summarize.daily-budget-usd` | `1.00` | Hard cost cap per UTC day |
| `hivemem.summarize.backfill-interval` | `PT5M` | Documentation only — see note below |
| `hivemem.summarize.backfill-batch-size` | `10` | Cells per backfill run |
| `hivemem.summarize.summary-threshold-chars` | `500` | Min content length to trigger needs_summary |
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
