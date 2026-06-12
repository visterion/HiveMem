import { defineStore } from 'pinia'
import { useApi } from '../api/useApi'
import type { MediaItem } from '../api/types'
import { groupPhotos, type PhotoGroup } from '../composables/photoGroups'

export const useMediaStore = defineStore('media', {
  state: () => ({
    photos: [] as MediaItem[],
    loading: false,
    loaded: false,
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
        this.photos = await useApi().call<MediaItem[]>('list_media', { sort: 'newest', limit: 200 })
        this.loaded = true
      } catch (e) {
        this.error = e instanceof Error ? e.message : 'load failed'
        this.photos = []
      } finally {
        this.loading = false
      }
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
