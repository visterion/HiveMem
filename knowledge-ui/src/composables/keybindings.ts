import { useEventListener } from '@vueuse/core'
import { useUiStore } from '../stores/ui'
import { useReaderStore } from '../stores/reader'
import { useCellStore } from '../stores/cell'
import { useCanvasStore } from '../stores/canvas'
import { i18n } from '../i18n'

export function useKeybindings() {
  const ui = useUiStore(), reader = useReaderStore(), cell = useCellStore()
  const canvas = useCanvasStore()
  useEventListener('keydown', (e: KeyboardEvent) => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault(); ui.activePanel = 'search'
    } else if (e.key === 'Escape') {
      if (reader.open) reader.close()
      else if (cell.currentId) {
        cell.clear()
        canvas.setFocus(null)
        canvas.setHover(null)
      }
      else if (ui.activePanel) ui.activePanel = null
    } else if (e.key === 'Enter' && cell.currentId && !reader.open) {
      reader.openReader(cell.currentId)
    } else if (e.key === '?' && !ui.showLoginDialog) {
      ui.pushToast('info', i18n.global.t('keybindings.hints'))
    }
  })
}
