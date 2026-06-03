# Roadmap

This page describes work that is **planned or partial** — features the README
references aspirationally or where the current implementation does not yet
match the full vision. Anything not listed here is considered stable; see the
Feature Status table in the [README](../README.md#feature-status).

## 🟡 Partial — already useful, not yet complete

### Temporal Knowledge Graph: contradiction detection

**Today.** Facts are bi-temporal (`valid_from` / `valid_until` /
`ingested_at`). Multi-hop graph traversal works. The schema knows a
`contradicts` relation type, and users (or agents) can label conflicting
facts manually.

**Missing.** No automatic detector. Two facts that disagree about the same
entity attribute will both sit in the graph until somebody notices.

**Planned.** A periodic sweep over recently-changed facts that flags
candidate contradictions for review (writing them as `pending` so the
existing approval workflow handles them).

### Privacy by Realm: per-realm model routing

**Today.** Realms (`legal`, `medical`, `private`, `work`, …) cleanly
separate cells, facts, tunnels, and search results. The `agents` table has
a `model_routing` JSONB column that can express "use Ollama for legal".

**Missing.** Nothing in the LLM call path actually consults `model_routing`.
A worker invoked in the `legal` realm can still reach a cloud model.

**Planned.** A routing interceptor in front of every LLM client
(`AnthropicSummarizer`, `VisionClient`, `EmbeddingClient`, …) that resolves
the realm of the current task and refuses or rewrites the call when the
configured policy forbids cloud models.

### Queen + Bees periodic agent

**Today.** The Queen and the isolated-cell Bee are registered in and
dispatched by the **Vistierie agent runtime** (LXC 102). Vistierie owns
scheduling (cron), subagent dispatch with context-shielding, per-run cost
accounting, and the per-tenant kill switch. HiveMem exposes three read-only
tool webhooks (`find_isolated_cells`, `read_cell`, `search_similar_cells`) and
a completion webhook (`/vistierie/runs/done`) under `/vistierie/**` so Vistierie
can drive Bee tasks and deliver results. Tunnel proposals from the Bee land as
`pending` entries and flow through the existing approval workflow; HiveMem
remains the sole writer. The feature is gated behind
`hivemem.queen.enabled=true` (default `false`).

**Missing.**

- No "Queen log" UI page showing run history and cost.
- No conversation UI in `knowledge-ui/` for teaching realm-level preferences
  in natural language.
- Additional Bee types (stale-fact Bee, duplicate-cell Bee,
  blueprint-drift Bee) are not yet implemented.

**Planned (rough order).**

1. UI: a "Queen log" page that reads from Vistierie's `runs` table and the
   HiveMem approval queue.
2. Conversation UI for preferences (small textarea per realm, stitched into
   Bee prompts).
3. Stale-fact, duplicate-cell, and blueprint-drift Bees.

The execution model is tracked in
[#28 Asynchronous Curator](https://github.com/visterion/HiveMem/issues/28).

### Consumption folder — automatic document separation

**Today.** `ConsumptionWatcher` polls a configured directory every
`hivemem.consumption.poll-interval`, picks up files that have been
size+mtime-stable for `stable-seconds`, and ingests them. Single files and
non-multi-page PDFs are ingested directly as `committed` cells. Multi-page
PDFs are rasterized, OCR'd per page, and dispatched to the Vistierie
`document-separator` agent via `POST /agents/document-separator/run`
(HiveMem-initiated, the first outbound Vistierie task dispatch). The page
digests + a correlation id ride inside the run `payload`; HiveMem stores the
`run_id` Vistierie returns and correlates the callback on it. When Vistierie
calls back on `POST /vistierie/separation/done` (envelope `{run_id, status,
output, …}`), HiveMem splits the PDF and ingests each part — high-confidence
boundaries as `committed`, low-confidence boundaries as `pending` (approval
queue). A non-`done` run or a missing `output` leaves the job awaiting so the
reconcile sweep degrades it; if Vistierie never responds, that sweep ingests
the whole batch as a single `pending` document after 10 minutes — nothing is
lost. The feature is gated behind `hivemem.consumption.enabled=true` (default
`false`); Queen must also be enabled for auto-split.

**Missing.**

- No barcode / separator-sheet support (by design; boundaries are content-based only).
- No split/merge correction UI — low-confidence splits are reviewed via the
  existing `approve_pending` approval queue, but there is no dedicated UI to
  re-split or merge parts.
- A Vistierie **routing rule** mapping `purpose=separator` → a Bedrock model
  (e.g. Sonnet) must exist, or separator runs fail with "no routing rule".
  This is Vistierie-side config, not HiveMem code.

**Planned (rough order).**

1. Dedicated split/merge correction UI in `knowledge-ui/`.
2. Optional barcode-sheet support.

The HiveMem→Vistierie run-creation contract (`POST /agents/{name}/run`,
`payload` + `completion_webhook` + `completion_webhook_token`, callback by
`run_id`) has been reconciled against Vistierie's real `RunController`.

The feature is tracked in
[#33 SP5 — Paperless-style consumption folder watcher](https://github.com/visterion/HiveMem/issues/33).

## 🔴 Planned — described in the README, not yet built

## Tracked GitHub issues

The README's Feature Status focuses on whether existing prose matches running
code. These open issues describe larger work that is *not yet promised in the
README* but is on the agenda:

| Issue | Topic | Relation |
|---|---|---|
| [#1](https://github.com/visterion/HiveMem/issues/1) | Multi-Master Sync Protocol (op-log replication) | Mirror one hive across laptop / desktop / home server. Design finalized 2026-04-25; sub-projects pending. |
| [#23](https://github.com/visterion/HiveMem/issues/23) | LongMemEval benchmark suite | End-to-end evaluation against Zep / Mem0 / MemGPT. |
| [#26](https://github.com/visterion/HiveMem/issues/26) | Progressive wake-up: multi-layer session bootstrap | Replaces today's monolithic `wake_up` with layered loading. |
| [#28](https://github.com/visterion/HiveMem/issues/28) | Asynchronous Curator — move agent work off the hot path | The execution model behind the Queen+Bees entry above. |
| [#29](https://github.com/visterion/HiveMem/issues/29) | Research-driven roadmap (meta) | Tracks priority and sequencing across #23, #26, #28 and related research-driven tickets. |
| [#30](https://github.com/visterion/HiveMem/issues/30) | SP2 — Attachment storage upload API | Paperless+Obsidian sub-project. |
| [#31](https://github.com/visterion/HiveMem/issues/31) | SP4 — Markdown editor + Obsidian vault import | UI on top of the existing knowledge UI. |
| [#32](https://github.com/visterion/HiveMem/issues/32) | SP3 — Ingest pipeline (OCR + email/doc → cells) | Extends today's OCR/extraction into a full ingest flow; depends on #30. |

## How this page stays honest

Every change that flips a row from 🔴 → 🟡 → ✅ in the README's Feature
Status table must update the corresponding entry here in the same commit.
When an entry reaches ✅ it is removed from this page entirely.
