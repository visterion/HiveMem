# Tools

HiveMem exposes **52 MCP tools** across search, knowledge graph, progressive summarization, agent fleet, references, attachments, saved searches, tag management, and admin. Large file uploads can also use the REST endpoint (`POST /api/attachments`) — see [Attachments](#attachments).

## Feature Overview

- **52 MCP tools** across search, knowledge graph, progressive summarization, agent fleet, references, attachments, saved searches, tag management, and admin
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

**Read (25):**

1. `status`: System overview and counts.
2. `search`: Semantic similarity + keyword search; returns metadata by default and supports `include` for optional fields (including `realm`). Optional params: `realm` (pass `"none"` to restrict to cells with no realm assigned), `tags` (string array — match-ANY overlap filter), `status` (`committed`|`pending`|`rejected`, default `committed`). Alternatively, pass `where` (selector object: `realm`, `realm_in` — array, may include `"none"` — `signal`, `topic`, `tags`, `status`; `query` is not allowed inside `where` — the top-level `query` param remains the search text; mutually exclusive with the flat `realm`/`signal`/`topic`/`tags`/`status` params).
3. `search_kg`: Knowledge graph triple lookup. Optional param `query` runs semantic search over `facts.embedding` (cosine similarity, returns a `score` field, ordered by score) instead of the default ILIKE filters on `subject`/`predicate`/`object_`; falls back to the ILIKE path if the embedding service is unavailable. Unlike `entity_overview` and `quick_facts`, `search_kg` does **not** resolve its query through the `kg_entity` alias registry (it's free-text/semantic matching on the raw subject) — for canonical facts on a fragmented entity, use `entity_overview`/`quick_facts` or search on the canonical name directly.
4. `get_cell`: Read a single knowledge item (logs access automatically); supports `include` for optional fields including `content` and `confidence` (per-document average confidence of active facts, nullable `real`; pass `include=['confidence']` to request it).
5. `list`: Navigate the Realm→Signal→Topic→Cell hierarchy (omit all params for realms; add `realm` for signals; add `realm`+`signal` for topics; add `realm`+`signal`+`topic` for cells).
6. `traverse`: Recursive bidirectional graph traversal from a starting cell. Required param: `cell_id`. Optional: `relation_filter`, `max_depth` (default 2, max 100), `max_nodes` (cap on distinct cells in the result, 1–1000, default 200). **Breaking change (response shape):** the tool no longer returns a bare edge array — it returns `{edges: [...], node_count: N, truncated: bool}`. `truncated` is `true` when the `max_nodes` cap or the internal 5000-edge backstop cut the traversal short; nodes are counted in depth order (skip-and-continue once the cap is hit), so the counted nodes are always the closest neighborhood of the start cell — edges between two counted nodes are kept even after the node cap is reached.
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
18. `facet_count`: Aggregate document counts grouped by one or more cell fields. Required param: `fields` (array of one or more of `tag`, `status`, `realm`, `year`, `signal`, or `fact:<predicate>` — e.g. `fact:vendor`, `fact:party`). Optional filters: `realm` (pass `"none"` to restrict to cells with no realm assigned), `signal`, `topic`, `status` (`committed`|`pending`|`rejected`), `query` (full-text/semantic), `tags` (match-ANY), `limit` (max values per field, default 10, max 100). Alternatively, pass `where` (selector object: `realm`, `realm_in` — array, may include `"none"` — `signal`, `topic`, `tags`, `query`, `status`; mutually exclusive with the flat `realm`/`signal`/`topic`/`tags`/`status`/`query` params). When `where` is present and `where.status` is omitted, `status` defaults to `committed`, matching every other where-consumer (list_cell_ids, bulk ops, search); the flat `status` param above is unaffected and still applies no status filter unless given. Returns `{field: [{value, count}, …]}` for each requested field. Allowed `fact:<predicate>` values: `vendor`, `party`, `amount_total`, `value_per_period`, `document_date`, `due_date`, `invoice_number`, `contract_number`.
19. `list_documents`: Browse documents in a realm (no query) with tag/status filters, sort, and paging; joins the extraction-source attachment. Optional params: `realm` (default `documents`; pass `"none"` to list cells with no realm assigned), `signal`, `topic`, `tags` (match-ANY), `status` (`committed`|`pending`|`rejected`, default `committed`), `sort` (`newest`|`oldest`|`title`, default `newest`), `limit` (1–200, default 50), `offset` (0+, default 0). Each row includes a nullable `confidence` (per-document average of active-fact confidence).
20. `list_media`: List image attachments for the photo gallery — committed cells Vision-classified `subtype_photo_general` or `subtype_whiteboard_photo`, joined to their extraction-source image attachment and per-image EXIF metadata (camera, width/height, capture date, GPS, reverse-geocoded place name). Optional params: `realm` (default: all realms), `sort` (`newest`|`oldest` by capture date, default `newest`), `limit` (1–500, default 100), `offset` (0+, default 0). Each row includes `attachment_id`, `thumbnail_uri`, `content_uri`, `taken_at`, `place_name`, and the EXIF fields. `document_scan` images are excluded (they appear in the Scans view).
21. `list_saved_searches`: Return all active saved searches belonging to the calling user (`id`, `name`, `filter`, `created_at`). No params required. Use `save_search` to create and `delete_saved_search` to remove.
22. `list_cell_ids`: List only cell IDs (+realm/signal/topic) matching a filter — the cheap discovery primitive for bulk operations. Optional param: `where` (object with `realm`, `realm_in` — array, may include `"none"` for cells without a realm — `signal`, `topic`, `tags` — match-ANY — `query` — full-text — and `status`, default `committed`; `realm` and `realm_in` are mutually exclusive). Also `limit` (1–1000, default 200) and `offset` (0+, default 0). Returns `{ids: [{id, realm, signal, topic}], total}`.
23. `entity_overview`: Everything about an entity in one call — top-ranked cells, active facts (exact + substring), and depth-1 tunnels of the best cell match. Replaces the `search` + `quick_facts`/`search_kg` + `traverse` triple. Required param: `subject` (entity name). Optional: `limit` (per-section limit, 1–20, default 5). Returns `{cells: [...], facts: [...], tunnels: [...]}`; unknown subjects return three empty arrays (no error).
24. `blueprints_missing`: Realms that have active cells but no active blueprint — backlog for orientation docs. No params. Returns `{realms: [{realm, cell_count}]}`.
25. `data_quality_report`: Memory-health report over active cells: cells missing `realm`/`signal`/`topic` (`unclassified`), cells with neither tunnels nor facts (`disconnected`), near-duplicate cell pairs by embedding cosine similarity (`duplicate_clusters`), and knowledge-graph subject fragmentation candidates (`potential_conflicts`). Optional params: `include` (array subset of `unclassified`|`disconnected`|`duplicate_clusters`|`potential_conflicts`, default all four), `threshold` (cosine similarity cutoff for duplicates, range 0.5–1.0, default 0.90), `subject_similarity` (pg_trgm similarity cutoff for ranking candidate subject pairs in `potential_conflicts`, range 0.0–1.0, default 0.3), `limit` (max duplicate pairs for `duplicate_clusters` and max predicates returned for `potential_conflicts`, 1–200, default 50). Out-of-range/invalid values are rejected, not clamped. Each `unclassified` sub-section returns `{count, sample: [{id, realm, signal, topic, summary}]}` (sample capped at 10); `disconnected` returns the same shape; `duplicate_clusters` returns `[{cell_a: {id, summary}, cell_b: {id, summary}, similarity}]`. If the embedding service is unavailable, `duplicate_clusters` is replaced with `{note: "embeddings unavailable"}`. `potential_conflicts` finds predicates with more than one distinct active subject (candidates for subject fragmentation, i.e. the same real-world entity recorded under different subject strings) and returns `[{predicate, subject_count, subjects, subjects_truncated, similar_pairs: [{a, b, similarity}]}]`; `similar_pairs` ranks subject pairs by `pg_trgm` similarity above `subject_similarity`. Predicates with more than 50 distinct subjects skip pairwise ranking for cost reasons — `similar_pairs` is empty, `subjects` is truncated to 50, and `subjects_truncated` is `true`. Use `kg_alias` to register a canonical subject and retro-migrate the fragmented facts, then `kg_add` with `on_conflict=supersede` to resolve any remaining `resulting_conflicts`.

**Write (23):**

26. `add_cell`: Store a cell with content, summary, key points, and insight; optional `dedupe_threshold` runs an embedding-based dedupe gate in one call.
27. `add_tunnel`: Link two cells together.
28. `kg_add`: Fact triple. Optional `on_conflict` gates against active conflicts sharing subject+predicate: `insert` (default, no gate), `return` (report conflicts without inserting), `reject` (error on conflict), `supersede` (invalidate conflicting active facts, then insert; response includes `superseded: N`).
29. `kg_invalidate`: Soft-delete/expire a fact.
30. `kg_rename_predicate`: Rename a predicate across every active fact matching it: invalidates each matching fact and re-adds it under the new predicate, preserving subject, object, confidence, source, status, and the original `valid_from` (only the predicate name changes). Required params: `from`, `to`. Optional: `subject` (narrow to one subject), `confirm` (required `true` above 200 matched facts). Matches are capped at 1000 facts. Returns `{renamed: N, matched: N}`.
31. `kg_alias`: Register alternate names for a canonical entity in the `kg_entity` alias registry and retro-migrate existing facts onto the canonical subject. Required params: `canonical` (canonical subject name), `aliases` (string array of alternate names). Optional: `confirm` (required `true` when more than 200 facts would be migrated). For each alias, invalidates matching facts (regardless of status — active, pending, or already-invalidated) and re-adds them under `canonical`, preserving predicate, object, confidence, source, and the original `valid_from`. Matches are capped at 1000 facts; exceeding the cap errors and asks the caller to narrow the aliases. Once registered, `kg_add` resolves its `subject` through the registry before writing (so new facts under any alias land on the canonical subject too), and `entity_overview`/`quick_facts` resolve a queried subject through the registry before reading. Returns `{registered, migrated, resulting_conflicts}`: `registered` is a boolean confirming the alias entry was upserted, `migrated` is the number of facts moved to the canonical subject, and `resulting_conflicts` is the count of active `(subject, predicate)` groups on the canonical subject that still have more than one active fact after migration (including pre-existing conflicts) — resolve these with `kg_add on_conflict=supersede`.
32. `update_identity`: Update session context facts.
33. `add_reference`: Store source documents/URLs.
34. `link_reference`: Cite source for a cell.
35. `remove_tunnel`: Expire a cell link.
36. `revise_cell`: Create a new version of a cell.
37. `revise_fact`: Create a new version of a fact.
38. `register_agent`: Add an agent to the fleet.
39. `diary_write`: Agent-private reflection tool.
40. `update_blueprint`: Update realm narrative.
41. `reclassify_cell`: Move a cell to a different realm/signal/topic in-place without creating a new revision. Leaves content, embeddings, tunnels, facts, and references untouched. Use for taxonomy migrations.
42. `upload_attachment`: Upload a file attachment (Base64-encoded). Required params: `realm` (target realm), `data` (Base64 payload), `filename`. Optional: `signal`, `topic`, `cell_id` (existing cell — creates a `related_to` tunnel). Always creates a new `pending` Cell whose content is the extracted text (or the filename if no text could be extracted); the Classifier agent enriches the cell asynchronously. Stores original in SeaweedFS, generates JPEG thumbnail at ingest. Returns `{ attachment_id, cell_id, mime_type, size_bytes, has_thumbnail }`. For large files (>~10 MB) prefer `POST /api/attachments` (multipart) — see [Attachments](#attachments).
43. `save_search`: Persist the current Scans filter as a named saved search for the calling user. Required param: `name` (human-readable label). Optional: `filter` (JSON object describing the filter state, serialized by the UI; defaults to `{}`). Upserts by name — if a saved search with the same name already exists for this user it is replaced.
44. `delete_saved_search`: Soft-delete a saved search by `id` (UUID). Only the owner can delete their own saved searches. Returns `{id, deleted}`.
45. `add_tags`: Add one or more tags to a cell (idempotent union — already-present tags are ignored). Required params: `cell_id` (UUID), `tags` (string array). Returns `{updated: 1}` when the cell was found, `{updated: 0}` if not found or already closed.
46. `remove_tags`: Remove one or more tags from a cell (idempotent — tags not present are ignored). Required params: `cell_id` (UUID), `tags` (string array). Returns `{updated: 1}` when the cell was found, `{updated: 0}` if not found or already closed.
47. `bulk_tag`: Add and/or remove tags on multiple cells in a single transaction. Exactly one of `cell_ids` (UUID array) or `where` (selector object, same shape as `list_cell_ids`: `realm`, `realm_in`, `signal`, `topic`, `tags`, `query`, `status`; `"none"` realm matches cells without a realm) must be provided. Optional: `add_tags` (string array), `remove_tags` (string array) — at least one required. `where` matches are capped at 1000 cells; matches over 200 require `confirm: true`. Operations are idempotent. Returns `{updated: N, matched: N}` — `matched` is the number of cells the selector/list resolved to, `updated` the number actually modified.
48. `bulk_reclassify`: Reclassify multiple cells in-place (realm/signal/topic) in a single transaction. Exactly one of `cell_ids` (UUID array) or `where` (selector object, same shape as `list_cell_ids`) must be provided. Optional: `realm`, `signal` (`facts`|`events`|`discoveries`|`preferences`|`advice`), `topic` — at least one required. `where` matches are capped at 1000 cells; matches over 200 require `confirm: true`. Note: a `where` selector with `status: "rejected"` is not reclassifiable — `reclassify_cell`'s live-version guard throws and rolls back the whole batch. Returns `{updated: N, matched: N}`.

**Admin (4):**

49. `approve_pending`: Admin tool to batch approve or reject agent writes.
50. `health`: Monitor DB and service state.
51. `queen_runs`: List recent Queen/Bee agent runs from Vistierie. Optional args: `limit` (1–200, default 50), `offset` (0+, default 0). Returns `{items:[{id,agent,trigger,status,startedAt,finishedAt,durationMs,llmCalls,costMicros}], total, costAvailable}`; on Vistierie outage returns `{items:[],total:0,costAvailable:false,unavailable:true}`. Cost fields (`llmCalls`, `costMicros`) are populated only when `HIVEMEM_QUEEN_VISTIERIE_ADMIN_TOKEN` is configured.
52. `queen_run_detail`: Fetch full detail for a single Queen/Bee run. Required arg: `run_id` (string). Returns `{run:{...}, events:[{type,...}]}` (run metadata + Vistierie event timeline); on outage returns `{run:{},events:[],unavailable:true}`.

## Tool-surface recommendation

**This is a recommendation only — no behavior change has been made.** The 52-tool surface above has grown organically over several rounds of feature work. If the surface is ever pared down for agent ergonomics (fewer tools to reason about per call), a natural split is:

- **Curated core set** (the ~13 tools that cover the large majority of day-to-day agent workflows — orientation, search, browsing, and the primary write path): `wake_up`, `search`, `list_cell_ids`, `list_documents`, `facet_count`, `get_cell`, `entity_overview`, `add_cell`, `revise_cell`, `bulk_reclassify`, `bulk_tag`, `kg_add`, `search_kg`.
- **Multiplex candidates** (lower-traffic families where several single-purpose tools could plausibly collapse into one parameterized tool per family, trading a larger input schema for a smaller tool list): diary (`diary_read` + `diary_write`), agents (`list_agents` + `register_agent`), saved searches (`list_saved_searches` + `save_search` + `delete_saved_search`), references (`add_reference` + `link_reference` + `reading_list`).

Everything not named above (blueprints, tunnels, tags, attachments, admin/Queen tooling, etc.) would stay as-is under this proposal. No tools have been removed, renamed, or merged as part of this note — it is documentation of a possible future direction, not a change to `ToolPermissionService` or the registered handlers.

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
| Keyword | 0.15 | PostgreSQL full-text search (tsvector, BM25-like). Matching is OR-of-lexemes: any query term hitting the `tsv` column scores > 0 (`ts_rank_cd`), with results containing more of the query's terms ranking higher — it is not an AND-of-terms requirement. |
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

## Saved Searches

The `save_search` / `list_saved_searches` / `delete_saved_search` trio lets the UI persist named filter presets for the Scans explorer.

| Tool | Permission | Behaviour |
|---|---|---|
| `save_search` | writer | Upsert a named search (by `name` per owner). `filter` is a free-form JSON object; defaults to `{}`. |
| `list_saved_searches` | reader | Return all active saved searches for the calling user (`id`, `name`, `filter`, `created_at`). |
| `delete_saved_search` | writer | Soft-delete by `id` (sets `valid_until`). Only the owner can delete. Returns `{id, deleted}`. |

Saved searches are stored in the `saved_searches` table (see [Architecture](architecture.md#data-model)).

## Fact-Based Facets

`facet_count` supports `fact:<predicate>` fields in addition to the standard cell fields. A `fact:<predicate>` facet counts distinct `object` values of active facts with the given predicate that are linked to cells matching the current filters.

**Allow-listed predicates:** `vendor`, `party`, `amount_total`, `value_per_period`, `document_date`, `due_date`, `invoice_number`, `contract_number`.

Example request: `fields: ["fact:vendor", "tag"]` returns both vendor-fact buckets and tag buckets for the filtered document set.

## Per-Document Confidence

`get_cell` and `list_documents` expose a nullable `confidence` field (a `real` in `[0, 1]`) representing the average confidence of all **active** facts linked to the cell (via `source_id`). It is `null` when the cell has no active facts.

- **`get_cell`**: opt-in via `include=['confidence']`.
- **`list_documents`**: `confidence` is always present in each row (nullable).
