import { describe, it, expect, beforeEach, vi } from 'vitest'
import { HttpApiClient } from '../../src/api/httpClient'

describe('HttpApiClient', () => {
  beforeEach(() => { vi.restoreAllMocks() })

  it('sends JSON-RPC with bearer token', async () => {
    const fetchMock = vi.fn(async (_url: string, init: RequestInit) => {
      const headers = init.headers as Record<string, string>
      expect(headers['Authorization']).toBe('Bearer test-token')
      const body = JSON.parse(init.body as string)
      expect(body.method).toBe('tools/call')
      expect(body.params.name).toBe('status')
      // Real MCP tools/call envelope: payload is JSON in result.content[0].text
      return new Response(JSON.stringify({
        jsonrpc: '2.0', id: body.id,
        result: { content: [{ type: 'text', text: JSON.stringify({ drawer_count: 42 }) }] },
      }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const c = new HttpApiClient({ endpoint: '/mcp', token: 'test-token' })
    const r = await c.call<{ drawer_count: number }>('status')
    expect(r.drawer_count).toBe(42)
  })

  it('surfaces the backend JSON-RPC error message on non-2xx responses (L-F7)', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ jsonrpc: '2.0', id: 1, error: { code: -32603, message: 'embedding service unavailable' } }),
      { status: 500 },
    )))
    const c = new HttpApiClient({ endpoint: '/mcp', token: 't' })
    await expect(c.call('search')).rejects.toThrow('embedding service unavailable')
  })

  it('falls back to the HTTP status for non-JSON error bodies', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html>bad gateway</html>', { status: 502 })))
    const c = new HttpApiClient({ endpoint: '/mcp', token: 't' })
    await expect(c.call('status')).rejects.toThrow('HTTP 502')
  })

  it('attaches an abort/timeout signal so requests cannot hang forever (L-F7)', async () => {
    const fetchMock = vi.fn(async (_url: string, init: RequestInit) => {
      expect(init.signal).toBeTruthy()
      return new Response(JSON.stringify({
        jsonrpc: '2.0', id: 1, result: { content: [{ type: 'text', text: '{}' }] },
      }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const c = new HttpApiClient({ endpoint: '/mcp', token: 't' })
    await c.call('status')
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('throws on JSON-RPC error', async () => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify({ jsonrpc: '2.0', id: 1, error: { code: -32601, message: 'Method not found' } }))
    ))
    const c = new HttpApiClient({ endpoint: '/mcp', token: 't' })
    await expect(c.call('bogus')).rejects.toThrow('Method not found')
  })

  it('subscribe polls status and emits on change', async () => {
    let t = '2026-04-19T12:00:00Z'
    // Real MCP tools/call envelope: status payload is JSON in result.content[0].text
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify({
        jsonrpc: '2.0', id: 1,
        result: { content: [{ type: 'text', text: JSON.stringify({ last_activity: t }) }] },
      }))
    ))
    const c = new HttpApiClient({ endpoint: '/mcp', token: 't', pollMs: 10 })
    const events: string[] = []
    const unsub = c.subscribe(e => events.push(e.type))
    await new Promise(r => setTimeout(r, 30))
    t = '2026-04-19T12:00:05Z'
    await new Promise(r => setTimeout(r, 30))
    unsub()
    expect(events).toContain('status')
  })
})
