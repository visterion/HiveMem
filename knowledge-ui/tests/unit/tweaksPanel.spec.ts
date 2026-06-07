import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TweaksPanel from '../../src/components/shell/TweaksPanel.vue'
import { i18n } from '../../src/i18n'
import { usePrefsStore } from '../../src/stores/prefs'

describe('TweaksPanel', () => {
  beforeEach(() => { setActivePinia(createPinia()); i18n.global.locale.value = 'de'; localStorage.clear() })

  it('opens and updates accent / density / hive via the store', async () => {
    const prefs = usePrefsStore(); prefs.init()
    const w = mount(TweaksPanel, { global: { plugins: [i18n] } })
    await w.find('[data-test="tweaks-toggle"]').trigger('click')
    await w.find('[data-test="accent-#9BCB3C"]').trigger('click')
    expect(prefs.accent).toBe('#9BCB3C')
    await w.find('[data-test="density-compact"]').trigger('click')
    expect(prefs.density).toBe('compact')
    await w.find('[data-test="hive-stark"]').trigger('click')
    expect(prefs.hive).toBe('stark')
  })
})
