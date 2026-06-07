export type Role = 'admin' | 'writer' | 'reader' | 'agent'
export type Relation = 'related_to' | 'builds_on' | 'contradicts' | 'refines'
export type CellStatus = 'committed' | 'pending' | 'rejected'

export interface Attachment {
  id: string
  mime_type: string
  original_filename: string
  size_bytes: number
}

export interface Cell {
  id: string
  realm: string
  signal: string | null
  topic: string | null
  title: string
  content: string
  summary: string | null
  key_points: string[]
  insight: string | null
  tags: string[]
  importance: 1 | 2 | 3
  status: CellStatus
  created_by: string
  created_at: string
  valid_from: string
  valid_until: string | null
  attachments?: Attachment[]
}

export interface SearchResult extends Cell {
  score_total: number
  score_semantic: number
  score_keyword: number
  score_recency: number
  score_importance: number
  score_popularity: number
  score_graph_proximity: number
  confidence_level?: string
}

export interface Realm { name: string; cell_count: number; signals: Signal[] }
export interface Signal { name: string; cell_count: number; topics: Topic[] }
export interface Topic { name: string; cell_count: number }

export interface Tunnel {
  id: string
  from_cell: string
  to_cell: string
  relation: Relation
  note: string | null
  status: CellStatus
  created_at: string
  valid_until: string | null
}

export interface Fact {
  id: string
  subject: string
  predicate: string
  object: string
  valid_from: string
  valid_until: string | null
}

export interface Reference {
  id: string
  title: string
  url: string | null
  ref_type: 'article' | 'paper' | 'book' | 'attachment' | 'other'
  status: 'unread' | 'reading' | 'done'
}

export interface StatusSummary {
  cell_count: number
  fact_count: number
  realm_count: number
  tunnel_count: number
  pending_count: number
  last_activity: string
}

export type HiveEvent =
  | { type: 'cell_added'; cell: Cell }
  | { type: 'cell_revised'; id: string; parent_id: string }
  | { type: 'tunnel_added'; tunnel: Tunnel }
  | { type: 'status'; last_activity: string }

export interface ApiClient {
  call<T>(tool: string, args?: Record<string, unknown>): Promise<T>
  subscribe(onEvent: (e: HiveEvent) => void): () => void
}

export interface QueenRun {
  id: string
  agent: string
  trigger: string | null
  status: string
  startedAt: string | null
  finishedAt: string | null
  durationMs: number | null
  llmCalls: number | null
  costMicros: number | null
}

export interface QueenRunList {
  items: QueenRun[]
  total: number
  costAvailable: boolean
  unavailable?: boolean
}

export interface QueenRunEvent {
  type: string
  [key: string]: unknown
}

export interface QueenRunDetail {
  run: Record<string, unknown>
  events: QueenRunEvent[]
  unavailable?: boolean
}

export interface PendingApproval {
  type: string
  id: string
  description: string | null
  realm: string | null
  signal: string | null
  created_by: string | null
  created_at: string
}

export interface DocumentRow {
  id: string
  realm: string
  signal: string | null
  topic: string | null
  summary: string | null
  tags: string[]
  importance: number
  status: string
  created_at: string
  attachment_id?: string | null
  mime_type?: string | null
  page_count?: number | null
  has_thumbnail?: boolean
  confidence?: number | null
  /** Derived client-side from fact:vendor / fact:party */
  correspondent?: string | null
}

export interface FacetValue { value: string; count: number }
export type FacetCounts = Record<string, FacetValue[]>

export interface SavedSearch {
  id: string
  name: string
  filter: Record<string, unknown>
  created_at?: string
}
