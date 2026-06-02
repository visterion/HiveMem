# HiveMem Documentation

Welcome. Pick the topic you need:

---

### [Getting Started](getting-started.md)
Prerequisites, Docker Compose setup, first token, connecting to Claude Code and Claude Desktop, and the CLAUDE.md snippet that teaches your agent to use HiveMem.

### [The Structure](structure.md)
The four-level knowledge hierarchy — Realms, Signals, Topics, Cells — plus Tunnels, Facts, and Blueprints. Start here if you want to understand how HiveMem organizes knowledge.

### [Tools](tools.md)
All 34 MCP tools with descriptions, the parallel REST attachment API, the 6 search signals and their weights, and the progressive summarization layers (content → summary → key points → insight).

### [Architecture](architecture.md)
System architecture diagram, PostgreSQL data model (ER), security and capability matrix, environment variable reference, and compliance details.

### [Authentication](auth.md)
The four roles (admin / writer / reader / agent), the approval workflow for agent writes, the `hivemem-token` CLI, and security implementation details.

### [Hook Integration](hook/)
Auto-inject relevant memory cells into every Claude Code session before you even ask. Includes setup, the 6-stage filtering pipeline, configuration reference, output format, and roadmap.

### [Operations](operations.md)
Backups, deploying changes, adding Flyway migrations, and debugging.

### [Consumption Folder](consumption.md)
Automatic scan ingest: drop PDFs into a folder, HiveMem polls and ingests. Multi-page batches are split into individual documents by a Vistierie LLM agent based on content (no separator sheets required). Hardware setup, how it works, config reference, and known limitations.

### [Vision](vision.md)
The cognitive science behind HiveMem — Working Memory, Cognitive Load Theory, the Extended Mind Thesis — and how Zettelkasten and PARA shaped the design.
