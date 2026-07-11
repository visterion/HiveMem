<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useCanvasStore } from '../stores/canvas'
import { useCellStore } from '../stores/cell'
import { sortByValidFrom } from '../composables/timeline'
import { realmColorFor } from '../composables/realmMeta'

const { t, locale } = useI18n()
const canvas = useCanvasStore()
const cellStore = useCellStore()

const idx = ref(0)
const touched = ref(false)

const sorted = computed(() => sortByValidFrom(canvas.cells))
const maxIdx = computed(() => Math.max(0, sorted.value.length - 1))
const current = computed(() => sorted.value[idx.value] ?? null)
// Newest-of-the-valid-set first, capped at 20 — cells arrive progressively via the
// canvas store's stream, so this must re-slice on every batch, not just on mount.
const visible = computed(() => sorted.value.slice(Math.max(0, idx.value - 19), idx.value + 1).reverse())

// Cells stream in progressively (batches via canvas._longPoll), so the "present"
// default must be re-applied after every batch — not just once on mount — until the
// user drags the slider themselves.
watch(() => sorted.value.length, (n) => {
  if (!touched.value) idx.value = Math.max(0, n - 1)
}, { immediate: true })

function openCell(id: string) {
  void cellStore.load(id)
}

function onInput(e: Event) {
  touched.value = true
  idx.value = Number((e.target as HTMLInputElement).value)
}
function fmtDate(iso: string | null): string {
  return iso ? new Date(iso).toLocaleDateString(locale.value) : '—'
}

const loadError = ref(false)
async function load() {
  loadError.value = false
  try { await canvas.loadTopLevel() } catch { loadError.value = true }
}
onMounted(() => { if (canvas.cells.length === 0) void load() })
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
        <div class="tm-list">
          <article
            v-for="c in visible"
            :key="c.id"
            class="card fade-in tm-card"
            data-test="tm-card"
            @click="openCell(c.id)"
          >
            <div class="tm-chips">
              <span class="chip" :style="{ borderColor: realmColorFor(c.realm), color: realmColorFor(c.realm) }">{{ c.realm }}</span>
              <span class="chip">{{ fmtDate(c.valid_from) }}</span>
            </div>
            <div class="h-display tm-title">{{ c.title }}</div>
            <p class="prose">{{ c.summary }}</p>
          </article>
        </div>
      </template>
      <div v-else-if="loadError" class="tm-empty tm-err">
        <span>{{ t('common.loadFailed') }}</span>
        <button class="retry" @click="load">{{ t('common.retry') }}</button>
      </div>
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
.tm-list { display:flex; flex-direction:column; gap:10px; margin-top:10px; }
.tm-card { padding:22px; cursor:pointer; }
.tm-card:hover { border-color:var(--honey); }
.tm-chips { display:flex; gap:9px; margin-bottom:12px; }
.tm-title { font-size:20px; margin-bottom:8px; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; }
.chip { font-size:11px; padding:2px 8px; border-radius:6px; font-weight:500; background:var(--bg-4); color:var(--text-1);
  border:1px solid var(--line); display:inline-flex; align-items:center; gap:5px; white-space:nowrap; }
.tm-empty { color:var(--text-2); padding:40px 0; }
.tm-err { display:flex; align-items:center; gap:12px; }
.retry { padding:7px 16px; border-radius:8px; border:1px solid var(--line-honey, rgba(240,180,40,.3));
  background:var(--honey-dim, rgba(240,180,40,.08)); color:var(--honey); font-size:13px; cursor:pointer; }
.retry:hover { background:var(--honey-dim, rgba(240,180,40,.16)); }
</style>
