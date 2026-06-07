<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useCellStore } from '../../stores/cell'
import { useReaderStore } from '../../stores/reader'
import { cellLabel } from '../../api/cellLabel'
import SignalBars from './SignalBars.vue'
import HmIcon from '../shell/HmIcon.vue'

const cellStore = useCellStore()
const reader = useReaderStore()
const router = useRouter()
const { t } = useI18n()

const cell = computed(() => cellStore.current?.cell ?? null)
const scores = computed<Record<string, number>>(() => (cellStore.selectedScores as any) ?? {})

function fmtDate(d: string | null): string {
  if (!d) return t('inspector.present')
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(d)
  return m ? `${m[3]}.${m[2]}.${m[1]}` : d
}
function openDoc() { if (cellStore.currentId) reader.openReader(cellStore.currentId) }
function showGraph() { router.push({ name: 'graph' }) }
</script>

<template>
  <div v-if="cell" class="inspector fade-in" :key="cell.id">
    <div class="insp-head">
      <div style="display:flex;justify-content:space-between;align-items:flex-start;gap:10px">
        <div class="h-eyebrow">{{ t('inspector.cell') }}</div>
        <button class="iconbtn" data-test="insp-close" @click="cellStore.clear()"><HmIcon name="close" :size="17" /></button>
      </div>
      <div class="insp-title">{{ cellLabel(cell) }}</div>
    </div>
    <div class="insp-body">
      <div class="section-label">{{ t('inspector.signals') }}</div>
      <SignalBars :scores="scores" />

      <div class="section-label">{{ t('inspector.metadata') }}</div>
      <div class="meta-grid">
        <div><div class="k">{{ t('inspector.type') }}</div><div class="v">{{ cell.signal || '—' }}</div></div>
        <div><div class="k">{{ t('inspector.importance') }}</div><div class="v">{{ cell.importance }}</div></div>
        <div><div class="k">{{ t('inspector.validFrom') }}</div><div class="v">{{ fmtDate(cell.valid_from) }}</div></div>
        <div><div class="k">{{ t('inspector.validUntil') }}</div><div class="v">{{ cell.valid_until ? fmtDate(cell.valid_until) : t('inspector.present') }}</div></div>
      </div>

      <div class="section-label">Time Machine</div>
      <div class="tl">
        <span>{{ fmtDate(cell.valid_from) }}</span>
        <span class="bar" />
        <span>{{ cell.valid_until ? fmtDate(cell.valid_until) : t('inspector.present') }}</span>
      </div>

      <div class="actions">
        <button class="btn" @click="openDoc"><HmIcon name="reader" :size="16" /> {{ t('inspector.openDoc') }}</button>
        <button class="btn ghost" @click="showGraph"><HmIcon name="graph" :size="16" /> {{ t('inspector.showGraph') }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.inspector { grid-column:4; width:var(--inspector-w); background:var(--bg-1); border-left:1px solid var(--line);
  display:flex; flex-direction:column; z-index:20; min-height:0; }
.insp-head { padding:18px 18px 0; }
.h-eyebrow { font-size:11.5px; letter-spacing:.14em; text-transform:uppercase; color:var(--text-2); font-weight:600; }
.iconbtn { width:32px; height:32px; border-radius:9px; display:grid; place-items:center; color:var(--text-2); background:none; border:none; cursor:pointer; }
.iconbtn:hover { color:var(--text-0); background:var(--bg-3); }
.insp-title { font-family:var(--font-display); font-size:18px; font-weight:600; line-height:1.3; letter-spacing:-.01em; margin-top:8px; }
.insp-body { flex:1; overflow-y:auto; padding:16px 18px 26px; min-height:0; }
.section-label { font-size:11px; letter-spacing:.12em; text-transform:uppercase; color:var(--text-2); font-weight:600; margin:18px 0 8px; }
.meta-grid { display:grid; grid-template-columns:1fr 1fr; gap:12px 14px; }
.meta-grid .k { font-size:11px; color:var(--text-2); text-transform:uppercase; letter-spacing:.08em; }
.meta-grid .v { font-size:13.5px; color:var(--text-0); margin-top:2px; font-weight:500; }
.tl { display:flex; align-items:center; gap:8px; font-family:var(--font-mono); font-size:12px; color:var(--text-1); }
.tl .bar { flex:1; height:4px; border-radius:3px; background:linear-gradient(90deg,var(--honey),transparent); }
.actions { display:flex; flex-direction:column; gap:9px; margin-top:24px; }
.btn { display:inline-flex; align-items:center; justify-content:center; gap:8px; padding:11px 16px; border-radius:11px;
  font-size:14px; font-weight:600; background:var(--honey); color:#1a1206; width:100%; border:none; cursor:pointer; }
.btn:hover { background:var(--honey-bright); }
.btn.ghost { background:var(--bg-3); color:var(--text-0); border:1px solid var(--line-2); }
.btn.ghost:hover { background:var(--bg-4); }
</style>
