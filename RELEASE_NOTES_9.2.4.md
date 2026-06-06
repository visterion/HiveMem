# HiveMem 9.2.4

Summarizer follow-up: fix the Vistierie `/llm/complete` request contract.

## Root cause

9.2.3 fixed the summarizer's persistence, but the summarizer had never actually run
against the real Vistierie gateway (it was disabled in prod, and unit tests used a mock
that accepts any body). When enabled, every `/llm/complete` call returned **400 Bad
Request**:

- The body sent `"tenant":"hivemem"`, but `/llm/complete` requires `agent_name`
  (`@NotBlank`) — a registered agent with an operational budget. Missing it → 400.
- The system prompt was sent as a `role:"system"` entry **inside** `messages`. Anthropic
  requires `system` as a top-level field; Vistierie's DTO has a dedicated `system` field.

## Fix (`AnthropicSummarizer` + config)

- Send `agent_name` (configurable via `HIVEMEM_SUMMARIZE_AGENT`, default
  `document-separator` — the agent the Queen bootstrap provisions with a budget; its
  `model_purpose` is ignored for `/llm/complete`, which routes on the per-request
  purpose/model).
- Move the system prompt to the top-level `system` field; `messages` now carries only the
  user turn.
- `model` stays `claude-haiku-4-5` (in Vistierie's price/allowlist).

## Test

`AnthropicSummarizerTest` gains a contract test that inspects the actual outgoing request
body: asserts `agent_name` is present, `tenant` is absent, `system` is top-level, and
`messages` contains exactly one `user` turn. (The previous tests asserted only on the
parsed response, which is why the contract drift went unnoticed.)

## Upgrade

No schema changes (still V0030). Drop-in over 9.2.3. If the summarizer is enabled, existing
`needs_summary` cells (loop-guarded, never lost on the earlier 400s) are summarized on the
next backfill tick.
