import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import type { FacetValue } from '../api/types'
import { realmMetaFor, realmColorFor, type RealmPriv } from '../composables/realmMeta'

export interface RealmEntry {
  id: string
  count: number
  priv: RealmPriv
  desc: string
  color: string
}

export const useRealmsStore = defineStore('realms', {
  state: () => ({
    realms: [] as RealmEntry[],
    loading: false,
    loaded: false,
  }),
  getters: {
    maxCount(s): number {
      return s.realms.reduce((m, r) => Math.max(m, r.count), 0)
    },
  },
  actions: {
    async loadRealms() {
      if (this.loaded) return
      this.loading = true
      try {
        const raw = await useApi().call<Record<string, FacetValue[]>>('facet_count', { fields: ['realm'] })
        const entries = raw['realm'] ?? []
        this.realms = entries
          .map(({ value, count }): RealmEntry => {
            const meta = realmMetaFor(value)
            return { id: value, count, priv: meta.priv, desc: meta.desc, color: realmColorFor(value) }
          })
          .sort((a, b) => b.count - a.count)
        this.loaded = true
      } finally {
        this.loading = false
      }
    },
  },
})
