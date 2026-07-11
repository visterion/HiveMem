import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import type { MediaItem } from '../api/types'
import { groupPhotos, type PhotoGroup } from '../composables/photoGroups'

const PAGE_SIZE = 200
// A Pinia getter only recomputes when a tracked reactive dependency changes;
// `new Date()` called directly inside `groups` isn't one, so the "Today"/"This
// week" boundaries stayed frozen at whatever moment `groups` first ran and only
// moved again if `photos` itself changed. `nowTick` is a real reactive
// dependency, refreshed on an interval, so the buckets actually roll over.
let clockTimer: ReturnType<typeof setInterval> | null = null

export const useMediaStore = defineStore('media', {
  state: () => ({
    photos: [] as MediaItem[],
    loading: false,
    loaded: false,
    /** True when the last page was full — older photos may exist (M57). */
    hasMore: false,
    error: null as string | null,
    lightboxIndex: null as number | null,
    nowTick: Date.now(),
  }),
  getters: {
    groups(s): PhotoGroup<MediaItem>[] {
      return groupPhotos(s.photos, new Date(s.nowTick))
    },
    lightboxItem(s): MediaItem | null {
      return s.lightboxIndex == null ? null : (s.photos[s.lightboxIndex] ?? null)
    },
  },
  actions: {
    // Start/stop the periodic nowTick refresh — called from the Photos page's
    // mount lifecycle so the interval doesn't run for the store's whole
    // (singleton) lifetime regardless of whether the gallery is even shown.
    startClock(intervalMs = 60_000) {
      if (clockTimer) return
      clockTimer = setInterval(() => { this.nowTick = Date.now() }, intervalMs)
    },
    stopClock() {
      if (clockTimer) { clearInterval(clockTimer); clockTimer = null }
    },
    async load() {
      if (this.loaded || this.loading) return
      this.loading = true
      this.error = null
      try {
        const rows = await useApi().call<MediaItem[]>('list_media', { sort: 'newest', limit: PAGE_SIZE }) ?? []
        this.photos = rows
        this.hasMore = rows.length >= PAGE_SIZE
        this.loaded = true
      } catch (e) {
        this.error = e instanceof Error ? e.message : 'load failed'
        this.photos = []
        this.hasMore = false
      } finally {
        this.loading = false
      }
    },
    // Append the next (older) page — before this, the gallery was silently hard-capped
    // at the first 200 photos (M57).
    async loadMore() {
      if (!this.loaded || this.loading || !this.hasMore) return
      this.loading = true
      try {
        const rows = await useApi().call<MediaItem[]>('list_media', {
          sort: 'newest', limit: PAGE_SIZE, offset: this.photos.length,
        }) ?? []
        // Paging by `offset: photos.length` over a newest-sorted feed: a photo
        // ingested between two loadMore() pages shifts every subsequent offset
        // by one, duplicating whichever row now straddles the page boundary.
        // De-duplicate on append by attachment_id (row identity).
        const seen = new Set(this.photos.map(p => p.attachment_id))
        const fresh = rows.filter(r => !seen.has(r.attachment_id))
        this.photos = [...this.photos, ...fresh]
        this.hasMore = rows.length >= PAGE_SIZE
      } catch (e) {
        this.error = e instanceof Error ? e.message : 'load failed'
      } finally {
        this.loading = false
      }
    },
    // Force a refetch of the first page (e.g. after an upload) — load() alone is
    // guarded by `loaded`, so uploads never appeared until a full page reload (M57).
    async refresh() {
      this.loaded = false
      this.hasMore = false
      this.lightboxIndex = null
      await this.load()
    },
    openLightbox(index: number) { this.lightboxIndex = index },
    closeLightbox() { this.lightboxIndex = null },
    next() {
      if (this.lightboxIndex == null || this.photos.length === 0) return
      this.lightboxIndex = (this.lightboxIndex + 1) % this.photos.length
    },
    prev() {
      if (this.lightboxIndex == null || this.photos.length === 0) return
      this.lightboxIndex = (this.lightboxIndex - 1 + this.photos.length) % this.photos.length
    },
  },
})
