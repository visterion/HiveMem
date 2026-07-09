# HiveMem Documentation

Welcome. Pick the topic you need:

---

### [Getting Started](getting-started.md)
Prerequisites, Docker Compose setup, first token, connecting to Claude Code and Claude Desktop, and the CLAUDE.md snippet that teaches your agent to use HiveMem.

### [The Structure](structure.md)
The four-level knowledge hierarchy — Realms, Signals, Topics, Cells — plus Tunnels, Facts, and Blueprints. Start here if you want to understand how HiveMem organizes knowledge.

### [Tools](tools.md)
All 46 MCP tools with descriptions, the parallel REST attachment API, the 6 search signals and their weights, and the progressive summarization layers (content → summary → key points → insight).

### [Architecture](architecture.md)
System architecture diagram, PostgreSQL data model (ER), security and capability matrix, environment variable reference, and compliance details.

### [Authentication](auth.md)
The four roles (admin / writer / reader / agent), the approval workflow for agent writes, the `hivemem-token` CLI, and security implementation details.

### [Hook Integration](hook/)
Auto-inject relevant memory cells into every Claude Code session before you even ask. Includes setup, the 6-stage filtering pipeline, configuration reference, output format, and roadmap.

### [Operations](operations.md)
Backups, deploying changes, adding Flyway migrations, and debugging.

### [Document & Scan Pipeline](document-pipeline.md)
The big picture: how a file becomes searchable knowledge end to end — the two entry points (watched folder + REST upload), the shared ingest core (hash → parse → dedup → store → cell), and the four async enrichment paths (OCR · Vision · Kroki · Summarizer). Start here, then follow the links into the deep-dive pages below.

### [Consumption Folder](consumption.md)
Automatic scan ingest: drop PDFs into a folder, HiveMem polls and ingests. Multi-page batches are split into individual documents by a Vistierie LLM agent based on content (no separator sheets required). Hardware setup, how it works, config reference, and known limitations.

### [OCR](ocr.md)
Tesseract OCR for scan-only PDFs: scan detection, page rasterization, the async backfill, and the optional Vision transcription fallback.

### [Document-Type Extraction](extraction.md)
Typed documents (invoices, contracts, …) are auto-classified during summarization; typed facts (vendor, amount, parties, dates) land in the knowledge graph.

### [Kroki + Vision](kroki-vision.md)
Async, opt-in, budget-capped enrichment: diagram thumbnails (Mermaid/PlantUML/Graphviz/D2) and image description via an LLM.

### [Auto-Summarizer](summarizer.md)
Long cells become curated, embedded summaries so multi-page documents stay findable by semantic search — cost-capped and opt-in.

### [Vision](vision.md)
The cognitive science behind HiveMem — Working Memory, Cognitive Load Theory, the Extended Mind Thesis — and how Zettelkasten and PARA shaped the design.
