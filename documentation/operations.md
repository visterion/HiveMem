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

```bash
# Set required env vars first:
export HIVEMEM_JDBC_URL=jdbc:postgresql://postgres:5432/hivemem
export HIVEMEM_DB_USER=hivemem
export HIVEMEM_DB_PASSWORD=secret
export HIVEMEM_EMBEDDING_URL=http://embeddings:8081
export HIVEMEM_API_TOKEN=your-admin-token

./deploy.sh java
```

The script builds the Docker image, restarts the container, and waits for a successful health check on `/mcp`.

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

## Attachment Storage (SeaweedFS)

Attachment storage is optional. Set `HIVEMEM_ATTACHMENT_ENABLED=true` to enable.

### Environment variables

| Variable | Default | Description |
|---|---|---|
| `HIVEMEM_ATTACHMENT_ENABLED` | `false` | Enable attachment storage |
| `SEAWEEDFS_S3_ENDPOINT` | `http://localhost:8333` | SeaweedFS S3 API endpoint |
| `SEAWEEDFS_S3_BUCKET` | `hivemem-attachments` | S3 bucket name |
| `SEAWEEDFS_S3_ACCESS_KEY` | `hivemem` | S3 access key |
| `SEAWEEDFS_S3_SECRET_KEY` | `hivemem_secret` | S3 secret key |

### Backup

The `seaweedfs_data` Docker volume must be backed up together with the PostgreSQL dump. The `attachments` table references objects by their S3 keys — a DB backup without the volume (or vice versa) results in broken references.

### Deployment

SeaweedFS is included in `docker-compose.yml` as a sidecar service. No additional configuration needed for the default setup.

> **Note:** The S3 client runs with `chunkedEncodingEnabled(false)`. SeaweedFS does not decode SigV4 streaming (`aws-chunked`) request bodies, so the AWS SDK's default chunked signing would otherwise bake the chunk framing into the stored object and corrupt every JPEG/PDF. Do not re-enable it.

### Repairing aws-chunked-corrupted objects

Objects uploaded before the `chunkedEncodingEnabled(false)` fix carry the `aws-chunked` framing inside the stored bytes (thumbnails render black, PDFs won't open). Repair them once, **after** deploying the fixed image, with an admin token:

```bash
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  https://<host>/admin/attachments/repair-chunked
# → {"scanned":N,"repaired_originals":x,"repaired_thumbnails":y,"failed":0}
```

The sweep is idempotent — it detects and skips already-clean objects, so it is safe to re-run.

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
HIVEMEM_QUEEN_HIVEMEM_BASE_URL=http://hivemem:8080
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
