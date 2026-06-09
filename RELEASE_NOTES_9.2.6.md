# HiveMem 9.2.6

Summarizer follow-up: stop truncating long responses.

## Root cause

With 9.2.5 the summarizer succeeded for most cells (summary + facts written), but cells
with rich content kept failing to parse. The cause: `max_tokens` was hardcoded at **800**.
For a cell that yields a full summary + 5 key_points + insight + a dozen facts, the model's
JSON response exceeds 800 tokens and is cut off mid-object — leaving invalid JSON that no
fence-stripping can repair. Those cells stayed `needs_summary` and were retried every
backfill (burning budget).

## Fix (`AnthropicSummarizer` + config)

Make the output cap configurable via `HIVEMEM_SUMMARIZE_MAX_OUTPUT`
(`hivemem.summarize.max-output-tokens`) and raise the default from 800 to **4096** — enough
to hold the structured summary plus all extracted facts. The contract test now asserts the
configured `max_tokens` is sent.

## Upgrade

No schema changes (still V0030). Drop-in over 9.2.5. Previously-truncated `needs_summary`
cells succeed on the next backfill tick.
