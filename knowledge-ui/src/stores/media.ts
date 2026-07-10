import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import type { MediaItem } from '../api/types'
import { groupPhotos, type PhotoGroup } from '../composables/photoGroups'

const PAGE_SIZE = 200

export const useMediaStore = defineStore('media', {
  state: () => ({
    photos: [] as MediaItem[],
    loading: false,
    loaded: false,
    /** True when the last page was full — older photos may exist (M57). */
    hasMore: false,
    error: null as string | null,
    lightboxIndex: null as number | null,
  }),
  getters: {
    groups(s): PhotoGroup<MediaItem>[] {
      return groupPhotos(s.photos, new Date())
    },
    lightboxItem(s): MediaItem | null {
      return s.lightboxIndex == null ? null : (s.photos[s.lightboxIndex] ?? null)
    },
  },
  actions: {
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
        this.photos = [...this.photos, ...rows]
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
