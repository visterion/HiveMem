---
name: hivemem-wakeup
description: Use at the start of every session to load identity and critical context from HiveMem.
---

# HiveMem Wakeup Skill

## Overview
This skill ensures that the agent has a complete understanding of the user's current identity, projects, and critical facts before proceeding with the session.

## When to Use
- **Start of session:** Call before the first response to the user.
- **Context shift:** When the user asks about past decisions or current status.

## Workflow

1.  **Initial Wakeup:** Always call `wake_up` first to load identity and critical context.
2.  **Analyze Intent:** Identify the user's current project or problem area.
3.  **Search Relevance:** Call `search` with the session topic to retrieve relevant historical cells.
4.  **Traverse Context:** If the topic is an entity, call `traverse` and `entity_overview` to see current relationships and valid-from dates.
5.  **Blueprint Review:** Call `get_blueprint` for the current realm to understand the narrative overview of the area.
6.  **Synthesize:** Start the response with a brief summary of what you've retrieved ("I've loaded your context for [Project] from HiveMem...").

## Security & Transparency
- **Bounded Scope:** Only reads from HiveMem; no write access allowed in the wakeup phase unless explicitly directed.
- **Traceability:** The wakeup sequence is logged in the session history and the HiveMem audit log.

---
