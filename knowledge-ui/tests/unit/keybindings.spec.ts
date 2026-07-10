import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useKeybindings } from '../../src/composables/keybindings'
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

  it('Escape from inside the reader dialog still closes the reader', () => {
    const reader = useReaderStore()
    reader.open = true
    const close = vi.spyOn(reader, 'close').mockImplementation(() => {})
    const readerDialog = document.createElement('div')
    readerDialog.setAttribute('role', 'dialog')
    const inner = document.createElement('button')
    readerDialog.appendChild(inner)
    document.body.appendChild(readerDialog)
    press(inner, 'Escape')
    expect(close).toHaveBeenCalledTimes(1)
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
