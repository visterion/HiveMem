---
name: hivemem-archive
description: Use when archiving a completed session, decision, or discovery into HiveMem.
---

# HiveMem Archive Skill

## Overview
This skill guides the agent in systematically persisting knowledge into the HiveMem long-term memory. It extracts facts, decisions, and context from the session.

## When to Use
- **End of session:** When the user says "archive", "save", or "done".
- **Significant discovery:** When a root cause is found or a feature is implemented.
- **Architectural decision:** When a choice (e.g., "PostgreSQL over ChromaDB") is made.

## Workflow

1.  **Analyze Session:** Summarize the core achievement and its rationale.
2.  **Classify (Realm/Signal/Topic):**
    - `Realm`: Free-form major area (e.g., `hivemem`, `work`, `personal`).
    - `Signal`: Fixed enum — one of `facts`, `events`, `discoveries`, `preferences`, `advice`.
    - `Topic`: Free-form specific subject within the realm (e.g., `hivemem-auth`).
3.  **Store Cell:** Pass `dedupe_threshold` (e.g. `0.92`) to `add_cell` so the server encodes once and refuses to insert if a near-duplicate exists. Fill all progressive fields.
    - Content: Verbatim source material
    - Summary: Short synopsis
    - Key points: Main takeaways
    - Insight: The "why"
4.  **Extract KG Facts:** For every atomic fact (Subject-Predicate-Object), call `kg_add`.
    - Use `on_conflict="return"` on `kg_add` to detect contradicting active facts without inserting.
    - If conflict: Invalidate old fact with `kg_invalidate` before adding new.
5.  **Establish Links:** Link the new cell to related knowledge via `add_tunnel`.

## Security & Transparency
- **Bounded Scope:** This skill only uses `hivemem_*` tools and the current project context.
- **Human-in-the-Loop:** Agents must use the `agent` token role, which sends all writes to the `pending` queue for admin approval.

---
