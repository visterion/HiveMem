<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useScansStore } from '../../stores/scans'
import type { FacetKey } from '../../stores/scans'
import { useUiStore } from '../../stores/ui'
import FacetGroup from './FacetGroup.vue'
import HmIcon from '../shell/HmIcon.vue'

const { t } = useI18n()
const store = useScansStore()
const ui = useUiStore()

const totalDocs = computed(() => {
  const statusCounts = store.facetCounts.status
  if (statusCounts && statusCounts.length > 0) {
    return statusCounts.reduce((n, s) => n + s.count, 0)
  }
  return store.results.length
})

function onToggle(field: string, value: string) {
  store.toggleFacet(field as FacetKey, value)
  store.reload().catch(() => {})
}

function selectView(id: string) {
  store.setSavedView(id)
  store.reload().catch(() => {})
}

async function onSaveView() {
  const name = window.prompt(t('scans.saveViewPrompt'))
  if (!name?.trim()) return
  // Serialize current facets to a plain filter object
  const filter: Partial<Record<FacetKey, string[]>> = {}
  const keys: FacetKey[] = ['tag', 'status', 'realm', 'year', 'signal', 'correspondent']
  for (const k of keys) {
    const vals = [...store.facets[k]]
    if (vals.length) filter[k] = vals
  }
  await store.saveView(name.trim(), filter)
}

async function onDeleteView(id: string, e: MouseEvent) {
  e.stopPropagation()
  await store.deleteView(id)
}

onMounted(() => {
  store.loadSavedViews()
})
</script>

<template>
  <div class="panel scans-panel" :class="{ open: ui.mobileDrawerOpen }">
    <div class="panel-head">
      <div>
        <div class="panel-title">{{ t('nav.scans') }}</div>
        <div class="panel-sub">{{ totalDocs }} {{ t('scans.items') }}</div>
      </div>
    </div>
    <div class="panel-body">
      <!-- Saved views section -->
      <div class="sv-header">
        <div class="facet-title">{{ t('scans.savedViews') }}</div>
        <button class="sv-save-btn" :title="t('scans.saveView')" @click="onSaveView">
          <HmIcon name="sparkle" :size="13" />
          {{ t('scans.saveView') }}
        </button>
      </div>

      <!-- All docs row -->
      <button
        :class="['sv-row', { on: store.savedView === 'all' }]"
        @click="selectView('all')"
      >
        <span class="sv-icon"><HmIcon name="scans" :size="15" /></span>
        <span class="sv-name">{{ t('scans.allDocs') }}</span>
        <span class="sv-ct">{{ totalDocs }}</span>
      </button>

      <!-- Saved view rows -->
      <button
        v-for="v in store.savedViews"
        :key="v.id"
        :class="['sv-row', { on: store.savedView === v.id }]"
        @click="selectView(v.id)"
      >
        <span class="sv-icon"><HmIcon name="sparkle" :size="15" /></span>
        <span class="sv-name">{{ v.name }}</span>
        <span class="sv-del" :title="t('scans.deleteView')" @click="onDeleteView(v.id, $event)">
          <HmIcon name="close" :size="11" />
        </span>
      </button>

      <div class="facet-div" />

      <!-- Facet groups -->
      <FacetGroup
        :title="t('scans.status')"
        field="status"
        :options="store.facetCounts.status || []"
        :selected="store.facets.status"
        :labels="{ committed: t('scans.committed'), pending: t('scans.pending') }"
        @toggle="onToggle"
      />
      <FacetGroup
        :title="t('scans.docType')"
        field="tag"
        :options="store.facetCounts.tag || []"
        :selected="store.facets.tag"
        :max="8"
        @toggle="onToggle"
      />
      <FacetGroup
        :title="t('nav.realms')"
        field="realm"
        :options="store.facetCounts.realm || []"
        :selected="store.facets.realm"
        :max="6"
        @toggle="onToggle"
      />
      <FacetGroup
        :title="t('scans.year')"
        field="year"
        :options="store.facetCounts.year || []"
        :selected="store.facets.year"
        @toggle="onToggle"
      />
      <FacetGroup
        :title="t('scans.signal')"
        field="signal"
        :options="store.facetCounts.signal || []"
        :selected="store.facets.signal"
        @toggle="onToggle"
      />
      <FacetGroup
        :title="t('scans.correspondent')"
        field="correspondent"
        :options="store.facetCounts.correspondent || []"
        :selected="store.facets.correspondent"
        :max="8"
        @toggle="onToggle"
      />
    </div>
  </div>
</template>

<style scoped>
.panel {
  grid-column: 2;
  width: var(--panel-w);
  background: var(--bg-1);
  border-right: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  z-index: 20;
  min-height: 0;
}
.panel-head {
  padding: 18px 18px 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.panel-title {
  font-family: var(--font-display);
  font-size: 19px;
  font-weight: 600;
  letter-spacing: -0.01em;
}
.panel-sub {
  font-size: 12px;
  color: var(--text-2);
  margin-top: 2px;
}
.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 0 10px 16px;
  min-height: 0;
}

/* Saved views */
.sv-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 7px 10px;
  border-radius: 9px;
  cursor: pointer;
  font-size: 13.5px;
  color: var(--text-1);
  width: 100%;
  background: none;
  border: none;
  text-align: left;
}
.sv-row:hover {
  background: var(--bg-3);
  color: var(--text-0);
}
.sv-row.on {
  background: var(--honey-dim, rgba(240, 180, 40, 0.12));
  color: var(--text-0);
  font-weight: 600;
}
.sv-icon {
  flex: none;
  color: var(--text-2);
  display: flex;
  align-items: center;
}
.sv-row.on .sv-icon {
  color: var(--honey);
}
.sv-name {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sv-ct {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-2);
  flex: none;
}
.sv-row.on .sv-ct {
  color: var(--honey);
}

.facet-div {
  height: 1px;
  background: var(--line);
  margin: 12px 6px;
}

.sv-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-right: 4px;
}

.facet-title {
  font-size: 11px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--text-2);
  font-weight: 600;
  padding: 6px 10px 8px;
}

.sv-save-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  padding: 3px 8px;
  border-radius: 6px;
  background: var(--honey-dim, rgba(240,180,40,.10));
  color: var(--honey, #e5a000);
  border: 1px solid var(--honey-dim, rgba(240,180,40,.2));
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.12s;
}
.sv-save-btn:hover { background: var(--honey-dim, rgba(240,180,40,.18)); }

.sv-del {
  flex: none;
  display: flex;
  align-items: center;
  color: var(--text-3, #777);
  border-radius: 4px;
  padding: 1px;
  opacity: 0;
  transition: opacity 0.12s;
}
.sv-row:hover .sv-del { opacity: 1; }
.sv-del:hover { color: var(--danger, #ff4444); background: var(--bg-3); }
@media (max-width: 959px) {
  .panel {
    grid-column: 1; width: min(88vw, 366px); position: fixed; top: 0; bottom: 0; left: 0;
    z-index: 50; transform: translateX(-100%); transition: transform .2s ease;
    box-shadow: var(--shadow-2); padding-bottom: calc(60px + env(safe-area-inset-bottom));
  }
  .panel.open { transform: translateX(0); }
}
</style>
