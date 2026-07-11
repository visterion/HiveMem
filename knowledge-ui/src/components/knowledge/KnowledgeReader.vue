<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useCellStore } from '../../stores/cell'
import { useReaderStore } from '../../stores/reader'
import { useUiStore } from '../../stores/ui'
import { cellLabel } from '../../api/cellLabel'
import HmIcon from '../shell/HmIcon.vue'
import CellEditor from './CellEditor.vue'
import NewCellDialog from './NewCellDialog.vue'
import ObsidianImportDialog from './ObsidianImportDialog.vue'
import TunnelEditor from './TunnelEditor.vue'
import { cellToMarkdown, cellMarkdownFilename } from '../../composables/cellMarkdown'
import { useLayout } from '../../composables/useLayout'

const cellStore = useCellStore()
const reader = useReaderStore()
const ui = useUiStore()
const { t } = useI18n()
const { isMobile } = useLayout()
// Optional: some unit tests mount KnowledgeReader without a router installed.
const route = useRoute()
const router = useRouter()

const cell = computed(() => cellStore.current?.cell ?? null)
const tab = ref<'summary' | 'keypoints' | 'insight' | 'text'>('summary')
const editing = ref(false)
const creating = ref(false)
const importing = ref(false)
const saving = ref(false)
const saveError = ref(false)
watch(() => cell.value?.id, () => { tab.value = 'summary'; editing.value = false; saveError.value = false })

// Deep link: /?cell=<id> restores the selected cell on a fresh page load.
onMounted(() => {
  const id = route?.query.cell
  if (typeof id === 'string' && cellStore.currentId !== id) cellStore.load(id)
})

// Mirror the selected cell into the URL (?cell=<id>) so it's shareable, and drop
// the param again once nothing is selected — regardless of which UI path changed
// the selection (search result click, inspector close, Escape). Scoped to the
// search route so other routes that also use cellStore (e.g. scans/hive) aren't
// affected. Query-only, same route name, so it won't fight Task 11's
// name-based afterEach cell-store reset.
watch(() => cellStore.currentId, (id) => {
  if (!route || !router || route.name !== 'search') return
  const query = { ...route.query }
  if (id) query.cell = id
  else delete query.cell
  router.replace({ query })
})

function startEdit() { saveError.value = false; editing.value = true }
function onCreated() { creating.value = false } // addCell already loads + selects the new cell

const newTag = ref('')
async function addTag() {
  const tag = newTag.value.trim()
  if (!tag || !cell.value) return
  newTag.value = ''
  if ((cell.value.tags ?? []).includes(tag)) return
  try {
    await cellStore.addTags(cell.value.id, [tag])
  } catch {
    newTag.value = tag // restore the typed tag so the user can retry
    ui.pushToast('error', t('common.actionFailed'))
  }
}
async function removeTag(tag: string) {
  if (!cell.value) return
  try {
    await cellStore.removeTags(cell.value.id, [tag])
  } catch {
    ui.pushToast('error', t('common.actionFailed'))
  }
}

function exportMarkdown() {
  if (!cell.value) return
  const blob = new Blob([cellToMarkdown(cell.value)], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = cellMarkdownFilename(cell.value)
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

async function onSave(content: string) {
  if (!cell.value) return
  saving.value = true
  saveError.value = false
  try {
    await cellStore.revise(cell.value.id, { content })
    editing.value = false
    tab.value = 'text'
  } catch {
    saveError.value = true
  } finally {
    saving.value = false
  }
}

const tabs = computed(() => [
  ['summary', t('reader.summary')],
  ['keypoints', t('reader.keyPoints')],
  ['insight', t('reader.insight')],
  ['text', t('reader.text')],
] as const)

const hasDoc = computed(() => !!cell.value?.attachments?.length)
function openDoc() { if (cellStore.currentId) reader.openReader(cellStore.currentId) }
</script>

<template>
  <div v-if="!cell" class="empty">
    <div>
      <div class="hexbig"><HmIcon name="reader" :size="40" /></div>
      <div class="h-display" style="font-size:21px;color:var(--text-1)">{{ t('knowledge.selectCell') }}</div>
      <div style="margin-top:8px;font-size:14px;color:var(--text-2)">{{ t(isMobile ? 'knowledge.selectCellSubMobile' : 'knowledge.selectCellSub') }}</div>
      <button class="new-btn" data-test="reader-new" @click="creating = true">＋ {{ t('editor.newCell') }}</button>
      <button class="new-btn ghost" data-test="reader-import" @click="importing = true">📥 {{ t('editor.importVault') }}</button>
    </div>
  </div>
  <div v-else class="reader fade-in" :key="cell.id">
    <div class="reader-inner">
      <div class="chips">
        <span class="chip">{{ cell.realm }}</span>
        <span v-if="cell.signal" class="chip">{{ cell.signal }}</span>
        <span class="chip honey">★ {{ cell.importance }}</span>
        <button v-if="hasDoc" class="chip honey doc" @click="openDoc">{{ t('reader.openReader') }}</button>
        <button v-if="!editing" class="chip doc" data-test="reader-edit" @click="startEdit">📝 {{ t('editor.edit') }}</button>
        <button v-if="!editing" class="chip doc" data-test="reader-new" @click="creating = true">＋ {{ t('editor.newCell') }}</button>
        <button v-if="!editing" class="chip doc" data-test="reader-export" @click="exportMarkdown">📤 {{ t('editor.exportMd') }}</button>
        <button v-if="!editing" class="chip doc" data-test="reader-import" @click="importing = true">📥 {{ t('editor.importVault') }}</button>
      </div>
      <h1 class="h-display title">{{ cellLabel(cell) }}</h1>

      <div v-if="!editing" class="tags-row" data-test="cell-tags">
        <span v-for="tg in cell.tags" :key="tg" class="tagchip" data-test="cell-tag-chip">
          {{ tg }}
          <button class="tagx" data-test="cell-tag-remove" :title="t('editor.removeTag')" @click="removeTag(tg)">✕</button>
        </span>
        <input
          v-model="newTag"
          class="tag-input"
          data-test="cell-tag-input"
          :placeholder="t('editor.addTag')"
          @keydown.enter.prevent="addTag"
        />
      </div>

      <div v-if="editing" class="edit-wrap" data-test="reader-editing">
        <p v-if="saveError" class="save-error" data-test="reader-save-error">{{ t('editor.saveError') }}</p>
        <CellEditor :content="cell.content" :saving="saving" @save="onSave" @cancel="editing = false" />
      </div>

      <template v-else>
      <div class="tabs">
        <button v-for="[k, lbl] in tabs" :key="k" :class="['tab', { on: tab === k }]" @click="tab = (k as any)">{{ lbl }}</button>
      </div>
      <div class="tab-body">
        <p v-if="tab === 'summary'" class="prose">{{ cell.summary || '—' }}</p>
        <div v-else-if="tab === 'keypoints'">
          <div v-for="(k, i) in cell.key_points" :key="i" class="keypoint">
            <span class="kx"><HmIcon name="check" :size="16" /></span><span>{{ k }}</span>
          </div>
        </div>
        <div v-else-if="tab === 'insight'" class="insight">{{ cell.insight || '—' }}</div>
        <div v-else class="ocr">{{ cell.content }}</div>
      </div>
      <TunnelEditor :key="cell.id" />
      </template>
    </div>
  </div>

  <!-- Hoisted to a stable top-level node so it survives the empty→reader transition
       when a freshly created cell loads (otherwise its `created` emit is lost). -->
  <NewCellDialog v-if="creating" @created="onCreated" @close="creating = false" />
  <ObsidianImportDialog v-if="importing" @imported="() => {}" @close="importing = false" />
</template>

<style scoped>
.empty { display:grid; place-items:center; height:100%; text-align:center; color:var(--text-2); }
.hexbig { width:86px; height:94px; margin:0 auto 18px; display:grid; place-items:center; color:var(--honey); opacity:.8; }
.h-display { font-family:var(--font-display); font-weight:600; letter-spacing:-.02em; color:var(--text-0); }
.reader { height:100%; overflow-y:auto; }
.reader-inner { max-width:760px; margin:0 auto; padding:34px 40px 60px; }
.chips { display:flex; gap:9px; align-items:center; margin-bottom:16px; flex-wrap:wrap; }
.chip { font-size:11px; padding:2px 8px; border-radius:6px; font-weight:500; background:var(--bg-4); color:var(--text-1);
  border:1px solid var(--line); display:inline-flex; align-items:center; gap:5px; white-space:nowrap; }
.chip.honey { background:var(--honey-dim); color:var(--honey); border-color:var(--line-honey); }
.chip.doc { cursor:pointer; }
.title { font-size:30px; line-height:1.18; margin:0 0 22px; }
.tabs { display:flex; gap:2px; border-bottom:1px solid var(--line); }
.tab { padding:9px 13px 11px; font-size:13px; color:var(--text-2); position:relative; font-weight:500; background:none; border:none; cursor:pointer; }
.tab:hover { color:var(--text-0); }
.tab.on { color:var(--honey); }
.tab.on::after { content:''; position:absolute; left:8px; right:8px; bottom:-1px; height:2px; background:var(--honey); border-radius:2px; }
.tab-body { padding-top:22px; }
.prose { font-size:14.5px; line-height:1.62; color:var(--text-1); }
.keypoint { display:flex; gap:10px; padding:7px 0; font-size:14px; color:var(--text-1); line-height:1.5; }
.keypoint .kx { color:var(--honey); flex:none; margin-top:2px; }
.insight { background:var(--honey-dim); border:1px solid var(--line-honey); border-radius:12px; padding:14px 16px;
  font-size:14.5px; line-height:1.6; color:var(--text-0); }
.ocr { font-family:var(--font-mono); font-size:12px; line-height:1.7; color:var(--text-1); background:var(--bg-0);
  border:1px solid var(--line); border-radius:10px; padding:14px; white-space:pre-wrap; }
.edit-wrap { padding-top:8px; }
.save-error { color:#ef9a9a; font-size:13px; margin:0 0 8px; }
.new-btn { margin-top:18px; padding:8px 16px; min-height:40px; border-radius:9px; border:1px solid var(--line-honey);
  background:var(--honey-dim); color:var(--honey); font-size:13px; font-weight:500; cursor:pointer; }
.new-btn:hover { background:var(--bg-3); }
.new-btn.ghost { background:var(--bg-4); color:var(--text-1); border-color:var(--line); margin-left:8px; }
.tags-row { display:flex; flex-wrap:wrap; gap:7px; align-items:center; margin:-8px 0 18px; }
.tagchip { display:inline-flex; align-items:center; gap:5px; font-size:11px; padding:3px 6px 3px 9px; border-radius:12px;
  background:var(--bg-4); color:var(--text-1); border:1px solid var(--line); }
.tagx { background:none; border:none; color:var(--text-2); cursor:pointer; font-size:11px; line-height:1; padding:2px; border-radius:6px; }
.tagx:hover { color:#ef9a9a; background:var(--bg-3); }
.tag-input { background:transparent; border:1px dashed var(--line); border-radius:12px; color:var(--text-1);
  padding:3px 9px; font-size:11px; min-width:110px; }
.tag-input:focus { outline:none; border-color:var(--accent, #8ab4f8); border-style:solid; }
</style>
