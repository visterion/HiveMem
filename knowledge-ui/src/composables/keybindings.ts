import { useEventListener } from '@vueuse/core'
import { useUiStore } from '../stores/ui'
import { useReaderStore } from '../stores/reader'
import { useCellStore } from '../stores/cell'
import { useCanvasStore } from '../stores/canvas'
import { i18n } from '../i18n'

function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false
  return !!target.closest('input, textarea, select, [contenteditable="true"], .cm-editor')
}

function isInDialog(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false
  return !!target.closest('[role="dialog"], .v-overlay')
}

export function useKeybindings() {
  const ui = useUiStore(), reader = useReaderStore(), cell = useCellStore()
  const canvas = useCanvasStore()
  useEventListener('keydown', (e: KeyboardEvent) => {
    const typing = isTypingTarget(e.target)
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault(); ui.activePanel = 'search'
    } else if (e.key === 'Escape') {
      if (reader.open) reader.close()
      // Any other visible dialog owns Escape — don't clear background state under it.
      else if (isInDialog(e.target)) return
      else if (cell.currentId) {
        cell.clear()
        canvas.setFocus(null)
        canvas.setHover(null)
      }
      else if (ui.activePanel) ui.activePanel = null
    } else if (!typing && e.key === 'Enter' && cell.currentId && !reader.open) {
      reader.openReader(cell.currentId)
    } else if (!typing && e.key === '?' && !ui.showLoginDialog) {
      ui.pushToast('info', i18n.global.t('keybindings.hints'))
    }
  })
}
