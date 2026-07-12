# 1. Introduction & Goals

> [!NOTE]
> **AI-Generated** — inferred from code and README; needs human review.

## What

HiveMem is a local-first knowledge-graph "second brain": it stores notes as **cells**,
extracts **facts** and relationships (**tunnels**) into a knowledge graph, ingests
documents and photos, and exposes the whole store to AI agents as long-term memory over an
**MCP** endpoint. The data stays local and owned by the user.

## Quality Goals

1. **Data ownership / local-first** — the knowledge store and its attachments remain under
   the user's control; no dependence on a third-party cloud for the primary data.
2. **Agent-retrievability** — the MCP surface (`documentation/tools.md`) must let an agent
   search, add, revise, and traverse memory efficiently.
3. **Durability** — cells and facts are versioned and recoverable (history, backups).

## Stakeholders

| Role | Interest |
|---|---|
| Primary user | Owns the knowledge base; uses the Vue UI and MCP-backed agents. |
| AI agents (MCP clients) | Read/write memory through the MCP tools. |
| Contributors | Extend backend modules, UI, or the ingestion pipeline. |
