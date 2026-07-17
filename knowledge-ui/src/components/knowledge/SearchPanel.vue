<script setup lang="ts">
import { computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useKnowledgeSearch, type KnowledgeFacetKey, type KnowledgeSort } from '../../composables/useKnowledgeSearch'
import { useCellStore } from '../../stores/cell'
import { useUiStore } from '../../stores/ui'
import { cellLabel } from '../../api/cellLabel'
import { realmColorFor } from '../../composables/realmMeta'
import ScoreRing from './ScoreRing.vue'
import HmIcon from '../shell/HmIcon.vue'
import FacetGroup from '../scans/FacetGroup.vue'
import SortMenu from '../scans/SortMenu.vue'

const { query, facets, sort, shown, facetCounts, loading, error, run, toggleFacet, setSort, clearFacets } = useKnowledgeSearch()
const cellStore = useCellStore()
const ui = useUiStore()
const route = useRoute()
const { t } = useI18n()

const sortOptions = computed<[string, string][]>(() => [
  ['relevance', t('knowledge.sortRelevance')],
  ['newest', t('scans.newestFirst')],
  ['oldest', t('scans.oldestFirst')],
  ['title', t('scans.titleAZ')],
])

onMounted(() => {
  const r = route.query.realm
  if (typeof r === 'string' && r) { facets.realm.add(r); if (sort.value === 'relevance') setSort('newest') }
  run()
})

let timer: number | null = null
watch(query, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => { timer = null; run() }, 180) as unknown as number
})
onUnmounted(() => { if (timer) { clearTimeout(timer); timer = null } })

const activeFilterCount = computed(() => facets.realm.size + facets.signal.size + facets.tag.size)
const onSort = (v: string) => setSort(v as KnowledgeSort)
const onToggle = (f: string, v: string) => toggleFacet(f as KnowledgeFacetKey, v)
</script>

<template>
  <div class="panel" :class="{ open: ui.mobileDrawerOpen }">
    <div class="panel-head">
      <div>
        <div class="panel-title">{{ t('nav.search') }}</div>
        <div class="panel-sub">{{ shown.length }} {{ t('knowledge.results') }}</div>
      </div>
    </div>
    <div class="searchbar">
      <HmIcon name="search" :size="17" />
      <input v-model="query" :placeholder="t('search.placeholder')" />
      <kbd>⌘K</kbd>
    </div>
    <div class="toolbar">
      <SortMenu :sort="sort" :options="sortOptions" align="left" @change="onSort" />
      <button v-if="activeFilterCount" class="clear-btn" @click="clearFacets()">
        <HmIcon name="close" :size="12" /> {{ t('knowledge.clearFilters') }} ({{ activeFilterCount }})
      </button>
    </div>
    <div class="panel-body">
      <FacetGroup :title="t('knowledge.facetsRealm')" field="realm"
        :options="facetCounts.realm || []" :selected="facets.realm" :max="8" @toggle="onToggle" />
      <FacetGroup :title="t('knowledge.facetsSignal')" field="signal"
        :options="facetCounts.signal || []" :selected="facets.signal" @toggle="onToggle" />
      <FacetGroup :title="t('knowledge.facetsTag')" field="tag"
        :options="facetCounts.tag || []" :selected="facets.tag" :max="10" @toggle="onToggle" />

      <div class="rows">
        <div v-for="c in shown" :key="c.id"
          :class="['row', { sel: cellStore.currentId === c.id }]" @click="cellStore.open(c)">
          <!-- The ring shows the relevance score. It only carries information under a relevance
               sort with a real query; an empty ring (score 0) otherwise reads as an unchecked
               radio and invites a click that does nothing. Show it only when meaningful. -->
          <ScoreRing v-if="sort === 'relevance' && (c.score_total ?? 0) > 0" :value="c.score_total ?? 0" />
          <div class="row-main">
            <div class="row-title">{{ cellLabel(c) }}</div>
            <div v-if="c.summary" class="row-snip">{{ c.summary }}</div>
            <div class="row-meta">
              <span class="dot" :style="{ background: realmColorFor(c.realm) }" />
              <span>{{ c.realm ?? '—' }}</span>
              <span class="sep">·</span>
              <span class="sig">{{ c.signal || '—' }}</span>
            </div>
          </div>
        </div>
      </div>
      <div v-if="error" class="hint error">
        {{ t('search.searchError') }}
        <button class="retry-btn" @click="run()">{{ t('common.retry') }}</button>
      </div>
      <div v-else-if="loading" class="hint">{{ t('search.searching') }}</div>
      <div v-else-if="!shown.length && (activeFilterCount || query)" class="hint">
        {{ t('common.noResults') }}
      </div>
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
.toolbar { display:flex; align-items:center; justify-content:space-between; gap:8px; padding:2px 8px 10px; flex-wrap:wrap; }
.clear-btn { display:inline-flex; align-items:center; gap:5px; font-size:12px; color:var(--text-2);
  background:var(--bg-3); border:1px solid var(--line); border-radius:8px; padding:5px 9px; cursor:pointer; }
.clear-btn:hover { color:var(--text-0); border-color:var(--line-2); }
.rows { display:flex; flex-direction:column; }
.row { padding:11px 12px; border-radius:11px; cursor:pointer; display:flex; gap:12px; align-items:flex-start;
  border:1px solid transparent; }
.row:hover { background:var(--bg-3); }
.row.sel { background:var(--honey-dim); border-color:var(--line-honey); }
.row-main { flex:1; min-width:0; }
.row-title { font-size:14px; color:var(--text-0); font-weight:500; line-height:1.35;
  display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; }
.row-snip { font-size:12px; color:var(--text-2); margin-top:3px; line-height:1.4;
  display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; }
.row-meta { display:flex; align-items:center; gap:7px; margin-top:5px; font-size:11.5px; color:var(--text-2); }
.row-meta .sep { color:var(--text-3); }
.row-meta .sig { text-transform:capitalize; }
.dot { width:7px; height:7px; border-radius:2px; transform:rotate(45deg); flex:none; }
.hint { color:var(--text-2); padding:8px 12px; font-size:13px; }
.hint.error { color:var(--danger, #ff6b6b); display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
.retry-btn { font-size:12px; color:var(--text-0); background:var(--bg-3); border:1px solid var(--line);
  border-radius:8px; padding:4px 10px; cursor:pointer; }
.retry-btn:hover { border-color:var(--line-2); }
@media (max-width: 959px) {
  .panel {
    grid-column: 1; width: min(88vw, 366px); position: fixed; top: 0; bottom: 0; left: 0;
    z-index: 50; transform: translateX(-100%); transition: transform .2s ease;
    box-shadow: var(--shadow-2); padding-bottom: calc(60px + env(safe-area-inset-bottom));
  }
  .panel.open { transform: translateX(0); }
}
</style>
