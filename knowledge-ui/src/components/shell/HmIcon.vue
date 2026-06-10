<script setup lang="ts">
import { computed } from 'vue'
const props = withDefaults(defineProps<{ name: string; size?: number }>(), { size: 22 })
// Each entry is the inner markup of the 24x24 stroke icon.
const PATHS: Record<string, string> = {
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.2-3.2"/>',
  graph: '<circle cx="6" cy="7" r="2.2"/><circle cx="18" cy="6" r="2.2"/><circle cx="16" cy="18" r="2.2"/><circle cx="8" cy="16" r="2.2"/><path d="M8 8.5 14.5 16M8.2 7.6 16 6.4M7.7 14 15.8 7.2M9.8 16.6 14.2 17.6"/>',
  realms: '<path d="M12 2 21 7v10l-9 5-9-5V7z"/><path d="M12 2v20M3 7l9 5 9-5"/>',
  reader: '<path d="M3 5.5A2 2 0 0 1 5 4h6v15H5a2 2 0 0 0-2 2z"/><path d="M21 5.5A2 2 0 0 0 19 4h-6v15h6a2 2 0 0 1 2 2z"/>',
  photos: '<rect x="3" y="4" width="18" height="16" rx="2.2"/><circle cx="8.5" cy="9.5" r="1.6"/><path d="m4 17 4.5-4.5L12 16l3-3 5 5"/>',
  scans: '<path d="M7 3H5a2 2 0 0 0-2 2v2M17 3h2a2 2 0 0 1 2 2v2M7 21H5a2 2 0 0 1-2-2v-2M17 21h2a2 2 0 0 0 2-2v-2"/><path d="M7 12h10" stroke-dasharray="0.1 3.4"/><path d="M8 8h8M8 16h6"/>',
  history: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 4v4h4"/><path d="M12 8v4l3 2"/>',
  queen: '<path d="M4 18h16M5 18l-1.5-8 4 3L12 6l4.5 7 4-3L19 18"/><circle cx="3.5" cy="9" r="1.1"/><circle cx="20.5" cy="9" r="1.1"/><circle cx="12" cy="4.5" r="1.1"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 12a7.4 7.4 0 0 0-.1-1.3l2-1.5-2-3.4-2.3 1a7.3 7.3 0 0 0-2.2-1.3L14.4 2H9.6l-.4 2.5a7.3 7.3 0 0 0-2.2 1.3l-2.3-1-2 3.4 2 1.5a7.4 7.4 0 0 0 0 2.6l-2 1.5 2 3.4 2.3-1a7.3 7.3 0 0 0 2.2 1.3l.4 2.5h4.8l.4-2.5a7.3 7.3 0 0 0 2.2-1.3l2.3 1 2-3.4-2-1.5c.1-.4.1-.8.1-1.3Z"/>',
  sparkle: '<path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z"/><path d="M19 15l.7 2 .7-2 .7 2"/>',
  close: '<path d="M6 6l12 12M18 6 6 18"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4 12H2M22 12h-2M5 5l1.5 1.5M17.5 17.5 19 19M19 5l-1.5 1.5M6.5 17.5 5 19"/>',
  moon: '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z"/>',
  chevron: '<path d="m9 6 6 6-6 6"/>',
  cinema: '<rect x="2.5" y="6" width="19" height="12" rx="2"/><path d="M2.5 9h19M7 6v3M12 6v3M17 6v3"/>',
  pages: '<rect x="4" y="6" width="13" height="15" rx="2"/><path d="M7 3h11a2 2 0 0 1 2 2v13"/>',
  chevdown: '<path d="m6 9 6 6 6-6"/>',
  sort: '<path d="M4 6h16M4 12h10M4 18h6"/>',
  check: '<path d="M5 12l4.5 4.5L19 7"/>',
  lock: '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
  cloud: '<path d="M7 18a4 4 0 0 1 0-8 5 5 0 0 1 9.6-1.5A3.5 3.5 0 0 1 17 18Z"/>',
}
const inner = computed(() => PATHS[props.name] ?? '')
</script>
<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor"
       stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" v-html="inner" />
</template>
