import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ObsidianImportDialog from '../../src/components/knowledge/ObsidianImportDialog.vue'
import { i18n } from '../../src/i18n'
import { resetApi } from '../../src/api/useApi'

const { loadAsyncMock } = vi.hoisted(() => ({ loadAsyncMock: vi.fn() }))
vi.mock('jszip', () => ({
  default: { loadAsync: loadAsyncMock },
}))

function zipStub(fileNames: string[]) {
  const files: Record<string, { dir: boolean; async: (t: string) => Promise<string> }> = {}
  for (const name of fileNames) files[name] = { dir: false, async: async () => `# ${name}\nbody` }
  return { files }
}

describe('ObsidianImportDialog — file input reset (E6)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
    loadAsyncMock.mockReset()
  })

  it('clears the file input value after handling a file — real browsers only re-fire "change" for an identical filename once the value is reset', async () => {
    loadAsyncMock.mockResolvedValue(zipStub(['note.md']))
    const w = mount(ObsidianImportDialog, { global: { plugins: [i18n] } })
    await flushPromises()

    const input = w.find('input[data-test="obsidian-file"]').element as HTMLInputElement
    const file = new File(['zip-bytes'], 'vault.zip', { type: 'application/zip' })
    Object.defineProperty(input, 'files', { value: [file], configurable: true })

    // Spy on the native `value` setter (real browsers refuse to let JS set a
    // file input's value to anything but '', so we can't fake a non-empty
    // starting value — instead assert the handler explicitly writes '' back,
    // which is what makes the browser treat a later identical selection as a
    // real change again).
    const nativeDesc = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!
    const valueWrites: string[] = []
    Object.defineProperty(input, 'value', {
      configurable: true,
      get() { return nativeDesc.get!.call(input) },
      set(v: string) { valueWrites.push(v); nativeDesc.set!.call(input, v) },
    })

    await w.find('input[data-test="obsidian-file"]').trigger('change')
    await flushPromises()

    expect(loadAsyncMock).toHaveBeenCalledTimes(1)
    expect(valueWrites).toContain('') // onFile() resets the input so a re-pick of the same file still fires 'change'
  })
})
