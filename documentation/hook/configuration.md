# Hook Configuration

All properties under `hivemem.hooks.*` in `application.yml`:

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | Master switch |
| `relevance-threshold` | `0.65` | Minimum `score_total` to inject |
| `min-semantic-score` | `0.35` | Minimum `score_semantic` to inject |
| `max-cells` | `3` | Maximum cells injected per turn |
| `dedup-window-turns` | `5` | Turns before a cell can be re-injected |
| `weights.semantic` | `0.70` | Ranking weight: vector cosine similarity |
| `weights.keyword` | `0.10` | Ranking weight: full-text match |
| `weights.recency` | `0.05` | Ranking weight: creation recency |
| `weights.importance` | `0.05` | Ranking weight: assigned importance (1–5) |
| `weights.popularity` | `0.05` | Ranking weight: access frequency |
| `weights.graph-proximity` | `0.05` | Ranking weight: tunnel-graph distance |

The `weights.*` block is the hook's own precision preset — it is independent of the UI/`search`-tool weights under `hivemem.search.weights`.

Example `application.yml`:

```yaml
hivemem:
  hooks:
    enabled: true
    relevance-threshold: 0.65
    min-semantic-score: 0.35
    max-cells: 3
    dedup-window-turns: 5
    weights:
      semantic: 0.70
      keyword: 0.10
      recency: 0.05
      importance: 0.05
      popularity: 0.05
      graph-proximity: 0.05
```

`threshold` and `maxCells` can also be overridden per-request via query params on the `/hooks/context` endpoint:

```
POST /hooks/context?threshold=0.70&maxCells=5
```
