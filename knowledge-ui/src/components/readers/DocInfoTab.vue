<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useCellStore } from '../../stores/cell'
import { useScansStore } from '../../stores/scans'
import { useUiStore } from '../../stores/ui'
import { useApi } from '../../api/useApi'
import { docName } from '../../api/cellLabel'
import MarkdownTab from './MarkdownTab.vue'
import HmIcon from '../shell/HmIcon.vue'

const { t } = useI18n()
const cellStore = useCellStore()
const scans = useScansStore()
const ui = useUiStore()

const cell = computed(() => cellStore.current?.cell ?? null)

// The scan-specific metadata (correspondent, confidence, page count, pending state)
// lives on the document-list row, not on the cell. Look it up by id; absent for
// non-scan cells (e.g. a knowledge cell opened in the same reader).
const row = computed(() =>
  cell.value ? (scans.results.find(d => d.id === cell.value!.id) ?? null) : null)

// Local, optimistic copies so tag edits and approval reflect immediately without a
// full reload round-trip blocking the UI.
const tags = ref<string[]>([])
const status = ref<string | null>(null)
watch(cell, c => { tags.value = [...(c?.tags ?? [])] }, { immediate: true })
watch(row, r => { status.value = r?.status ?? null }, { immediate: true })

const confidencePct = computed(() =>
  row.value?.confidence != null ? Math.round(row.value.confidence * 100) : null)

const dateFormatted = computed(() => {
  const d = row.value?.created_at || cell.value?.created_at || ''
  return d ? d.slice(0, 10).split('-').reverse().join('.') : '—'
})

async function addTag() {
  const raw = window.prompt(t('scans.addTagPrompt'))
  if (!raw?.trim() || !cell.value) return
  const add = raw.split(',').map(s => s.trim()).filter(Boolean)
  if (!add.length) return
  const prev = [...tags.value]
  tags.value = [...new Set([...tags.value, ...add])]
  try {
    await scans.editTags(cell.value.id, add, [])
  } catch {
    tags.value = prev
    ui.pushToast('error', t('common.actionFailed'))
  }
}

async function removeTag(tag: string) {
  if (!cell.value) return
  const prev = [...tags.value]
  tags.value = tags.value.filter(x => x !== tag)
  try {
    await scans.editTags(cell.value.id, [], [tag])
  } catch {
    tags.value = prev
    ui.pushToast('error', t('common.actionFailed'))
  }
}

async function approve() {
  if (!cell.value) return
  const prev = status.value
  status.value = 'committed'
  try {
    await useApi().call('approve_pending', { ids: [cell.value.id], decision: 'committed' })
  } catch {
    // Roll back so the approve button reappears and the user can retry.
    status.value = prev
    ui.pushToast('error', t('common.actionFailed'))
    return
  }
  await scans.reload().catch(() => {})
}
</script>

<template>
  <div v-if="cell" class="dinfo">
    <h1 class="di-title">{{ docName(cell) }}</h1>

    <div class="di-chips">
      <span class="chip">{{ cell.realm }}</span>
      <span v-if="status" :class="['chip', status]">{{ t('scans.' + status) }}</span>
    </div>

    <!-- Scan metadata (only when a matching document row exists) -->
    <div v-if="row" class="di-meta" data-test="di-meta">
      <div v-if="confidencePct !== null" class="conf-row" data-test="di-confidence">
        <span class="conf-label">{{ t('scans.confidence') }}</span>
        <div class="conf-track"><div class="conf-fill" :style="{ width: confidencePct + '%' }" /></div>
        <span class="conf-pct">{{ confidencePct }}%</span>
      </div>
      <div class="meta-grid">
        <span class="mg-label">{{ t('scans.date') }}</span>
        <span class="mg-val">{{ dateFormatted }}</span>
        <span class="mg-label">{{ t('scans.pages') }}</span>
        <span class="mg-val">{{ row.page_count ?? '—' }}</span>
        <span class="mg-label">{{ t('scans.correspondent') }}</span>
        <span class="mg-val">{{ row.correspondent || cell.topic || '—' }}</span>
      </div>
    </div>

    <!-- Editable tags -->
    <div class="di-tags" data-test="di-tags">
      <span v-for="tag in tags" :key="tag" class="di-tag">
        {{ tag }}
        <button class="di-tag-del" data-test="di-tag-del" :title="t('scans.clearAll')" @click="removeTag(tag)">
          <HmIcon name="close" :size="9" />
        </button>
      </span>
      <button class="di-tag-add" :title="t('scans.addTag')" @click="addTag">
        <HmIcon name="sparkle" :size="11" /> {{ t('scans.addTag') }}
      </button>
    </div>

    <button v-if="status === 'pending'" class="di-approve" data-test="di-approve" @click="approve">
      {{ t('scans.approve') }}
    </button>

    <!-- Layers -->
    <section v-if="cell.summary" class="di-section" data-test="di-summary">
      <h3 class="di-h">{{ t('reader.summary') }}</h3>
      <p class="di-prose">{{ cell.summary }}</p>
    </section>

    <section v-if="cell.key_points && cell.key_points.length" class="di-section" data-test="di-keypoints">
      <h3 class="di-h">{{ t('reader.keyPoints') }}</h3>
      <div v-for="(k, i) in cell.key_points" :key="i" class="di-kp">
        <span class="di-kx"><HmIcon name="check" :size="15" /></span><span>{{ k }}</span>
      </div>
    </section>

    <section v-if="cell.insight" class="di-section" data-test="di-insight">
      <h3 class="di-h">{{ t('reader.insight') }}</h3>
      <div class="di-insight">{{ cell.insight }}</div>
    </section>

    <section v-if="cell.content" class="di-section" data-test="di-text">
      <h3 class="di-h">{{ t('reader.text') }}</h3>
      <MarkdownTab :content="cell.content" />
    </section>
  </div>
</template>

<style scoped>
.dinfo { max-width: 760px; margin: 0 auto; padding: 24px 4px 60px; display: flex; flex-direction: column; gap: 16px; }
.di-title { font-family: var(--font-display); font-size: 22px; line-height: 1.2; color: var(--text-0, #fff); margin: 0; }
.di-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip { font-size: 11px; padding: 2px 8px; border-radius: 6px; background: var(--bg-4, rgba(255,255,255,.07)); color: var(--text-1, #eee); border: 1px solid var(--line, #2a2a3a); }
.chip.committed { background: rgba(0,200,100,.15); color: var(--good, #00c864); border-color: var(--good, #00c864); }
.chip.pending { background: rgba(255,180,0,.15); color: var(--honey, #ffb400); border-color: var(--honey, #ffb400); }

.di-meta { display: flex; flex-direction: column; gap: 10px; padding: 12px 14px; background: var(--bg-1, #16161e); border: 1px solid var(--line, #2a2a3a); border-radius: 10px; }
.conf-row { display: flex; align-items: center; gap: 8px; font-size: 11px; }
.conf-label { color: var(--text-3, #777); min-width: 70px; }
.conf-track { flex: 1; height: 5px; border-radius: 3px; background: var(--bg-3, rgba(255,255,255,.08)); overflow: hidden; }
.conf-fill { height: 100%; border-radius: 3px; background: var(--honey, #e5a000); }
.conf-pct { color: var(--honey, #e5a000); font-size: 10.5px; min-width: 32px; text-align: right; }
.meta-grid { display: grid; grid-template-columns: auto 1fr; gap: 4px 14px; font-size: 12.5px; }
.mg-label { color: var(--text-3, #777); white-space: nowrap; }
.mg-val { color: var(--text-1, #eee); }

.di-tags { display: flex; flex-wrap: wrap; gap: 5px; align-items: center; }
.di-tag { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; padding: 3px 8px; border-radius: 6px; background: var(--bg-4, rgba(255,255,255,.06)); color: var(--text-2, #aaa); }
.di-tag-del { background: none; border: none; cursor: pointer; color: var(--text-3, #777); display: flex; align-items: center; padding: 0; }
.di-tag-del:hover { color: var(--danger, #ff4444); }
.di-tag-add { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; padding: 3px 9px; border-radius: 6px; background: var(--honey-dim, rgba(240,180,40,.08)); color: var(--honey, #e5a000); border: 1px dashed var(--honey-dim, rgba(240,180,40,.3)); cursor: pointer; }
.di-tag-add:hover { background: var(--honey-dim, rgba(240,180,40,.16)); }

.di-approve { align-self: flex-start; font-size: 13px; font-weight: 600; padding: 8px 18px; border-radius: 8px; cursor: pointer; background: rgba(0,200,100,.15); color: var(--good, #00c864); border: 1px solid var(--good, #00c864); }
.di-approve:hover { background: rgba(0,200,100,.25); }

.di-section { display: flex; flex-direction: column; gap: 8px; }
.di-h { font-size: 11px; letter-spacing: .08em; text-transform: uppercase; color: var(--text-3, #888); margin: 0; font-weight: 700; }
.di-prose { font-size: 15px; line-height: 1.6; color: var(--text-1, #eee); margin: 0; }
.di-kp { display: flex; gap: 10px; font-size: 14px; color: var(--text-1, #eee); line-height: 1.5; }
.di-kx { color: var(--honey, #e5a000); flex: none; margin-top: 2px; }
.di-insight { background: var(--honey-dim, rgba(240,180,40,.08)); border: 1px solid var(--line-honey, rgba(240,180,40,.3)); border-radius: 12px; padding: 14px 16px; font-size: 14.5px; line-height: 1.6; color: var(--text-0, #fff); }
</style>
