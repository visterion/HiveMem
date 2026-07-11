import { palace as mockPalace } from '../data/mock'
import type { ApiClient, HiveEvent, Cell, Realm, Signal, Tunnel, Fact, StatusSummary, Reference, SearchResult, DocumentRow, FacetValue, SavedSearch, MediaItem } from './types'

interface MockConfig { latencyMs?: [number, number]; eventInterval?: number }

type Handler = (args: any) => unknown

// Synthesized vendor/party data keyed by cell id (for fact:* facet simulation)
const MOCK_CELL_FACTS: Record<string, { vendor?: string; party?: string }> = {
  'doc-contract-001': { vendor: 'Acme Corp' },
  'doc-invoice-001':  { vendor: 'CloudProvider GmbH' },
  'doc-contract-002': { party: 'Beta Partners' },
  'doc-invoice-002':  { vendor: 'Design Tools AG' },
  'doc-contract-003': { vendor: 'Munich Datacenter GmbH' },
  'doc-receipt-001':  { party: 'Conference Org Berlin' },
}

export class MockApiClient implements ApiClient {
  private config: Required<MockConfig>
  private subscribers = new Set<(e: HiveEvent) => void>()
  private timer: number | null = null
  private handlers: Record<string, Handler>

  // Long-poll stream state — chronological cursor over the seeded dataset.
  private streamQueue: Cell[] = []
  private streamDelivered = new Set<string>()
  private streamTunnelQueue: Tunnel[] = []
  private streamInitialized = false

  // In-memory saved searches (upsert by owner+name).
  // filter is stored as a JSON string to match the real backend contract (filter::text).
  private savedSearches: Array<Omit<SavedSearch, 'filter'> & { owner: string; filter: string }> = []
  private savedSearchCounter = 1
  private mediaSeed: MediaItem[] | null = null

  constructor(config: MockConfig = {}) {
    // In Vitest the real timers still run, but 900 ms per cell makes the streaming
    // tests time out. Use minimal latency when running inside the test runner.
    const isTest = (import.meta.env as unknown as Record<string, string>).MODE === 'test'
    const defaultLatency: [number, number] = isTest ? [0, 0] : [50, 200]
    this.config = { latencyMs: defaultLatency, eventInterval: 15000, ...config }
    this.handlers = {
      status: () => this.status(),
      wake_up: () => this.wakeUp(),
      list: (args: { realm?: string }) => this.listRealms(args),
      search: (args: { query?: string; limit?: number }) => this.search(args),
      get_cell: (args: { cell_id: string }) => this.getCell(args),
      // entity_overview returns { cells, facts, tunnels }; the mock only needs to keep the
      // UI functional, so a facts-only shape (via quickFacts) is fine for all depths.
      entity_overview: (a: any) => ({ cells: [], facts: this.quickFacts(a), tunnels: [] }),
      traverse: (args: { cell_id: string; depth?: number; max_depth?: number }) => this.traverse(args),
      hivemem_list_tunnels: () => mockPalace.tunnels,
      hivemem_stream_next: (args: { since?: string; timeout_ms?: number }) => this.streamNext(args),
      reading_list: () => mockPalace.references ?? [],
      pending_approvals: () => this.queenPending(),
      approve_pending: () => ({ ok: true }),
      queen_runs: () => this.queenRuns(),
      queen_run_detail: (args: { run_id: string }) => this.queenRunDetail(args),
      list_agents: () => [],
      diary_read: () => [],
      get_blueprint: () => null,
      time_machine: () => mockPalace.facts,
      search_kg: () => mockPalace.facts,
      history: () => [],
      list_documents: (a: any) => this.listDocuments(a),
      facet_count: (a: any) => this.facetCount(a),
      saved_searches: (a: any) => a.action === 'save' ? this.saveSearch(a) : a.action === 'delete' ? this.deleteSavedSearch(a) : this.listSavedSearches(),
      add_cell: (a: any) => this.addCell(a),
      add_tunnel: (a: any) => this.addTunnel(a),
      revise_cell: (a: any) => this.reviseCell(a),
      manage_tags: (a: any) => this.manageTags(a),
      reclassify: (a: any) => this.bulkReclassify(a),
      list_media: (a: any) => this.listMedia(a),
    }
  }

  async call<T>(tool: string, args: Record<string, unknown> = {}): Promise<T> {
    await this.delay()
    const fn = this.handlers[tool]
    if (!fn) throw new Error(`Mock not implemented: ${tool}`)
    return fn(args) as T
  }

  subscribe(onEvent: (e: HiveEvent) => void): () => void {
    this.subscribers.add(onEvent)
    if (!this.timer) this.startTicker()
    return () => {
      this.subscribers.delete(onEvent)
      if (this.subscribers.size === 0 && this.timer) {
        clearInterval(this.timer); this.timer = null
      }
    }
  }

  private startTicker() {
    // Intentionally silent — streaming happens via hivemem_stream_next long-poll.
    // Subscribers can still attach; they simply won't receive synthetic events.
  }

  private delay() {
    const [a, b] = this.config.latencyMs
    return new Promise(r => setTimeout(r, a + Math.random() * (b - a)))
  }

  private status(): StatusSummary {
    return {
      cell_count: mockPalace.cells.length,
      fact_count: mockPalace.facts.length,
      realm_count: mockPalace.realms.length,
      tunnel_count: mockPalace.tunnels.length,
      pending_count: 0,
      last_activity: new Date().toISOString(),
    }
  }

  private wakeUp() {
    return { role: 'admin', identity: 'mock-user', realms: mockPalace.realms.map(r => r.name) }
  }

  private listRealms(args: { realm?: string }): Realm[] | Signal[] {
    if (args.realm) return mockPalace.realms.find(r => r.name === args.realm)?.signals ?? []
    return mockPalace.realms
  }

  private search(args: { query?: string; limit?: number; status?: string; tags?: string[]; realm?: string }): SearchResult[] {
    const q = (args.query || '').toLowerCase()
    // 'all' mirrors the backend sentinel (DocumentListRepository): bypass the
    // status filter entirely rather than matching the literal string "all"
    // (same pattern as filterDocCells below).
    const filterStatus = args.status && args.status !== 'all' ? args.status : null
    const filterTags = args.tags && args.tags.length > 0 ? args.tags : null
    let all = q
      ? mockPalace.cells.filter(c => c.title.toLowerCase().includes(q) || c.content.toLowerCase().includes(q))
      : mockPalace.cells
    if (args.realm) all = all.filter(c => c.realm === args.realm)
    if (filterStatus) {
      all = all.filter(c => (c.status ?? 'committed') === filterStatus)
    }
    if (filterTags) {
      all = all.filter(c => filterTags.some(t => (c.tags ?? []).includes(t)))
    }
    const SIGS = ['semantic', 'keyword', 'recency', 'importance', 'popularity', 'graph_proximity'] as const
    return all.slice(0, args.limit ?? 100).map((cell, i) => {
      const base = Math.max(0.1, 0.92 - i * 0.05)
      const scores: Record<string, number> = {}
      for (const s of SIGS) {
        scores['score_' + s] = Math.max(0, Math.min(1, base - 0.08 + ((i + s.length) % 4) * 0.06))
      }
      return { ...cell, ...scores, score_total: base, confidence_level: base > 0.6 ? 'HIGH' : 'MEDIUM' } as SearchResult
    })
  }

  private filterDocCells(args: {
    realm?: string; status?: string; tags?: string[]
    signal?: string; topic?: string
  }): Cell[] {
    const realm = args.realm ?? 'documents'
    // 'all' mirrors the backend sentinel (DocumentListRepository): bypass the
    // status filter entirely rather than matching the literal string "all".
    const status = args.status && args.status !== 'all' ? args.status : null
    const filterTags = args.tags && args.tags.length > 0 ? args.tags : null
    let cells = mockPalace.cells.filter(c => c.realm === realm)
    if (status) cells = cells.filter(c => (c.status ?? 'committed') === status)
    if (filterTags) cells = cells.filter(c => filterTags.some(t => (c.tags ?? []).includes(t)))
    if (args.signal) cells = cells.filter(c => c.signal === args.signal)
    if (args.topic) cells = cells.filter(c => c.topic === args.topic)
    return cells
  }

  private listDocuments(args: {
    realm?: string; status?: string; tags?: string[]
    signal?: string; topic?: string
    sort?: string; offset?: number; limit?: number
  }): DocumentRow[] {
    let cells = this.filterDocCells(args)
    // Sort
    const sort = args.sort ?? 'newest'
    if (sort === 'newest') {
      cells = [...cells].sort((a, b) => (b.created_at ?? '').localeCompare(a.created_at ?? ''))
    } else if (sort === 'oldest') {
      cells = [...cells].sort((a, b) => (a.created_at ?? '').localeCompare(b.created_at ?? ''))
    } else if (sort === 'title') {
      cells = [...cells].sort((a, b) => (a.title ?? '').localeCompare(b.title ?? ''))
    }
    // Paginate
    const offset = args.offset ?? 0
    const limit = args.limit ?? 50
    cells = cells.slice(offset, offset + limit)
    // Map to DocumentRow — synthesize attachment fields for ~half the docs
    return cells.map((c, i): DocumentRow => {
      const hasAtt = i % 2 === 0
      // Synthesize a confidence score (0–1) from the cell index as a deterministic mock
      const confidence = parseFloat((0.55 + ((c.id.charCodeAt(c.id.length - 1) % 10) * 0.04)).toFixed(2))
      const facts = MOCK_CELL_FACTS[c.id]
      const correspondent = facts?.vendor ?? facts?.party ?? null
      return {
        id: c.id,
        realm: c.realm,
        signal: c.signal ?? null,
        topic: c.topic ?? null,
        summary: c.summary ?? null,
        tags: c.tags ?? [],
        importance: c.importance,
        status: c.status ?? 'committed',
        created_at: c.created_at,
        attachment_id: hasAtt ? 'att-' + c.id : null,
        mime_type: hasAtt ? 'application/pdf' : null,
        page_count: hasAtt ? (1 + (c.id.charCodeAt(c.id.length - 1) % 4)) : null,
        has_thumbnail: hasAtt,
        confidence,
        correspondent,
      }
    })
  }

  private facetCount(args: {
    realm?: string; status?: string; tags?: string[]
    signal?: string; topic?: string; fields?: string[]
  }): Record<string, FacetValue[]> {
    // Mirror FacetRepository: with no realm filter, count across ALL active
    // cells; with a realm given, restrict to that realm (+doc filters).
    const cells = args.realm ? this.filterDocCells(args) : mockPalace.cells
    const fields = args.fields ?? ['tag', 'status']
    const result: Record<string, FacetValue[]> = {}
    for (const field of fields) {
      const counts = new Map<string, number>()
      if (field === 'tag') {
        for (const c of cells) {
          for (const t of (c.tags ?? [])) {
            counts.set(t, (counts.get(t) ?? 0) + 1)
          }
        }
      } else if (field === 'status') {
        for (const c of cells) {
          const s = c.status ?? 'committed'
          counts.set(s, (counts.get(s) ?? 0) + 1)
        }
      } else if (field === 'realm') {
        for (const c of cells) {
          counts.set(c.realm, (counts.get(c.realm) ?? 0) + 1)
        }
      } else if (field === 'signal') {
        for (const c of cells) {
          const s = c.signal ?? '(none)'
          counts.set(s, (counts.get(s) ?? 0) + 1)
        }
      } else if (field === 'year') {
        for (const c of cells) {
          const y = (c.created_at ?? '').slice(0, 4) || 'unknown'
          counts.set(y, (counts.get(y) ?? 0) + 1)
        }
      } else if (field.startsWith('fact:')) {
        const predicate = field.slice(5) as 'vendor' | 'party' | string
        for (const c of cells) {
          const cellFacts = MOCK_CELL_FACTS[c.id]
          if (!cellFacts) continue
          let val: string | undefined
          if (predicate === 'vendor') val = cellFacts.vendor
          else if (predicate === 'party') val = cellFacts.party
          // other predicates (amount_total, etc.) are not seeded — skip
          if (val) counts.set(val, (counts.get(val) ?? 0) + 1)
        }
      }
      result[field] = [...counts.entries()]
        .sort((a, b) => b[1] - a[1])
        .map(([value, count]) => ({ value, count }))
    }
    return result
  }

  private saveSearch(args: { name: string; filter: Record<string, unknown> }): Omit<SavedSearch, 'filter'> & { owner: string; filter: string } {
    const owner = 'mock-user'
    const existing = this.savedSearches.findIndex(s => s.owner === owner && s.name === args.name)
    const row = {
      id: existing >= 0 ? this.savedSearches[existing].id : 'ss-' + (this.savedSearchCounter++),
      owner,
      name: args.name,
      // Serialize to JSON string — mirrors real backend `filter::text` projection
      filter: JSON.stringify(args.filter),
      created_at: new Date().toISOString(),
    }
    if (existing >= 0) this.savedSearches[existing] = row
    else this.savedSearches.push(row)
    return row
  }

  private listSavedSearches(): Array<Omit<SavedSearch, 'filter'> & { owner: string; filter: string }> {
    return this.savedSearches.filter(s => s.owner === 'mock-user')
  }

  private deleteSavedSearch(args: { id: string }): { id: string; deleted: boolean } {
    const before = this.savedSearches.length
    this.savedSearches = this.savedSearches.filter(s => s.id !== args.id)
    return { id: args.id, deleted: this.savedSearches.length < before }
  }

  // manage_tags: unified add/remove over one or more cells (cell_ids single- or multi-element).
  private manageTags(args: { cell_ids: string[]; add?: string[]; remove?: string[] }): { updated: number } {
    let updated = 0
    for (const id of args.cell_ids ?? []) {
      const c = mockPalace.cells.find(x => x.id === id)
      if (!c) continue
      const current = new Set(c.tags ?? [])
      for (const t of args.add ?? []) current.add(t)
      const remove = new Set(args.remove ?? [])
      c.tags = [...current].filter(t => !remove.has(t))
      updated++
    }
    return { updated }
  }

  private bulkReclassify(args: { cell_ids: string[]; realm?: string; signal?: string; topic?: string }): { updated: number } {
    let updated = 0
    for (const id of args.cell_ids) {
      const c = mockPalace.cells.find(x => x.id === id)
      if (!c) continue
      if (args.realm !== undefined) c.realm = args.realm
      if (args.signal !== undefined) (c as any).signal = args.signal
      if (args.topic !== undefined) (c as any).topic = args.topic
      updated++
    }
    return { updated }
  }

  private getCell(args: { cell_id: string }): Cell {
    const c = mockPalace.cells.find(x => x.id === args.cell_id)
    if (!c) throw new Error(`Cell not found: ${args.cell_id}`)
    return c
  }

  // Insert a brand-new cell. Mirrors the real add_cell contract, which returns
  // { inserted, id, realm, signal, topic, status } (the caller re-fetches via get_cell).
  private addCounter = 1
  private addCell(args: {
    content: string; realm?: string; signal?: string; topic?: string
    summary?: string; key_points?: string[]; insight?: string; tags?: string[]
    importance?: number; valid_from?: string; status?: string
  }): { inserted: boolean; id: string; realm: string; signal: string | null; topic: string | null; status: string } {
    let n = this.addCounter++
    while (mockPalace.cells.some(c => c.id === `new-${n}`)) n = this.addCounter++
    const id = `new-${n}`
    const now = new Date().toISOString()
    const realm = args.realm ?? 'personal'
    const signal = (args.signal ?? null) as Cell['signal']
    const topic = args.topic ?? null
    const status = (args.status ?? 'committed') as Cell['status']
    const cell: Cell = {
      id,
      realm,
      signal,
      topic,
      title: topic ?? (args.content.slice(0, 40)),
      content: args.content,
      summary: args.summary ?? null,
      key_points: args.key_points ?? [],
      insight: args.insight ?? null,
      tags: args.tags ?? [],
      importance: (args.importance ?? 3) as Cell['importance'],
      status,
      created_by: 'mock-user',
      created_at: now,
      valid_from: args.valid_from ?? now,
      valid_until: null,
      attachments: [],
    }
    mockPalace.cells.push(cell)
    return { inserted: true, id, realm, signal, topic, status }
  }

  // Create a cell→cell tunnel. Mirrors add_tunnel, returning
  // { id, from_cell, to_cell, relation, note, status }.
  private tunnelCounter = 1
  private addTunnel(args: { from_cell: string; to_cell: string; relation: string; note?: string; status?: string }):
    { id: string; from_cell: string; to_cell: string; relation: string; note: string | null; status: string } {
    let n = this.tunnelCounter++
    while (mockPalace.tunnels.some(t => t.id === `tun-new-${n}`)) n = this.tunnelCounter++
    const id = `tun-new-${n}`
    const note = args.note ?? null
    const status = (args.status ?? 'committed') as Tunnel['status']
    const tunnel: Tunnel = {
      id,
      from_cell: args.from_cell,
      to_cell: args.to_cell,
      relation: args.relation as Tunnel['relation'],
      note,
      status,
      created_at: new Date().toISOString(),
      valid_until: null,
    }
    mockPalace.tunnels.push(tunnel)
    return { id, from_cell: args.from_cell, to_cell: args.to_cell, relation: args.relation, note, status }
  }

  // Append-only revision: close the old version (valid_until) and insert a new cell
  // with a fresh id and parent link. Mirrors the real revise_cell contract, which
  // returns only { old_id, new_id } (the caller re-fetches the new revision).
  private reviseCounter = 1
  private reviseCell(args: { old_id: string; new_content: string; new_summary?: string }): { old_id: string; new_id: string } {
    const old = mockPalace.cells.find(c => c.id === args.old_id)
    if (!old) throw new Error(`Cell not found: ${args.old_id}`)
    // Guard against id collisions — mockPalace is module-level shared state, so a fresh
    // client instance (counter reset) could otherwise reuse an id minted in a prior test.
    let n = this.reviseCounter++
    while (mockPalace.cells.some(c => c.id === `${args.old_id}-r${n}`)) n = this.reviseCounter++
    const newId = `${args.old_id}-r${n}`
    const now = new Date().toISOString()
    const clone: Cell = {
      ...old,
      id: newId,
      content: args.new_content,
      summary: args.new_summary ?? old.summary,
      valid_from: now,
      valid_until: null,
    }
    old.valid_until = now
    mockPalace.cells.push(clone)
    return { old_id: args.old_id, new_id: newId }
  }

  private quickFacts(args: { subject: string }): Fact[] {
    return mockPalace.facts.filter(f => f.subject === args.subject)
  }

  // Long-poll: holds the request for up to `timeout_ms`, returning as soon as
  // a new cell (and any now-ready tunnels) is available. Mirrors the contract
  // expected from a real server-sent long-poll endpoint.
  private async streamNext(args: { timeout_ms?: number }): Promise<{ cells: Cell[]; tunnels: Tunnel[]; done: boolean }> {
    if (!this.streamInitialized) {
      this.streamQueue = [...mockPalace.cells].sort((a, b) =>
        (a.valid_from ?? '').localeCompare(b.valid_from ?? ''))
      this.streamTunnelQueue = [...mockPalace.tunnels]
      this.streamInitialized = true
    }
    // Simulate server-side blocking wait. First call returns ~instantly so the
    // UI has seed data; subsequent calls wait ~1s before returning the next cell.
    // In the test runner use zero wait so cells stream instantly without timeouts.
    const isTest = (import.meta.env as unknown as Record<string, string>).MODE === 'test'
    const firstCall = this.streamDelivered.size === 0
    const wait = isTest ? 0 : (firstCall ? 0 : Math.min(args.timeout_ms ?? 25000, 900 + Math.random() * 500))
    if (wait > 0) await new Promise(r => setTimeout(r, wait))

    const next = this.streamQueue.shift()
    if (!next) return { cells: [], tunnels: [], done: true }
    this.streamDelivered.add(next.id)

    const ready: Tunnel[] = []
    const remaining: Tunnel[] = []
    for (const t of this.streamTunnelQueue) {
      if (this.streamDelivered.has(t.from_cell) && this.streamDelivered.has(t.to_cell)) ready.push(t)
      else remaining.push(t)
    }
    this.streamTunnelQueue = remaining
    return { cells: [next], tunnels: ready, done: this.streamQueue.length === 0 }
  }

  private traverse(args: { cell_id: string; depth?: number; max_depth?: number }): { edges: Tunnel[]; node_count: number; truncated: boolean } {
    const depth = args.max_depth ?? args.depth ?? 1
    const seen = new Set<string>([args.cell_id])
    const frontier = [args.cell_id]
    const result: Tunnel[] = []
    for (let d = 0; d < depth; d++) {
      const next: string[] = []
      for (const id of frontier) {
        for (const t of mockPalace.tunnels) {
          if (t.from_cell === id && !seen.has(t.to_cell)) { seen.add(t.to_cell); next.push(t.to_cell); result.push(t) }
          if (t.to_cell === id && !seen.has(t.from_cell)) { seen.add(t.from_cell); next.push(t.from_cell); result.push(t) }
        }
      }
      frontier.splice(0, frontier.length, ...next)
    }
    return { edges: result, node_count: seen.size, truncated: false }
  }

  private queenRuns() {
    return {
      items: [
        { id: 'run-001', agent: 'queen', trigger: 'scheduled', status: 'done',
          startedAt: '2026-06-02T03:00:00Z', finishedAt: '2026-06-02T03:00:14Z',
          durationMs: 14000, llmCalls: 4, costMicros: 18200 },
        { id: 'run-002', agent: 'isolated-cell-bee', trigger: 'subagent', status: 'done',
          startedAt: '2026-06-02T03:00:02Z', finishedAt: '2026-06-02T03:00:09Z',
          durationMs: 7000, llmCalls: 1, costMicros: 4100 },
        { id: 'run-003', agent: 'queen', trigger: 'scheduled', status: 'failed',
          startedAt: '2026-06-01T03:00:00Z', finishedAt: '2026-06-01T03:00:03Z',
          durationMs: 3000, llmCalls: 0, costMicros: 0 },
      ],
      total: 3,
      costAvailable: true,
    }
  }

  private queenRunDetail(args: { run_id: string }) {
    return {
      run: { id: args.run_id, status: 'done', summary: 'Surveyed 12 isolated cells, proposed 3 tunnels.',
             output: { proposals: 3 }, error: null },
      events: [
        { type: 'run_started', at: '2026-06-02T03:00:00Z' },
        { type: 'subagent_spawned', at: '2026-06-02T03:00:02Z', agent: 'isolated-cell-bee', cell_id: 'c-42' },
        { type: 'run_finished', at: '2026-06-02T03:00:14Z' },
      ],
    }
  }

  private queenPending() {
    return [
      { type: 'tunnel', id: 'p-1', description: 'c-42 → c-7 (related_to): both cover yoyo migrations',
        realm: 'engineering', signal: null, created_by: 'queen', created_at: '2026-06-02T03:00:13Z' },
      { type: 'tunnel', id: 'p-2', description: 'c-99 → c-13 (refines): supersedes the older rename note',
        realm: 'hivemem', signal: null, created_by: 'queen', created_at: '2026-06-02T03:00:14Z' },
    ]
  }

  private buildMediaSeed(): MediaItem[] {
    const makes: Array<[string, string]> = [
      ['Apple', 'iPhone 16 Pro'], ['Google', 'Pixel 9 Pro'], ['Apple', 'iPhone 15'],
    ]
    // [daysAgo, width, height, hasGps]
    const specs: Array<[number, number, number, boolean]> = [
      [0, 4032, 3024, true], [0, 3024, 4032, true], [0, 4032, 3024, false],
      [2, 3024, 4032, true], [5, 4032, 3024, false], [9, 4032, 3024, true],
      [12, 3024, 4032, false], [20, 4032, 3024, true], [28, 4032, 3024, false],
      [33, 3024, 4032, true], [40, 4032, 3024, true], [47, 4032, 3024, false],
      [55, 3024, 4032, true], [62, 4032, 3024, false],
    ]
    const base = Date.parse('2026-06-12T12:00:00Z')
    return specs.map(([daysAgo, w, h, hasGps], i) => {
      const taken = new Date(base - daysAgo * 86400000).toISOString()
      const [mk, md] = makes[i % makes.length]
      const noExif = i % 4 === 2 // ~every 4th has null camera/taken to exercise "—"
      const id = 'ph' + (i + 1)
      return {
        cell_id: 'media-' + id,
        attachment_id: 'att-' + id,
        realm: 'private',
        summary: noExif ? null : 'Foto ' + (i + 1),
        tags: ['subtype_photo_general'],
        mime_type: 'image/jpeg',
        size_bytes: 2_000_000 + i * 137_000,
        created_at: taken,
        taken_at: noExif ? null : taken,
        width: w,
        height: h,
        camera_make: noExif ? null : mk,
        camera_model: noExif ? null : md,
        gps_lat: hasGps ? 49.4874 : null,
        gps_lon: hasGps ? 8.4660 : null,
        place_name: hasGps ? 'Mannheim, DE' : null,
        thumbnail_uri: 'hivemem://attachments/att-' + id + '/thumbnail',
        content_uri: 'hivemem://attachments/att-' + id + '/content',
      }
    })
  }

  private listMedia(args: { realm?: string; sort?: string; limit?: number; offset?: number }): MediaItem[] {
    if (!this.mediaSeed) this.mediaSeed = this.buildMediaSeed()
    let rows = this.mediaSeed.slice()
    if (args.realm) rows = rows.filter(r => r.realm === args.realm)
    const eff = (r: MediaItem) => r.taken_at ?? r.created_at ?? ''
    rows.sort((a, b) => args.sort === 'oldest'
      ? eff(a).localeCompare(eff(b)) : eff(b).localeCompare(eff(a)))
    const offset = args.offset ?? 0
    const limit = args.limit ?? 100
    return rows.slice(offset, offset + limit)
  }
}

// Silence unused-import warnings for Reference — retained for when references are populated
export type { Reference }
