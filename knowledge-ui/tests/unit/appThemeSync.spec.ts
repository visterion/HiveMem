import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import App from '../../src/App.vue'
import { i18n } from '../../src/i18n'
import { resetApi } from '../../src/api/useApi'
import { MockApiClient } from '../../src/api/mockClient'
import { usePrefsStore } from '../../src/stores/prefs'

// App.vue reads/writes vuetify's theme (useTheme()), so — like appAuthError.spec.ts —
// this instance needs the app's real theme names ('hivememDark'/'hivememLight') registered.
const testVuetify = createVuetify({
  components, directives,
  theme: { defaultTheme: 'hivememDark', themes: { hivememDark: { dark: true, colors: {} }, hivememLight: { dark: false, colors: {} } } },
})

const globalOpts = {
  global: {
    plugins: [i18n, testVuetify],
    stubs: { AppShell: true, VSnackbar: { template: '<div><slot /><slot name="actions" /></div>' } },
  },
}

describe('App.vue theme sync (uses theme.change, not the deprecated theme.global.name)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
    vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(async (tool: string) => {
      if (tool === 'wake_up') return { role: 'admin', identity: 'me' }
      return {}
    })
  })
  afterEach(() => vi.restoreAllMocks())

  it('calls theme.change when prefs.theme changes (not a direct theme.global.name assignment)', async () => {
    // theme.change() is the only API surface that should drive the switch. If App.vue
    // regressed to `vTheme.global.name.value = ...`, this spy would never be invoked
    // and the assertions below would fail — that's the behaviour we're pinning.
    const changeSpy = vi.spyOn(testVuetify.theme, 'change')

    const w = mount(App, globalOpts)
    await flushPromises()

    // immediate:true watcher fires at mount with the default theme ('dark')
    expect(changeSpy).toHaveBeenCalledWith('hivememDark')

    const prefs = usePrefsStore()
    prefs.setTheme('light')
    await flushPromises()

    expect(changeSpy).toHaveBeenCalledWith('hivememLight')

    w.unmount()
  })
})
