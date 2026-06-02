# HiveMem

<img width="1637" height="811" alt="image" src="https://github.com/user-attachments/assets/b9ceda91-0678-4d9b-bae8-2b5ba69d53d4" />

> **Your second brain — and it stays yours. Forever. Local.**
>
> A sovereign personal knowledge system. The conversations, decisions, documents, and
> half-formed thoughts you produce across Claude, ChatGPT, Gemini, Copilot — and
> the files you accumulate in real life — all come home to one place that
> outlives any vendor and obeys only you.

---

## Why HiveMem exists

When you think hard today, you often think with an LLM in the loop. School,
work, authorities, court cases, taxes, family, health, relationships — these
conversations contain your most private thinking. More intimate than any diary.

And then they evaporate:

- Your subscription lapses or you switch providers → **history gone**
- The provider retires a model or rewrites their ToS → **answers no longer reproducible**
- An account ban, a provider going under, a country blocking the service → **everything lost**
- The data sits on a vendor's servers, fed into training, served on subpoena, exposed in the next breach

HiveMem is built around the opposite stance:

1. **Sovereignty** — Your data lives in your instance. Postgres + SeaweedFS,
   on hardware you control. No vendor sees the contents unless you explicitly
   route a single LLM call through them.
2. **Persistence** — Everything is append-only with `valid_from`/`valid_until`.
   No subscription change can revoke access. No retention policy you didn't
   author can delete what's yours.
3. **Portability** — A HiveMem instance packs into one encrypted archive
   (Postgres dump + binary store + config) and restores anywhere.
   Vendor lock-in: zero.
4. **Aggregation** — What you write in Claude.ai, ChatGPT, Gemini, Claude
   Code, Copilot lands in HiveMem too. Those tools become front-ends;
   HiveMem holds the truth.
5. **Privacy by realm** — Strict separation per life area
   (`legal`, `medical`, `private`, `work`). Per-realm routing rules: anything
   touching authorities or health stays on local models, never reaches a
   cloud provider.

## Knowledge doesn't rot here

The long-term goal is a periodic agent — the **Queen** — that wakes on a
schedule, surveys your knowledge, and dispatches specialized worker agents
(**Bees**) to flag isolated cells, stale facts, duplicate candidates, and
realms drifting from their blueprint. Everything risky stays a *proposal*
that flows through the existing approval workflow; you keep the kill switch.

The schema, agent registry, and approval pipeline are in place today. The
scheduler, the Bees themselves, the audit table, and the conversation UI
that teaches the Queen your preferences are not yet built.

→ **[Roadmap](documentation/roadmap.md)** — what's planned, what's partial,
and the order of work.

→ **[Scientific foundations](documentation/vision.md)** — the cognitive-science
and PKM theory HiveMem's design is built on (Working Memory, Cognitive Load,
Extended Mind, Forgetting Curve, Zettelkasten, PARA).

---

[![CI](https://github.com/visterion/HiveMem/actions/workflows/ci.yml/badge.svg)](https://github.com/visterion/HiveMem/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/visterion/HiveMem/graph/badge.svg)](https://codecov.io/gh/visterion/HiveMem)
[![GitHub release](https://img.shields.io/github/v/tag/visterion/HiveMem?label=release)](https://github.com/visterion/HiveMem/releases)
[![GHCR](https://img.shields.io/badge/ghcr.io-visterion%2Fhivemem-blue)](https://github.com/visterion/HiveMem/pkgs/container/hivemem)
[![Java](https://img.shields.io/badge/java-25-blue)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/spring%20boot-4.0.5-6DB33F)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/postgresql-17-336791)](https://postgresql.org)
[![Tests](https://img.shields.io/badge/tests-JUnit%20%2B%20Testcontainers-brightgreen)](https://github.com/visterion/HiveMem/actions/workflows/ci.yml)
[![MCP Tools](https://img.shields.io/badge/MCP%20tools-36-orange)](documentation/tools.md)
[![License: Sustainable Use](https://img.shields.io/badge/license-Sustainable%20Use-blue)](https://github.com/visterion/HiveMem/blob/main/LICENSE)
[![SafeSkill](https://safeskill.dev/api/badge/visterion-hivemem)](https://safeskill.dev/scan/visterion-hivemem)

**Docker images:** [`ghcr.io/visterion/hivemem:main`](https://github.com/visterion/HiveMem/pkgs/container/hivemem) for the rolling `main` branch, plus semver tags such as `ghcr.io/visterion/hivemem:8.1.0` for cut releases.

## Highlights

- **[6-Signal Ranked Search](documentation/tools.md#search-signals)** — Semantic similarity, keyword, recency, importance, popularity, and graph proximity — combined into one ranked result.
- **[Temporal Knowledge Graph](documentation/architecture.md#data-model)** — Facts with `valid_from`/`valid_until`, contradiction detection, and multi-hop graph traversal.
- **[Progressive Summarization](documentation/tools.md#progressive-summarization)** — Four layers per cell: content, summary, key points, and insight. Never lose nuance.
- **[Long cells stay searchable](documentation/summarizer.md)** — auto-summarizer turns multi-page documents into curated summaries that are embedded for semantic search; cost-capped, opt-in.
- **[Scanned PDFs become searchable](documentation/ocr.md)** — Tesseract OCR extracts text from scan-only PDFs; combined with the auto-summarizer, even paper-mailed documents are findable by semantic search.
- **[Document-Type Extraction](documentation/extraction.md)** — invoices, contracts, and other typed documents are auto-classified during summarization; typed facts (vendor, amount, parties, dates) land in the knowledge graph.
- **[Kroki + Vision](documentation/kroki-vision.md)** — Diagramm-Thumbnails (Mermaid/PUML/Graphviz/D2) und Bildbeschreibung via Claude Haiku, async + opt-in.
- **[Append-Only Versioning + Time Machine](documentation/structure.md)** — No data is ever deleted. Query your knowledge at any point in time.
- **[Agent Fleet + Approval Workflow](documentation/auth.md)** — Agents write pending suggestions; only admins approve. Every write is human-gated.
- **[Auto-Inject Hook for Claude Code](documentation/hook/)** — Relevant memories injected into every session automatically, before you even ask.
- **[Full instance portability](documentation/backup.md)** — Export the entire HiveMem instance (Postgres + attachments + identity) into one tar.gz, restore it on another host with one command. Mission promise made provable.

→ **[Get started](documentation/getting-started.md)**

## Feature Status

Honest snapshot of what is shipping today versus what the surrounding prose
describes as the long-term shape. See the [roadmap](documentation/roadmap.md)
for details on every 🟡 / 🔴 row.

| Feature | Status | Notes |
|---|---|---|
| [6-Signal Ranked Search](documentation/tools.md#search-signals) | ✅ Stable | semantic + keyword + recency + importance + popularity + graph proximity, all wired into one SQL ranker |
| [Progressive Summarization](documentation/tools.md#progressive-summarization) | ✅ Stable | content / summary / key points / insight, all four populated automatically |
| [Auto-Summarizer for long cells](documentation/summarizer.md) | ✅ Stable | summary is embedded for semantic search, cost-capped per realm |
| [OCR for scanned PDFs](documentation/ocr.md) | ✅ Stable | Tesseract, async backfill, Vision fallback |
| [Document-Type Extraction](documentation/extraction.md) | ✅ Stable | invoices/contracts/etc → typed facts in the knowledge graph |
| [Kroki + Vision](documentation/kroki-vision.md) | ✅ Stable | diagram thumbnails + Claude Haiku image description, opt-in, budget-capped |
| [Append-Only Versioning + Time Machine](documentation/structure.md) | ✅ Stable | `time_machine` queries by event time and ingestion time |
| [Agent Approval Workflow](documentation/auth.md) | ✅ Stable | every agent write lands as `pending` until an admin approves |
| [Auto-Inject Hook (Claude Code)](documentation/hook/) | ✅ Stable | 6-stage filter pipeline, Bearer-token auth |
| [Full Instance Portability](documentation/backup.md) | ✅ Stable | one-command tar.gz of Postgres + attachments + identity |
| [OAuth Custom Connector](documentation/oauth.md) | ✅ Stable | RFC 8414 / 9728 discovery, PKCE |
| Temporal Knowledge Graph | 🟡 Partial | bi-temporal facts and multi-hop traversal ship; **automatic contradiction detection is not yet implemented** |
| Privacy by Realm — model routing | 🟡 Partial | data segregation by realm works; **per-realm enforcement of "stays on local models" is not yet wired into the LLM call path** |
| Queen + Bees periodic agent | 🟡 Partial | Queen + isolated-cell-Bee run on Vistierie's agent runtime (cron, subagent dispatch, run/cost audit, kill switch); proposals land as `pending` tunnels via the approval workflow. An admin-only Queen-log UI (`/queen`) shows runs + event timelines and the proposal approval queue. **Still missing: preference UI, further Bee types.** |

## Documentation

| | |
|---|---|
| [Vision](documentation/vision.md) | Cognitive-science and PKM foundations behind HiveMem's design |
| [Getting Started](documentation/getting-started.md) | Prerequisites, embedding service, token creation, connect to Claude |
| [The Structure](documentation/structure.md) | Realms, signals, topics, cells, tunnels — the knowledge hierarchy |
| [Architecture](documentation/architecture.md) | System diagram, data model, security matrix |
| [Tools](documentation/tools.md) | All 36 MCP tools, the parallel REST attachment API, search signals, progressive summarization |
| [Authentication](documentation/auth.md) | Roles, token management, security details |
| [OAuth + Custom Connector](documentation/oauth.md) | Add HiveMem as a Claude.ai/ChatGPT Custom Connector |
| [Backup + Portability](documentation/backup.md) | Export and restore entire instances, disaster recovery, cloning |
| [Hook Integration](documentation/hook/) | Auto-inject context into Claude Code sessions |
| [Operations](documentation/operations.md) | Deployment, migrations, debugging |
| [Roadmap](documentation/roadmap.md) | What's planned, what's partial, order of work |

## License

HiveMem is fair-code licensed under the [Sustainable Use License](LICENSE). Free for personal and internal business use. See [LICENSING.md](LICENSING.md) for details.
