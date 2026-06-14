import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useHistoryClose } from '../../src/composables/useHistoryClose'

describe('useHistoryClose', () => {
  beforeEach(() => { vi.restoreAllMocks() })
  afterEach(() => {
    // Drain any instance still armed because a test mocked history.back (so the
    // real popstate never fired). Keeps window listeners from leaking between tests.
    window.dispatchEvent(new PopStateEvent('popstate'))
    vi.restoreAllMocks()
  })

  it('arm() pushes a history sentinel', () => {
    const push = vi.spyOn(history, 'pushState')
    const { arm } = useHistoryClose(() => {})
    arm()
    expect(push).toHaveBeenCalledTimes(1)
    expect(push.mock.calls[0][0]).toMatchObject({ hmViewer: true })
  })

  it('popstate while armed calls onClose exactly once', () => {
    const onClose = vi.fn()
    const { arm } = useHistoryClose(onClose)
    arm()
    window.dispatchEvent(new PopStateEvent('popstate'))
    window.dispatchEvent(new PopStateEvent('popstate')) // already disarmed -> ignored
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('requestClose() while armed triggers history.back()', () => {
    const back = vi.spyOn(history, 'back').mockImplementation(() => {})
    const onClose = vi.fn()
    const { arm, requestClose } = useHistoryClose(onClose)
    arm()
    requestClose()
    expect(back).toHaveBeenCalledTimes(1)
    expect(onClose).not.toHaveBeenCalled() // close happens via the resulting popstate
  })

  it('Escape key triggers requestClose -> history.back()', () => {
    const back = vi.spyOn(history, 'back').mockImplementation(() => {})
    const { arm } = useHistoryClose(() => {})
    arm()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(back).toHaveBeenCalledTimes(1)
  })

  it('requestClose() when not armed calls onClose directly', () => {
    const onClose = vi.fn()
    const { requestClose } = useHistoryClose(onClose)
    requestClose()
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
