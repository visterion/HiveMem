import type { AuthMode } from './authMode'

const GUARD_KEY = 'hivemem_reauth_at'
const GUARD_MS = 5 * 60 * 1000

interface Nav {
  reload: () => void
  assign: (url: string) => void
}

const defaultNav: Nav = {
  reload: () => window.location.reload(),
  assign: (url) => { window.location.href = url },
}

/**
 * Single re-auth path for the whole app. In Access mode a full navigation is required —
 * Cloudflare answers an expired XHR with a cross-origin redirect (often surfacing as a
 * fetch TypeError), which no fetch handler can follow. The guard stops a backend outage
 * from turning every 10s poll into a reload loop.
 */
export function triggerReauth(mode: AuthMode, nav: Nav = defaultNav): void {
  if (mode === 'legacy') {
    nav.assign('/login')
    return
  }
  const last = Number(sessionStorage.getItem(GUARD_KEY) ?? 0)
  if (Date.now() - last < GUARD_MS) return
  sessionStorage.setItem(GUARD_KEY, String(Date.now()))
  nav.reload()
}

/** Test seam. */
export function __resetReauthGuard(): void {
  sessionStorage.removeItem(GUARD_KEY)
}
