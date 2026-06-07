<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useCellStore } from '../../stores/cell'
import { useReaderStore } from '../../stores/reader'
import { cellLabel } from '../../api/cellLabel'
import HmIcon from '../shell/HmIcon.vue'

const cellStore = useCellStore()
const reader = useReaderStore()
const { t } = useI18n()

const cell = computed(() => cellStore.current?.cell ?? null)
const tab = ref<'summary' | 'keypoints' | 'insight' | 'text'>('summary')
watch(() => cell.value?.id, () => { tab.value = 'summary' })

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
      <div style="margin-top:8px;font-size:14px;color:var(--text-2)">{{ t('knowledge.selectCellSub') }}</div>
    </div>
  </div>
  <div v-else class="reader fade-in" :key="cell.id">
    <div class="reader-inner">
      <div class="chips">
        <span class="chip">{{ cell.realm }}</span>
        <span v-if="cell.signal" class="chip">{{ cell.signal }}</span>
        <span class="chip honey">★ {{ cell.importance }}</span>
        <button v-if="hasDoc" class="chip honey doc" @click="openDoc">{{ t('reader.openReader') }}</button>
      </div>
      <h1 class="h-display title">{{ (cell as any).title || cellLabel(cell) }}</h1>
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
    </div>
  </div>
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
</style>
