import { describe, it, expect, vi, beforeEach } from 'vitest'
import { triggerReauth, __resetReauthGuard } from '../../src/api/reauth'

describe('reauth', () => {
  beforeEach(() => {
    __resetReauthGuard()
    sessionStorage.clear()
  })

  it('reloads once in access mode', () => {
    const reload = vi.fn()
    triggerReauth('access', { reload, assign: vi.fn() })
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('does not reload twice within the guard window', () => {
    const reload = vi.fn()
    triggerReauth('access', { reload, assign: vi.fn() })
    triggerReauth('access', { reload, assign: vi.fn() })
    // A backend outage returns non-JSON on every poll; without the guard each poll would
    // navigate and the tab would loop forever.
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('redirects to /login in legacy mode', () => {
    const assign = vi.fn()
    triggerReauth('legacy', { reload: vi.fn(), assign })
    expect(assign).toHaveBeenCalledWith('/login')
  })
})
