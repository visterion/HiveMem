<script setup lang="ts">
import type { DocumentRow } from '../../api/types'
import { cellLabel } from '../../api/cellLabel'
import Snippet from './Snippet.vue'
import HmIcon from '../shell/HmIcon.vue'

const props = defineProps<{ rows: DocumentRow[]; q: string; selection: Set<string> }>()
const emit = defineEmits<{ (e: 'open', id: string): void; (e: 'select', id: string): void }>()

function formatDate(iso: string): string {
  return iso.slice(0, 10).split('-').reverse().join('.')
}
</script>

<template>
  <div class="doctable">
    <div class="dtbl-head">
      <span></span>
      <span>Typ</span>
      <span>Titel</span>
      <span>Korrespondent</span>
      <span>Tags</span>
      <span>Datum</span>
      <span>Status</span>
    </div>
    <div
      v-for="d in rows"
      :key="d.id"
      :class="['dtbl-row', { sel: selection.has(d.id) }]"
    >
      <!-- Checkbox -->
      <button
        :class="['dc-check', 'sm', { on: selection.has(d.id) }]"
        @click.stop="emit('select', d.id)"
      >
        <HmIcon name="check" :size="11" />
      </button>

      <!-- Type / first tag dot -->
      <span class="dtbl-type">
        <span class="type-dot" />
        <span>{{ d.tags?.[0] || '—' }}</span>
      </span>

      <!-- Title -->
      <button class="dtbl-title" @click="emit('open', d.id)">
        <span>{{ cellLabel(d) }}</span>
        <Snippet :text="d.summary || ''" :q="q" />
      </button>

      <!-- Correspondent -->
      <span class="dtbl-muted">{{ d.topic || '—' }}</span>

      <!-- Tags -->
      <span class="dtbl-tags">
        <span v-for="tag in (d.tags ?? []).slice(0, 3)" :key="tag" class="mini-tag">{{ tag }}</span>
      </span>

      <!-- Date -->
      <span class="dtbl-muted">{{ formatDate(d.created_at) }}</span>

      <!-- Status -->
      <span :class="['sl-status', d.status]">{{ d.status }}</span>
    </div>
  </div>
</template>

<style scoped>
.doctable {
  display: flex;
  flex-direction: column;
  width: 100%;
}

.dtbl-head {
  display: grid;
  grid-template-columns: 28px 90px 1fr 140px 140px 90px 90px;
  gap: 0 8px;
  padding: 6px 12px;
  font-size: 11px;
  font-weight: 600;
  color: var(--text-3, #aaa);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  border-bottom: 1px solid var(--line, #e0e0e0);
}

.dtbl-row {
  display: grid;
  grid-template-columns: 28px 90px 1fr 140px 140px 90px 90px;
  gap: 0 8px;
  padding: 7px 12px;
  align-items: center;
  border-bottom: 1px solid var(--line, #e0e0e0);
  transition: background 0.1s;
}
.dtbl-row:hover { background: var(--bg-0, #f9f9f9); }
.dtbl-row.sel { background: var(--honey-glow, rgba(229,160,0,.08)); }

.dc-check {
  all: unset;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--bg-0, #f5f5f5);
  border: 1px solid var(--line, #e0e0e0);
  display: grid;
  place-items: center;
  cursor: pointer;
  opacity: 0.5;
  transition: opacity 0.15s, background 0.15s;
}
.dc-check:hover { opacity: 1; }
.dc-check.on {
  background: var(--honey, #e5a000);
  border-color: var(--honey, #e5a000);
  opacity: 1;
  color: #fff;
}

.dtbl-type {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--text-2, #888);
}
.type-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--honey, #e5a000);
  flex: none;
}

.dtbl-title {
  all: unset;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-0, #111);
  min-width: 0;
}
.dtbl-title:hover span:first-child { text-decoration: underline; }

.dtbl-muted {
  font-size: 12px;
  color: var(--text-2, #888);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dtbl-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
}
.mini-tag {
  display: inline-block;
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 8px;
  background: var(--bg-0, #f0f0f0);
  color: var(--text-2, #666);
  border: 1px solid var(--line, #e0e0e0);
}

.sl-status {
  display: inline-block;
  font-size: 10.5px;
  padding: 2px 7px;
  border-radius: 8px;
  font-weight: 500;
  text-transform: capitalize;
  background: var(--bg-0, #f0f0f0);
  color: var(--text-2, #666);
}
.sl-status.committed {
  background: rgba(34, 197, 94, 0.12);
  color: #16a34a;
}
.sl-status.pending {
  background: rgba(229, 160, 0, 0.15);
  color: var(--honey-deep, #b07700);
}
.sl-status.rejected {
  background: rgba(239, 68, 68, 0.12);
  color: #dc2626;
}
</style>
