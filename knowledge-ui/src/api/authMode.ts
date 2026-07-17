export type AuthMode = 'access' | 'legacy'

let mode: AuthMode | null = null

/**
 * Fetched once at startup and kept in memory. The re-auth handler must never depend on
 * a live fetch: in Access mode /api/config sits behind Access, so fetching it after the
 * session expired would hit the very redirect/TypeError we are trying to classify.
 *
 * Bounded with a short timeout: a slow/unreachable backend (dev-proxy to a down backend,
 * offline page load) must not hang App.vue's startup forever — a missing/late answer here
 * falls back to 'legacy' fast, same as any other fetch failure.
 */
export async function loadAuthMode(): Promise<AuthMode> {
  if (mode) return mode
  // Mock mode means "no backend" — there is no /api/config to consult, and authMode is
  // never used for a real decision (MockApiClient never goes through httpClient/triggerReauth).
  // Skip the fetch so mock/offline loads mount instantly instead of waiting out the timeout.
  // Mirrors the mock-detection condition useApi.ts uses for its localStorage check.
  if (typeof localStorage !== 'undefined' && localStorage.getItem('hivemem_mock') === 'true') {
    mode = 'legacy'
    return mode
  }
  try {
    const res = await fetch('/api/config', { credentials: 'same-origin', signal: AbortSignal.timeout(1500) })
    const body = await res.json()
    mode = body.authMode === 'access' ? 'access' : 'legacy'
  } catch {
    mode = 'legacy'
  }
  return mode
}

export function authMode(): AuthMode {
  if (!mode) throw new Error('auth mode not loaded — call loadAuthMode() before any data call')
  return mode
}

/** Test seam: reset the cached mode so specs can call loadAuthMode() fresh. */
export function __resetAuthMode(): void {
  mode = null
}
