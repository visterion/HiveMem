import { describe, expect, it } from 'vitest'
import { router } from '../../src/router'
import { createPinia, setActivePinia } from 'pinia'
import { adminGuard } from '../../src/router'
import { useAuthStore } from '../../src/stores/auth'
import type { RouteLocationNormalized } from 'vue-router'

const asRoute = (meta: Record<string, unknown>) => ({ meta } as RouteLocationNormalized)

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

  it('scans route maps default + panel components and is not full', () => {
    const byName = Object.fromEntries(router.getRoutes().map(r => [r.name, r]))
    expect(byName.scans.components).toHaveProperty('default')
    expect(byName.scans.components).toHaveProperty('panel')
    expect(byName.scans.meta.full).toBeFalsy()
  })

  it('realms route maps default + panel components and is not full', () => {
    const byName = Object.fromEntries(router.getRoutes().map(r => [r.name, r]))
    expect(byName.realms.components).toBeTruthy()
    expect(byName.realms.components).toHaveProperty('default')
    expect(byName.realms.components).toHaveProperty('panel')
    expect(byName.realms.components).not.toHaveProperty('inspector')
    expect(byName.realms.meta.full).toBeFalsy()
  })
})

describe('adminGuard', () => {
  it('redirects a known non-admin away from admin routes', () => {
    setActivePinia(createPinia())
    useAuthStore().role = 'writer' as never
    expect(adminGuard(asRoute({ role: 'admin' }))).toEqual({ name: 'search' })
  })

  it('lets admins and unknown roles through, and ignores non-admin routes', () => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    expect(adminGuard(asRoute({ role: 'admin' }))).toBe(true) // role unknown yet
    auth.role = 'admin' as never
    expect(adminGuard(asRoute({ role: 'admin' }))).toBe(true)
    auth.role = 'writer' as never
    expect(adminGuard(asRoute({}))).toBe(true)
  })
})
