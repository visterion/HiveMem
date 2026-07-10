# Hook Roadmap

Known limitations and planned improvements.

## Realm filter is soft, not hard

CWD promotion reorders results but does not exclude cells from the wrong realm. A cell tagged `tooling` and a cell tagged `personal` are both candidates when working in `/root/hivemem`. Planned: a configurable `cwd-realm-map` that passes a hard realm filter to `ranked_search`:

```yaml
hivemem.hooks.cwd-realm-map:
  /root/hivemem: tooling
  /home/vu/bogis-skills: work
```

## Skip heuristic is word-count based, not intent based

Current skip triggers on `< 5 words` and social starters. A more robust version would check: does the prompt contain a technical noun (word length > 6)? If not and the first word is a modal verb, skip. Planned improvement to `SkipHeuristics`.

## No feedback loop

If Claude ignores injected context, the system learns nothing. Popularity scores grow only when `hivemem_get_cell` is explicitly called. A PostResponse hook does not exist in Claude Code yet. Longer-term: stronger `weight_popularity` + decay agent as a proxy for relevance feedback.
