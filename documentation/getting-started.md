# Getting Started

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) (v20+)
- An external PostgreSQL database with pgvector extension (e.g. `pgvector/pgvector:pg17`)
- An external embeddings service reachable via HTTP (see below)

## Embedding Service

HiveMem requires an external embedding service. An ONNX-based service is included in `embedding-service/` and can be configured via environment variables instead of code changes.

The service must expose:
- `POST /embeddings` — `{"text": "...", "mode": "document"}` → `{"vector": [...], "model": "...", "dimension": N}`
- `GET /info` — `{"model": "...", "dimension": N}` (used by HiveMem for model change detection)

**Automatic reencoding:** When HiveMem detects a model change at startup (different model name or dimension), it automatically backs up the database, re-encodes all cells, and rebuilds the HNSW index. Search is blocked (503) during reencoding.

Key environment variables:
- `MODEL_PATH` — mounted directory with local model files; preferred for manual installs
- `MODEL_REPO` — HF repo used when `MODEL_PATH` is unset
- `MODEL_NAME` — model identifier reported by `/info`
- `ONNX_FILE` / `TOKENIZER_FILE` — optional explicit filenames inside the model directory
- `POOLING` — `mean` or `cls`
- `MAX_LENGTH` — tokenizer truncation/padding length
- `QUERY_PREFIX` / `DOCUMENT_PREFIX` — optional retrieval prefixes

To build the embedding service:

```bash
cd embedding-service
docker build -t hivemem-embeddings .
```

## Quick Start

No clone needed. Save this as `docker-compose.yml` and run `docker compose up -d`:

```yaml
services:
  hivemem-db:
    image: pgvector/pgvector:pg17
    container_name: hivemem-db
    environment:
      POSTGRES_DB: hivemem
      POSTGRES_USER: hivemem
      POSTGRES_PASSWORD: ${HIVEMEM_DB_PASSWORD:-changeme}
    volumes:
      - hivemem-pgdata:/var/lib/postgresql/data
    networks:
      - hivemem-net
    restart: unless-stopped

  hivemem-embeddings:
    image: ghcr.io/visterion/hivemem-embeddings:latest
    container_name: hivemem-embeddings
    volumes:
      - hivemem-embeddings-models:/app/models
    networks:
      - hivemem-net
    restart: unless-stopped

  hivemem:
    image: ghcr.io/visterion/hivemem:latest
    container_name: hivemem
    ports:
      - "8421:8421"
    environment:
      HIVEMEM_JDBC_URL: jdbc:postgresql://hivemem-db:5432/hivemem
      HIVEMEM_DB_USER: hivemem
      HIVEMEM_DB_PASSWORD: ${HIVEMEM_DB_PASSWORD:-changeme}
      HIVEMEM_EMBEDDING_URL: http://hivemem-embeddings:80
    depends_on:
      - hivemem-db
      - hivemem-embeddings
    networks:
      - hivemem-net
    restart: unless-stopped

networks:
  hivemem-net:

volumes:
  hivemem-pgdata:
  hivemem-embeddings-models:
```

```bash
# Set a password (or it defaults to "changeme")
export HIVEMEM_DB_PASSWORD=your-secret-here

# Start everything
docker compose up -d

# Wait for startup (Flyway migrations run automatically)
docker logs -f hivemem

# Create your first API token
docker exec hivemem hivemem-token create my-admin --role admin
# Save the printed token — it's shown once and never stored
```

That's it. Three containers, all images from GHCR, no build needed.

For a pinned production rollout, use the current release tags such as `:8.1.0`. Use `:main` only if you explicitly want the rolling branch build.

### Build from source (optional)

```bash
git clone https://github.com/visterion/HiveMem.git
cd HiveMem
docker build -t hivemem .
```

At startup, Spring Boot runs Flyway migrations against the configured PostgreSQL database. Check progress:

```bash
docker logs -f hivemem
```

Wait for the Spring Boot startup log and a successful `/mcp` response before proceeding.

## Required Environment Variables

| Variable | Description |
|---|---|
| `HIVEMEM_JDBC_URL` | JDBC connection string (e.g. `jdbc:postgresql://postgres:5432/hivemem`) |
| `HIVEMEM_DB_USER` | PostgreSQL username |
| `HIVEMEM_DB_PASSWORD` | PostgreSQL password |
| `HIVEMEM_EMBEDDING_URL` | URL of the external embeddings service |
| `HIVEMEM_API_TOKEN` | Used by `deploy.sh` for the health-check smoke test |

## Create an API Token

Use the `hivemem-token` CLI (included in the Docker image):

```bash
docker exec hivemem hivemem-token create my-admin --role admin
```

The plaintext token is printed once and never stored. Save it immediately.

See [Authentication](auth.md) for the full token management reference.

## Connect to Claude Code

**CLI (recommended):**

```bash
claude mcp add --scope user hivemem --transport http http://localhost:8421/mcp \
  --header "Authorization: Bearer YOUR_TOKEN_HERE"
```

Restart Claude Code. The 34 HiveMem tools are now available in every session.

**Manual config** (`~/.claude.json` for user-level, or `.mcp.json` for project-level):

```json
{
  "mcpServers": {
    "hivemem": {
      "type": "http",
      "url": "http://localhost:8421/mcp",
      "headers": {
        "Authorization": "Bearer YOUR_TOKEN_HERE"
      }
    }
  }
}
```

## Connect to Claude Desktop

Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "hivemem": {
      "type": "http",
      "url": "http://localhost:8421/mcp",
      "headers": {
        "Authorization": "Bearer YOUR_TOKEN_HERE"
      }
    }
  }
}
```

## Teach Your Agent to Use HiveMem

The MCP server ships instructions that tell the agent *how* to use the 46 tools. But the agent won't reliably *remember to archive* unless you tell it to in your own CLAUDE.md.

Add this to your **user-level** CLAUDE.md (`~/.claude/CLAUDE.md`) so it applies to every project:

```markdown
## HiveMem — Persistent Knowledge

You have a HiveMem MCP server available as your long-term memory. Use it
aggressively.

### Availability check

HiveMem tools are exposed under the `mcp__hivemem__*` namespace (e.g.
`mcp__hivemem__wake_up`, `mcp__hivemem__search`). If those tools are not
listed in the current session, skip this section entirely — do not mention
HiveMem, do not apologize for its absence.

### Session start (HARD RULE)

Call `wake_up` BEFORE your first response, BEFORE any other tool call,
BEFORE reading any file. No exceptions beyond the availability check above.

### During conversation — search proactively

Wake_up is a snapshot, not a subscription. Search actively on these signals:

- **Named reference.** User mentions a named project, person, decision, tool,
  or system not in wake_up → `search` BEFORE answering. Even if you
  think you remember.
- **Temporal reference.** "last week", "a while back", "we decided earlier",
  "remember when" → `search` with keywords, or `time_machine`
  for point-in-time queries.
- **Uncertainty.** About to say "I'm not sure" or hedge? Search FIRST. Only
  hedge if the search returns nothing.
- **Topic drift.** Conversation shifts to a new area not in wake_up → quick
  `search` before engaging.
- **Entity-specific.** User asks about a specific entity → `entity_overview`
  (add `depth=quick` for a fast facts-only lookup), `search_kg` for relationships.

**Anti-patterns — do NOT:**
- Hedge instead of searching ("I think we discussed...")
- Answer from wake_up when the topic wasn't in wake_up
- Batch searches for session end
- Wait for the user to prompt you

One `search` is cheap. Answering wrong is expensive.

### During work

After any significant action (bug fix, feature, design decision, deployment,
investigation), archive immediately — do not batch, do not wait.

Archiving:
1. `add_cell` with `dedupe_threshold: 0.92`
2. `kg_add` for each fact with `on_conflict=return` and `valid_from` set
3. `search` for related cells, then `add_tunnel` for the top
   2-3 matches

When a fact changes: `kg_invalidate` the old one FIRST, then
`kg_add` the new one.

### Session end

Archive anything significant not yet stored. When the user says "archive",
"save", or "persist": archive the full session.

### Classification

Realm = life/work area. Signal = nature of knowledge. Topic = specific subject.

Call `list` before inventing new realms — it navigates the
Realm→Signal→Topic→Cell hierarchy (omit all params for realms, add `realm` for
signals, add `realm`+`signal` for topics).

**Signals:** `facts` | `events` | `discoveries` | `preferences` | `advice`

Fill `content`, `summary`, `key_points`, and `insight` (when there is a
non-obvious takeaway). Every fact needs `valid_from`.

### What to archive
- Decisions + the "why" (not just the "what")
- Discoveries, surprises, lessons learned
- Infrastructure / deployment changes
- Bug root causes + fixes
- New patterns, conventions, processes

### What NOT to archive
- Routine code changes obvious from git history
- Temporary debugging steps
- Information already in project CLAUDE.md or README

### Precedence

Project-local CLAUDE.md overrides these rules if it says otherwise.
```

**Why user-level?** Project-level CLAUDE.md files describe the *project*. HiveMem is *your* memory across all projects. A user-level CLAUDE.md ensures every agent, in every repo, knows to persist knowledge — even in repos that have never heard of HiveMem.

**Why is the MCP protocol not enough?** The MCP `instructions` field tells the agent *how* to use the tools correctly. But it cannot force the agent to *decide* to archive — that decision depends on the conversation context, which only the CLAUDE.md can influence. The MCP protocol is the "API docs"; the CLAUDE.md is the "job description".
