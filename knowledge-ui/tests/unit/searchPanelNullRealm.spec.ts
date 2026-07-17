import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import KnowledgeReader from '../../src/components/knowledge/KnowledgeReader.vue'
import { i18n } from '../../src/i18n'
import { resetApi } from '../../src/api/useApi'
import { MockApiClient } from '../../src/api/mockClient'
import { useKnowledgeSearch, __resetKnowledgeSearch } from '../../src/composables/useKnowledgeSearch'

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'search', component: { template: '<div/>' } },
      { path: '/realms', name: 'realms', component: { template: '<div/>' } },
    ],
  })
}

// The results list rendered by KnowledgeReader (the stage) must survive a realm=null cell —
// realmColorFor(null) used to throw on `.length`. (The results moved here out of SearchPanel.)
describe('knowledge results with realm=null', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'de'
    setActivePinia(createPinia())
    __resetKnowledgeSearch()
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
  })

  it('renders a result row with realm=null without throwing', async () => {
    vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(async (tool: string) => {
      if (tool === 'search') {
        // Cells have no `title` field (see cellLabel.ts) — cellLabel derives the label
        // from summary/content/topic, so `topic` is what surfaces the identifying text here.
        return [{ id: 'c1', realm: null, signal: null, topic: 'Inbox cell', score_total: 0.5 }]
      }
      return {}
    })
    // Seed the shared search with the null-realm result, then mount the stage that renders it.
    const search = useKnowledgeSearch()
    search.query.value = 'a'
    await search.run()
    const vuetify = createVuetify({ components, directives })
    const router = makeRouter(); router.push('/'); await router.isReady()
    const w = mount(KnowledgeReader, { global: { plugins: [vuetify, i18n, router] } })
    await flushPromises()
    expect(w.text()).toContain('Inbox cell')
  })
})
