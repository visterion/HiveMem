# Tools

HiveMem exposes **37 MCP tools** across search, knowledge graph, progressive summarization, agent fleet, references, attachments, and admin. Large file uploads can also use the REST endpoint (`POST /api/attachments`) — see [Attachments](#attachments).

## Feature Overview

- **37 MCP tools** across search, knowledge graph, progressive summarization, agent fleet, references, attachments, and admin
- **6-signal ranked search** — semantic similarity + keyword match + recency + importance + popularity + graph proximity
- **Append-only versioning** — never lose history, revise with parent_id chains, point-in-time queries
- **Progressive summarization** — content, summary, key_points, insight per cell
- **Temporal knowledge graph** — facts with valid_from/valid_until, contradiction detection, multi-hop traversal
- **Role-based token auth** — multiple tokens, 4 roles (admin/writer/reader/agent), per-role tool visibility
- **Agent fleet** with approval workflow — agents write pending suggestions, only admins approve
- **Blueprints** — curated narrative overviews per realm, append-only versioned
- **References & reading list** — track sources, link to cells, filter by type/status
- **Spring Boot 4.0.6 + Java 25** — MCP server with jOOQ, Flyway migrations, Caffeine cache
- **Automatic embedding reencoding** — detects model changes at startup, re-encodes all vectors with backup and progress tracking
- **Comprehensive JUnit + Testcontainers suite** — unit, integration, HTTP end-to-end, performance, security, concurrency

## Tool List

**Read (20):**

1. `status`: System overview and counts.
2. `search`: Semantic similarity + keyword search; returns metadata by default and supports `include` for optional fields. Optional params: `tags` (string array — match-ANY overlap filter), `status` (`committed`|`pending`|`rejected`, default `committed`).
3. `search_kg`: Knowledge graph triple lookup.
4. `get_cell`: Read a single knowledge item (logs access automatically); supports `include` for optional fields including content.
5. `list`: Navigate the Realm→Signal→Topic→Cell hierarchy (omit all params for realms; add `realm` for signals; add `realm`+`signal` for topics; add `realm`+`signal`+`topic` for cells).
6. `traverse`: Recursive graph traversal.
7. `quick_facts`: Context-aware facts about an entity.
8. `time_machine`: Historical knowledge retrieval.
9. `wake_up`: Initial session context. The response includes a `default_language` field (the backend-configured default UI language, set via `HIVEMEM_LANGUAGE`, default `de`).
10. `history`: Trace revisions of a cell or fact (type-dispatched, recursive CTE depth cap 100).
11. `pending_approvals`: List work awaiting review.
12. `get_blueprint`: Narrative realm overviews.
13. `reading_list`: Manage unread/in-progress sources.
14. `list_agents`: View active agent fleet.
15. `diary_read`: Read agent diary entries.
16. `list_attachments`: List all file attachments linked to a cell (metadata only, no file content).
17. `get_attachment_info`: Get metadata for a single attachment by ID. Return fields include `cell_id` (UUID of the extraction cell), `content_uri` (`hivemem://attachments/{id}/content`), `thumbnail_uri` (`hivemem://attachments/{id}/thumbnail` or null), and `page_count` (INTEGER for PDFs, `null` for other types). Download via `GET /api/attachments/{id}/content`.
18. `facet_count`: Aggregate document counts grouped by one or more cell fields. Required param: `fields` (array of one or more of `tag`, `status`, `realm`, `year`, `signal`). Optional filters: `realm`, `signal`, `topic`, `status` (`committed`|`pending`|`rejected`), `query` (full-text/semantic), `tags` (match-ANY), `limit` (max values per field, default 50). Returns `{field: [{value, count}, …]}` for each requested field.
19. `queen_runs` *(admin only)*: List recent Queen/Bee agent runs from Vistierie. Optional args: `limit` (1–200, default 50), `offset` (0+, default 0). Returns `{items:[{id,agent,trigger,status,startedAt,finishedAt,durationMs,llmCalls,costMicros}], total, costAvailable}`; on Vistierie outage returns `{items:[],total:0,costAvailable:false,unavailable:true}`. Cost fields (`llmCalls`, `costMicros`) are populated only when `HIVEMEM_QUEEN_VISTIERIE_ADMIN_TOKEN` is configured.
20. `queen_run_detail` *(admin only)*: Fetch full detail for a single Queen/Bee run. Required arg: `run_id` (string). Returns `{run:{...}, events:[{type,...}]}` (run metadata + Vistierie event timeline); on outage returns `{run:{},events:[],unavailable:true}`.

**Write (15):**

20. `add_cell`: Store a cell with content, summary, key points, and insight; optional `dedupe_threshold` runs an embedding-based dedupe gate in one call.
21. `add_tunnel`: Link two cells together.
22. `kg_add`: Fact triple; optional `on_conflict` (`insert`|`return`|`reject`) gates against active conflicts.
23. `kg_invalidate`: Soft-delete/expire a fact.
24. `update_identity`: Update session context facts.
25. `add_reference`: Store source documents/URLs.
26. `link_reference`: Cite source for a cell.
27. `remove_tunnel`: Expire a cell link.
28. `revise_cell`: Create a new version of a cell.
29. `revise_fact`: Create a new version of a fact.
30. `register_agent`: Add an agent to the fleet.
31. `diary_write`: Agent-private reflection tool.
32. `update_blueprint`: Update realm narrative.
33. `reclassify_cell`: Move a cell to a different realm/signal/topic in-place without creating a new revision. Leaves content, embeddings, tunnels, facts, and references untouched. Use for taxonomy migrations.
34. `upload_attachment`: Upload a file attachment (Base64-encoded). Required params: `realm` (target realm), `data` (Base64 payload), `filename`. Optional: `signal`, `topic`, `cell_id` (existing cell — creates a `related_to` tunnel). Always creates a new `pending` Cell whose content is the extracted text (or the filename if no text could be extracted); the Classifier agent enriches the cell asynchronously. Stores original in SeaweedFS, generates JPEG thumbnail at ingest. Returns `{ attachment_id, cell_id, mime_type, size_bytes, has_thumbnail }`. For large files (>~10 MB) prefer `POST /api/attachments` (multipart) — see [Attachments](#attachments).

**Admin (2):**

35. `approve_pending`: Admin tool to batch approve or reject agent writes.
36. `health`: Monitor DB and service state.

## Attachments

In addition to the MCP tools above (`upload_attachment`, `list_attachments`, `get_attachment_info`), HiveMem exposes a parallel REST API under `/api/attachments` for efficient binary transfers — the JSON-RPC `upload_attachment` requires base64 encoding, which is wasteful for large files. The REST endpoints use the same token authentication as MCP.

| Endpoint | Purpose |
|---|---|
| `POST /api/attachments` (multipart) | Upload a file. Required form fields: `realm`, `file`. Optional: `signal`, `topic`, `cell_id` (creates a `related_to` tunnel to that cell). Same downstream behaviour as `upload_attachment`. |
| `GET /api/attachments/{id}/content` | Download the original binary. |
| `GET /api/attachments/{id}/thumbnail` | Download the JPEG thumbnail when present. |
| `DELETE /api/attachments/{id}` | Soft-delete an attachment. |

## Search Signals

The `search` tool combines 6 signals with configurable weights:

| Signal | Default Weight | Description |
|---|---|---|
| Semantic | 0.30 | Vector cosine similarity |
| Keyword | 0.15 | PostgreSQL full-text search (tsvector, BM25-like) |
| Recency | 0.15 | Exponential decay, 90-day half-life |
| Importance | 0.15 | User/agent assigned 1-5 scale |
| Popularity | 0.15 | Access frequency (materialized view) |
| Graph proximity | 0.10 | Boost for cells reachable from the top semantic candidates via tunnels (depth ≤ 2). Per-relation weights default to `builds_on=1.0`, `refines=0.8`, `related_to=0.6`, `contradicts=0.4`. |

Weights are configurable via `hivemem.search.weights` in `application.yml` and per-call via the MCP `search` arguments (`weight_semantic`, `weight_keyword`, `weight_recency`, `weight_importance`, `weight_popularity`, `weight_graph_proximity`).

#### Confidence Level

Each search result includes a `confidence_level` field indicating how strongly the result is supported by the query:

| Level | score_total | Meaning |
|-------|-------------|---------|
| `HIGH` | ≥ 0.80 | Strong evidence — safe to use as primary context |
| `MEDIUM` | ≥ 0.65 | Reasonable match — use with normal attribution |
| `LOW` | ≥ 0.55 | Weak match — use cautiously, consider caveating |
| `NONE` | < 0.55 | Below minimum threshold — treat as background noise |

Thresholds are configurable via `hivemem.search.confidence.high/medium/low` in `application.yml`.
`score_total` (the raw numeric value) is always present alongside `confidence_level`.

`search` defaults to `summary`, `tags`, `importance`, and `created_at` plus required identity fields (`id`, `realm`, `signal`, `topic`). `get_cell` defaults to `summary`, `key_points`, `insight`, `tags`, `importance`, `source`, `actionability`, `status`, `created_at`, and `attachments` plus the same required identity fields. Pass `include` to request a specific subset of optional fields, including `content`.

- `attachments` (get_cell only, on by default): list of the cell's original files, each `{id, mime_type, original_filename, size_bytes, page_count}` (`page_count` is an INTEGER, populated for PDFs at ingest — `null` for non-PDFs). Download the bytes at `GET /api/attachments/{id}/content`. Internal storage keys are not exposed.

## Progressive Summarization

Every cell supports four progressive fields:

| Field | Purpose |
|---|---|
| `content` | Full verbatim text |
| `summary` | One-sentence summary for scanning |
| `key_points` | 3-5 core takeaways |
| `insight` | Personal conclusion / implication |

Plus `actionability` (actionable / reference / someday / archive) and `importance` (1-5).
