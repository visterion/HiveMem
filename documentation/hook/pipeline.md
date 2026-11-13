# Hook Pipeline

Each incoming prompt passes through these stages in order. Any stage can short-circuit to an empty response.

## Stage 1 — Skip Heuristics

Prompts that carry no useful search signal are rejected immediately — no embedding, no DB call.

Skipped if:
- Prompt is null or blank
- Prompt starts with `!nomem` (explicit opt-out)
- Prompt is in the meta-phrase list (`ok`, `ja`, `danke`, `wie geht's`, `alles klar`, …)
- Prompt is a pure code block (starts and ends with ` ``` `)
- Fewer than 5 words
- Starts with a social phrase (`wie geht `, `wie läuft `, `hallo`, `guten morgen`, …) and has ≤ 8 words

Force-include: prefix prompt with `!mem ` to bypass all skip logic. The `!mem ` marker itself is stripped before the prompt is embedded and searched.

## Stage 2 — Embedding + Search

The prompt is embedded via whichever embedding backend is active — `paraphrase-multilingual-MiniLM-L12-v2` on the default ONNX backend, or the configured Ollama model (e.g. Qwen3-Embedding-8B) on the optional GPU backend, see [architecture.md](../architecture.md#gpu-embedding-backend-optional) — and passed to `ranked_search`, a PostgreSQL function combining six signals. The hook uses its own precision-tuned weight preset (`hivemem.hooks.weights.*`), independent of the UI/`search`-tool weights:

| Signal | Default weight | What it measures |
|---|---|---|
| `score_semantic` | 0.70 | Cosine similarity between prompt and cell embeddings |
| `score_keyword` | 0.10 | BM25 over summary and content (k1 1.5, b 0.75), normalised so the best lexical match of the prompt scores 1.0 |
| `score_recency` | 0.05 | How recently the cell was created |
| `score_importance` | 0.05 | Manually assigned importance (1–5) |
| `score_popularity` | 0.05 | Access frequency |
| `score_graph_proximity` | 0.05 | Distance via tunnel graph |

`ranked_search` applies a hard pre-filter: `score_semantic > 0.3 OR score_keyword > 0`. Results are ordered by `score_total` descending.

## Stage 3 — Relevance Threshold

```
score_total >= relevanceThreshold  (default: 0.65)
```

Cells below this threshold are discarded. Tunable via `hivemem.hooks.relevance-threshold` or the `?threshold=` query param.

The keyword signal is BM25 and moves the total: the best lexical match of the prompt gets the full keyword weight (0.10), and a cell that shares no word with the prompt scores 0, as before. The earlier full-text signal gave about 0.9 to nearly every cell that shared any word with the prompt, and because there are no stop words that is nearly every cell. A candidate that shares only common words with the prompt now scores close to 0 and loses most of that contribution, up to about 0.09 of its total at the same threshold, so the hook injects less. The default threshold was chosen with the earlier offset in place and may need re-tuning. A prompt of more than 64 distinct words, or with fewer than two rare words (words found in at most half of the cells), gets the BM25 keyword score without the proximity component that ranks up cells in which the rare words stand close together.

## Stage 4 — Semantic Floor

```
score_semantic >= minSemanticScore  (default: 0.35)
```

Prevents pure keyword matches from being injected. A cell that matches on keyword overlap alone (e.g. "deployment" appearing in both prompt and cell) without semantic similarity is filtered out here.

This addresses the failure mode where a topically unrelated cell scores high due to shared vocabulary.

## Stage 5 — Session Dedup

Cells already injected within the last `dedupWindowTurns` turns (default: 5) in the same session are suppressed. Avoids repeating context Claude has already seen.

## Stage 6 — CWD Promotion

If the request includes a `cwd` (current working directory), the last path component is extracted (e.g. `/root/hivemem` → `hivemem`). Cells whose `realm`, `topic`, or `tags` contain this string are sorted to the front, even if their `score_total` is lower than other results.

This is a soft promotion — it reorders results but does not filter. A configurable hard realm filter is planned (see [roadmap](roadmap.md)).
