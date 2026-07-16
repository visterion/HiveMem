# Inbox Archivist

The inbox archivist is a Vistierie agent that automatically files cells out of
the `inbox` staging realm and into the right realm/signal/topic — fully
automatically, with a full audit trail so every move can be reviewed or
reversed.

## Why an inbox realm

Cells created via upload or capture without an explicit classification land in
`inbox` first, not directly in a life-area realm. This gives enrichment (OCR,
Vision, Kroki, the auto-summarizer) time to finish before anything tries to
classify the cell — classifying on raw, un-enriched content produces worse
placements than classifying once the cell has a summary, key points, and
extracted facts to work from.

## What it does

Each run, the archivist:

1. Surveys cells currently in `inbox` (bounded batch per run).
2. Reads the existing realm/signal/topic taxonomy so it can prefer an
   existing bucket over inventing a new one.
3. For each cell, reads its content/summary and decides where it belongs.
4. Either reclassifies the cell into a target realm/signal/topic, or — when
   it isn't confident enough to place the cell correctly — leaves it in
   `inbox` and records why, so it doesn't get re-surveyed every run.

It prefers existing realms and topics; new ones are only created as a
fallback when nothing existing fits. When uncertain, it skips rather than
guesses — a wrong guess is worse than a cell that stays in `inbox` a bit
longer.

## Trigger model

The archivist runs on two triggers:

- **On-demand, right after enrichment settles.** A cell is "ready" once none
  of its enrichment steps are still pending — no `ocr_pending`,
  `vision_pending`, or `kroki_pending` tag, and no `needs_summary` tag (OCR
  that has terminally failed also counts as settled, so a cell isn't stuck
  waiting on an enrichment step that will never finish). As soon as a cell in
  `inbox` reaches that state, a run is triggered. Because the agent surveys
  the whole inbox per run rather than a single cell, a burst of uploads
  settling around the same time collapses into a single run (debounced per
  instance).
- **Safety-net cron.** A scheduled run (default `0 0 4 * * *`, i.e. daily at
  04:00) catches anything the on-demand trigger missed — a trigger that
  failed to fire, cells that were already sitting in `inbox` before the
  agent was enabled, etc. The on-demand trigger is a latency optimization;
  the cron is the guarantee.

## Guarded tools

The archivist reaches HiveMem through a small, purpose-built set of
Vistierie-agent tools/webhooks, separate from the general-purpose MCP tool
surface:

- `find_inbox_cells` — list cells currently sitting in `inbox`.
- `read_cell` — read one cell's content/summary/facts.
- `list_taxonomy` — list existing realms/signals/topics, so the agent can
  reuse them instead of inventing new ones.
- `reclassify_cell` *(guarded write)* — move a cell to a new
  realm/signal/topic; requires a `reason`. Refuses to "reclassify" a cell
  back into `inbox` itself.
- `skip_inbox_cell` *(guarded write)* — leave a cell in `inbox` deliberately,
  tagging it so it isn't re-surveyed every run; requires a `reason`.

Both write tools are reason-required by design: every move or skip is
self-documenting.

## Move log and reversibility

Every reclassification and skip is recorded in the op-log with the old and
new realm/signal/topic (for a move) or just the reason (for a skip). Nothing
about a move is destructive:

- The cell's content, embeddings, tunnels, and facts are untouched — only its
  realm/signal/topic classification changes, without creating a new
  revision.
- The move is visible in the cell's own history and via time-machine
  point-in-time queries, same as any other classification change.
- The admin-only `archivist_log` MCP tool (see [Tools](tools.md)) surfaces
  the full move/skip log, newest first, so an admin can review — or manually
  correct — anything the archivist did.
- Admin-only Queen-log UI (`/queen`) shows the archivist's runs alongside the
  Queen's and other Bees', including run history and timing.

This is the same design principle used elsewhere in HiveMem's agent fleet:
full automation is fine as long as every write is reasoned, logged, and
reversible.

## Configuration

| Property | Default | Purpose |
|----------|---------|---------|
| `hivemem.queen.archivist-schedule` | `0 0 4 * * *` | Safety-net cron (Spring 6-field), offset from the Queen's own 03:00 schedule |
| `hivemem.queen.archivist-debounce-seconds` | `60` | Minimum seconds between on-demand triggers (per instance), so a burst of enrichment completions collapses into one run |

The archivist shares its enable switch (`hivemem.queen.enabled` /
`HIVEMEM_QUEEN_ENABLED`) and Vistierie connection settings with the rest of
the Queen/Bee agent fleet — see [Architecture](architecture.md) for the
shared agent-runtime configuration.

## Related

- [Tools](tools.md) — `archivist_log` and the rest of the MCP tool surface.
- [Document & Scan Pipeline](document-pipeline.md) — how a cell reaches
  `inbox` in the first place and what enrichment happens before the
  archivist looks at it.
- [Auto-Summarizer](summarizer.md) — one of the enrichment steps the
  archivist waits on.
