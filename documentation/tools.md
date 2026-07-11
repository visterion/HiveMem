# Tools

HiveMem exposes **47 MCP tools** across search, knowledge graph, progressive summarization, agent fleet, references, attachments, saved searches, tag management, and admin. Large file uploads can also use the REST endpoint (`POST /api/attachments`) — see [Attachments](#attachments).

## Feature Overview

- **47 MCP tools** across search, knowledge graph, progressive summarization, agent fleet, references, attachments, saved searches, tag management, and admin
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

**Read (23):**

1. `status`: System overview and counts.
2. `search`: Semantic similarity + keyword search; returns metadata plus `score_total` and `confidence_level` by default and supports `include` for optional fields (including `realm`, `content`, and `scores`). `source` is **not** available via `search.include` (the ranked-search query never selects it) — request `get_cell` for that field; passing it to `search.include` raises `Invalid include field`. `key_points` and `insight` are supported since v9.21.0. By default each hit carries only `score_total` and `confidence_level`; pass `include=['scores']` to also get the six per-signal sub-scores (`score_semantic`, `score_keyword`, `score_recency`, `score_importance`, `score_popularity`, `score_graph_proximity`). Use `profile` to pick a fixed 6-signal weight preset (`balanced` (default, equals the historical weights)|`semantic`|`recent`|`important`|`keyword`); the individual `weight_*` params are soft-deprecated (no longer advertised, still accepted as an escape hatch, and override the corresponding profile weight when passed). Filter with `where` (selector object: `realm` — pass `"none"` to restrict to cells with no realm assigned — `realm_in` — array, may include `"none"` — `signal`, `topic`, `tags` — match-ANY overlap — `status` — `committed`|`pending`|`rejected`|`all`, default `committed`; pass `all` to include every status (committed, pending, rejected) on both the ranked (with `query`) and filter-only browse (without `query`) paths — omitted means committed only); `query` is not allowed inside `where` — the top-level `query` param remains the search text. The flat `realm`/`signal`/`topic`/`tags`/`status` params are soft-deprecated (no longer advertised in the tool schema) but still honored for backward compatibility; they remain mutually exclusive with `where`. `query` is optional (since v9.21.1): if it is blank/absent but a `realm`, `realm_in`, `signal`, `topic`, or `tags` filter is present (via `where` or the flat params), `search` falls back to a filter-only browse — cells matching the filter, newest first (`created_at DESC`), with the same `include` projection (including `key_points`/`insight`) but no ranking at all (no `score_total`, `confidence_level`, or per-signal scores, since there is nothing to rank against, no embedding call is made). If `query` is blank/absent **and** no filter is present, the call still fails with `Missing query` — full-table dumps are not a default.
3. `search_kg`: Knowledge graph triple lookup. Optional param `query` runs semantic search over `facts.embedding` (cosine similarity, returns a `score` field, ordered by score) instead of the default ILIKE filters on `subject`/`predicate`/`object_`; falls back to the ILIKE path if the embedding service is unavailable — in that case the `query` text is matched via an ILIKE fallback (or, if `subject`/`predicate`/`object_` filters were also given, those are used instead) and every returned fact carries `"degraded": true` so callers can tell the result set is not semantically ranked. Unlike `entity_overview`, `search_kg` does **not** resolve its query through the `kg_entity` alias registry (it's free-text/semantic matching on the raw subject) — for canonical facts on a fragmented entity, use `entity_overview` or search on the canonical name directly.
4. `get_cell`: Read a single knowledge item (logs access automatically); supports `include` for optional fields including `content` and `confidence` (per-document average confidence of active facts, nullable `real`; pass `include=['confidence']` to request it).
5. `list`: Navigate the Realm→Signal→Topic→Cell hierarchy (omit all params for realms; add `realm` for signals; add `realm`+`signal` for topics; add `realm`+`signal`+`topic` for cells).
6. `traverse`: Recursive bidirectional graph traversal from a starting cell. Required param: `cell_id`. Optional: `relation_filter`, `max_depth` (default 2, max 10 — lowered from 100 since the recursive CTE re-qualifies every edge at every depth on cyclic graphs, and a 5s statement timeout backstops the query), `max_nodes` (cap on distinct cells in the result, 1–1000, default 200). **Breaking change (response shape):** the tool no longer returns a bare edge array — it returns `{edges: [...], node_count: N, truncated: bool}`. `truncated` is `true` when the `max_nodes` cap or the internal 5000-edge backstop cut the traversal short; nodes are counted in depth order (skip-and-continue once the cap is hit), so the counted nodes are always the closest neighborhood of the start cell — edges between two counted nodes are kept even after the node cap is reached.
7. `time_machine`: Historical knowledge retrieval.
8. `wake_up`: Initial session context. The response includes a `default_language` field (the backend-configured default UI language, set via `HIVEMEM_LANGUAGE`, default `de`).
9. `history`: Trace revisions of a cell or fact (type-dispatched, recursive CTE depth cap 100).
10. `pending_approvals`: List work awaiting review.
11. `get_blueprint`: Narrative realm overviews.
12. `reading_list`: Manage unread/in-progress sources.
13. `list_agents`: View active agent fleet.
14. `diary_read`: Read agent diary entries.
15. `list_attachments`: List all file attachments linked to a cell (metadata only, no file content).
16. `get_attachment_info`: Get metadata for a single attachment by ID. Return fields include `cell_id` (UUID of the extraction cell), `content_uri` (`hivemem://attachments/{id}/content`), `thumbnail_uri` (`hivemem://attachments/{id}/thumbnail` or null), and `page_count` (INTEGER for PDFs, `null` for other types). Download via `GET /api/attachments/{id}/content`.
17. `facet_count`: Aggregate document counts grouped by one or more cell fields. Required param: `fields` (array of one or more of `tag`, `status`, `realm`, `year`, `signal`, or `fact:<predicate>` — e.g. `fact:vendor`, `fact:party`). Optional filters: `realm` (pass `"none"` to restrict to cells with no realm assigned), `signal`, `topic`, `status` (`committed`|`pending`|`rejected`), `query` (full-text/semantic), `tags` (match-ANY), `limit` (max values per field, default 10, max 100). Alternatively, pass `where` (selector object: `realm`, `realm_in` — array, may include `"none"` — `signal`, `topic`, `tags`, `query`, `status`; mutually exclusive with the flat `realm`/`signal`/`topic`/`tags`/`status`/`query` params). When `where` is present and `where.status` is omitted, `status` defaults to `committed`, matching every other where-consumer (list_cell_ids, bulk ops, search); the flat `status` param above is unaffected and still applies no status filter unless given. Returns `{field: [{value, count}, …]}` for each requested field. Allowed `fact:<predicate>` values: `vendor`, `party`, `amount_total`, `value_per_period`, `document_date`, `due_date`, `invoice_number`, `contract_number`.
18. `list_documents`: Browse documents in a realm (no query) with tag/status filters, sort, and paging; joins the extraction-source attachment. Optional params: `realm` (default `documents`; pass `"none"` to list cells with no realm assigned), `signal`, `topic`, `tags` (match-ANY), `status` (`committed`|`pending`|`rejected`|`all`, default `committed`; `all` bypasses the status filter entirely so all statuses are returned — used by the Scans grid so its count basis matches `facet_count`'s unfiltered default), `sort` (`newest`|`oldest`|`title`, default `newest`), `limit` (1–200, default 50), `offset` (0+, default 0). Each row includes a nullable `confidence` (per-document average of active-fact confidence).
19. `list_media`: List image attachments for the photo gallery — committed cells Vision-classified `subtype_photo_general` or `subtype_whiteboard_photo`, joined to their extraction-source image attachment and per-image EXIF metadata (camera, width/height, capture date, GPS, reverse-geocoded place name). Optional params: `realm` (default: all realms; pass `"none"` to restrict to photos with no realm assigned), `sort` (`newest`|`oldest` by capture date, default `newest`), `limit` (1–500, default 100), `offset` (0+, default 0). Each row includes `attachment_id`, `thumbnail_uri`, `content_uri`, `taken_at`, `place_name`, and the EXIF fields. `document_scan` images are excluded (they appear in the Scans view).
20. `list_cell_ids`: List only cell IDs (+realm/signal/topic) matching a filter — the cheap discovery primitive for bulk operations. Optional param: `where` (object with `realm`, `realm_in` — array, may include `"none"` for cells without a realm — `signal`, `topic`, `tags` — match-ANY — `query` — full-text — and `status`, default `committed`; `realm` and `realm_in` are mutually exclusive). Also `limit` (1–1000, default 200) and `offset` (0+, default 0). Returns `{ids: [{id, realm, signal, topic}], total}`.
21. `entity_overview`: Everything about an entity in one call — top-ranked cells, active facts (exact + substring), and depth-1 tunnels of the best cell match. Replaces the `search` + `quick_facts`/`search_kg` + `traverse` triple, and (via `depth=quick`) the former standalone `quick_facts` tool. Required param: `subject` (entity name). Optional: `limit` (per-section limit, 1–20, default 5); `depth` (`quick` = facts only, fast; `full` = cells + facts + tunnels, default). Returns `{cells: [...], facts: [...], tunnels: [...]}`; with `depth=quick`, `cells` and `tunnels` are always empty and only `facts` is populated. Unknown subjects return three empty arrays (no error).
22. `blueprints_missing`: Realms that have active cells but no active blueprint — backlog for orientation docs. No params. Returns `{realms: [{realm, cell_count}]}`.
23. `data_quality_report`: Memory-health report over active cells: cells missing `realm`/`signal`/`topic` (`unclassified`), cells with neither tunnels nor facts (`disconnected`), near-duplicate cell pairs by embedding cosine similarity (`duplicate_clusters`), and knowledge-graph subject fragmentation candidates (`potential_conflicts`). Optional params: `include` (array subset of `unclassified`|`disconnected`|`duplicate_clusters`|`potential_conflicts`, default all four), `threshold` (cosine similarity cutoff for duplicates, range 0.5–1.0, default 0.90), `subject_similarity` (pg_trgm similarity cutoff for ranking candidate subject pairs in `potential_conflicts`, range 0.0–1.0, default 0.3), `limit` (max duplicate pairs for `duplicate_clusters` and max predicates returned for `potential_conflicts`, 1–200, default 50). Out-of-range/invalid values are rejected, not clamped. Each `unclassified` sub-section returns `{count, sample: [{id, realm, signal, topic, summary}]}` (sample capped at 10); `disconnected` returns the same shape; `duplicate_clusters` returns `[{cell_a: {id, summary}, cell_b: {id, summary}, similarity}]`. If the embedding service is unavailable, `duplicate_clusters` is replaced with `{note: "embeddings unavailable"}`. `potential_conflicts` finds predicates with more than one distinct active subject (candidates for subject fragmentation, i.e. the same real-world entity recorded under different subject strings) and returns `[{predicate, subject_count, subjects, subjects_truncated, similar_pairs: [{a, b, similarity}]}]`; `similar_pairs` ranks subject pairs by `pg_trgm` similarity above `subject_similarity`. Predicates with more than 50 distinct subjects skip pairwise ranking for cost reasons — `similar_pairs` is empty, `subjects` is truncated to 50, and `subjects_truncated` is `true`. Use `kg_alias` to register a canonical subject and retro-migrate the fragmented facts, then `kg_add` with `on_conflict=supersede` to resolve any remaining `resulting_conflicts`.

**Write (20):**

24. `add_cell`: Store a cell with content, summary, key points, and insight; optional `dedupe_threshold` runs an embedding-based dedupe gate in one call.
25. `add_tunnel`: Link two cells together.
26. `kg_add`: Fact triple. Optional `on_conflict` gates against active conflicts sharing subject+predicate: `insert` (default, no gate), `return` (report conflicts without inserting), `reject` (error on conflict), `supersede` (invalidate conflicting active facts, then insert; response includes `superseded: N`).
27. `kg_invalidate`: Soft-delete/expire a fact.
28. `kg_rename_predicate`: Rename a predicate across every active fact matching it: invalidates each matching fact and re-adds it under the new predicate, preserving subject, object, confidence, source, status, and the original `valid_from` (only the predicate name changes). Required params: `from`, `to`. Optional: `subject` (narrow to one subject), `confirm` (required `true` above 200 matched facts). Matches are capped at 1000 facts. Returns `{renamed: N, matched: N}`.
29. `kg_alias`: Register alternate names for a canonical entity in the `kg_entity` alias registry and retro-migrate existing facts onto the canonical subject. Required params: `canonical` (canonical subject name), `aliases` (string array of alternate names). Optional: `confirm` (required `true` when more than 200 facts would be migrated). For each alias, invalidates matching facts (regardless of status — active, pending, or already-invalidated) and re-adds them under `canonical`, preserving predicate, object, confidence, source, and the original `valid_from`. Matches are capped at 1000 facts; exceeding the cap errors and asks the caller to narrow the aliases. Once registered, `kg_add` resolves its `subject` through the registry before writing (so new facts under any alias land on the canonical subject too), and `entity_overview` resolves a queried subject through the registry before reading. Returns `{registered, migrated, resulting_conflicts}`: `registered` is a boolean confirming the alias entry was upserted, `migrated` is the number of facts moved to the canonical subject, and `resulting_conflicts` is the count of active `(subject, predicate)` groups on the canonical subject that still have more than one active fact after migration (including pre-existing conflicts) — resolve these with `kg_add on_conflict=supersede`.
30. `update_identity`: Update session context facts.
31. `add_reference`: Store source documents/URLs.
32. `link_reference`: Cite source for a cell.
33. `remove_tunnel`: Expire a cell link.
34. `revise_cell`: Create a new version of a cell.
35. `revise_fact`: Create a new version of a fact.
36. `register_agent`: Add an agent to the fleet.
37. `diary_write`: Agent-private reflection tool.
38. `update_blueprint`: Update realm narrative.
39. `reclassify`: Reclassify one or more cells in-place (realm/signal/topic) without creating a new revision. Leaves content, embeddings, tunnels, facts, and references untouched — use for taxonomy migrations. Exactly one of `cell_ids` (UUID array; single cell = `cell_ids:[id]`) or `where` (selector object, same shape as `list_cell_ids`: `realm`, `realm_in`, `signal`, `topic`, `tags`, `query`, `status`; `"none"` realm matches cells without a realm) must be provided. Optional: `realm`, `signal` (`facts`|`events`|`discoveries`|`preferences`|`advice`), `topic` — at least one required. `where` matches are capped at 1000 cells; matches over 200 require `confirm: true`. A `where` selector with `status: "rejected"` is not reclassifiable — the live-version guard throws and rolls back the whole batch. Returns `{updated: N, matched: N}`.
40. `reject_cell`: Mark a cell as rejected (soft-delete). Sets the live committed or pending version to `status=rejected` so it drops out of search; targets the live version only (a closed/non-current version throws). Idempotent — rejecting an already-rejected cell is a no-op. Does **not** cascade to the cell's facts or tunnels. Required param: `cell_id` (UUID). Optional: `reason` (recorded in the op-log, e.g. `"duplicate of <id>"`). Returns `{id, status: "rejected"}`.
41. `upload_attachment`: Upload a file attachment (Base64-encoded). Required params: `realm` (target realm), `data` (Base64 payload), `filename`, `mime_type` (e.g. `application/pdf`, `image/png`, `message/rfc822`). Optional: `signal`, `topic`, `cell_id` (existing cell — creates a `related_to` tunnel). Always creates a new `pending` Cell whose content is the extracted text (or the filename if no text could be extracted); the Classifier agent enriches the cell asynchronously. Stores original in SeaweedFS, generates JPEG thumbnail at ingest. Returns `{ attachment_id, cell_id, mime_type, size_bytes, has_thumbnail }`. For files larger than ~1 MB prefer `POST /api/attachments` (multipart) — Base64 inflates the JSON-RPC payload by ~33% — see [Attachments](#attachments).
42. `saved_searches`: Manage the calling user's saved searches (action-multiplexed). **writer** role — this merged tool replaces the former `save_search`/`delete_saved_search`/`list_saved_searches` trio, and moves listing from `reader` into `writer` (readers no longer see saved searches). Required param: `action` (`save`|`delete`|`list`). `action=save` requires `name` (human-readable label) and optionally `filter` (JSON object/string describing the filter state; defaults to `{}`); upserts by name. `action=delete` requires `id` (UUID); soft-delete, owner-scoped; returns `{id, deleted}`. `action=list` returns all active saved searches for the caller (`id`, `name`, `filter`, `created_at`). Return shape depends on `action`.
43. `manage_tags`: Add and/or remove tags on one or more cells in a single transaction. Exactly one of `cell_ids` (UUID array; single cell = `cell_ids:[id]`) or `where` (selector object, same shape as `list_cell_ids`: `realm`, `realm_in`, `signal`, `topic`, `tags`, `query`, `status`; `"none"` realm matches cells without a realm) must be provided. Optional: `add` (string array), `remove` (string array) — at least one required. `where` matches are capped at 1000 cells; matches over 200 require `confirm: true`. Operations are idempotent. Returns `{updated: N, matched: N}` — `matched` is the number of cells the selector/list resolved to, `updated` the number actually modified.

**Admin (4):**

44. `approve_pending`: Admin tool to batch approve or reject agent writes.
45. `health`: Monitor DB and service state.
46. `queen_runs`: List recent Queen/Bee agent runs from Vistierie. Optional args: `limit` (1–200, default 50), `offset` (0+, default 0). Returns `{items:[{id,agent,trigger,status,startedAt,finishedAt,durationMs,llmCalls,costMicros}], total, costAvailable}`; on Vistierie outage returns `{items:[],total:0,costAvailable:false,unavailable:true}`. Cost fields (`llmCalls`, `costMicros`) are populated only when `HIVEMEM_QUEEN_VISTIERIE_ADMIN_TOKEN` is configured.
47. `queen_run_detail`: Fetch full detail for a single Queen/Bee run. Required arg: `run_id` (string). Returns `{run:{...}, events:[{type,...}]}` (run metadata + Vistierie event timeline); on outage returns `{run:{},events:[],unavailable:true}`.

## Tool-surface recommendation

**Implemented (tool-consolidation round):** the tag, reclassify, saved-search, and quick-facts families have been collapsed into single tools. `add_tags`/`remove_tags`/`bulk_tag` → `manage_tags`; `reclassify_cell`/`bulk_reclassify` → `reclassify`; `save_search`/`delete_saved_search`/`list_saved_searches` → the action-multiplexed `saved_searches`; and the standalone `quick_facts` was folded into `entity_overview` (`depth=quick`). This merged 9 tools into 4 and brought the surface to the tool count documented above.

The remaining surface has still grown organically over several rounds of feature work. If it is ever pared down further for agent ergonomics (fewer tools to reason about per call), a natural split is:

- **Curated core set** (the ~13 tools that cover the large majority of day-to-day agent workflows — orientation, search, browsing, and the primary write path): `wake_up`, `search`, `list_cell_ids`, `list_documents`, `facet_count`, `get_cell`, `entity_overview`, `add_cell`, `revise_cell`, `reclassify`, `manage_tags`, `kg_add`, `search_kg`.
- **Remaining multiplex candidates** (lower-traffic families where several single-purpose tools could still plausibly collapse into one parameterized tool per family, trading a larger input schema for a smaller tool list): diary (`diary_read` + `diary_write`), agents (`list_agents` + `register_agent`), references (`add_reference` + `link_reference` + `reading_list`).

Everything not named above (blueprints, tunnels, attachments, admin/Queen tooling, etc.) stays as-is; consolidating the remaining candidates is a possible future direction, not yet a change to `ToolPermissionService` or the registered handlers.

## Attachments

In addition to the MCP tools above (`upload_attachment`, `list_attachments`, `get_attachment_info`), HiveMem exposes a parallel REST API under `/api/attachments` for efficient binary transfers — the JSON-RPC `upload_attachment` requires base64 encoding, which is wasteful for large files. The REST endpoints use the same token authentication as MCP.

| Endpoint | Purpose |
|---|---|
| `POST /api/attachments` (multipart) | Upload a file. Required form fields: `realm`, `file`. Optional: `signal`, `topic`, `cell_id` (creates a `related_to` tunnel to that cell). Same downstream behaviour as `upload_attachment`. |
| `GET /api/attachments/{id}/content` | Download the original binary (supports HTTP `Range` requests, e.g. for PDF viewers). |
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
| Graph proximity | 0.10 | Boost for cells reachable from the top semantic candidates via tunnels in either direction (depth ≤ 2). Per-relation weights default to `builds_on=1.0`, `refines=0.8`, `related_to=0.6`, `contradicts=0.4`. |

Baseline weights are configurable via `hivemem.search.weights` in `application.yml`. Per call, choose a **`profile`** preset instead of tuning individual weights:

| Profile | Emphasis |
|---|---|
| `balanced` (default) | The historical defaults above (semantic 0.30, keyword 0.15, recency 0.15, importance 0.15, popularity 0.15, graph_proximity 0.10). |
| `semantic` | Leans on vector similarity. |
| `recent` | Leans on recency. |
| `important` | Leans on user/agent importance. |
| `keyword` | Leans on full-text keyword matching. |

The six `weight_*` arguments (`weight_semantic`, `weight_keyword`, `weight_recency`, `weight_importance`, `weight_popularity`, `weight_graph_proximity`) are **soft-deprecated**: no longer advertised in the tool schema, but still accepted as an escape hatch. An explicit `weight_*` overrides the corresponding weight from the selected profile.

#### Confidence Level

Each search result includes a `confidence_level` field. It is **relative to the returned result-set** (not an absolute threshold): the mean and population standard deviation (`sigma`) of the `score_total` values across the returned hits are computed once per result-set, and each hit is classified against those bands:

| Level | Condition |
|-------|-----------|
| `NONE` | `score_total < floor`, **or** the result-set has fewer than 2 elements |
| `HIGH` | `score_total >= mean + sigma` |
| `LOW` | `score_total < mean` |
| `MEDIUM` | otherwise (also when `sigma == 0` for all above-floor hits) |

The absolute `floor` is configurable via `hivemem.search.confidence.floor` in `application.yml` (default `0.20`); anything below it is `NONE` regardless of the relative bands. There are no longer any `high`/`medium`/`low` threshold properties.
`score_total` (the raw numeric value) and `confidence_level` are always present on every hit.

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

The single action-multiplexed `saved_searches` tool (**writer** role) lets the UI persist named filter presets for the Scans explorer. It replaces the former `save_search` / `list_saved_searches` / `delete_saved_search` trio — note that listing moved from `reader` to `writer`, so readers no longer see saved searches.

| `action` | Behaviour |
|---|---|
| `save` | Upsert a named search (by `name` per owner). Requires `name`; optional `filter` is a free-form JSON object/string, defaults to `{}`. Returns the new row. |
| `list` | Return all active saved searches for the calling user (`id`, `name`, `filter`, `created_at`). |
| `delete` | Soft-delete by `id` (sets `valid_until`). Only the owner can delete. Returns `{id, deleted}`. |

Saved searches are stored in the `saved_searches` table (see [Architecture](architecture.md#data-model)).

## Fact-Based Facets

`facet_count` supports `fact:<predicate>` fields in addition to the standard cell fields. A `fact:<predicate>` facet counts distinct `object` values of active facts with the given predicate that are linked to cells matching the current filters.

**Allow-listed predicates:** `vendor`, `party`, `amount_total`, `value_per_period`, `document_date`, `due_date`, `invoice_number`, `contract_number`.

Example request: `fields: ["fact:vendor", "tag"]` returns both vendor-fact buckets and tag buckets for the filtered document set.

## Per-Document Confidence

`get_cell` and `list_documents` expose a nullable `confidence` field (a `real` in `[0, 1]`) representing the average confidence of all **active** facts linked to the cell (via `source_id`). It is `null` when the cell has no active facts.

- **`get_cell`**: opt-in via `include=['confidence']`.
- **`list_documents`**: `confidence` is always present in each row (nullable).
