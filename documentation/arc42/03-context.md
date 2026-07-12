# 3. Context & Scope

> [!NOTE]
> **AI-Generated** — inferred from code analysis; needs human review.

## System Boundary

The **HiveMem backend** (Spring Boot) is the system. It serves the Vue SPA, a REST API, and
an MCP endpoint, and persists to Postgres and an S3-compatible object store.

## External Interfaces

| Interface | Direction | Purpose | Auth |
|---|---|---|---|
| `/` (SPA) + `/api/**` | inbound | Web UI and its data API | session cookie `s` |
| `/admin/**` | inbound | Administrative operations | session cookie `s` |
| `/mcp` | inbound | Agent memory (MCP tools) | bearer token |
| `/login`, OAuth | inbound | Authentication | credentials / OAuth |
| Embedding service | outbound | Text → vector embeddings (ONNX sidecar) | network-local |
| Object store (SeaweedFS / S3) | outbound | Attachment & media bytes | S3 credentials |
| Postgres | outbound | Cells, facts, tunnels, metadata | DB credentials |

See [../auth.md](../auth.md) for the auth model and [../architecture.md](../architecture.md)
for the full component picture.
