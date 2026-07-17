import { defineStore } from 'pinia'
import type { Role } from '../api/types'
import { useApi, resetApi } from '../api/useApi'
import { applyBackendDefault } from '../i18n'
import { authMode } from '../api/authMode'
import { triggerReauth } from '../api/reauth'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    role: null as Role | null,
    identity: null as string | null
  }),
  getters: { isAuthenticated: (s) => !!s.role },
  actions: {
    async init() {
      const api = useApi()
      const w = await api.call<{ role: Role; identity: string; default_language?: string }>('wake_up')
      this.role = w.role
      this.identity = w.identity
      applyBackendDefault(w.default_language)

      // A non-admin who deep-links straight to /queen lands there before wake_up
      // resolves — router.ts's adminGuard runs at navigation time, when the role
      // is still unknown, and (by design) lets an unknown role through. Once the
      // role actually resolves here, re-check the CURRENT route: if it's admin-only
      // and this user isn't admin, nothing else re-navigates them away, and they'd
      // stay on /queen indefinitely. Dynamic import avoids the static import
      // cycle (router.ts imports useAuthStore).
      if (this.role !== 'admin') {
        const { router } = await import('../router')
        if (router.currentRoute.value.meta.role === 'admin') {
          await router.replace({ name: 'search' })
        }
      }
    },
    async logout() {
      const mode = authMode()
      try {
        if (mode === 'legacy') {
          await fetch('/logout', { method: 'POST' })
        }
      } finally {
        // Clear client state even if the network call failed — the UI must never
        // stay "logged in" after the user asked to leave (L-F13).
        this.role = null
        this.identity = null
        resetApi()
        if (mode === 'access') {
          window.location.href = '/cdn-cgi/access/logout'
        } else {
          triggerReauth(mode)
        }
      }
    }
  }
})
