import { getCurrentInstance, onBeforeUnmount } from 'vue'

export function useHistoryClose(onClose: () => void) {
  let armed = false

  function teardown() {
    window.removeEventListener('popstate', handlePop)
    window.removeEventListener('keydown', handleKey)
  }
  function handlePop() {
    if (!armed) return
    armed = false
    teardown()
    onClose()
  }
  function handleKey(e: KeyboardEvent) {
    if (e.key === 'Escape') requestClose()
  }
  // url lets callers embed a deep-link query param (e.g. ?doc=<id> / ?cell=<id>) into
  // the pushed entry, so the address bar reflects what's open and the link is
  // shareable. Falls back to the current URL when the caller has nothing to add.
  function arm(url?: string) {
    if (armed) return
    armed = true
    history.pushState({ hmViewer: true }, '', url ?? location.href)
    window.addEventListener('popstate', handlePop)
    window.addEventListener('keydown', handleKey)
  }
  function requestClose() {
    if (armed) history.back() // -> popstate -> handlePop -> onClose
    else onClose()
  }
  // Clean up after a close that did NOT go through requestClose (e.g. the host
  // was closed out-of-band by a route guard or another store action). Removes the
  // listeners first, then pops our sentinel so it can't linger in the back-stack.
  // onClose is NOT called again — the host is already closed.
  function disarm() {
    if (!armed) return
    armed = false
    teardown()
    history.back()
  }

  // Guarded so the composable is also safe to call outside a component setup
  // (e.g. unit tests) without emitting a lifecycle warning.
  if (getCurrentInstance()) {
    onBeforeUnmount(() => { if (armed) { armed = false; teardown() } })
  }

  return { arm, requestClose, disarm }
}
