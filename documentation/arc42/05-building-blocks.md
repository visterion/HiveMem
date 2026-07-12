# 5. Building Blocks

> [!NOTE]
> **AI-Generated** — inferred from the package/module layout; needs human review.

## Level 1 — Modules

| Module | Path | Responsibility |
|---|---|---|
| Backend | `java-server/` | Spring Boot: REST, MCP, persistence, ingestion. |
| Frontend | `knowledge-ui/` | Vue 3 + Vite SPA (built and bundled into the backend jar). |
| Embedding sidecar | `embedding-service/` | Python ONNX service turning text into vectors. |

## Level 2 — Backend packages (`java-server/src/main/java/com/hivemem/`)

| Package | Responsibility |
|---|---|
| `mcp`, `tools` | MCP endpoint and the agent-facing tool surface. |
| `cells`, `kg` | Cells (notes) and the knowledge graph (facts, tunnels). |
| `search`, `savedsearch`, `popularity` | Retrieval, saved searches, ranking signals. |
| `write` | Write/revise pipeline for cells and facts. |
| `web`, `admin` | REST controllers and admin operations. |
| `auth`, `oauth`, `hooks` | Authentication, OAuth, and hook pipeline. |
| `extraction`, `ocr`, `summarize`, `consumption` | Document/photo ingestion & enrichment. |
| `embedding` | Client to the embedding sidecar. |
| `attachment` | Object-store (S3) attachment handling. |
| `queen`, `sync`, `backup` | Orchestration, sync, and backup concerns. |

Detailed package prose: [../structure.md](../structure.md). Ingestion flow:
[../document-pipeline.md](../document-pipeline.md).
