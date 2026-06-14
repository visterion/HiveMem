import { onBeforeUnmount } from 'vue'

// Module-level: only one armed instance at a time (one viewer open).
// Storing teardown here lets a new arm() displace a stale armed instance.
let _activeTeardown: (() => void) | null = null

export function useHistoryClose(onClose: () => void) {
  let armed = false
  let controller: AbortController | null = null

  function teardown() {
    controller?.abort()
    controller = null
    if (_activeTeardown === _myTeardown) _activeTeardown = null
  }
  function _myTeardown() { teardown() }
  function handlePop() {
    if (!armed) return
    armed = false
    teardown()
    onClose()
  }
  function handleKey(e: KeyboardEvent) {
    if (e.key === 'Escape') requestClose()
  }
  function arm() {
    if (armed) return
    // Displace any stale armed instance (test isolation / single-viewer guarantee)
    _activeTeardown?.()
    armed = true
    controller = new AbortController()
    const { signal } = controller
    _activeTeardown = _myTeardown
    history.pushState({ hmViewer: true }, '')
    window.addEventListener('popstate', handlePop, { signal })
    window.addEventListener('keydown', handleKey, { signal })
  }
  function requestClose() {
    if (armed) history.back() // -> popstate -> handlePop -> onClose
    else onClose()
  }

  onBeforeUnmount(() => { if (armed) { armed = false; teardown() } })

  return { arm, requestClose }
}
