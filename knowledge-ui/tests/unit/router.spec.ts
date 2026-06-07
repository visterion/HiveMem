import { describe, expect, it } from 'vitest'
import { router } from '../../src/router'

describe('router', () => {
  it('has the SP-A route table with meta', () => {
    const byName = Object.fromEntries(router.getRoutes().map(r => [r.name, r]))
    expect(byName.search.path).toBe('/')
    expect(byName.search.meta.title).toBe('nav.search')
    expect(byName.hive.path).toBe('/hive')
    for (const n of ['search','hive','graph','realms','photos','scans','timemachine','queen','settings']) {
      expect(byName[n], `route ${n}`).toBeTruthy()
      expect(byName[n].meta.title, `meta.title ${n}`).toBeTruthy()
    }
    expect(byName.graph.meta.full).toBe(true)
    expect(byName.search.meta.full).toBeFalsy()
    expect(byName.cinema.path).toBe('/cinema') // kept, not in rail
  })

  it('search route maps default + panel + inspector components', () => {
    const byName = Object.fromEntries(router.getRoutes().map(r => [r.name, r]))
    expect(byName.search.components).toBeTruthy()
    expect(byName.search.components).toHaveProperty('default')
    expect(byName.search.components).toHaveProperty('panel')
    expect(byName.search.components).toHaveProperty('inspector')
  })
})
