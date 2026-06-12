<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useCanvasStore } from '../stores/canvas'
import { sortByValidFrom } from '../composables/timeline'
import { realmColorFor } from '../composables/realmMeta'

const { t, locale } = useI18n()
const canvas = useCanvasStore()

const idx = ref(0)
const touched = ref(false)

const sorted = computed(() => sortByValidFrom(canvas.cells))
const maxIdx = computed(() => Math.max(0, sorted.value.length - 1))
const current = computed(() => sorted.value[idx.value] ?? null)

watch(() => sorted.value.length, (n) => {
  if (!touched.value) idx.value = Math.max(0, n - 1)
  else if (idx.value > n - 1) idx.value = Math.max(0, n - 1)
})

function onInput(e: Event) {
  touched.value = true
  idx.value = Number((e.target as HTMLInputElement).value)
}
function fmtDate(iso: string | null): string {
  return iso ? new Date(iso).toLocaleDateString(locale.value) : '—'
}

onMounted(() => { if (canvas.cells.length === 0) void canvas.loadTopLevel() })
</script>

<template>
  <div class="page">
    <div class="tm-wrap">
      <div class="h-eyebrow">{{ t('nav.timemachine') }}</div>
      <h1 class="h-display" style="font-size:28px;margin:4px 0 6px">
        {{ t('timemachine.sub', { from: t('timemachine.validFrom'), present: t('timemachine.present') }) }}
      </h1>
      <p class="prose" style="margin-bottom:28px">{{ t('timemachine.prose') }}</p>

      <template v-if="sorted.length">
        <input class="tm-range" type="range" min="0" :max="maxIdx" :value="idx" @input="onInput" />
        <div class="tm-ticks">
          <span v-for="(c, i) in sorted" :key="c.id" class="tm-tick" :class="{ on: i <= idx }" :title="c.valid_from" />
        </div>
        <div class="tm-count">{{ t('timemachine.cellsAsOf', { n: idx + 1, date: fmtDate(current ? current.valid_from : null) }) }}</div>
        <div v-if="current" :key="current.id" class="card fade-in tm-card">
          <div class="tm-chips">
            <span class="chip" :style="{ borderColor: realmColorFor(current.realm), color: realmColorFor(current.realm) }">{{ current.realm }}</span>
            <span class="chip">{{ fmtDate(current.valid_from) }}</span>
          </div>
          <div class="h-display tm-title">{{ current.title }}</div>
          <p class="prose">{{ current.summary }}</p>
        </div>
      </template>
      <div v-else class="tm-empty">
        {{ canvas.loaded && !canvas.streamActive ? t('timemachine.empty') : t('common.loading') }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { flex:1; overflow-y:auto; min-height:0; height:100%; }
.tm-wrap { padding:34px 44px; max-width:900px; margin:0 auto; }
.h-eyebrow { font-size:11.5px; letter-spacing:.14em; text-transform:uppercase; color:var(--text-2); font-weight:600; }
.h-display { font-family:var(--font-display); font-weight:600; letter-spacing:-.02em; color:var(--text-0); }
.prose { font-size:14.5px; line-height:1.62; color:var(--text-1); }

.tm-range { -webkit-appearance:none; appearance:none; width:100%; height:6px; border-radius:4px; background:var(--bg-4); outline:none; }
.tm-range::-webkit-slider-thumb { -webkit-appearance:none; appearance:none; width:22px; height:22px; border-radius:50%; background:var(--honey);
  box-shadow:0 0 0 5px var(--honey-dim), 0 2px 8px rgba(0,0,0,0.5); cursor:pointer; }
.tm-range::-moz-range-thumb { width:22px; height:22px; border:none; border-radius:50%; background:var(--honey);
  box-shadow:0 0 0 5px var(--honey-dim), 0 2px 8px rgba(0,0,0,0.5); cursor:pointer; }
.tm-ticks { display:flex; justify-content:space-between; margin-top:12px; }
.tm-tick { width:8px; height:8px; border-radius:2px; transform:rotate(45deg); background:var(--bg-4); }
.tm-tick.on { background:var(--honey); }
.tm-count { font-size:12px; color:var(--text-2); margin-top:18px; }

.card { background:var(--bg-2); border:1px solid var(--line); border-radius:var(--radius, 14px); box-shadow:var(--shadow-1); }
.tm-card { padding:22px; margin-top:10px; }
.tm-chips { display:flex; gap:9px; margin-bottom:12px; }
.tm-title { font-size:20px; margin-bottom:8px; }
.chip { font-size:11px; padding:2px 8px; border-radius:6px; font-weight:500; background:var(--bg-4); color:var(--text-1);
  border:1px solid var(--line); display:inline-flex; align-items:center; gap:5px; white-space:nowrap; }
.tm-empty { color:var(--text-2); padding:40px 0; }
</style>
