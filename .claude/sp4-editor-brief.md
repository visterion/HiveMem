# SP4 Editor — Ralph Loop Task Brief (GitHub issue #31)

Authoritative spec for every loop iteration. Read this + `sp4-editor-progress.md` at the
START of each iteration. The GitHub issue is the source of truth for acceptance:
`gh issue view 31`.

## Goal

Add WRITE capability on top of the existing reader mode in `knowledge-ui/`, plus an
Obsidian-vault bulk import. **No backend changes** — everything goes through the existing
MCP tools via `ApiClient.call(tool, args)`.

## Locked technical decisions (do NOT re-litigate)

- **Editor: CodeMirror 6** (`@codemirror/*`). NOT Monaco. Vite-friendly, no web-worker setup,
  small bundle. Markdown language + syntax highlight + keymap.
- **Diff preview: `diff` (jsdiff)** before save.
- **KaTeX preview**: `katex` is already a dependency — reuse it for math preview.
- **Obsidian import: fully client-side** with **`jszip`** to read the uploaded `.zip` in the
  browser, parse each `.md`, then issue `add_cell` / `add_tunnel` calls. This keeps the
  "no backend changes" acceptance criterion. (Path-based import is optional; zip-upload is
  the required path.)
- **Auto-push per slice**: HiveMem convention — commit directly to `main` and `git push`
  after each green slice. Do NOT create version tags or deploy (that needs explicit user OK).

## Codebase pointers (verified)

- Reader shell: `src/components/Reader.vue`; tabs live in `src/components/readers/`
  (`DocInfoTab.vue`, `DocumentViewer.vue`, `MarkdownTab.vue`, `ViewerToolbar.vue`,
  `EmlTab.vue`, `attachmentTabs.ts`).
- Cell state: `src/stores/cell.ts` (`current`, `load`, `open`, `ensureAttachments`).
- API client: `src/api/types.ts` → `ApiClient.call<T>(tool, args)`; impl in
  `src/api/httpClient.ts` (JSON-RPC `tools/call`) and `src/api/mockClient.ts`.
  Access via `useApi()` (`src/api/useApi.ts`).
- Types: `Cell`, `Tunnel`, `Realm/Signal/Topic` in `src/api/types.ts`.
- MCP tools to call (already exist server-side): `revise_cell`, `add_cell`, `add_tunnel`,
  `list`, `search`, `add_tags`/`remove_tags` (or pass tags via `revise_cell`).
  Confirm exact arg shapes against `documentation/tools.md` before wiring each one.

## Workflow discipline (mandatory — HiveMem rules)

1. **TDD** (superpowers `test-driven-development`): write a FAILING vitest first, then implement.
2. **Type/build gate is `npm run build`** (runs `vue-tsc -b`) — NOT `vue-tsc --noEmit`.
   A slice is not done until `npm run build` is clean.
3. **Unit**: `npm run test:unit` green.
4. **E2E**: `npm run test:e2e` green LOCALLY for any UI flow (CI gates on Playwright;
   vitest-green ≠ CI-green for structural UI). Run `npx playwright install chromium` if needed.
5. **Commit + push to `main`** after each green slice (direct-to-main is expected here).
6. **Docs**: if a slice changes documented behavior, update the matching `documentation/`
   page in the same commit. (No new MCP tools expected → likely no `tools.md` change.)
7. **Verify before claiming**: never say a slice works without pasting the real command output.
8. **Mock parity**: `mockClient.ts` must support new tool calls so local dev + e2e work
   (mock `revise_cell`/`add_cell`/`add_tunnel`/`list` sensibly).

## Slices (ordered; each is independently shippable → its own commit+push)

- [ ] **S1 — Inline editor + save roundtrip.** Edit/view toggle in reader toolbar
  (`ViewerToolbar.vue`/`Reader.vue`). CodeMirror 6 markdown editor. Explicit save (Cmd+S)
  → `revise_cell` (append-only; parent_id chain preserved). Show jsdiff pre-save diff.
  Acceptance: edit+save roundtrip on a cell preserves revision history.
- [ ] **S2 — New cell.** "New" button opens a blank editor with realm/signal/topic dropdowns
  populated from `list` (signal is the fixed enum: facts/events/discoveries/preferences/advice).
  Save → `add_cell` with all layers (content + summary/key_points/insight optional).
- [ ] **S3 — Tag + importance pickers.** Inline chip editor for tags; importance 1–5 picker.
  Persist via `revise_cell` (or `add_tags`/`remove_tags`).
- [ ] **S4 — Tunnel editor.** Search-as-you-type cell picker (`search`), relation-type select,
  optional note → `add_tunnel`. Show existing tunnels.
- [ ] **S5 — Export as .md.** Toolbar action: download current cell content as `.md`
  (frontmatter with realm/signal/topic/tags/valid_from + body).
- [ ] **S6 — Obsidian vault bulk import.** Dialog: upload `.zip` (jszip, client-side).
  Per `.md`: wiki-links `[[Foo]]` → tunnels (create target cell if missing), `#tag` → tags,
  frontmatter `created` → `valid_from`. Dry-run preview + progress bar. Classification:
  reuse SP3 ingest if available, else a simple heuristic (default realm/signal).
  Acceptance: importing 50+ notes yields correct realm/signal assignment, tunnels from
  wiki-links, tags preserved.

## Acceptance (mirror of issue #31 — all must hold for completion)

- [ ] Edit+save roundtrip on a real cell preserves revision history (parent_id chain).
- [ ] Obsidian import of 50+ notes: correct realm/signal, tunnels from wiki-links, tags kept.
- [ ] Frontend tests cover the edit/preview/save cycle.
- [ ] No backend changes (only existing `revise_cell`/`add_cell`/`add_tunnel`/`list`).

## Definition of DONE (gate for the completion promise)

ALL of the following, with pasted command output as evidence:
1. Every slice S1–S6 checkbox above is checked and committed+pushed to `main`.
2. `npm run build` clean, `npm run test:unit` green, `npm run test:e2e` green — output shown.
3. All four acceptance criteria verified.
4. A HiveMem cell archived for the feature (search dup → `add_cell` all L0–L3 layers →
   `kg_add` key facts → `add_tunnel` to 2–3 related cells).

Only then output exactly: `<promise>SP4 EDITOR COMPLETE</promise>`

## Each-iteration protocol

1. Read this brief + `sp4-editor-progress.md`.
2. Pick the FIRST unchecked slice (or finish an in-progress one).
3. Do it test-first → build → unit → e2e → commit → push.
4. Update `sp4-editor-progress.md` (check the box, note commit hash, next step).
5. Report status with evidence. If blocked, use systematic-debugging — do NOT fake "done".
