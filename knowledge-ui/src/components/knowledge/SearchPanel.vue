<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/useApi'
import type { SearchResult } from '../../api/types'
import { useCellStore } from '../../stores/cell'
import { cellLabel } from '../../api/cellLabel'
import { paletteForRealm } from '../../composables/realmPalette'
import ScoreRing from './ScoreRing.vue'
import HmIcon from '../shell/HmIcon.vue'

const q = ref('')
const results = ref<SearchResult[]>([])
const loading = ref(false)
const typeF = ref('all')
const cellStore = useCellStore()
const route = useRoute()
const router = useRouter()
const { t } = useI18n()

const TYPES = ['all', 'facts', 'events', 'discoveries', 'preferences', 'advice']

const realmFilter = computed<string | null>(() => {
  const r = route.query.realm
  return typeof r === 'string' && r ? r : null
})

async function runSearch() {
  const v = q.value
  if (!v && !realmFilter.value) { results.value = []; return }
  loading.value = true
  try {
    const args: Record<string, unknown> = { query: v, limit: 50, include: ['content', 'summary', 'created_at'] }
    if (realmFilter.value) args.realm = realmFilter.value
    results.value = await useApi().call<SearchResult[]>('search', args)
  } finally { loading.value = false }
}

let timer: number | null = null
watch(q, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(runSearch, 180) as unknown as number
})
// re-run immediately when the realm filter changes (incl. initial mount with ?realm=)
watch(realmFilter, runSearch, { immediate: true })

function clearRealm() {
  const { realm: _realm, ...rest } = route.query
  router.replace({ query: rest })
}

const shown = computed(() =>
  typeF.value === 'all' ? results.value : results.value.filter(c => c.signal === typeF.value))

// stable per-realm color without a realm list: hash the realm name to an index
function realmColor(realm: string): string {
  let h = 0
  for (let i = 0; i < realm.length; i++) h = (h * 31 + realm.charCodeAt(i)) >>> 0
  return paletteForRealm(h % 12).base
}
function typeLabel(ty: string) { return ty === 'all' ? t('knowledge.allTypes') : ty }
</script>

<template>
  <div class="panel">
    <div class="panel-head">
      <div>
        <div class="panel-title">{{ t('nav.search') }}</div>
        <div class="panel-sub">{{ shown.length }} {{ t('knowledge.results') }} · {{ t('inspector.signals') }}</div>
      </div>
    </div>
    <div class="searchbar">
      <HmIcon name="search" :size="17" />
      <input v-model="q" :placeholder="t('search.placeholder')" />
      <kbd>⌘K</kbd>
    </div>
    <div v-if="realmFilter" class="realm-chip">
      <span class="dot" :style="{ background: realmColor(realmFilter) }" />
      <span>{{ realmFilter }}</span>
      <button class="x" @click="clearRealm" :aria-label="t('search.clearRealm')"><HmIcon name="close" :size="12" /></button>
    </div>
    <div class="seg">
      <button v-for="ty in TYPES" :key="ty" :class="{ on: typeF === ty }" @click="typeF = ty">{{ typeLabel(ty) }}</button>
    </div>
    <div class="panel-body">
      <div class="rows">
        <div v-for="c in shown" :key="c.id" :class="['row', { sel: cellStore.currentId === c.id }]" @click="cellStore.open(c)">
          <ScoreRing :value="c.score_total ?? 0" />
          <div class="row-main">
            <div class="row-title">{{ cellLabel(c) }}</div>
            <div class="row-meta">
              <span class="dot" :style="{ background: realmColor(c.realm) }" />
              <span>{{ c.realm }}</span>
              <span style="color:var(--text-3)">·</span>
              <span>{{ c.signal || '—' }}</span>
            </div>
          </div>
          <span class="row-score">{{ (c.score_total ?? 0).toFixed(2) }}</span>
        </div>
      </div>
      <div v-if="loading" class="hint">{{ t('search.searching') }}</div>
      <div v-else-if="!shown.length && (q || realmFilter)" class="hint">{{ t('common.noResults') }}</div>
      <div v-else-if="realmFilter && !q && !loading" class="hint">{{ t('search.withinRealm', { realm: realmFilter }) }}</div>
    </div>
  </div>
</template>

<style scoped>
.panel { grid-column:2; width:var(--panel-w); background:var(--bg-1); border-right:1px solid var(--line);
  display:flex; flex-direction:column; z-index:20; min-height:0; }
.panel-head { padding:18px 18px 14px; display:flex; align-items:center; justify-content:space-between; gap:10px; }
.panel-title { font-family:var(--font-display); font-size:19px; font-weight:600; letter-spacing:-.01em; }
.panel-sub { font-size:12px; color:var(--text-2); margin-top:2px; }
.panel-body { flex:1; overflow-y:auto; padding:0 10px 16px; min-height:0; }
.searchbar { margin:0 8px 6px; padding:10px 12px; display:flex; align-items:center; gap:9px;
  background:var(--bg-3); border:1px solid var(--line); border-radius:11px; }
.searchbar:focus-within { border-color:var(--line-honey); box-shadow:0 0 0 3px var(--honey-dim); }
.searchbar input { flex:1; background:none; border:none; outline:none; color:var(--text-0); font-size:14.5px; }
.searchbar input::placeholder { color:var(--text-2); }
.searchbar kbd { font-family:var(--font-mono); font-size:11px; color:var(--text-2); border:1px solid var(--line-2); border-radius:5px; padding:1px 5px; }
.realm-chip { margin:0 8px 8px; display:inline-flex; align-items:center; gap:7px; padding:5px 8px 5px 10px;
  background:var(--bg-3); border:1px solid var(--line); border-radius:999px; font-size:12.5px; color:var(--text-1);
  text-transform:capitalize; width:fit-content; }
.realm-chip .x { display:flex; align-items:center; background:none; border:none; color:var(--text-2); cursor:pointer; padding:0; }
.realm-chip .x:hover { color:var(--text-0); }
.seg { display:flex; gap:6px; padding:6px 8px 12px; flex-wrap:wrap; }
.seg button { font-size:12px; color:var(--text-1); padding:5px 11px; border-radius:999px; border:1px solid var(--line);
  background:var(--bg-2); white-space:nowrap; cursor:pointer; }
.seg button:hover { border-color:var(--line-2); color:var(--text-0); }
.seg button.on { color:var(--bg-0); background:var(--honey); border-color:var(--honey); font-weight:600; }
.rows { display:flex; flex-direction:column; }
.row { padding:11px 12px; border-radius:11px; cursor:pointer; display:flex; gap:12px; align-items:flex-start;
  border:1px solid transparent; }
.row:hover { background:var(--bg-3); }
.row.sel { background:var(--honey-dim); border-color:var(--line-honey); }
.row-main { flex:1; min-width:0; }
.row-title { font-size:14px; color:var(--text-0); font-weight:500; line-height:1.35;
  display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; }
.row-meta { display:flex; align-items:center; gap:7px; margin-top:5px; font-size:11.5px; color:var(--text-2); }
.dot { width:7px; height:7px; border-radius:2px; transform:rotate(45deg); flex:none; }
.row-score { font-family:var(--font-mono); font-size:11px; color:var(--honey); flex:none; padding-top:2px; }
.hint { color:var(--text-2); padding:8px 12px; font-size:13px; }
</style>
