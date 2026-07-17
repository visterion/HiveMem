import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import SearchPanel from '../../src/components/knowledge/SearchPanel.vue'
import { i18n } from '../../src/i18n'
import { resetApi } from '../../src/api/useApi'
import { MockApiClient } from '../../src/api/mockClient'

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'search', component: { template: '<div/>' } },
      { path: '/realms', name: 'realms', component: { template: '<div/>' } },
    ],
  })
}

describe('knowledge SearchPanel with realm=null', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'de'
    setActivePinia(createPinia())
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
    vi.useFakeTimers()
  })
  afterEach(() => vi.useRealTimers())

  // A search result whose realm is null (an unclassified inbox cell) must render without throwing.
  it('renders a result row with realm=null', async () => {
    vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(async (tool: string) => {
      if (tool === 'search') {
        // Cells have no `title` field (see cellLabel.ts) — cellLabel derives the label
        // from summary/content/topic, so `topic` is what surfaces the identifying text here.
        return [{ id: 'c1', realm: null, signal: null, topic: 'Inbox cell', score_total: 0.5 }]
      }
      if (tool === 'facet_count') return {}
      return {}
    })
    const vuetify = createVuetify({ components, directives })
    const router = makeRouter(); router.push('/'); await router.isReady()
    const w = mount(SearchPanel, { global: { plugins: [vuetify, i18n, router] } })
    await w.find('input').setValue('a')
    await vi.advanceTimersByTimeAsync(500)
    await flushPromises()
    expect(w.text()).toContain('Inbox cell')
  })
})
