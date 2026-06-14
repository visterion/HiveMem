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
  function arm() {
    if (armed) return
    armed = true
    history.pushState({ hmViewer: true }, '')
    window.addEventListener('popstate', handlePop)
    window.addEventListener('keydown', handleKey)
  }
  function requestClose() {
    if (armed) history.back() // -> popstate -> handlePop -> onClose
    else onClose()
  }

  // Guarded so the composable is also safe to call outside a component setup
  // (e.g. unit tests) without emitting a lifecycle warning.
  if (getCurrentInstance()) {
    onBeforeUnmount(() => { if (armed) { armed = false; teardown() } })
  }

  return { arm, requestClose }
}
