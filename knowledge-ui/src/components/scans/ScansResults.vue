<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useScansStore } from '../../stores/scans'
import type { FacetKey } from '../../stores/scans'
import { useUiStore } from '../../stores/ui'
import { useApi } from '../../api/useApi'
import { useLayout } from '../../composables/useLayout'
import DocCard from './DocCard.vue'
import DocTable from './DocTable.vue'
import SortMenu from './SortMenu.vue'
import HmIcon from '../shell/HmIcon.vue'

const { t } = useI18n()
const store = useScansStore()
const ui = useUiStore()
const { isMobile } = useLayout()
// Optional: some unit tests mount ScansResults without a router installed.
const route = useRoute()

// reload() can fail (network/backend restart); before this, a failed load left
// `store.filtered` empty and showed the misleading "no results" empty state
// instead of an error + retry (E5).
const loadError = ref(false)
const lastLoadSucceeded = ref(false)

async function doReload() {
  try {
    await store.reload()
    loadError.value = false
    lastLoadSucceeded.value = true
  } catch {
    loadError.value = true
    lastLoadSucceeded.value = false
  }
}

// Local debounced query, kept in sync when the store's query changes elsewhere
// (e.g. saved views or programmatic resets).
const q = ref(store.query)
watch(() => store.query, v => { if (v !== q.value) q.value = v })
let debounceTimer: ReturnType<typeof setTimeout> | null = null

function onInput() {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    debounceTimer = null
    store.setQuery(q.value)
    doReload()
  }, 200)
}

onUnmounted(() => { if (debounceTimer) { clearTimeout(debounceTimer); debounceTimer = null } })

// Infinite scroll: the store already exposes loadMore()/hasMore (M54), but nothing
// called it — results silently capped at PAGE_SIZE. Observe a sentinel at the end
// of the scroll container and page in more while it's visible (H8).
const sentinelEl = ref<HTMLElement | null>(null)
let sentinelObserver: IntersectionObserver | null = null

function maybeLoadMore() {
  if (store.hasMore && !store.loading) store.loadMore()
}

onMounted(() => {
  if (typeof IntersectionObserver === 'undefined') return
  sentinelObserver = new IntersectionObserver(entries => {
    if (entries.some(e => e.isIntersecting)) maybeLoadMore()
  })
  if (sentinelEl.value) sentinelObserver.observe(sentinelEl.value)
})
onUnmounted(() => { sentinelObserver?.disconnect(); sentinelObserver = null })

function onSort(v: string) {
  store.setSort(v as 'newest' | 'oldest' | 'title')
  doReload()
}

function onFacetChip(key: FacetKey, val: string) {
  store.toggleFacet(key, val)
  doReload()
}

function onClearFacets() {
  store.clearFacets()
  doReload()
}

async function onApprove() {
  try {
    await useApi().call('approve_pending', {
      ids: [...store.selection],
      decision: 'committed',
    })
  } catch {
    // Keep the selection so the user can retry the failed approval.
    ui.pushToast('error', t('common.actionFailed'))
    return
  }
  store.clearSelection()
  doReload()
}

async function onBulkTag() {
  const raw = window.prompt(t('scans.bulkTagPrompt'))
  if (!raw?.trim()) return
  const tags = raw.split(',').map(s => s.trim()).filter(Boolean)
  if (!tags.length) return
  try {
    await store.bulkTag(tags)
  } catch {
    ui.pushToast('error', t('common.actionFailed'))
  }
}

async function onBulkRealm() {
  const realm = window.prompt(t('scans.bulkRealmPrompt'))
  if (!realm?.trim()) return
  try {
    await store.bulkReclassify(realm.trim())
  } catch {
    ui.pushToast('error', t('common.actionFailed'))
  }
}

// All facet keys that can have active values
const FACET_KEYS: FacetKey[] = ['tag', 'status', 'realm', 'year', 'signal', 'correspondent']

onMounted(async () => {
  doReload()
  // Deep link: /scans?doc=<id> opens the reader for that document directly.
  const doc = route?.query.doc
  if (typeof doc === 'string') {
    await store.load()
    await store.openDocument(doc, 'document')
  }
})
</script>

<template>
  <div class="scans-stage">
    <!-- Toolbar -->
    <div class="scans-toolbar">
      <div class="searchbar">
        <HmIcon name="search" :size="16" />
        <input
          v-model="q"
          class="search-input"
          type="text"
          :placeholder="isMobile ? t('scans.searchShort') : t('scans.searchPlaceholder')"
          @input="onInput"
        />
      </div>

      <SortMenu :sort="store.sort" @change="onSort" />

      <div class="toggle">
        <button
          :class="['toggle-btn', { active: store.mode === 'grid' }]"
          :title="t('scans.gridView')"
          @click="store.setMode('grid')"
        >
          <HmIcon name="photos" :size="16" />
        </button>
        <button
          :class="['toggle-btn', { active: store.mode === 'list' }]"
          :title="t('scans.listView')"
          @click="store.setMode('list')"
        >
          <HmIcon name="pages" :size="16" />
        </button>
      </div>
    </div>

    <!-- Result bar -->
    <div class="res-bar">
      <span class="res-count">
        <b>{{ store.filtered.length }}</b> {{ t('scans.items') }}
      </span>

      <div class="filter-chips">
        <template v-for="key in FACET_KEYS" :key="key">
          <span
            v-for="val in [...store.facets[key]]"
            :key="key + '-' + val"
            class="fchip"
            @click="onFacetChip(key, val)"
          >
            {{ key === 'status' ? t('scans.' + val) : val }}
            <HmIcon name="close" :size="11" />
          </span>
        </template>

        <span
          v-if="store.activeCount > 0"
          class="fchip clear"
          @click="onClearFacets"
        >
          {{ t('scans.clearAll') }}
        </span>
      </div>
    </div>

    <!-- Results area -->
    <div class="scan-canvas">
      <template v-if="loadError">
        <div class="empty error">
          <HmIcon name="scans" :size="48" class="hexbig" />
          <span>{{ t('scans.loadError') }}</span>
          <button class="retry-btn" @click="doReload">{{ t('common.retry') }}</button>
        </div>
      </template>

      <!-- Gated on lastLoadSucceeded: a failed load must not show "no results" —
           that's misleading when the real problem is a network/backend error (E5). -->
      <template v-else-if="store.filtered.length === 0 && !store.loading && lastLoadSucceeded">
        <div class="empty">
          <HmIcon name="scans" :size="48" class="hexbig" />
          <span>{{ t('scans.noResults') }}</span>
        </div>
      </template>

      <template v-else-if="store.mode === 'grid'">
        <div class="docgrid">
          <DocCard
            v-for="d in store.filtered"
            :key="d.id"
            :d="d"
            :q="store.query"
            :selected="store.selection.has(d.id)"
            @open="store.openDocument(d.id, 'document')"
            @openInfo="store.openDocument(d.id, 'info')"
            @select="store.toggleSelect(d.id)"
          />
        </div>
      </template>

      <template v-else>
        <DocTable
          :rows="store.filtered"
          :q="store.query"
          :selection="store.selection"
          @open="store.openDocument"
          @select="store.toggleSelect"
        />
      </template>

      <div v-if="store.searchTruncated" class="truncated-hint">{{ t('scans.searchTruncated') }}</div>
      <div ref="sentinelEl" class="scroll-sentinel" />
    </div>

    <!-- Bulk bar -->
    <div v-if="store.selection.size > 0" class="bulkbar">
      <span class="bulk-count">
        {{ store.selection.size }} {{ t('scans.selected') }}
      </span>
      <div class="bulk-actions">
        <button class="bulk-btn" @click="onBulkTag">
          {{ t('scans.tag') }}
        </button>
        <button class="bulk-btn" @click="onBulkRealm">
          {{ t('scans.realm') }}
        </button>
        <button class="bulk-btn approve" @click="onApprove">
          {{ t('scans.approve') }}
        </button>
        <button class="bulk-btn" disabled>
          {{ t('scans.export') }}
        </button>
        <button class="bulk-close" @click="store.clearSelection">
          <HmIcon name="close" :size="16" />
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ── Stage wrapper ────────────────────────────────────────────────────────── */
.scans-stage {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  position: relative;
}

/* ── Toolbar ─────────────────────────────────────────────────────────────── */
.scans-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--line, #e0e0e0);
  flex-shrink: 0;
}

.searchbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  background: var(--bg-3, rgba(255,255,255,.06));
  border: 1px solid var(--line, #e0e0e0);
  border-radius: 9px;
  padding: 7px 11px;
  color: var(--text-2, #888);
}

.search-input {
  border: none;
  outline: none;
  background: transparent;
  font-size: 13px;
  color: var(--text-1, #eee);
  width: 100%;
}

@media (max-width: 959px) {
  .search-input { min-width: 120px; }
}

.toggle {
  display: flex;
  gap: 2px;
  border: 1px solid var(--line, #e0e0e0);
  border-radius: 9px;
  overflow: hidden;
}

.toggle-btn {
  background: none;
  border: none;
  cursor: pointer;
  padding: 6px 9px;
  color: var(--text-2, #888);
  display: flex;
  align-items: center;
  transition: background 0.12s, color 0.12s;
}

.toggle-btn.active,
.toggle-btn:hover {
  background: var(--bg-3, rgba(255,255,255,.1));
  color: var(--text-1, #eee);
}

/* ── Result bar ──────────────────────────────────────────────────────────── */
.res-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 16px;
  flex-shrink: 0;
  flex-wrap: wrap;
}

.res-count {
  font-size: 12px;
  color: var(--text-2, #888);
  white-space: nowrap;
}

.filter-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.fchip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  padding: 3px 8px;
  border-radius: 8px;
  background: var(--honey-glow, rgba(229,160,0,.15));
  color: var(--honey-deep, #b07700);
  border: 1px solid var(--honey, #e5a000);
  cursor: pointer;
  transition: opacity 0.12s;
}

.fchip:hover { opacity: 0.75; }

.fchip.clear {
  background: var(--bg-3, rgba(255,255,255,.07));
  color: var(--text-2, #aaa);
  border-color: var(--line, #e0e0e0);
}

/* ── Canvas (scrollable results) ─────────────────────────────────────────── */
.scan-canvas {
  flex: 1;
  overflow-y: auto;
  padding: 12px 16px 80px;
  min-height: 0;
}

/* ── Doc grid ────────────────────────────────────────────────────────────── */
.docgrid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 14px;
}

/* ── Empty state ─────────────────────────────────────────────────────────── */
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 60px 20px;
  color: var(--text-3, #aaa);
  font-size: 13.5px;
  text-align: center;
}

.hexbig {
  opacity: 0.3;
}

.empty.error span {
  color: var(--danger, #ff6b6b);
}

.retry-btn {
  font-size: 12.5px;
  color: var(--text-1, #eee);
  background: var(--bg-3, rgba(255,255,255,.06));
  border: 1px solid var(--line, #e0e0e0);
  border-radius: 8px;
  padding: 6px 14px;
  cursor: pointer;
}
.retry-btn:hover { background: var(--bg-4, rgba(255,255,255,.12)); }

.scroll-sentinel {
  height: 1px;
}

.truncated-hint {
  text-align: center;
  font-size: 12px;
  color: var(--text-2, #888);
  padding: 12px 0;
}

/* ── Bulk bar ────────────────────────────────────────────────────────────── */
.bulkbar {
  position: absolute;
  bottom: 16px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--bg-2, #1a1a2e);
  border: 1px solid var(--line-2, rgba(255,255,255,.15));
  border-radius: 12px;
  padding: 8px 14px;
  box-shadow: 0 8px 32px rgba(0,0,0,.4);
  z-index: 10;
  white-space: nowrap;
}

.bulk-count {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-1, #eee);
  padding-right: 6px;
  border-right: 1px solid var(--line, rgba(255,255,255,.1));
  margin-right: 2px;
}

.bulk-actions {
  display: flex;
  align-items: center;
  gap: 5px;
}

.bulk-btn {
  font-size: 12px;
  font-weight: 600;
  padding: 5px 12px;
  border-radius: 7px;
  border: 1px solid var(--line, rgba(255,255,255,.12));
  background: var(--bg-3, rgba(255,255,255,.06));
  color: var(--text-1, #eee);
  cursor: pointer;
  transition: background 0.12s;
}

.bulk-btn:hover:not(:disabled) { background: var(--bg-4, rgba(255,255,255,.12)); }
.bulk-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.bulk-btn.approve {
  background: rgba(0,200,100,.15);
  color: var(--good, #00c864);
  border-color: var(--good, #00c864);
}
.bulk-btn.approve:hover { background: rgba(0,200,100,.25); }

.bulk-close {
  background: none;
  border: none;
  cursor: pointer;
  color: var(--text-2, #aaa);
  display: flex;
  align-items: center;
  padding: 4px;
  border-radius: 5px;
  margin-left: 2px;
}
.bulk-close:hover { background: var(--bg-3, rgba(255,255,255,.08)); color: var(--text-1, #eee); }
</style>
