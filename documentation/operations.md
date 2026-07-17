# Operations

## Backups

For full instance portability (Postgres + SeaweedFS attachments + manifest in one tar.gz, with `--mode=move` and `--mode=clone` restore), use the dedicated backup CLI documented in [Backup + Portability](backup.md). Quick form:

```bash
java -jar app.jar --spring.profiles.active=backup \
    backup export --out /var/lib/hivemem/exports/backup.tar.gz
```

For a quick Postgres-only safety net (no attachments, no manifest), the raw `pg_dump` route is still available — but `backup.md` is the canonical mechanism for disaster recovery and host migration:

```bash
# Postgres-only quick dump (no attachments)
docker exec hivemem-db pg_dump -U hivemem hivemem | gzip > "hivemem-$(date +%Y%m%d).sql.gz"
```

The Postgres-only dump alone is **not** sufficient if attachments are enabled — the `attachments` table references SeaweedFS objects by S3 key.

## Run Tests

Tests use [Testcontainers](https://java.testcontainers.org/) — a `pgvector/pgvector:pg17` container is started and destroyed per session. Embeddings are stubbed with a fixed test client (deterministic vectors, no external service needed).

```bash
cd java-server
mvn test
```

The exact test count changes over time; use the CI badge and workflow runs as the current source of truth.

## Deploy Changes

Deployment is compose-based. `docker-compose.yml` wires the services together on the
`hivemem-net` network (`hivemem-db`, `hivemem-embeddings`, `seaweedfs`, `hivemem` on
port `8421`) and has **no committed secret defaults** — put the secrets in a `.env`
file next to the compose file (gitignored):

```bash
# .env (next to docker-compose.yml)
HIVEMEM_DB_PASSWORD=<strong password>
SEAWEEDFS_S3_ACCESS_KEY=<access key>
SEAWEEDFS_S3_SECRET_KEY=<secret key>
```

The SeaweedFS credentials must match `seaweedfs/s3.json`, which you create once from
`seaweedfs/s3.json.template` (the real file is gitignored).

**Local build + deploy** (builds the image from source via the multi-stage Dockerfile,
then recreates the stack — dependencies come up first via their healthchecks):

```bash
./deploy.sh java
```

The script builds the image, runs `docker compose up -d`, and waits for HTTP 200 on
`http://localhost:8421/login`. To roll back, `docker compose up -d` with the previously
working image (compose pins the image name; earlier builds remain in the local image
store under their image IDs).

**Production** pulls a pinned release tag (e.g. `9.3.0`) from GHCR instead of building —
bump the tag in the production compose file and `docker compose pull && docker compose
up -d hivemem`.

## Migrations

Schema changes are managed by [Flyway](https://flywaydb.org/). Migrations run automatically at Spring Boot application startup.

Migration files live in `java-server/src/main/resources/db/migration/` using the Flyway naming convention (`V0001__description.sql`, `V0002__description.sql`, etc.).

To add a new migration:

```bash
cat > java-server/src/main/resources/db/migration/V0009__my_feature.sql << 'EOF'
CREATE TABLE IF NOT EXISTS my_table (...);
EOF
```

Deploy the application — Flyway applies pending migrations on startup.

## Enabling OAuth (MCP Custom Connectors)

HiveMem's OAuth 2.0 authorization server (used by Claude.ai / ChatGPT Custom Connectors) is
**disabled by default**. While `HIVEMEM_OAUTH_ENABLED` is `false`, all `/oauth/*` and
`/.well-known/oauth-*` endpoints return `404`. A non-blank `HIVEMEM_OAUTH_ISSUER` is
additionally required for OAuth to *function* — with `enabled=true` but a blank issuer the
discovery documents return `200` with an empty issuer and the `/mcp` `WWW-Authenticate`
header is suppressed, so the connector flow will not work. For the full connector flow and
security model see [OAuth 2.0 + Custom Connector Setup](oauth.md).

| Variable | Default | Description |
|---|---|---|
| `HIVEMEM_OAUTH_ENABLED` | `false` | Set to `true` to activate the OAuth endpoints |
| `HIVEMEM_OAUTH_ISSUER` | *(empty)* | The **public HTTPS origin** the server is reached at |

`HIVEMEM_OAUTH_ISSUER` **must** be the public HTTPS origin (e.g. `https://mem.ufelmann.com`),
not the internal container URL. It flows verbatim into the discovery metadata (`issuer`,
`authorization_endpoint`, `token_endpoint`, …) and into the `iss` of issued tokens, so any
mismatch fails client-side validation.

Add both under the `hivemem` service's `environment:` block in `/opt/hivemem/docker-compose.yml`:

```yaml
services:
  hivemem:
    environment:
      HIVEMEM_OAUTH_ENABLED: "true"
      HIVEMEM_OAUTH_ISSUER: "https://mem.ufelmann.com"
```

Recreate only the `hivemem` service (this is an outward-facing change — get explicit
authorization first):

```bash
cd /opt/hivemem && docker compose up -d hivemem
```

Verify the discovery endpoint responds with the configured issuer:

```bash
curl -s https://mem.ufelmann.com/.well-known/oauth-authorization-server | jq .issuer
# → "https://mem.ufelmann.com"   (HTTP 200)
```

If OAuth is disabled, this returns `404`.

## Enabling Cloudflare Access (human auth hardening)

HiveMem can authenticate humans (the SPA) with a Cloudflare Access JWT instead of a session
cookie/login form. This is **off by default** (`HIVEMEM_ACCESS_ENABLED=false`) — the legacy
session-login flow is the default and remains a fully supported production mode for
self-hosted deployments without Cloudflare in front. Machine paths (`/mcp`, `/hooks`, `/sync`,
`/vistierie`) are unaffected either way — they always use a bearer token. See
[Authentication → Human auth vs. machine auth](auth.md#human-auth-vs-machine-auth) for the
full path matrix and status-code behavior.

| Variable | Default | Description |
|---|---|---|
| `HIVEMEM_ACCESS_ENABLED` | `false` | Set to `true` to require an Access JWT for humans and disable session login (`/login`, `/logout` return `410 Gone`) |
| `HIVEMEM_ACCESS_TEAM_DOMAIN` | *(empty)* | Your Cloudflare Access team domain, e.g. `https://<your-team>.cloudflareaccess.com` — used to fetch the JWKS and verify the JWT issuer |
| `HIVEMEM_ACCESS_AUD` | *(empty)* | The Access application's Audience (AUD) tag — verified against the JWT's `aud` claim |
| `HIVEMEM_ACCESS_JWKS_TTL` | `PT15M` | How long the fetched JWKS is cached before HiveMem re-fetches it (ISO-8601 duration) |

With `enabled=true`, a blank `team-domain` or `audience` fails application startup — this is a
fail-closed check: there is no state where Access "believes" it's on but nothing actually
verifies the JWT.

### Rollout order — do not enable before the Access application exists

**Enabling `HIVEMEM_ACCESS_ENABLED=true` before the Cloudflare Access application and its
bypass policies exist locks the GUI out** — session login is disabled the moment `enabled=true`
takes effect, and if Access isn't actually in front of the origin yet, nothing else can prove
who you are. Recovery requires shell access to the host running the container. Follow this
order:

1. **Create the Access application in the Cloudflare dashboard first.** Note the Audience
   (AUD) tag. Add bypass ("Skip identity verification") policies for the **machine paths** —
   these must never require an Access login, because MCP clients, hooks, peer sync, and
   webhooks can't present one:
   - `/mcp`
   - `/hooks`
   - `/sync`
   - `/vistierie`
   - `/admin/peers**` (CLI/scripts only, e.g. `connect-peers.sh`; the rest of `/admin` stays
     protected)
   - `/oauth/token`, `/oauth/register`
   - `/.well-known/*`
   - `/api/config` (the SPA needs this to learn which auth mode it's in before it can
     authenticate at all)
   - the PWA shell assets (manifest, service worker, icons)

   Everything else — `/api/**`, SPA routes, `/admin/**` except `/admin/peers**`,
   `/oauth/authorize` — stays behind the Access policy. Test the policy before moving on.
2. **Deploy the updated backend + SPA with `HIVEMEM_ACCESS_ENABLED` still `false`.** This
   activates the path split (`/api/tools/call`, `/mcp` no longer accepting a session) in legacy
   mode, where it can be verified against the existing session-login flow before Access is live.
3. **Map a human's email to their `api_tokens` row** with the `hivemem-token` CLI, run inside
   the container:

   ```bash
   docker exec hivemem hivemem-token set-email <token-name> you@example.com
   ```

   This is how HiveMem authorizes an Access-authenticated human — the JWT only proves an email,
   this mapping supplies the role and realm scoping. **Warning:** an `api_tokens` row is
   dual-use — it carries both the bearer token (for CLI/MCP use) and, once mapped, the email
   (for GUI use). Re-running `set-email` to move an email onto a *different* row auto-revokes
   the previous row's bearer token as part of the same transaction (the partial unique index on
   `lower(email)` requires it) — if that bearer token is still in active use elsewhere, reissue
   it afterward. Reassigning the *same* row's email again is a no-op in this regard.
4. **Set `HIVEMEM_ACCESS_TEAM_DOMAIN` and `HIVEMEM_ACCESS_AUD`** in the environment (do not set
   `enabled=true` yet).
5. **Set `HIVEMEM_ACCESS_ENABLED=true`** and recreate the `hivemem` service. This is the step
   that actually locks out session login — only take it once steps 1–4 are verified.
6. Verify from every device that needs access, including any installed PWA (iOS/Android
   home-screen shells re-check auth independently of a desktop browser tab).

## Attachment Storage (SeaweedFS)

Attachment storage is optional. Set `HIVEMEM_ATTACHMENT_ENABLED=true` to enable.

### Environment variables

| Variable | Default | Description |
|---|---|---|
| `HIVEMEM_ATTACHMENT_ENABLED` | `false` | Enable attachment storage |
| `SEAWEEDFS_S3_ENDPOINT` | `http://localhost:8333` | SeaweedFS S3 API endpoint |
| `SEAWEEDFS_S3_BUCKET` | `hivemem-attachments` | S3 bucket name |
| `SEAWEEDFS_S3_ACCESS_KEY` | *(empty — required)* | S3 access key; must match `seaweedfs/s3.json` |
| `SEAWEEDFS_S3_SECRET_KEY` | *(empty — required)* | S3 secret key; must match `seaweedfs/s3.json` |

### Backup

The `seaweedfs_data` Docker volume must be backed up together with the PostgreSQL dump. The `attachments` table references objects by their S3 keys — a DB backup without the volume (or vice versa) results in broken references.

### Deployment

SeaweedFS is included in `docker-compose.yml` as a sidecar service. Create
`seaweedfs/s3.json` from `seaweedfs/s3.json.template` with the same credentials you set
in `SEAWEEDFS_S3_ACCESS_KEY` / `SEAWEEDFS_S3_SECRET_KEY`; the compose file mounts it
read-only. The S3 port (`8333`) is **not** published to the host — only the `hivemem`
service reaches it over the compose network. For ad-hoc inspection use `docker exec`
inside a container on `hivemem-net`, or temporarily publish the port.

> **Note:** The S3 client runs with `chunkedEncodingEnabled(false)`. SeaweedFS does not decode SigV4 streaming (`aws-chunked`) request bodies, so the AWS SDK's default chunked signing would otherwise bake the chunk framing into the stored object and corrupt every JPEG/PDF. Do not re-enable it.

### Repairing aws-chunked-corrupted objects

Objects uploaded before the `chunkedEncodingEnabled(false)` fix carry the `aws-chunked` framing inside the stored bytes (thumbnails render black, PDFs won't open). Repair them once, **after** deploying the fixed image, with an admin token:

```bash
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  https://<host>/admin/attachments/repair-chunked
# → {"scanned":N,"repaired_originals":x,"repaired_thumbnails":y,"failed":0}
```

The sweep is idempotent — it detects and skips already-clean objects, so it is safe to re-run.

## Consumption (Bulk Import)

Drop scan files (PDF, images) into the consumption watch directory. The pipeline picks them up automatically.

### Page limit

PDFs larger than `hivemem.consumption.max-pages` (default 200) are moved straight to `failed/` at intake. Split oversized PDFs before dropping them in.

### Automatic recovery

The `ConsumptionRecoverySweep` runs at startup and every 5 minutes (configurable). It handles two failure modes without operator action:

- **Crash-stranded files** — files whose ledger row is still `processing` past `hivemem.consumption.recovery-stale-threshold` (default 30 min) are re-staged automatically. This covers files that were mid-ingest when the container was restarted.
- **Failed files** — files in `failed/` are moved back to the watch root and retried as long as their attempt count is below `hivemem.consumption.failed-retry-limit` (default 3). Files that exhaust the retry limit remain in `failed/` for manual inspection.

### Embedding backfill

If the embedding service is unavailable when a cell is committed, the cell is tagged `embedding_pending` and committed without a vector. `EmbeddingBackfillService` finds all such cells every `hivemem.embedding.backfill-interval-ms` (default 5 min) and backfills their embeddings once the service recovers. Semantic search is restored within one backfill cycle — no operator action needed.

### Verifying completeness

After a bulk import, check the ledger and the `failed/` directory:

```sql
-- All files should be 'done'; any 'failed' or 'processing' rows need attention
SELECT state, count(*) FROM consumption_file GROUP BY state;
```

```bash
# Inspect any terminally failed files
ls /path/to/consumption/failed/
```

For embedding coverage, count cells with the `embedding_pending` tag — it should drop to zero within one backfill cycle after the embedding service is healthy.

### Configuration

| Property | Default | Description |
|---|---|---|
| `hivemem.consumption.recovery-interval` | `5m` | How often the recovery sweep runs |
| `hivemem.consumption.recovery-stale-threshold` | `30m` | How long a `processing` ledger row must be stale before it is re-staged |
| `hivemem.consumption.failed-retry-limit` | `3` | Max retry attempts for files in `failed/` before they are left there permanently |
| `hivemem.embedding.timeout` | `30s` | HTTP timeout per embedding request |
| `hivemem.embedding.max-retries` | `3` | Retry attempts before the embedding request is abandoned |
| `hivemem.embedding.retry-backoff-ms` | `500` | Base backoff (ms, exponential) between embedding retries |
| `hivemem.embedding.backfill-interval-ms` | `300000` | How often the embedding backfill sweep runs (ms) |
| `hivemem.embedding.backfill-batch-size` | `50` | Max cells processed per backfill sweep |

## Debugging

```bash
docker logs hivemem --tail 50  # Container logs
```

### Re-scanning the same document

A physical re-scan produces different bytes (so SHA-256 dedup does not catch it), but the content dedup discards the new copy and keeps the original (a `duplicate_of` tunnel records the link; the discarded cell is soft-deleted, its unique S3 object removed). Dedup runs once the cell's embedding exists: for short documents (≤500 chars) that is at OCR time, but long OCR'd documents are embedded only after the summarizer produces a summary, so for those dedup runs in the scheduled summarizer (every 5 min) — a re-scan of a long document is therefore deduped on the next summarizer cycle, not instantly. Only `consumption:`-sourced cells are ever discarded. If legitimately distinct documents are being merged, raise `hivemem.consumption.dedup.text-threshold` (and/or `recall-threshold`); to turn the feature off set `hivemem.consumption.dedup.enabled=false`.

### Self-healing embedding backfill

On startup, any live committed cell with a NULL embedding and content longer than 500 characters is tagged `needs_summary`; the scheduled summarizer (every 5 min) then generates its summary and re-embeds it. This is automatic and idempotent — no operator action is needed. It restores semantic search for scans that previously missed their embedding (long OCR'd documents are embedded only after summarization).

### Retro-dedup of existing scans

`POST /admin/dedup-backfill` walks existing live `consumption:`-sourced cells oldest→newest and discards re-scans (same soft-delete + `duplicate_of` tunnel + S3 cleanup as live dedup). Run it **only after embeddings have been backfilled** — give the startup `needs_summary` tagging plus at least one 5-min summarizer cycle to embed the long scans first, otherwise they have no embedding to match on. This is a production write — get explicit authorization before running it.

```bash
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  https://<host>/admin/dedup-backfill
# → {"checked":N,"discarded":x}
```

## Queen + Bees on Vistierie (LXC 102)

### Prerequisites

HiveMem and Vistierie must share the external `hivemem-net` Docker network. This network is already used for summarizer and vision LLM calls, so no additional network configuration is needed.

HiveMem authenticates to Vistierie as tenant `hivemem` using the existing `HIVEMEM_VISTIERIE_TOKEN`. Reuse that token — no new Vistierie credential is needed for Queen/Bee registration.

### 1 — Set Vistierie budgets (mandatory)

Budgets are required. Without them, every cron tick from Vistierie returns 403 and the Queen never runs.

```bash
# Set tenant-level budget
curl -X PATCH http://vistierie:8090/admin/tenants/hivemem/budget \
  -H "Authorization: Bearer $VISTIERIE_ADMIN_TOKEN" \
  -H 'content-type: application/json' \
  -d '{"daily_cap_micros":2000000,"monthly_cap_micros":20000000}'

# Set per-agent budgets for both agents
for a in queen isolated-cell-bee; do
  curl -X PATCH http://vistierie:8090/admin/tenants/hivemem/agents/$a/budget \
    -H "Authorization: Bearer $VISTIERIE_ADMIN_TOKEN" \
    -H 'content-type: application/json' \
    -d '{"daily_cap_micros":1000000,"monthly_cap_micros":10000000}'
done
```

### 2 — Enable the Queen in HiveMem

Add the following environment variables to your HiveMem deployment and restart the container. On startup HiveMem registers `isolated-cell-bee` and then `queen` in Vistierie (idempotent; safe to restart at any time).

```
HIVEMEM_QUEEN_ENABLED=true
HIVEMEM_QUEEN_HIVEMEM_BASE_URL=http://hivemem:8421
HIVEMEM_QUEEN_WEBHOOK_TOKEN=<strong random token>
HIVEMEM_QUEEN_COMPLETION_WEBHOOK_TOKEN=<second strong random token>
HIVEMEM_QUEEN_SCHEDULE=0 0 3 * * *
```

Use distinct, independently-rotatable tokens for `HIVEMEM_QUEEN_WEBHOOK_TOKEN` (read-only tool webhooks) and `HIVEMEM_QUEEN_COMPLETION_WEBHOOK_TOKEN` (result ingestion).

| Variable | Required | Description |
|---|---|---|
| `HIVEMEM_QUEEN_ENABLED` | Yes | Set to `true` to activate Queen + Bee registration on startup |
| `HIVEMEM_QUEEN_HIVEMEM_BASE_URL` | Yes | Base URL HiveMem advertises to Vistierie for webhook callbacks |
| `HIVEMEM_QUEEN_WEBHOOK_TOKEN` | Yes | Bearer token for read-only tool webhooks (`/vistierie/tools/**`) |
| `HIVEMEM_QUEEN_COMPLETION_WEBHOOK_TOKEN` | Yes | Bearer token for result ingestion (`/vistierie/runs/done`) |
| `HIVEMEM_QUEEN_SCHEDULE` | Yes | Cron expression controlling how often the Queen runs (e.g. `0 0 3 * * *`) |
| `HIVEMEM_QUEEN_VISTIERIE_ADMIN_TOKEN` | No | Vistierie admin token. When set, the Queen-Log UI shows per-run cost + LLM-call counts via `GET /admin/runs`. Unset → cost columns hidden (falls back to tenant `GET /runs`, no cost data). |

### 3 — Verify registration

```bash
curl http://vistierie:8090/agents \
  -H "Authorization: Bearer $HIVEMEM_VISTIERIE_TOKEN"
```

Expect the response to include entries for `isolated-cell-bee` and `queen`.

### 4 — Optional smoke test (manual trigger)

```bash
curl -X POST http://vistierie:8090/agents/queen/run \
  -H "Authorization: Bearer $HIVEMEM_VISTIERIE_TOKEN" \
  -H 'content-type: application/json' \
  -d '{"payload":{}}'
```

Then check HiveMem's pending approvals (`approve_pending` MCP tool or the admin UI) for new `pending` tunnel proposals from the Bee.

### Kill switch

To halt all Queen and Bee activity immediately:

```bash
curl -X POST http://vistierie:8090/admin/tenants/hivemem/kill \
  -H "Authorization: Bearer $VISTIERIE_ADMIN_TOKEN"
```

Re-enable by removing the kill flag via the Vistierie admin API, then restarting HiveMem (re-registration is idempotent).
