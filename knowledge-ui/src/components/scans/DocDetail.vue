<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DocumentRow } from '../../api/types'
import { useApi } from '../../api/useApi'
import { useReaderStore } from '../../stores/reader'
import { useScansStore } from '../../stores/scans'
import { cellLabel } from '../../api/cellLabel'
import DocThumb from './DocThumb.vue'
import HmIcon from '../shell/HmIcon.vue'

const props = withDefaults(defineProps<{ d: DocumentRow; q?: string }>(), { q: '' })

const { t } = useI18n()
const reader = useReaderStore()
const scans = useScansStore()

const content = ref('')
const cellTags = ref<string[]>(props.d.tags ?? [])
const similar = ref<DocumentRow[]>([])
const tab = ref<'ocr' | 'similar' | 'corr'>('ocr')
const correspondent = ref<string | null>(props.d.correspondent ?? null)
const confidence = computed(() => props.d.confidence ?? null)

// ── DOC_STATES map ───────────────────────────────────────────────────────────
interface StateEntry { label: string; tone: string }
const DOC_STATES: Record<string, StateEntry> = {
  ocr_pending:      { label: 'OCR…',              tone: 'warn' },
  ocr_failed:       { label: 'OCR-Fehler',         tone: 'danger' },
  vision_pending:   { label: 'Vision…',            tone: 'info' },
  needs_summary:    { label: 'Zusammenfassung…',   tone: 'warn' },
  kroki_pending:    { label: 'Diagramm…',          tone: 'info' },
}

function toneColor(tone: string): string {
  if (tone === 'good')   return 'var(--good)'
  if (tone === 'warn')   return 'var(--honey)'
  if (tone === 'danger') return 'var(--danger)'
  if (tone === 'info')   return 'var(--cyber)'
  return 'var(--text-2)'
}

const states = computed((): Array<StateEntry & { color: string }> => {
  const tags = cellTags.value
  const found: Array<StateEntry & { color: string }> = []
  for (const tag of tags) {
    if (DOC_STATES[tag]) {
      found.push({ ...DOC_STATES[tag], color: toneColor(DOC_STATES[tag].tone) })
    } else if (tag.startsWith('subtype_')) {
      found.push({ label: 'Klassifiziert', tone: 'good', color: toneColor('good') })
    }
  }
  if (found.length === 0 && props.d.status === 'committed') {
    found.push({ label: 'Fertig', tone: 'good', color: toneColor('good') })
  }
  return found
})

const subtype = computed(() => {
  const tags = cellTags.value
  const st = tags.find(t => t.startsWith('subtype_'))
  if (st) return st.replace('subtype_', '')
  return tags[0] || '—'
})

const dateFormatted = computed(() => {
  const d = props.d.created_at || ''
  return d.slice(0, 10).split('-').reverse().join('.')
})

// ── OCR highlight ────────────────────────────────────────────────────────────
function highlightedContent(raw: string, q: string): string {
  if (!q || !raw) return escapeHtml(raw)
  const escaped = escapeHtml(raw)
  const escapedQ = q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return escaped.replace(new RegExp(escapedQ, 'gi'), m => `<mark>${m}</mark>`)
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

// ── Load cell data ────────────────────────────────────────────────────────────
async function loadCell(id: string) {
  try {
    const cell = await useApi().call<any>('get_cell', { cell_id: id, include: ['content', 'tags', 'attachments'] })
    content.value = cell.content ?? ''
    cellTags.value = cell.tags ?? props.d.tags ?? []
  } catch {
    content.value = ''
    cellTags.value = props.d.tags ?? []
  }
  try {
    const query = props.d.tags?.[0] || props.d.summary || 'a'
    const results = await useApi().call<DocumentRow[]>('search', { query, realm: 'documents', limit: 5 })
    similar.value = results.filter(x => x.id !== props.d.id).slice(0, 4)
  } catch {
    similar.value = []
  }
  // Load correspondent from quick_facts (best-effort)
  if (props.d.correspondent) {
    correspondent.value = props.d.correspondent
  } else {
    try {
      const facts = await useApi().call<Record<string, string>>('quick_facts', { cell_id: id })
      correspondent.value = facts?.vendor ?? facts?.party ?? null
    } catch {
      correspondent.value = null
    }
  }
}

// ── Tag editing ───────────────────────────────────────────────────────────────
async function addTag() {
  const raw = window.prompt(t('scans.addTagPrompt'))
  if (!raw?.trim()) return
  const newTags = raw.split(',').map(s => s.trim()).filter(Boolean)
  if (!newTags.length) return
  await scans.editTags(props.d.id, newTags, [])
  cellTags.value = [...new Set([...cellTags.value, ...newTags])]
}

async function removeTag(tag: string) {
  await scans.editTags(props.d.id, [], [tag])
  cellTags.value = cellTags.value.filter(t => t !== tag)
}

onMounted(() => loadCell(props.d.id))
watch(() => props.d.id, id => loadCell(id))

// ── Keyboard navigation ───────────────────────────────────────────────────────
function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') { scans.closeDetail(); return }
  if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
    const list = scans.filtered
    const idx = list.findIndex(x => x.id === props.d.id)
    if (idx === -1) return
    const next = e.key === 'ArrowLeft' ? idx - 1 : idx + 1
    if (next >= 0 && next < list.length) scans.open(list[next].id)
  }
}

onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => window.removeEventListener('keydown', onKey))

// ── Actions ───────────────────────────────────────────────────────────────────
function openOriginal() {
  reader.openReader(props.d.id)
}

async function approve() {
  await useApi().call('approve_pending', { ids: [props.d.id], decision: 'committed' }).catch(() => {})
}
</script>

<template>
  <div class="docoverlay" @click="scans.closeDetail()">
    <div class="docmodal" @click.stop>
      <!-- Preview column -->
      <div class="dm-preview">
        <DocThumb :d="d" :big="true" />
        <div v-if="(d.page_count ?? 0) > 1" class="dm-pager">
          {{ t('scans.page') }} 1 {{ t('scans.of') }} {{ d.page_count }}
        </div>
      </div>

      <!-- Side column -->
      <div class="dm-side">
        <!-- Head -->
        <div class="dm-head">
          <div class="dm-chips">
            <span class="dm-chip realm">{{ d.realm }}</span>
            <span v-if="cellTags[0]" class="dm-chip tag">{{ cellTags[0] }}</span>
            <span :class="['sl-status', d.status]">{{ d.status }}</span>
          </div>
          <button class="dm-close" @click="scans.closeDetail()">
            <HmIcon name="close" :size="18" />
          </button>
        </div>

        <!-- Title -->
        <h2 class="dm-title">{{ cellLabel(d) }}</h2>

        <!-- Correspondent -->
        <div class="dm-correspondent">{{ correspondent || d.topic || '—' }}</div>

        <!-- Confidence bar -->
        <div v-if="confidence !== null" class="conf-row" data-test="confidence-bar">
          <span class="conf-label">{{ t('scans.confidence') }}</span>
          <div class="conf-track">
            <div
              class="conf-fill"
              :style="{ width: (confidence * 100).toFixed(0) + '%' }"
            />
          </div>
          <span class="conf-pct">{{ (confidence * 100).toFixed(0) }}%</span>
        </div>

        <!-- Meta grid -->
        <div class="meta-grid">
          <span class="mg-label">{{ t('scans.date') }}</span>
          <span class="mg-val">{{ dateFormatted }}</span>

          <span class="mg-label">{{ t('scans.pages') }}</span>
          <span class="mg-val">{{ d.page_count ?? '—' }}</span>

          <span class="mg-label">{{ t('scans.type') }}</span>
          <span class="mg-val">{{ subtype }}</span>

          <span class="mg-label">ASN</span>
          <span class="mg-val">—</span>

          <span class="mg-label">{{ t('scans.amount') }}</span>
          <span class="mg-val">—</span>

          <span class="mg-label">{{ t('scans.correspondent') }}</span>
          <span class="mg-val">{{ correspondent || '—' }}</span>
        </div>

        <!-- State pipeline -->
        <div class="states">
          <span
            v-for="(s, i) in states"
            :key="i"
            class="state-pill"
            :style="{ color: s.color, borderColor: s.color }"
          >{{ s.label }}</span>
        </div>

        <!-- Tags (editable) -->
        <div class="dm-tags">
          <span
            v-for="tag in cellTags"
            :key="tag"
            class="dm-tag dm-tag-editable"
          >
            {{ tag }}
            <button class="dm-tag-del" :title="t('scans.clearAll')" @click="removeTag(tag)">
              <HmIcon name="close" :size="9" />
            </button>
          </span>
          <button class="dm-tag-add" :title="t('scans.addTag')" @click="addTag">
            <HmIcon name="sparkle" :size="11" />
            {{ t('scans.addTag') }}
          </button>
        </div>

        <!-- Tabs -->
        <div class="tabs">
          <button :class="['tab-btn', { active: tab === 'ocr' }]" @click="tab = 'ocr'">OCR</button>
          <button :class="['tab-btn', { active: tab === 'similar' }]" @click="tab = 'similar'">{{ t('scans.similar') }}</button>
          <button :class="['tab-btn', { active: tab === 'corr' }]" @click="tab = 'corr'">{{ t('scans.correspondence') }}</button>
        </div>

        <!-- Tab content -->
        <div class="dm-tab-body">
          <!-- OCR -->
          <div v-if="tab === 'ocr'" class="ocr">
            <p v-if="!content" class="ocr-empty">—</p>
            <!-- eslint-disable-next-line vue/no-v-html -->
            <pre v-else class="ocr-text" v-html="highlightedContent(content, q ?? '')" />
          </div>

          <!-- Similar -->
          <div v-else-if="tab === 'similar'" class="related">
            <div v-if="similar.length === 0" class="related-empty">—</div>
            <div
              v-for="s in similar"
              :key="s.id"
              class="related-row"
              @click="scans.open(s.id)"
            >
              <span class="related-title">{{ cellLabel(s) }}</span>
              <span class="related-date">{{ (s.created_at || '').slice(0, 10) }}</span>
            </div>
          </div>

          <!-- Correspondence -->
          <div v-else class="corr-tab">—</div>
        </div>

        <!-- Footer -->
        <div class="dm-foot">
          <template v-if="d.status === 'pending'">
            <button class="dm-btn approve" @click="approve">{{ t('scans.approve') }}</button>
            <button class="dm-btn open-orig" data-test="open-original" @click="openOriginal">{{ t('scans.openDoc') }}</button>
          </template>
          <template v-else>
            <button class="dm-btn open-orig" data-test="open-original" @click="openOriginal">{{ t('scans.openOriginal') }}</button>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ── Overlay ─────────────────────────────────────────────────────────────── */
.docoverlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 900;
}

.docmodal {
  background: var(--bg-1, #1e1e2e);
  border-radius: 14px;
  border: 1px solid var(--line, rgba(255,255,255,.1));
  display: flex;
  width: min(90vw, 900px);
  max-height: 90vh;
  overflow: hidden;
  box-shadow: 0 24px 64px rgba(0,0,0,.6);
}

/* ── Preview column ─────────────────────────────────────────────────────── */
.dm-preview {
  flex: 0 0 260px;
  background: var(--bg-0, #16161e);
  display: flex;
  flex-direction: column;
  padding: 18px;
  gap: 10px;
}

.dm-pager {
  text-align: center;
  font-size: 11.5px;
  color: var(--text-2, #aaa);
}

/* ── Side column ────────────────────────────────────────────────────────── */
.dm-side {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 20px 22px;
  gap: 10px;
  min-width: 0;
}

/* Head */
.dm-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.dm-chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px;
}

.dm-chip {
  font-size: 10.5px;
  padding: 2px 8px;
  border-radius: 6px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: .03em;
}

.dm-chip.realm { background: var(--bg-3, rgba(255,255,255,.07)); color: var(--text-2, #aaa); }
.dm-chip.tag   { background: var(--cyber-dim, rgba(0,230,255,.12)); color: var(--cyber, #00e6ff); }

.sl-status {
  font-size: 10px;
  padding: 2px 7px;
  border-radius: 6px;
  font-weight: 700;
  text-transform: uppercase;
}
.sl-status.committed { background: rgba(0,200,100,.15); color: var(--good, #00c864); }
.sl-status.pending   { background: rgba(255,180,0,.15);  color: var(--honey, #ffb400); }

.dm-close {
  background: none;
  border: none;
  cursor: pointer;
  color: var(--text-2, #aaa);
  display: flex;
  align-items: center;
  padding: 3px;
  border-radius: 5px;
  flex-shrink: 0;
}
.dm-close:hover { background: var(--bg-3, rgba(255,255,255,.08)); color: var(--text-1, #eee); }

/* Title */
.dm-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-1, #eee);
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dm-correspondent {
  font-size: 12px;
  color: var(--text-2, #aaa);
  margin-top: -4px;
}

/* Meta grid */
.meta-grid {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 3px 14px;
  font-size: 12px;
}

.mg-label {
  color: var(--text-3, #777);
  white-space: nowrap;
}

.mg-val {
  color: var(--text-1, #eee);
}

/* States */
.states {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.state-pill {
  font-size: 10.5px;
  padding: 2px 8px;
  border-radius: 6px;
  border: 1px solid;
  font-weight: 600;
  background: transparent;
}

/* Confidence bar */
.conf-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
}

.conf-label {
  color: var(--text-3, #777);
  white-space: nowrap;
  min-width: 60px;
}

.conf-track {
  flex: 1;
  height: 5px;
  border-radius: 3px;
  background: var(--bg-3, rgba(255,255,255,.08));
  overflow: hidden;
}

.conf-fill {
  height: 100%;
  border-radius: 3px;
  background: var(--honey, #e5a000);
  transition: width 0.3s;
}

.conf-pct {
  color: var(--honey, #e5a000);
  font-family: var(--font-mono, monospace);
  font-size: 10.5px;
  min-width: 32px;
  text-align: right;
}

/* Tags */
.dm-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}

.dm-tag {
  font-size: 10px;
  padding: 2px 7px;
  border-radius: 5px;
  background: var(--bg-3, rgba(255,255,255,.06));
  color: var(--text-2, #aaa);
}

.dm-tag-editable {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding-right: 4px;
}

.dm-tag-del {
  background: none;
  border: none;
  cursor: pointer;
  color: var(--text-3, #777);
  display: flex;
  align-items: center;
  padding: 0;
  border-radius: 2px;
  transition: color 0.1s;
}
.dm-tag-del:hover { color: var(--danger, #ff4444); }

.dm-tag-add {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 10px;
  padding: 2px 7px;
  border-radius: 5px;
  background: var(--honey-dim, rgba(240,180,40,.08));
  color: var(--honey, #e5a000);
  border: 1px dashed var(--honey-dim, rgba(240,180,40,.3));
  cursor: pointer;
  transition: background 0.12s;
}
.dm-tag-add:hover { background: var(--honey-dim, rgba(240,180,40,.16)); }

/* Tabs */
.tabs {
  display: flex;
  gap: 2px;
  border-bottom: 1px solid var(--line, rgba(255,255,255,.09));
  padding-bottom: 0;
}

.tab-btn {
  background: none;
  border: none;
  cursor: pointer;
  font-size: 12px;
  padding: 5px 12px;
  color: var(--text-2, #aaa);
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  border-radius: 4px 4px 0 0;
}
.tab-btn.active { color: var(--text-1, #eee); border-bottom-color: var(--accent, #7f6af0); }
.tab-btn:hover:not(.active) { background: var(--bg-3, rgba(255,255,255,.05)); }

/* Tab body */
.dm-tab-body {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}

/* OCR */
.ocr {
  padding: 8px 0;
}

.ocr-text {
  font-size: 11.5px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text-2, #aaa);
  margin: 0;
}

.ocr-empty {
  color: var(--text-3, #777);
  font-size: 12px;
}

.ocr-text :deep(mark) {
  background: var(--honey, #ffb400);
  color: #000;
  border-radius: 2px;
  padding: 0 2px;
}

/* Related */
.related {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 6px 0;
}

.related-empty {
  color: var(--text-3, #777);
  font-size: 12px;
}

.related-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 5px 8px;
  border-radius: 6px;
  cursor: pointer;
  gap: 10px;
}
.related-row:hover { background: var(--bg-3, rgba(255,255,255,.06)); }

.related-title {
  font-size: 12.5px;
  color: var(--text-1, #eee);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.related-date {
  font-size: 11px;
  color: var(--text-3, #777);
  flex-shrink: 0;
}

.corr-tab {
  color: var(--text-3, #777);
  font-size: 12px;
  padding: 8px 0;
}

/* Footer */
.dm-foot {
  display: flex;
  gap: 8px;
  padding-top: 10px;
  border-top: 1px solid var(--line, rgba(255,255,255,.08));
  flex-shrink: 0;
}

.dm-btn {
  font-size: 12.5px;
  padding: 6px 16px;
  border-radius: 7px;
  border: 1px solid var(--line, rgba(255,255,255,.1));
  cursor: pointer;
  font-weight: 600;
  background: var(--bg-3, rgba(255,255,255,.06));
  color: var(--text-1, #eee);
  transition: background 0.15s;
}
.dm-btn:hover { background: var(--bg-4, rgba(255,255,255,.12)); }
.dm-btn.approve { background: rgba(0,200,100,.15); color: var(--good, #00c864); border-color: var(--good, #00c864); }
.dm-btn.approve:hover { background: rgba(0,200,100,.25); }
</style>
