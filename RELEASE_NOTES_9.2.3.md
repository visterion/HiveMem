# HiveMem 9.2.3

Summarizer fix: cells now get the meaning extracted, not just the raw text.

## Root cause

Two issues kept scanned documents (and any long cell) showing only raw OCR text with no
summary, key_points, insight or tags:

1. **Dropped metadata (code bug).** `SummarizerService.summarizeOne` persisted its result
   via `reviseCell(content, summary)` — which only carries `content` + `summary`. The
   `key_points`, `insight` and `tags` the LLM produced were computed and then silently
   discarded. Only `summary`, `document_type` and facts were ever stored. So even with the
   summarizer running, cells never got tags or key points.

2. **Test gap.** `SummarizerServiceIT` asserted only on `summary` and inlined the lossy
   call sequence instead of driving the real `summarizeOne`, so the dropped fields went
   unnoticed.

(In production the symptom was compounded by the summarizer being disabled — a deployment
config matter, not a code default.)

## Fix

- **`WriteToolRepository.reviseCell`** gains an overload accepting `key_points` / `insight`
  / `tags`. A null value carries the old one over; tags are merged (union) with the
  existing tags. The original 6-arg method is now a thin delegate — behaviour unchanged for
  all existing callers (OCR, manual revise, etc.).
- **`WriteToolService.reviseCellWithSummary`** uses it; the summarizer now calls this so
  `key_points`, `insight` and `tags` are persisted on the revised cell.
- **Loop guard:** if the LLM returns no summary, `summarizeOne` no longer calls
  `reviseCell(content, null)` (which would re-tag `needs_summary` on the new revision and
  reschedule the cell forever). It logs and clears `needs_summary` instead.
- **`SummarizerServiceIT`** rewritten to drive the real `summarizeOne` and assert summary +
  key_points + insight + tags + facts are all persisted, plus a null-summary loop-guard
  test.

## Docs

`documentation/summarizer.md`: added a "What gets written" section and corrected the
enabling env vars (Vistierie gateway, not a bare `ANTHROPIC_API_KEY`).

## Upgrade

No schema changes (still at V0030). Drop-in over 9.2.2. To populate summaries/tags for
existing cells, enable the summarizer (`HIVEMEM_SUMMARIZE_ENABLED=true` + Vistierie token)
and restart — the startup backfill tags all long, summary-less cells and the scheduler
works through them.
