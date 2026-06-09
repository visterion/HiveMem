# HiveMem 9.2.2

UI fix: cells are now identifiable in the knowledge UI. Previously every search result
and the detail-panel header rendered blank.

## Root cause

The UI bound `cell.title`, but HiveMem cells have **no `title`** — neither `search` nor
`get_cell` returns one (cells carry `content`/`summary`/`key_points`/`insight`). So every
result row and panel header showed nothing, and bare scanned documents (whose `summary` is
still null until OCR/summarisation) were completely unidentifiable.

## Fix (UI only — `knowledge-ui`)

- **`cellLabel()` helper** (`src/api/cellLabel.ts`): label = `summary` → content snippet
  (strips `[page=N]` markers) → `topic` → short id. Unit-tested.
- **SearchPanel**: requests `include: ['content','summary']` and labels each result with
  `cellLabel`, so results — including scanned documents — are identifiable by their text.
- **ScanPanel / cell store**: opening a search result seeds the panel from the already-rich
  row (via `cellStore.open`), so the detail panel shows the OCR/parsed **TEXT** immediately
  and "Open reader" still opens the source. Dropped the broken `quick_facts(subject=title)`
  call (title never existed) — facts now key on summary/topic, skipped when absent.

## Not included

Clicking a realm in the Realms panel to browse its cells is **not** in this release: the
ranked-search SQL function hard-filters on `semantic > 0.3 OR keyword > 0`, so a blank
(browse) query returns nothing. True realm browsing needs a separate search-function change
and is deferred. Find documents via Search (any content word, e.g. a name or "Seite").

## Upgrade

No schema changes (still at V0030). UI-only rebuild. Drop-in over 9.2.1.
