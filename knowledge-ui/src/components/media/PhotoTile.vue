<script setup lang="ts">
import { ref, computed } from 'vue'
import type { MediaItem } from '../../api/types'

const props = defineProps<{ item: MediaItem }>()
defineEmits<{ (e: 'open'): void }>()

const imgFailed = ref(false)
const thumbUrl = computed(() =>
  props.item.attachment_id ? `/api/attachments/${props.item.attachment_id}/thumbnail` : null)

// Deterministic gradient placeholder derived from the cell id — shows in mock/dev
// (thumbnail 404s) and behind the real image while it loads.
function gradientFor(seed: string): string {
  let h = 0
  for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) >>> 0
  const a = h % 360
  const b = (a + 40) % 360
  return `linear-gradient(145deg, hsl(${a},55%,42%), hsl(${b},50%,30%))`
}
const bg = computed(() => gradientFor(props.item.cell_id))
</script>

<template>
  <button class="photo" :style="{ background: bg }" @click="$emit('open')">
    <img v-if="thumbUrl" :src="thumbUrl" loading="lazy" alt=""
         :class="{ hidden: imgFailed }" @error="imgFailed = true" />
    <span class="photo-glow" />
  </button>
</template>

<style scoped>
.photo { position:relative; border-radius:4px; overflow:hidden; cursor:pointer; border:none;
  padding:0; width:100%; height:100%; transition:filter .18s, transform .18s; display:block; }
.photo:hover { filter:brightness(1.08); transform:scale(0.995); }
.photo img { position:absolute; inset:0; width:100%; height:100%; object-fit:cover; display:block; }
.photo img.hidden { display:none; }
.photo-glow { position:absolute; inset:0; pointer-events:none;
  background:radial-gradient(120% 80% at 70% 0%, rgba(255,255,255,0.18), transparent 60%); }
</style>
