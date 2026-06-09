# HiveMem 9.2.5

Summarizer follow-up: tolerate markdown-fenced LLM responses.

## Root cause

With 9.2.4 the `/llm/complete` contract was correct and the LLM started responding — but
every parse failed with `Failed to parse summarizer JSON: ```json…`. Despite the prompt
asking for raw JSON, the model wraps its answer in a ```json … ``` code fence.
`AnthropicSummarizer` called `MAPPER.readTree(text)` directly, which chokes on the leading
fence. (The reassembly `PageGrouper` already strips fences; the summarizer didn't.)

## Fix (`AnthropicSummarizer`)

Add a tolerant `stripJsonFences` step before parsing: strip a leading ```json / ``` fence
and narrow to the substring between the first `{` and last `}` — robust to both fenced and
prose-wrapped responses. Mirrors `PageGrouper`'s tolerant array parse.

## Test

`AnthropicSummarizerTest.parsesMarkdownFencedJsonResponse` stubs a ```json-fenced payload
and asserts summary/key_points/tags parse correctly.

## Upgrade

No schema changes (still V0030). Drop-in over 9.2.4. Loop-guarded `needs_summary` cells
(never lost across the 9.2.3→9.2.5 fixes) are summarized on the next backfill tick.
