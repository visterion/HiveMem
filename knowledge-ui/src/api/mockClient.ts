import { palace as mockPalace } from '../data/mock'
import type { ApiClient, HiveEvent, Cell, Realm, Signal, Tunnel, Fact, StatusSummary, Reference } from './types'

interface MockConfig { latencyMs?: [number, number]; eventInterval?: number }

type Handler = (args: any) => unknown

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

  constructor(config: MockConfig = {}) {
    this.config = { latencyMs: [50, 200], eventInterval: 15000, ...config }
    this.handlers = {
      status: () => this.status(),
      wake_up: () => this.wakeUp(),
      list: (args: { realm?: string }) => this.listRealms(args),
      search: (args: { query?: string; limit?: number }) => this.search(args),
      get_cell: (args: { cell_id: string }) => this.getCell(args),
      quick_facts: (args: { subject: string }) => this.quickFacts(args),
      traverse: (args: { cell_id: string; depth?: number }) => this.traverse(args),
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

  private search(args: { query?: string; limit?: number }): Cell[] {
    const q = (args.query || '').toLowerCase()
    const all = q
      ? mockPalace.cells.filter(c => c.title.toLowerCase().includes(q) || c.content.toLowerCase().includes(q))
      : mockPalace.cells
    return all.slice(0, args.limit ?? 100)
  }

  private getCell(args: { cell_id: string }): Cell {
    const c = mockPalace.cells.find(x => x.id === args.cell_id)
    if (!c) throw new Error(`Cell not found: ${args.cell_id}`)
    return c
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
    const firstCall = this.streamDelivered.size === 0
    const wait = firstCall ? 0 : Math.min(args.timeout_ms ?? 25000, 900 + Math.random() * 500)
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

  private traverse(args: { cell_id: string; depth?: number }): Tunnel[] {
    const depth = args.depth ?? 1
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
    return result
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
}

// Silence unused-import warnings for Reference — retained for when references are populated
export type { Reference }
