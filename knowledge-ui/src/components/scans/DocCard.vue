<script setup lang="ts">
import type { DocumentRow, SearchDocumentRow } from '../../api/types'
import { docName } from '../../api/cellLabel'
import DocThumb from './DocThumb.vue'
import Snippet from './Snippet.vue'
import HmIcon from '../shell/HmIcon.vue'

const props = defineProps<{ d: DocumentRow | SearchDocumentRow; q: string; selected: boolean }>()
const emit = defineEmits<{ (e: 'open'): void; (e: 'openInfo'): void; (e: 'select'): void }>()

function formatDate(iso: string): string {
  return iso.slice(0, 10).split('-').reverse().join('.')
}
</script>

<template>
  <div :class="['doccard', { sel: selected }]">
    <!-- Thumbnail area -->
    <button class="dc-thumb" @click="emit('open')">
      <DocThumb :d="d" />
      <span v-if="d.status === 'pending'" class="dc-pending">Pending</span>
    </button>

    <!-- Checkbox -->
    <button :class="['dc-check', { on: selected }]" @click.stop="emit('select')">
      <HmIcon name="check" :size="13" />
    </button>

    <!-- Card body — tapping the text opens the overview (summaries + raw text). -->
    <div class="dc-body" @click="emit('openInfo')">
      <div class="dc-title">{{ docName(d) }}</div>
      <div class="dc-corr">
        <HmIcon name="scans" :size="12" />
        <span>{{ d.correspondent || '—' }}</span>
      </div>
      <Snippet :text="d.summary || ''" :q="q" />
      <div class="dc-tags">
        <span class="chip">{{ d.realm }}</span>
        <span v-if="d.tags && d.tags[0]" class="chip honey">{{ d.tags[0] }}</span>
      </div>
      <div class="dc-foot">
        <span class="dc-date">{{ formatDate(d.created_at) }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.doccard {
  position: relative;
  display: flex;
  flex-direction: column;
  background: var(--bg-1, #fff);
  border: 1px solid var(--line, #e0e0e0);
  border-radius: 12px;
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow 0.15s;
}
.doccard:hover { box-shadow: 0 4px 18px -4px rgba(0,0,0,.18); }
.doccard.sel { border-color: var(--honey, #e5a000); box-shadow: 0 0 0 2px var(--honey-glow, rgba(229,160,0,.25)); }

.dc-thumb {
  all: unset;
  display: block;
  cursor: pointer;
  position: relative;
}
.dc-pending {
  position: absolute;
  top: 8px;
  left: 8px;
  font-size: 10px;
  background: var(--honey, #e5a000);
  color: #fff;
  border-radius: 5px;
  padding: 2px 6px;
  pointer-events: none;
}

.dc-check {
  all: unset;
  position: absolute;
  top: 8px;
  right: 8px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--bg-0, #f5f5f5);
  border: 1px solid var(--line, #e0e0e0);
  display: grid;
  place-items: center;
  cursor: pointer;
  opacity: 0.6;
  transition: opacity 0.15s, background 0.15s;
}
.dc-check:hover { opacity: 1; }
.dc-check.on {
  background: var(--honey, #e5a000);
  border-color: var(--honey, #e5a000);
  opacity: 1;
  color: #fff;
}

.dc-body {
  padding: 10px 12px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  cursor: pointer;
}
.dc-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--text-0, #111);
  line-height: 1.35;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.dc-corr {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--text-2, #888);
}
.dc-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}
.chip {
  display: inline-block;
  font-size: 10.5px;
  padding: 2px 7px;
  border-radius: 10px;
  background: var(--bg-0, #f0f0f0);
  color: var(--text-2, #666);
  border: 1px solid var(--line, #e0e0e0);
}
.chip.honey {
  background: var(--honey-glow, rgba(229,160,0,.15));
  color: var(--honey-deep, #b07700);
  border-color: var(--honey, #e5a000);
}
.dc-foot {
  margin-top: 4px;
  font-size: 11px;
  color: var(--text-3, #aaa);
}
</style>
