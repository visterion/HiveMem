import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useKeybindings } from '../../src/composables/keybindings'
import { useHistoryClose } from '../../src/composables/useHistoryClose'
import { useCellStore } from '../../src/stores/cell'
import { useReaderStore } from '../../src/stores/reader'
import { useUiStore } from '../../src/stores/ui'

const Host = defineComponent({
  setup() {
    useKeybindings()
    return () => null
  }
})

function press(target: HTMLElement, key: string) {
  target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }))
}

describe('useKeybindings input/dialog guards', () => {
  let wrapper: VueWrapper
  let input: HTMLInputElement
  let dialogInput: HTMLInputElement

  beforeEach(() => {
    setActivePinia(createPinia())
    wrapper = mount(Host, { attachTo: document.body })
    input = document.createElement('input')
    document.body.appendChild(input)
    const dialog = document.createElement('div')
    dialog.setAttribute('role', 'dialog')
    dialogInput = document.createElement('input')
    dialog.appendChild(dialogInput)
    document.body.appendChild(dialog)
  })

  afterEach(() => {
    wrapper.unmount()
    document.body.innerHTML = ''
  })

  it('Enter on the page body opens the reader for the current cell', () => {
    const cell = useCellStore(); const reader = useReaderStore()
    cell.currentId = 'c1'
    const open = vi.spyOn(reader, 'openReader').mockImplementation(() => {})
    press(document.body, 'Enter')
    expect(open).toHaveBeenCalledWith('c1')
  })

  it('Enter inside a text input does NOT open the reader', () => {
    const cell = useCellStore(); const reader = useReaderStore()
    cell.currentId = 'c1'
    const open = vi.spyOn(reader, 'openReader').mockImplementation(() => {})
    press(input, 'Enter')
    expect(open).not.toHaveBeenCalled()
  })

  it('"?" inside a text input does NOT toast the hints', () => {
    const ui = useUiStore()
    const toast = vi.spyOn(ui, 'pushToast')
    press(input, '?')
    expect(toast).not.toHaveBeenCalled()
    press(document.body, '?')
    expect(toast).toHaveBeenCalledTimes(1)
  })

  it('Escape does NOT call reader.close() directly while the reader is open (H9)', () => {
    // useHistoryClose (armed by Reader.vue while open) owns Escape for the reader —
    // it pushed the history sentinel, so it must be the only path that closes it.
    // If useKeybindings also called reader.close() here, both paths would each
    // trigger a history.back(), navigating the user out of the route.
    const reader = useReaderStore()
    reader.open = true
    const close = vi.spyOn(reader, 'close').mockImplementation(() => {})
    const readerDialog = document.createElement('div')
    readerDialog.setAttribute('role', 'dialog')
    const inner = document.createElement('button')
    readerDialog.appendChild(inner)
    document.body.appendChild(readerDialog)
    press(inner, 'Escape')
    expect(close).not.toHaveBeenCalled()
  })

  it('Escape while the reader is open triggers exactly one history.back(), via useHistoryClose alone (H9)', () => {
    const reader = useReaderStore()
    reader.open = true
    const back = vi.spyOn(history, 'back').mockImplementation(() => {})
    const { arm } = useHistoryClose(() => reader.close())
    arm()
    press(document.body, 'Escape')
    expect(back).toHaveBeenCalledTimes(1)
    // cleanup: this instance is still "armed" from the mocked back(); drain it
    // so the real popstate listener doesn't leak into later tests.
    window.dispatchEvent(new PopStateEvent('popstate'))
  })

  it('Escape inside a dialog does NOT clear the selected cell', () => {
    const cell = useCellStore()
    cell.currentId = 'c1'
    const clear = vi.spyOn(cell, 'clear')
    press(dialogInput, 'Escape')
    expect(clear).not.toHaveBeenCalled()
    press(document.body, 'Escape')
    expect(clear).toHaveBeenCalledTimes(1)
  })
})
